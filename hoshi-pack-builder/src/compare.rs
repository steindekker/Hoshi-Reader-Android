//! Parity check: decode every row of two packs and diff their values per key.

use std::collections::{BTreeMap, HashMap};
use std::path::Path;

use anyhow::{Context, Result};
use rayon::prelude::*;
use rusqlite::{Connection, OpenFlags};

use crate::z;

struct Pack {
    meta: BTreeMap<String, Vec<u8>>,
    rows: Vec<(String, String, Vec<u8>)>,
}

fn open(path: &Path) -> Result<Pack> {
    let path = if path.is_dir() { path.join(crate::pack::PACK_FILE) } else { path.to_path_buf() };
    let conn = Connection::open_with_flags(&path, OpenFlags::SQLITE_OPEN_READ_ONLY)
        .with_context(|| format!("open {}", path.display()))?;
    let mut meta = BTreeMap::new();
    let mut st = conn.prepare("SELECT key, CAST(value AS BLOB) FROM meta")?;
    for r in st.query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, Vec<u8>>(1)?)))? {
        let (k, v) = r?;
        meta.insert(k, v);
    }
    let mut st = conn.prepare("SELECT term, reading, data FROM entries ORDER BY rowid")?;
    let rows = st
        .query_map([], |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)))?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    Ok(Pack { meta, rows })
}

fn decode(p: &Pack) -> Result<Vec<serde_json::Map<String, serde_json::Value>>> {
    let zdict = p.meta.get("zdict").cloned().unwrap_or_default();
    p.rows
        .par_iter()
        .map(|(t, r, b)| {
            let raw = z::inflate(b, &zdict).with_context(|| format!("row ({t}, {r})"))?;
            Ok(serde_json::from_slice(&raw)?)
        })
        .collect()
}

/// Format-1 packs are read under format-2 names, without `ae-tags`, so the two
/// formats compare value for value.
fn rename(k: &str) -> Option<&str> {
    match crate::pack::LEGACY_KEYS.iter().find(|(old, _)| *old == k) {
        Some((_, new)) => *new,
        None => Some(k),
    }
}

fn is_legacy(p: &Pack) -> bool {
    p.meta.get("format").map(|v| v.as_slice()) == Some(b"1")
}

fn normalize_row(m: serde_json::Map<String, serde_json::Value>) -> serde_json::Map<String, serde_json::Value> {
    m.into_iter().filter_map(|(k, v)| rename(&k).map(|n| (n.to_string(), v))).collect()
}

/// A format-1 JSON meta value (defaults / fallback_keys / keys) under format-2 names.
fn normalize_meta(v: &[u8]) -> Vec<u8> {
    let Ok(j) = serde_json::from_slice::<serde_json::Value>(v) else { return v.to_vec() };
    let j = match j {
        serde_json::Value::Object(m) => serde_json::Value::Object(normalize_row(m)),
        serde_json::Value::Array(a) => serde_json::Value::Array(
            a.into_iter()
                .filter_map(|x| match x.as_str().map(rename) {
                    Some(Some(n)) => Some(serde_json::Value::from(n)),
                    Some(None) => None,
                    None => Some(x),
                })
                .collect(),
        ),
        other => other,
    };
    serde_json::to_vec(&j).unwrap()
}

/// A JSON meta value re-serialized compactly, so formatting never counts as a difference.
fn canonical(v: &[u8]) -> Vec<u8> {
    serde_json::from_slice::<serde_json::Value>(v).map(|j| serde_json::to_vec(&j).unwrap()).unwrap_or_else(|_| v.to_vec())
}

fn short(s: &str, around: usize) -> String {
    let chars: Vec<char> = s.chars().collect();
    let a = around.saturating_sub(40).min(chars.len());
    let b = (around + 80).min(chars.len());
    format!("{}{}{}", if a > 0 { "…" } else { "" }, chars[a..b].iter().collect::<String>(), if b < chars.len() { "…" } else { "" })
}

/// Prints a report; returns the number of differing values.
pub fn compare(a_path: &Path, b_path: &Path, samples: usize) -> Result<usize> {
    let (a, b) = rayon::join(|| open(a_path), || open(b_path));
    let (a, b) = (a?, b?);
    let (da, db) = rayon::join(|| decode(&a), || decode(&b));
    let (mut da, mut db) = (da?, db?);
    let (legacy_a, legacy_b) = (is_legacy(&a), is_legacy(&b));
    if legacy_a {
        da = da.into_iter().map(normalize_row).collect();
    }
    if legacy_b {
        db = db.into_iter().map(normalize_row).collect();
    }
    // Across formats the blobs, zdict and version necessarily differ; values must not.
    let cross = legacy_a != legacy_b;
    if cross {
        println!("comparing across formats: format-1 keys read as format-2 names, ae-tags ignored");
    }
    println!("A {}: {} rows   B {}: {} rows", a_path.display(), a.rows.len(), b_path.display(), b.rows.len());

    let mut meta_diffs = 0usize;
    for key in ["format", "version", "entries", "defaults", "fallback_keys", "keys", "zdict", "built_at"] {
        let json_key = matches!(key, "defaults" | "fallback_keys" | "keys");
        let norm = |p: &Pack, legacy: bool| {
            p.meta.get(key).map(|v| match (json_key, legacy) {
                (true, true) => canonical(&normalize_meta(v)),
                (true, false) => canonical(v),
                _ => v.clone(),
            })
        };
        let (x, y) = (norm(&a, legacy_a), norm(&b, legacy_b));
        let (x, y) = (x.as_ref(), y.as_ref());
        let show = |v: Option<&Vec<u8>>| match v {
            None => "<missing>".to_string(),
            Some(v) if key == "zdict" => format!("{} bytes", v.len()),
            Some(v) => String::from_utf8_lossy(v).into_owned(),
        };
        let expected = key == "built_at" || (cross && matches!(key, "format" | "version" | "zdict"));
        let mark = if x == y { "same" } else if expected { "differs (expected)" } else { "DIFFERS" };
        if x != y && !expected {
            meta_diffs += 1;
        }
        println!("meta {key:14} {mark:20} A={}  B={}", show(x), show(y));
    }

    let index_b: HashMap<(&str, &str), usize> =
        b.rows.iter().enumerate().map(|(i, (t, r, _))| ((t.as_str(), r.as_str()), i)).collect();
    let mut only_a = Vec::new();
    let mut same_blob = 0usize;
    let mut same_order = a.rows.len() == b.rows.len();
    let mut per_key: BTreeMap<String, (usize, Vec<String>)> = BTreeMap::new();
    let mut matched = vec![false; b.rows.len()];
    for (i, (t, r, blob)) in a.rows.iter().enumerate() {
        let Some(&j) = index_b.get(&(t.as_str(), r.as_str())) else {
            only_a.push(format!("({t}, {r})"));
            same_order = false;
            continue;
        };
        matched[j] = true;
        same_order &= i == j;
        if !cross && *blob == b.rows[j].2 {
            same_blob += 1;
            continue;
        }
        let (va, vb) = (&da[i], &db[j]);
        let mut keys: Vec<&String> = va.keys().chain(vb.keys()).collect();
        keys.sort();
        keys.dedup();
        for k in keys {
            let (x, y) = (va.get(k).and_then(|v| v.as_str()), vb.get(k).and_then(|v| v.as_str()));
            if x == y {
                continue;
            }
            let e = per_key.entry(k.clone()).or_default();
            e.0 += 1;
            if e.1.len() < samples {
                let desc = match (x, y) {
                    (Some(x), Some(y)) => {
                        let at = x.chars().zip(y.chars()).take_while(|(p, q)| p == q).count();
                        format!("({t}, {r}) at char {at}\n      A: {}\n      B: {}", short(x, at), short(y, at))
                    }
                    (Some(_), None) => format!("({t}, {r}) only in A"),
                    (None, Some(_)) => format!("({t}, {r}) only in B"),
                    (None, None) => unreachable!(),
                };
                e.1.push(desc);
            }
        }
    }
    let only_b: Vec<String> =
        b.rows.iter().zip(&matched).filter(|(_, m)| !**m).map(|((t, r, _), _)| format!("({t}, {r})")).collect();
    println!("rows: {} identical blobs, {} only in A, {} only in B, row order {}", if cross { "n/a (formats differ):".to_string() } else { same_blob.to_string() }, only_a.len(), only_b.len(),
        if same_order { "identical" } else { "differs" });
    for (name, list) in [("only in A", &only_a), ("only in B", &only_b)] {
        for s in list.iter().take(samples) {
            println!("  {name}: {s}");
        }
    }
    let mut total = meta_diffs + only_a.len() + only_b.len();
    if per_key.is_empty() {
        println!("values: no differences");
    }
    for (k, (n, ex)) in &per_key {
        total += n;
        println!("key {k}: {n} rows differ");
        for e in ex {
            println!("    {e}");
        }
    }
    Ok(total)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn reads_format_1_under_format_2_names() {
        let row: serde_json::Map<String, serde_json::Value> = serde_json::from_str(
            r#"{"ae-definition":"d","ae-definition-source":"s","ae-pitch":"p","ae-examples":"e","ae-frequency":"9","ae-tags":"t"}"#,
        )
        .unwrap();
        let got = serde_json::to_string(&normalize_row(row)).unwrap();
        assert_eq!(
            got,
            r#"{"definition":"d","definition-source":"s","pitch-graph":"p","example-sentences":"e","frequency-rank":"9"}"#
        );
        let fallback = br#"["ae-definition", "ae-definition-source", "ae-examples", "ae-tags"]"#;
        assert_eq!(normalize_meta(fallback), canonical(crate::pack::FALLBACK_KEYS_JSON.as_bytes()));
        let defaults = br#"{"ae-definition": "{glossary-first}"}"#;
        assert_eq!(normalize_meta(defaults), canonical(crate::pack::DEFAULTS_JSON.as_bytes()));
    }
}
