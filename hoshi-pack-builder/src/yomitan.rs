//! Yomitan dictionary zips, read in place: bank files are inflated straight from
//! the mmapped zip and their top-level array is deserialized one entry at a time,
//! so a 190 MB bank never becomes one JSON tree.

use std::collections::BTreeMap;
use std::fmt;
use std::fs::File;
use std::io::{BufReader, Cursor, Read};
use std::path::{Path, PathBuf};

use anyhow::{Context, Result, bail};
use memmap2::Mmap;
use serde::de::{DeserializeSeed, Deserializer, IgnoredAny, SeqAccess, Visitor};
use serde_json::Value;

/// Banks larger than this (uncompressed) are parsed from the inflating reader
/// instead of an inflated buffer, bounding memory on small hosts.
pub static STREAM_OVER: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(64 << 20);

pub struct DictZip {
    pub id: &'static str,
    pub path: PathBuf,
    pub title: String,
    pub revision: String,
    map: Mmap,
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
        Ok(DictZip { id, path: path.to_path_buf(), title, revision, map })
    }

    pub fn archive(&self) -> Result<zip::ZipArchive<Cursor<&[u8]>>> {
        Ok(zip::ZipArchive::new(Cursor::new(&self.map[..]))?)
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

    /// Calls `f` for every element of the bank's top-level array, in order.
    pub fn for_each<T, F>(&self, bank: &Bank, f: F) -> Result<()>
    where
        T: for<'de> serde::Deserialize<'de>,
        F: FnMut(T),
    {
        let mut ar = self.archive()?;
        let mut file = ar.by_index(bank.index)?;
        let seed = Each { f, _t: std::marker::PhantomData };
        if bank.size > STREAM_OVER.load(std::sync::atomic::Ordering::Relaxed) {
            let mut de = serde_json::Deserializer::from_reader(BufReader::with_capacity(1 << 20, file));
            seed.deserialize(&mut de).with_context(|| format!("{} {}", self.id, bank.name))?;
            de.end()?;
        } else {
            let mut buf = Vec::with_capacity(bank.size as usize);
            file.read_to_end(&mut buf)?;
            let mut de = serde_json::Deserializer::from_slice(&buf);
            seed.deserialize(&mut de).with_context(|| format!("{} {}", self.id, bank.name))?;
            de.end()?;
        }
        Ok(())
    }
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
            let file = File::open(&path)?;
            // SAFETY: read-only map of a file nothing here writes.
            let map = unsafe { Mmap::map(&file)? };
            let Ok((title, _)) = read_index(&map) else { continue };
            if let Some(&(id, _)) = IDS.iter().find(|(_, m)| m(&title)) {
                if !out.contains_key(id) {
                    out.insert(id, DictZip::open(id, &path)?);
                }
            }
        }
    }
    Ok(out)
}

struct Each<T, F> {
    f: F,
    _t: std::marker::PhantomData<T>,
}

impl<'de, T: serde::Deserialize<'de>, F: FnMut(T)> DeserializeSeed<'de> for Each<T, F> {
    type Value = ();
    fn deserialize<D: Deserializer<'de>>(self, d: D) -> Result<(), D::Error> {
        d.deserialize_seq(self)
    }
}

impl<'de, T: serde::Deserialize<'de>, F: FnMut(T)> Visitor<'de> for Each<T, F> {
    type Value = ();
    fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
        f.write_str("a Yomitan bank array")
    }
    fn visit_seq<A: SeqAccess<'de>>(mut self, mut seq: A) -> Result<(), A::Error> {
        while let Some(e) = seq.next_element::<T>()? {
            (self.f)(e);
        }
        Ok(())
    }
}

/// A term-bank entry: `[term, reading, defTags, rules, score, definitions, ...]`,
/// keeping only the fields the pack reads.
pub struct TermEntry {
    pub term: String,
    /// `e[1] or ""`.
    pub reading: String,
    pub def_tags: Option<String>,
    pub definitions: Value,
}

impl<'de> serde::Deserialize<'de> for TermEntry {
    fn deserialize<D: Deserializer<'de>>(d: D) -> Result<Self, D::Error> {
        struct V;
        impl<'de> Visitor<'de> for V {
            type Value = TermEntry;
            fn expecting(&self, f: &mut fmt::Formatter) -> fmt::Result {
                f.write_str("a term entry array")
            }
            fn visit_seq<A: SeqAccess<'de>>(self, mut seq: A) -> Result<TermEntry, A::Error> {
                use serde::de::Error;
                let term: String = seq.next_element()?.ok_or_else(|| A::Error::custom("no term"))?;
                let reading: Option<String> = seq.next_element()?.flatten();
                let def_tags: Option<String> = seq.next_element()?.flatten();
                let _rules: Option<IgnoredAny> = seq.next_element()?;
                let _score: Option<IgnoredAny> = seq.next_element()?;
                let definitions: Value = seq.next_element()?.unwrap_or(Value::Null);
                while seq.next_element::<IgnoredAny>()?.is_some() {}
                Ok(TermEntry { term, reading: reading.unwrap_or_default(), def_tags, definitions })
            }
        }
        d.deserialize_seq(V)
    }
}
