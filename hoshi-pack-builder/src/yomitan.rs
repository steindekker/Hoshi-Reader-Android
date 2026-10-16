//! Yomitan dictionary zips, read in place: bank files are inflated straight from
//! the mmapped zip and their top-level array is deserialized one entry at a time,
//! so a 190 MB bank never becomes one JSON tree.

use std::collections::BTreeMap;
use std::fmt;
use std::fs::File;
use std::io::{BufReader, Cursor, Read};
use std::sync::Arc;
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use memmap2::Mmap;
use serde::de::{DeserializeSeed, Deserializer, IgnoredAny, SeqAccess, Visitor};
use serde_json::Value;
use bumpalo::Bump;

use crate::json::{J, JSeed, OptStrSeed, StrSeed};

/// In-memory banks bigger than SPLIT_OVER are split into pieces of about PIECE
/// bytes, parsed in parallel. Finding the cuts is a sequential scan, so only the
/// few huge single banks (広辞苑, 用例.jp) are worth it.
const SPLIT_OVER: usize = 48 << 20;
const PIECE: usize = 8 << 20;

/// Banks larger than this (uncompressed) are parsed from the inflating reader
/// instead of an inflated buffer, bounding memory on small hosts.
pub static STREAM_OVER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(64 << 20);

pub struct DictZip {
    pub id: &'static str,
    pub path: PathBuf,
    pub title: String,
    pub revision: String,
    /// Parsed once: the central directory of a zip with thousands of images
    /// costs real time to read, and clones share it.
    archive: zip::ZipArchive<MapReader>,
}

/// A cursor over a shared mmap, so archive clones are cheap and `Send`.
#[derive(Clone)]
pub struct MapReader {
    map: Arc<Mmap>,
    pos: u64,
}

impl Read for MapReader {
    fn read(&mut self, out: &mut [u8]) -> std::io::Result<usize> {
        let start = (self.pos as usize).min(self.map.len());
        let n = out.len().min(self.map.len() - start);
        out[..n].copy_from_slice(&self.map[start..start + n]);
        self.pos += n as u64;
        Ok(n)
    }
}

impl std::io::Seek for MapReader {
    fn seek(&mut self, to: std::io::SeekFrom) -> std::io::Result<u64> {
        let len = self.map.len() as i64;
        let pos = match to {
            std::io::SeekFrom::Start(p) => p as i64,
            std::io::SeekFrom::End(d) => len + d,
            std::io::SeekFrom::Current(d) => self.pos as i64 + d,
        };
        if pos < 0 {
            return Err(std::io::Error::new(std::io::ErrorKind::InvalidInput, "seek before start"));
        }
        self.pos = pos as u64;
        Ok(self.pos)
    }
}

/// One bank file inside a zip.
#[derive(Clone)]
pub struct Bank {
    pub name: String,
    pub index: usize,
    pub size: u64,
}

/// The dictionary ids the pack reads and how each zip's index.json title names it.
pub const IDS: &[(&str, fn(&str) -> bool)] = &[
    ("sankoku8", |t| t == "三省堂国語辞典\u{3000}第八版"),
    ("koujien7", |t| t == "広辞苑 第七版"),
    ("jitsuyo", |t| t == "実用日本語表現辞典"),
    ("daijisen", |t| t == "大辞泉 第二版"),
    ("jmdict", |t| t.starts_with("JMdict")),
    ("jitendex", |t| t.starts_with("Jitendex")),
    ("saito", |t| t == "NEW斎藤和英大辞典"),
    ("yourei", |t| t == "用例.jp"),
    ("jpdb", |t| t.starts_with("JPDB")),
];

impl DictZip {
    pub fn open(id: &'static str, path: &Path) -> Result<Self> {
        let file = File::open(path).with_context(|| format!("open {}", path.display()))?;
        // SAFETY: the zip is only read, and nothing in this process writes it.
        let map = unsafe { Mmap::map(&file)? };
        let (title, revision) = read_index(&map).with_context(|| format!("{}", path.display()))?;
        let archive = zip::ZipArchive::new(MapReader { map: Arc::new(map), pos: 0 })?;
        Ok(DictZip { id, path: path.to_path_buf(), title, revision, archive })
    }

    pub fn archive(&self) -> Result<zip::ZipArchive<MapReader>> {
        Ok(self.archive.clone())
    }

    /// `sorted(dir.glob(f"{prefix}*.json"))` over the zip's file names, by basename.
    pub fn banks(&self, prefix: &str) -> Result<Vec<Bank>> {
        let mut ar = self.archive()?;
        let mut out = Vec::new();
        for i in 0..ar.len() {
            let f = ar.by_index_raw(i)?;
            let name = f.name()?.to_string();
            let base = name.rsplit('/').next().unwrap_or(&name).to_string();
            if base.starts_with(prefix) && base.ends_with(".json") && !f.is_dir() {
                out.push(Bank { name: base, index: i, size: f.size() });
            }
        }
        out.sort_by(|a, b| a.name.cmp(&b.name));
        Ok(out)
    }

    /// Folds the bank's entries into one accumulator per piece, pieces in bank
    /// order; `f` gets each entry's index in the bank. Banks held in memory are
    /// cut at top-level element boundaries and their pieces parsed in parallel;
    /// a streamed bank is one piece.
    pub fn map_pieces<B, A, M, F>(&self, bank: &Bank, mk: M, f: F) -> Result<Vec<A>>
    where
        B: Bankable,
        A: Send,
        M: Fn() -> A + Sync,
        F: for<'a> Fn(&mut A, usize, B::Of<'a>) + Sync,
    {
        use rayon::prelude::*;
        let t0 = std::time::Instant::now();
        let _log = Defer(move || crate::prof::log(&format!("bank {} {} ({} MB)", self.id, bank.name, bank.size >> 20), t0));
        let mut ar = self.archive()?;
        let mut file = ar.by_index(bank.index)?;
        let ctx = || format!("{} {}", self.id, bank.name);
        if bank.size > STREAM_OVER.load(std::sync::atomic::Ordering::Relaxed) {
            let mut acc = mk();
            let mut bump = Bump::new();
            let seed = Fold::<B, A, F> { acc: &mut acc, i: 0, f: &f, bump: &mut bump, _b: std::marker::PhantomData };
            let mut de = serde_json::Deserializer::from_reader(BufReader::with_capacity(1 << 20, file));
            seed.deserialize(&mut de).with_context(ctx)?;
            de.end()?;
            return Ok(vec![acc]);
        }
        let mut buf = Vec::with_capacity(bank.size as usize);
        file.read_to_end(&mut buf)?;
        if buf.len() <= SPLIT_OVER {
            let mut acc = mk();
            let mut bump = Bump::new();
            let seed = Fold::<B, A, F> { acc: &mut acc, i: 0, f: &f, bump: &mut bump, _b: std::marker::PhantomData };
            let mut de = serde_json::Deserializer::from_slice(&buf);
            seed.deserialize(&mut de).with_context(ctx)?;
            de.end()?;
            return Ok(vec![acc]);
        }
        let ts = std::time::Instant::now();
        let pieces = split_array(&buf, PIECE).with_context(ctx)?;
        crate::prof::log(&format!("split {} {} into {}", self.id, bank.name, pieces.len()), ts);
        pieces
            .par_iter()
            .map(|&(start, end, first)| {
                let mut piece = Vec::with_capacity(end - start + 2);
                piece.push(b'[');
                piece.extend_from_slice(&buf[start..end]);
                piece.push(b']');
                let mut acc = mk();
                let mut bump = Bump::new();
                let seed = Fold::<B, A, F> { acc: &mut acc, i: first, f: &f, bump: &mut bump, _b: std::marker::PhantomData };
                let mut de = serde_json::Deserializer::from_slice(&piece);
                seed.deserialize(&mut de).with_context(ctx)?;
                de.end()?;
                Ok(acc)
            })
            .collect()
    }

}

/// Cuts a JSON array into pieces of whole elements, each about `size` bytes:
/// (start, end, index of its first element), with `buf[start..end]` the
/// elements and the commas between them.
fn split_array(buf: &[u8], size: usize) -> Result<Vec<(usize, usize, usize)>> {
    let mut i = 0;
    while i < buf.len() && buf[i].is_ascii_whitespace() {
        i += 1;
    }
    if buf.get(i) != Some(&b'[') {
        bail!("bank is not a JSON array");
    }
    let open = i + 1;
    let (mut depth, mut in_str, mut esc) = (0usize, false, false);
    let mut pieces = Vec::new();
    let (mut start, mut first, mut count) = (open, 0usize, 0usize);
    let mut close = None;
    for (j, &b) in buf.iter().enumerate().skip(open) {
        if in_str {
            if esc {
                esc = false;
            } else if b == b'\\' {
                esc = true;
            } else if b == b'"' {
                in_str = false;
            }
            continue;
        }
        match b {
            b'"' => in_str = true,
            b'[' | b'{' => depth += 1,
            b']' | b'}' if depth == 0 => {
                close = Some(j);
                break;
            }
            b']' | b'}' => depth -= 1,
            b',' if depth == 0 => {
                count += 1;
                if j - start >= size {
                    pieces.push((start, j, first));
                    start = j + 1;
                    first = count;
                }
            }
            _ => {}
        }
    }
    let close = close.context("unterminated bank array")?;
    if buf[start..close].iter().any(|b| !b.is_ascii_whitespace()) || pieces.is_empty() {
        pieces.push((start, close, first));
    }
    Ok(pieces)
}

fn read_index(map: &[u8]) -> Result<(String, String)> {
    let mut ar = zip::ZipArchive::new(Cursor::new(map))?;
    let mut raw = Vec::new();
    match ar.by_name("index.json") {
        Ok(mut f) => {
            f.read_to_end(&mut raw)?;
        }
        Err(_) => bail!("no root index.json"),
    }
    let raw = raw.strip_prefix(b"\xef\xbb\xbf").unwrap_or(&raw);
    let v: Value = serde_json::from_slice(raw)?;
    let s = |k: &str| v.get(k).and_then(Value::as_str).unwrap_or("").to_string();
    Ok((s("title"), s("revision")))
}

/// Finds every dictionary of `IDS` among the zips in `dir`, plus explicit
/// `id=path` overrides (which win).
pub fn discover(dir: Option<&Path>, overrides: &[(String, PathBuf)]) -> Result<BTreeMap<&'static str, DictZip>> {
    let mut out = BTreeMap::new();
    for (id, path) in overrides {
        let Some(&(sid, _)) = IDS.iter().find(|(i, _)| i == id) else {
            bail!("unknown dictionary id {id} (known: {})", IDS.iter().map(|x| x.0).collect::<Vec<_>>().join(", "));
        };
        out.insert(sid, DictZip::open(sid, path)?);
    }
    if let Some(dir) = dir {
        let mut zips: Vec<PathBuf> = std::fs::read_dir(dir)
            .with_context(|| format!("read {}", dir.display()))?
            .filter_map(|e| e.ok().map(|e| e.path()))
            .filter(|p| p.extension().is_some_and(|x| x == "zip"))
            .collect();
        zips.sort();
        for path in zips {
            let Ok(z) = DictZip::open("", &path) else { continue };
            if let Some(&(id, _)) = IDS.iter().find(|(_, m)| m(&z.title)) {
                if !out.contains_key(id) {
                    out.insert(id, DictZip { id, ..z });
                }
            }
        }
    }
    Ok(out)
}

/// What a bank's array holds: how to read one element into the entry arena.
pub trait Bankable {
    type Of<'b>;
    fn next<'de: 'b, 'b, S: SeqAccess<'de>>(seq: &mut S, bump: &'b Bump) -> Result<Option<Self::Of<'b>>, S::Error>;
}

/// term_bank_*.json
pub struct Terms;
impl Bankable for Terms {
    type Of<'b> = TermEntry<'b>;
    fn next<'de: 'b, 'b, S: SeqAccess<'de>>(seq: &mut S, bump: &'b Bump) -> Result<Option<TermEntry<'b>>, S::Error> {
        seq.next_element_seed(EntrySeed(bump))
    }
}

/// term_meta_bank_*.json: each entry as its array of values.
pub struct Metas;
impl Bankable for Metas {
    type Of<'b> = &'b [J<'b>];
    fn next<'de: 'b, 'b, S: SeqAccess<'de>>(seq: &mut S, bump: &'b Bump) -> Result<Option<&'b [J<'b>]>, S::Error> {
        Ok(seq.next_element_seed(JSeed(bump))?.map(|j| match j {
            J::Arr(a) => a,
            _ => &[],
        }))
    }
}

/// Folds a bank array into an accumulator, passing each entry's bank index;
/// the arena is reset every few hundred entries.
struct Fold<'x, B, A, F> {
    acc: &'x mut A,
    i: usize,
    f: &'x F,
    bump: &'x mut Bump,
    _b: std::marker::PhantomData<B>,
}

impl<'de, B: Bankable, A, F: for<'a> Fn(&mut A, usize, B::Of<'a>)> DeserializeSeed<'de> for Fold<'_, B, A, F> {
    type Value = ();
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
        d.deserialize_seq(self)
    }
}

impl<'de, B: Bankable, A, F: for<'a> Fn(&mut A, usize, B::Of<'a>)> Visitor<'de> for Fold<'_, B, A, F> {
    type Value = ();
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a Yomitan bank array")
    }
    fn visit_seq<S: SeqAccess<'de>>(self, mut seq: S) -> Result<(), S::Error> {
        let Fold { acc, mut i, f, bump, .. } = self;
        loop {
            {
                let Some(e) = B::next(&mut seq, &*bump)? else { break };
                f(acc, i, e);
            }
            i += 1;
            // Resetting has a fixed cost; a few hundred entries' trees are small.
            if i % 256 == 0 {
                bump.reset();
            }
        }
        Ok(())
    }
}

/// A term-bank entry: `[term, reading, defTags, rules, score, definitions, ...]`,
/// keeping only the fields the pack reads.
pub struct TermEntry<'b> {
    pub term: &'b str,
    /// `e[1] or ""`.
    pub reading: &'b str,
    pub def_tags: Option<&'b str>,
    pub definitions: J<'b>,
}

struct EntrySeed<'b>(&'b Bump);

impl<'de: 'b, 'b> DeserializeSeed<'de> for EntrySeed<'b> {
    type Value = TermEntry<'b>;
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<TermEntry<'b>, D::Error> {
        d.deserialize_seq(self)
    }
}

impl<'de: 'b, 'b> Visitor<'de> for EntrySeed<'b> {
    type Value = TermEntry<'b>;
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a term entry array")
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<TermEntry<'b>, A::Error> {
        use serde::de::Error;
        let term = seq.next_element_seed(StrSeed(self.0))?.ok_or_else(|| A::Error::custom("no term"))?;
        let reading = seq.next_element_seed(OptStrSeed(self.0))?.flatten().unwrap_or("");
        let def_tags = seq.next_element_seed(OptStrSeed(self.0))?.flatten();
        let _rules: Option<IgnoredAny> = seq.next_element()?;
        let _score: Option<IgnoredAny> = seq.next_element()?;
        let definitions = seq.next_element_seed(JSeed(self.0))?.unwrap_or(J::Null);
        while seq.next_element::<IgnoredAny>()?.is_some() {}
        Ok(TermEntry { term, reading, def_tags, definitions })
    }
}

struct Defer<F: FnMut()>(F);
impl<F: FnMut()> Drop for Defer<F> {
    fn drop(&mut self) {
        (self.0)()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_at_top_level_commas() {
        let buf = br#" [ ["a,]", 1], {"x": [2, 3]}, "q\"]", 4 ] "#;
        let pieces = split_array(buf, 1).unwrap();
        let parts: Vec<(&str, usize)> =
            pieces.iter().map(|&(a, b, f)| (std::str::from_utf8(&buf[a..b]).unwrap().trim(), f)).collect();
        assert_eq!(parts, [(r#"["a,]", 1]"#, 0), (r#"{"x": [2, 3]}"#, 1), (r#""q\"]""#, 2), ("4", 3)]);
        let whole = split_array(buf, 1 << 20).unwrap();
        assert_eq!(whole.len(), 1);
        assert_eq!(split_array(b"[]", 1).unwrap().len(), 1);
    }
}
