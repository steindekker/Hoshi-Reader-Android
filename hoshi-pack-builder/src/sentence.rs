//! Port of `ankienrich/sentence_model.py`: `jrp-accent.sqlite` from the accent
//! index plus a verbatim copy of Sudachi's `system.dic`, listed in the manifest's
//! "sentence" block. Files whose content is unchanged are left alone.

use std::collections::HashMap;
use std::path::Path;

use anyhow::{Context, Result, bail};
use rusqlite::{Connection, OpenFlags, params};
use serde_json::{Value, json};
use crate::sha::Sha256;

use crate::accent::AccentIndex;
use crate::pack::file_sha256;

pub const FORMAT: u32 = 1;
pub const ACCENT_FILE: &str = "jrp-accent.sqlite";
pub const DICTIONARY_FILE: &str = "sudachi-system.dic";

fn positions(v: &[i64]) -> String {
    v.iter().map(i64::to_string).collect::<Vec<_>>().join(",")
}

/// A position list as a set (`frozenset(positions)`).
fn as_set(v: &[i64]) -> Vec<i64> {
    let mut s = v.to_vec();
    s.sort_unstable();
    s.dedup();
    s
}

/// `_accent_rows`, streamed: the (lemma, reading, positions) rows come from
/// `rows(acc)` in index order; this returns, for every reading where
/// `AccentIndex.by_reading` is not None, the reading and its first entry's
/// positions, in first-seen order.
fn by_reading(acc: &AccentIndex) -> Result<Vec<(&str, &[i64])>> {
    // reading -> (first entry's positions, whether every entry agrees as a set);
    // [0,3] and [3,0] carry the same accents.
    let mut seen: HashMap<&str, (&[i64], bool)> = HashMap::new();
    let mut readings: Vec<&str> = Vec::new();
    for row in rows(acc) {
        let (_, reading, pos) = row?;
        match seen.get_mut(reading) {
            Some((first, agree)) => {
                if *agree && as_set(first) != as_set(pos) {
                    *agree = false;
                }
            }
            None => {
                seen.insert(reading, (pos, true));
                readings.push(reading);
            }
        }
    }
    Ok(readings.into_iter().filter_map(|r| seen[r].1.then_some((r, seen[r].0))).collect())
}

fn rows(acc: &AccentIndex) -> impl Iterator<Item = Result<(&str, &str, &[i64])>> {
    acc.iter().map(|(key, pos)| match key.split_once('\t') {
        Some((lemma, reading)) if !reading.contains('\t') => Ok((lemma, reading, pos)),
        _ => bail!("accent key {key:?} is not 'lemma<TAB>reading'"),
    })
}

fn accent_version(path: &Path) -> Option<String> {
    let conn = Connection::open_with_flags(path, OpenFlags::SQLITE_OPEN_READ_ONLY).ok()?;
    conn.query_row("SELECT value FROM meta WHERE key='version'", [], |r| r.get(0)).ok()
}

fn build_accent(out: &Path, acc: &AccentIndex) -> Result<Value> {
    let by_reading = by_reading(acc)?;
    let mut h = Sha256::new();
    h.update(FORMAT.to_string().as_bytes());
    for row in rows(acc) {
        let (l, r, p) = row?;
        h.update(format!("{l}\t{r}\t{}\n", positions(p)).as_bytes());
    }
    h.update(b"\0");
    for (r, p) in &by_reading {
        h.update(format!("{r}\t{}\n", positions(p)).as_bytes());
    }
    let version: String = h.finalize().iter().take(8).map(|b| format!("{b:02x}")).collect();
    let target = out.join(ACCENT_FILE);
    if accent_version(&target).as_deref() != Some(version.as_str()) {
        let tmp = out.join(format!("{ACCENT_FILE}.tmp"));
        let _ = std::fs::remove_file(&tmp);
        let mut conn = Connection::open(&tmp)?;
        conn.execute_batch(
            "PRAGMA journal_mode = OFF;
             PRAGMA page_size = 4096;
             CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
             CREATE TABLE accent (lemma TEXT NOT NULL, reading TEXT NOT NULL, positions TEXT NOT NULL);
             CREATE TABLE by_reading (reading TEXT PRIMARY KEY, positions TEXT NOT NULL) WITHOUT ROWID;",
        )?;
        let tx = conn.transaction()?;
        {
            let mut ins = tx.prepare("INSERT INTO accent VALUES (?,?,?)")?;
            for row in rows(acc) {
                let (l, r, p) = row?;
                ins.execute(params![l, r, positions(p)])?;
            }
            tx.execute_batch("CREATE UNIQUE INDEX accent_key ON accent (lemma, reading)")?;
            let mut ins = tx.prepare("INSERT INTO by_reading VALUES (?,?)")?;
            for (r, p) in &by_reading {
                ins.execute(params![r, positions(p)])?;
            }
            let mut ins = tx.prepare("INSERT INTO meta VALUES (?,?)")?;
            ins.execute(params!["format", FORMAT.to_string()])?;
            ins.execute(params!["version", version])?;
            ins.execute(params!["entries", acc.len().to_string()])?;
        }
        tx.commit()?;
        conn.execute_batch("VACUUM")?;
        conn.close().map_err(|(_, e)| e)?;
        std::fs::rename(&tmp, &target)?;
    }
    Ok(json!({
        "file": ACCENT_FILE, "version": version, "size": std::fs::metadata(&target)?.len(),
        "sha256": file_sha256(&target)?, "entries": acc.len(),
    }))
}

fn copy_dictionary(out: &Path, source: &Path, label: &str) -> Result<Value> {
    let sha = file_sha256(source)?;
    let size = std::fs::metadata(source)?.len();
    let target = out.join(DICTIONARY_FILE);
    let same = std::fs::metadata(&target).is_ok_and(|m| m.len() == size) && file_sha256(&target)? == sha;
    if !same {
        let tmp = out.join(format!("{DICTIONARY_FILE}.tmp"));
        std::fs::copy(source, &tmp)?;
        std::fs::rename(&tmp, &target)?;
    }
    Ok(json!({"file": DICTIONARY_FILE, "version": &sha[..16], "size": size, "sha256": sha, "source": label}))
}

/// "sudachidict_full <version>" from the dist-info beside an installed package's
/// `sudachidict_full/resources/system.dic`, as `importlib.metadata` reports it.
pub fn detect_label(dic: &Path) -> Option<String> {
    let site = dic.parent()?.parent()?.parent()?;
    for e in std::fs::read_dir(site).ok()?.flatten() {
        let name = e.file_name().to_string_lossy().into_owned();
        if let Some(v) = name.strip_prefix("sudachidict_full-").and_then(|n| n.strip_suffix(".dist-info")) {
            return Some(format!("sudachidict_full {v}"));
        }
    }
    None
}

/// `sentence_model.build`: the manifest's "sentence" block.
pub fn build(out: &Path, acc: &AccentIndex, dic: &Path, label: Option<String>) -> Result<Value> {
    let label = label.or_else(|| detect_label(dic)).with_context(|| {
        format!("cannot tell the Sudachi dictionary's version from {}; pass --sudachi-source", dic.display())
    })?;
    Ok(json!({
        "format": FORMAT,
        "accent": build_accent(out, acc)?,
        "dictionary": copy_dictionary(out, dic, &label)?,
    }))
}
