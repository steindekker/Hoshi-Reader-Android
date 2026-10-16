//! Everything `pack.Sources` opens, built straight from the dictionary zips: the
//! definition rows (`defs.sqlite`), examples (`examples.sqlite`), JPDB ranks
//! (`freq.sqlite`) and the accent index. Strings are interned in one arena and
//! all definition HTML and example text lives in a temp file read back through
//! mmap, so the resident set stays small (box has ~1 GB to spare).

use std::collections::{BTreeMap, HashSet};
use std::fs::File;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::Mutex;

use anyhow::{Context, Result};
use memmap2::Mmap;
use rayon::prelude::*;
use serde_json::Value;

use crate::accent::AccentIndex;
use crate::defs::{self, DefRow, JE, JJ_CHAIN};
use crate::examples::{self, ExRow};
use crate::freq::Freq;
use crate::strtab::StrTable;
use crate::yomitan::{DictZip, TermEntry};

const NO_HTML: u32 = u32::MAX;

/// Append-only text store: a temp file while loading, an mmap afterwards.
struct Store {
    file: Mutex<(File, u64)>,
    path: PathBuf,
}

impl Store {
    fn create(path: PathBuf) -> Result<Self> {
        let file = File::options().read(true).write(true).create(true).truncate(true).open(&path)
            .with_context(|| format!("create {}", path.display()))?;
        Ok(Store { file: Mutex::new((file, 0)), path })
    }

    /// Writes `buf`, returning its offset in the store.
    fn append(&self, buf: &[u8]) -> Result<u64> {
        let mut g = self.file.lock().unwrap();
        let off = g.1;
        g.0.write_all(buf)?;
        g.1 += buf.len() as u64;
        Ok(off)
    }

    fn finish(self) -> Result<(Option<Mmap>, u64)> {
        let (file, len) = self.file.into_inner().unwrap();
        // SAFETY: the file is private to this process and no longer written.
        let map = if len == 0 { None } else { Some(unsafe { Mmap::map(&file)? }) };
        drop(file);
        std::fs::remove_file(&self.path)?;
        Ok((map, len))
    }
}

/// Strings shared by the parse tasks; each bank interns its rows in one lock.
type Shared = Mutex<StrTable>;

struct Def {
    term: u32,
    reading: u32,
    dict: u8,
    sense_ord: i32,
    off: u64,
    len: u32,
}

/// Example text is stored as jp, en, plain back to back from `off`.
struct Ex {
    term: u32,
    row: ExRow,
}

pub struct Sources {
    strings: StrTable,
    defs: Vec<Def>,
    /// Row indices grouped by term, rowid order within a term.
    by_term: Vec<u32>,
    term_start: Vec<u32>,
    examples: Vec<ExRow>,
    ex_start: Vec<u32>,
    has_examples: bool,
    store: Option<Mmap>,
    freq: Option<Freq>,
    pub accent: AccentIndex,
    /// Distinct (term, reading) headwords in definition-chain order.
    pub pairs: Vec<(u32, u32)>,
    pub counts: BTreeMap<String, usize>,
}

pub struct Inputs<'a> {
    pub dicts: &'a BTreeMap<&'static str, DictZip>,
    pub tatoeba: Option<&'a Path>,
    pub accent: &'a Path,
    pub scratch: &'a Path,
}

/// A chain dictionary. With `earlier`, rows of a term an earlier chain
/// dictionary has can never be chosen, so their HTML is not stored.
fn parse_jj(z: &DictZip, dict: u8, store: &Store, strings: &Shared, earlier: Option<&[bool]>) -> Result<Vec<Vec<Def>>> {
    z.banks("term_bank_")?
        .par_iter()
        .map(|bank| {
            let mut raw: Vec<(String, String, u32, u32)> = Vec::new();
            let mut buf = String::new();
            let mut html = String::new();
            z.for_each(bank, |e: TermEntry| {
                html.clear();
                defs::render_sc(&mut html, &e.definitions);
                if !html.is_empty() {
                    raw.push((e.term, e.reading, buf.len() as u32, html.len() as u32));
                    buf.push_str(&html);
                }
            })?;
            let mut rows = Vec::with_capacity(raw.len());
            {
                let mut s = strings.lock().unwrap();
                for (t, r, off, len) in &raw {
                    rows.push(Def { term: s.intern(t), reading: s.intern(r), dict, sense_ord: 0, off: *off as u64, len: *len });
                }
            }
            drop(raw);
            if let Some(earlier) = earlier {
                let mut kept = String::new();
                for d in &mut rows {
                    if earlier.get(d.term as usize).copied().unwrap_or(false) {
                        d.len = NO_HTML;
                    } else {
                        let o = kept.len() as u64;
                        kept.push_str(&buf[d.off as usize..d.off as usize + d.len as usize]);
                        d.off = o;
                    }
                }
                buf = kept;
            }
            let base = store.append(buf.as_bytes())?;
            for d in &mut rows {
                d.off += base;
            }
            Ok(rows)
        })
        .collect()
}

fn parse_je(z: &DictZip, store: &Store, strings: &Shared) -> Result<Vec<Vec<Def>>> {
    z.banks("term_bank_")?
        .par_iter()
        .map(|bank| {
            let mut raw: Vec<(String, String, i32, u32, u32)> = Vec::new();
            let mut buf = String::new();
            let mut i = 0i64;
            z.for_each(bank, |e: TermEntry| {
                i += 1;
                let gl = defs::glosses(&e.definitions);
                if gl.is_empty() {
                    return;
                }
                let off = buf.len();
                for (k, g) in gl.iter().enumerate() {
                    if k > 0 {
                        buf.push_str("; ");
                    }
                    buf.push_str(g);
                }
                let sense_ord = defs::sense_ord(e.def_tags.as_deref(), i) as i32;
                raw.push((e.term, e.reading, sense_ord, off as u32, (buf.len() - off) as u32));
            })?;
            let base = store.append(buf.as_bytes())?;
            let mut s = strings.lock().unwrap();
            Ok(raw
                .iter()
                .map(|(t, r, so, off, len)| Def {
                    term: s.intern(t),
                    reading: s.intern(r),
                    dict: JE,
                    sense_ord: *so,
                    off: base + *off as u64,
                    len: *len,
                })
                .collect())
        })
        .collect()
}

/// Stores one batch of examples, interning (term, reading) under one lock.
fn store_examples(
    batch: Vec<(String, String, Vec<examples::Example>)>,
    src: u8,
    store: &Store,
    strings: &Shared,
) -> Result<Vec<Ex>> {
    let mut buf = String::new();
    let mut spans = Vec::new();
    for (_, _, exs) in &batch {
        for ex in exs {
            let off = buf.len() as u64;
            buf.push_str(&ex.jp);
            buf.push_str(&ex.en);
            buf.push_str(&ex.plain);
            spans.push((off, ex.jp.len() as u32, ex.en.len() as u32, ex.plain.len() as u32));
        }
    }
    let base = store.append(buf.as_bytes())?;
    let mut s = strings.lock().unwrap();
    let mut spans = spans.into_iter();
    let mut out = Vec::with_capacity(spans.len());
    for (term, reading, exs) in &batch {
        let (t, r) = (s.intern(term), s.intern(reading));
        for ord in 0..exs.len() {
            let (off, jp, en, plain) = spans.next().unwrap();
            out.push(Ex { term: t, row: ExRow { reading: r, src, ord: ord as u8, off: base + off, jp, en, plain } });
        }
    }
    Ok(out)
}

fn parse_examples(z: &DictZip, src: u8, store: &Store, strings: &Shared) -> Result<Vec<Vec<Ex>>> {
    z.banks("term_bank_")?
        .par_iter()
        .map(|bank| {
            let mut batch = Vec::new();
            z.for_each(bank, |e: TermEntry| {
                let mut ex = match src {
                    examples::JITENDEX => examples::jitendex(&e.definitions),
                    examples::SAITO => examples::saito(&e.definitions, &e.term),
                    _ => examples::yourei(&e.definitions),
                };
                ex.truncate(examples::BUILD_CAP);
                if !ex.is_empty() {
                    batch.push((e.term, e.reading, ex));
                }
            })?;
            store_examples(batch, src, store, strings)
        })
        .collect()
}

fn parse_freq(z: &DictZip) -> Result<Freq> {
    let mut freq = Freq::default();
    for bank in z.banks("term_meta_bank_")? {
        z.for_each(&bank, |e: Vec<Value>| freq.add(&e))?;
    }
    freq.finish();
    Ok(freq)
}

/// Stable counting sort of row indices by key: rowid order survives in a group.
fn group(keys: &[u32], nkeys: usize) -> (Vec<u32>, Vec<u32>) {
    let mut start = vec![0u32; nkeys + 1];
    for &k in keys {
        start[k as usize + 1] += 1;
    }
    for i in 0..nkeys {
        start[i + 1] += start[i];
    }
    let mut fill = start.clone();
    let mut order = vec![0u32; keys.len()];
    for (row, &k) in keys.iter().enumerate() {
        order[fill[k as usize] as usize] = row as u32;
        fill[k as usize] += 1;
    }
    (order, start)
}

/// Hands memory freed by the parse phase back to the OS.
fn trim() {
    #[cfg(target_env = "gnu")]
    unsafe {
        libc::malloc_trim(0);
    }
}

impl Sources {
    pub fn load(inp: Inputs) -> Result<Self> {
        let store = Store::create(inp.scratch.join(".hoshi-pack-store.tmp"))?;
        let strings: Shared = Mutex::new(StrTable::default());
        strings.lock().unwrap().intern("");
        let get = |id: &str| inp.dicts.get(id);
        let last = JJ_CHAIN.len() - 1;

        // Wave 1: everything except the last chain dictionary.
        let mut jj: Vec<Option<Vec<Vec<Def>>>> = (0..JJ_CHAIN.len()).map(|_| None).collect();
        let mut je = None;
        let ex_srcs = [examples::JITENDEX, examples::SAITO, examples::YOUREI];
        let mut ex_parts: Vec<Option<Vec<Vec<Ex>>>> = vec![None, None, None];
        let (mut freq, mut tatoeba, mut accent) = (None, None, None);
        let errs: Mutex<Vec<anyhow::Error>> = Mutex::new(Vec::new());
        {
            let (store, strings, errs) = (&store, &strings, &errs);
            let fail = move |r: Result<()>| {
                if let Err(e) = r {
                    errs.lock().unwrap().push(e);
                }
            };
            rayon::scope(|s| {
                for (d, slot) in jj[..last].iter_mut().enumerate() {
                    if let Some(z) = get(JJ_CHAIN[d]) {
                        s.spawn(move |_| fail(parse_jj(z, d as u8, store, strings, None).map(|v| *slot = Some(v))));
                    }
                }
                if let Some(z) = get(defs::JE_DICT) {
                    let je = &mut je;
                    s.spawn(move |_| fail(parse_je(z, store, strings).map(|v| *je = Some(v))));
                }
                for (slot, (src, id)) in ex_parts.iter_mut().zip(ex_srcs.into_iter().zip(["jitendex", "saito", "yourei"])) {
                    if let Some(z) = get(id) {
                        s.spawn(move |_| fail(parse_examples(z, src, store, strings).map(|v| *slot = Some(v))));
                    }
                }
                if let Some(z) = get("jpdb") {
                    let freq = &mut freq;
                    s.spawn(move |_| fail(parse_freq(z).map(|v| *freq = Some(v))));
                }
                if let Some(dir) = inp.tatoeba {
                    let tatoeba = &mut tatoeba;
                    s.spawn(move |_| {
                        fail(examples::tatoeba(dir).and_then(|rows| {
                            let batch = rows.into_iter().map(|((h, r), e)| (h, r, e)).collect();
                            *tatoeba = Some(store_examples(batch, examples::TATOEBA, store, strings)?);
                            Ok(())
                        }))
                    });
                }
                let accent = &mut accent;
                s.spawn(move |_| fail(AccentIndex::load(inp.accent).map(|v| *accent = Some(v))));
            });
        }
        if let Some(e) = errs.into_inner().unwrap().pop() {
            return Err(e);
        }
        trim();

        // Wave 2: the last chain dictionary, pruned against the earlier ones.
        if let Some(z) = get(JJ_CHAIN[last]) {
            let mut earlier = vec![false; strings.lock().unwrap().len()];
            for d in jj[..last].iter().flatten().flatten().flatten() {
                earlier[d.term as usize] = true;
            }
            jj[last] = Some(parse_jj(z, last as u8, &store, &strings, Some(&earlier))?);
        }
        let (store, store_len) = store.finish()?;
        let strings = strings.into_inner().unwrap();

        let mut counts = BTreeMap::new();
        counts.insert("temp store MB".into(), (store_len >> 20) as usize);
        // defs.sqlite rowid order: chain dictionaries, then JMdict.
        let mut defs = Vec::new();
        for (dict, banks) in jj.into_iter().chain(std::iter::once(je)).enumerate() {
            let Some(banks) = banks else { continue };
            let name = if dict as u8 == JE { defs::JE_DICT } else { JJ_CHAIN[dict] };
            let before = defs.len();
            defs.extend(banks.into_iter().flatten());
            counts.insert(format!("defs/{name}"), defs.len() - before);
        }
        let def_terms: Vec<u32> = defs.iter().map(|d| d.term).collect();
        let (by_term, term_start) = group(&def_terms, strings.len());

        // pack._pairs: a reading-less entry is keyed under its headword.
        let mut seen = HashSet::new();
        let mut pairs = Vec::new();
        for d in &defs {
            let key = (d.term, if d.reading == 0 { d.term } else { d.reading });
            if seen.insert(key) {
                pairs.push(key);
            }
        }
        drop(seen);

        // examples.sqlite rowid order: jitendex, saito, yourei, then Tatoeba.
        // Only terms the definition index knows are ever looked up.
        let knows = |t: u32| term_start[t as usize] != term_start[t as usize + 1];
        let mut ex_rows = Vec::new();
        let mut has_examples = false;
        let named = ex_parts.into_iter().zip(["jitendex", "saito", "yourei"]).chain(std::iter::once((tatoeba.map(|t| vec![t]), "tatoeba")));
        for (parts, name) in named {
            let Some(parts) = parts else { continue };
            has_examples = true;
            let mut n = 0;
            for e in parts.into_iter().flatten() {
                n += 1;
                if knows(e.term) {
                    ex_rows.push(e);
                }
            }
            counts.insert(format!("examples/{name}"), n);
        }
        let ex_terms: Vec<u32> = ex_rows.iter().map(|e| e.term).collect();
        let (ex_order, ex_start) = group(&ex_terms, strings.len());
        drop(ex_terms);
        let mut ex_rows: Vec<Option<ExRow>> = ex_rows.into_iter().map(|e| Some(e.row)).collect();
        let examples: Vec<ExRow> = ex_order.iter().map(|&i| ex_rows[i as usize].take().unwrap()).collect();
        drop(ex_rows);

        if let Some(f) = &freq {
            counts.insert("freq/jpdb".into(), f.len());
        }
        let accent = accent.unwrap();
        counts.insert("accent".into(), accent.len());
        let src = Sources {
            strings, defs, by_term, term_start, examples, ex_start, has_examples, store, freq, accent, pairs, counts,
        };
        trim();
        Ok(src)
    }

    pub fn str(&self, id: u32) -> &str {
        self.strings.str(id)
    }

    fn text(&self, off: u64, len: u32) -> &str {
        let map = self.store.as_ref().unwrap();
        // SAFETY: the store only ever receives whole &str values.
        unsafe { std::str::from_utf8_unchecked(&map[off as usize..off as usize + len as usize]) }
    }

    fn html(&self, d: &Def) -> &str {
        if d.len == NO_HTML { "" } else { self.text(d.off, d.len) }
    }

    /// (jp, en, plain) of an example row.
    pub fn example(&self, r: &ExRow) -> (&str, &str, &str) {
        let s = self.text(r.off, r.jp + r.en + r.plain);
        let (jp, rest) = s.split_at(r.jp as usize);
        let (en, plain) = rest.split_at(r.en as usize);
        (jp, en, plain)
    }

    /// `pack.fields_for`: the pack values for one headword, in key order.
    pub fn fields_for(&self, term: u32, reading: u32) -> Vec<(&'static str, String)> {
        use crate::pack::{DEFINITION, DEFINITION_SOURCE, EXAMPLES, FREQUENCY, PITCH, TAGS};
        let term_s = self.str(term);
        let reading_s = self.str(reading);
        let (a, b) = (self.term_start[term as usize] as usize, self.term_start[term as usize + 1] as usize);
        let rows: Vec<DefRow> = self.by_term[a..b]
            .iter()
            .map(|&i| {
                let d = &self.defs[i as usize];
                DefRow { reading: self.str(d.reading), dict: d.dict, sense_ord: d.sense_ord as i64, html: self.html(d) }
            })
            .collect();
        let Some((meaning, source)) = defs::definition_for(&rows, reading_s) else {
            return Vec::new();
        };
        let mut out = vec![(DEFINITION, meaning), (DEFINITION_SOURCE, source)];
        let mut tags = String::from("ankienrich::enriched");
        if !reading_s.is_empty() {
            if let Some(acc) = self.accent.accents_for(term_s, reading_s) {
                out.push((PITCH, crate::accent::render_multi(reading_s, acc)));
            }
        }
        if self.has_examples {
            let (a, b) = (self.ex_start[term as usize] as usize, self.ex_start[term as usize + 1] as usize);
            let ex = examples::examples_for(&self.examples[a..b], reading, |r| self.example(r), |id| self.str(id));
            tags.push_str(if ex.is_empty() { " ankienrich::no-example" } else { " ankienrich::examples" });
            if !ex.is_empty() {
                out.push((EXAMPLES, ex));
            }
        }
        if let Some(f) = &self.freq {
            if let Some(rank) = f.lookup(term_s, reading_s) {
                out.push((FREQUENCY, rank.to_string()));
            }
        }
        out.push((TAGS, tags));
        out.retain(|(_, v)| !v.is_empty());
        out
    }
}
