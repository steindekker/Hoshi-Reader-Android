//! Port of `ankienrich/pack.py` `build`: pack format 1 (see README).

use std::collections::HashMap;
use std::path::Path;
use std::time::Instant;

use anyhow::Result;
use rayon::prelude::*;
use rusqlite::{Connection, params};
use serde_json::{Map, Value, json};
use sha2::{Digest, Sha256};

use crate::py::{self, PyRandom};
use crate::sources::Sources;
use crate::z::Deflater;

pub const FORMAT: u32 = 1;
pub const PACK_FILE: &str = "hoshi-pack.sqlite";
pub const MANIFEST_FILE: &str = "pack.json";

pub const DEFINITION: &str = "ae-definition";
pub const DEFINITION_SOURCE: &str = "ae-definition-source";
pub const PITCH: &str = "ae-pitch";
pub const EXAMPLES: &str = "ae-examples";
pub const FREQUENCY: &str = "ae-frequency";
pub const TAGS: &str = "ae-tags";
pub const KEY_ORDER: [&str; 6] = [DEFINITION, DEFINITION_SOURCE, PITCH, EXAMPLES, FREQUENCY, TAGS];

/// `json.dumps(DEFAULTS, ensure_ascii=False)` and `json.dumps(FALLBACK_KEYS)`.
pub const DEFAULTS_JSON: &str = r#"{"ae-definition": "{glossary-first}"}"#;
pub const FALLBACK_KEYS_JSON: &str = r#"["ae-definition", "ae-definition-source", "ae-examples", "ae-tags"]"#;

const ZDICT_SIZE: usize = 32 * 1024;
const ZDICT_SAMPLE_EVERY: u32 = 50;
const ZDICT_CHUNK: usize = 64;
const CHUNK_PAIRS: usize = 32 * 1024;

/// `_encode`: compact JSON, insertion order, raw UTF-8.
pub fn encode(values: &[(&str, String)], out: &mut Vec<u8>) {
    out.clear();
    out.push(b'{');
    for (i, (k, v)) in values.iter().enumerate() {
        if i > 0 {
            out.push(b',');
        }
        py::json_str_into(out, k);
        out.push(b':');
        py::json_str_into(out, v);
    }
    out.push(b'}');
}

/// `train_zdict`: the most repeated 64-byte chunks, most common last.
pub fn train_zdict(samples: &[Vec<u8>]) -> Vec<u8> {
    // chunk -> (count, first seen); Counter.most_common() is a stable sort on count.
    let mut counts: HashMap<&[u8], (u32, u32)> = HashMap::new();
    let mut order = 0u32;
    for raw in samples {
        let end = raw.len().saturating_sub(ZDICT_CHUNK);
        let mut i = 0;
        while i < end {
            let e = counts.entry(&raw[i..i + ZDICT_CHUNK]).or_insert_with(|| {
                order += 1;
                (0, order)
            });
            e.0 += 1;
            i += ZDICT_CHUNK / 4;
        }
    }
    let mut common: Vec<(&[u8], (u32, u32))> = counts.into_iter().filter(|(_, (n, _))| *n > 3).collect();
    common.sort_by(|a, b| b.1.0.cmp(&a.1.0).then(a.1.1.cmp(&b.1.1)));
    let mut picked = Vec::new();
    let mut total = 0;
    for (chunk, _) in common {
        if total + chunk.len() > ZDICT_SIZE {
            break;
        }
        picked.push(chunk);
        total += chunk.len();
    }
    picked.iter().rev().flat_map(|c| c.iter().copied()).collect()
}

pub struct Built {
    pub version: String,
    pub built_at: String,
    pub entries: usize,
}

/// `datetime.now(timezone.utc).replace(microsecond=0).isoformat()`.
fn now_iso() -> String {
    let secs = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs() as i64;
    let (days, rem) = (secs.div_euclid(86400), secs.rem_euclid(86400));
    // Civil-from-days (Howard Hinnant).
    let z = days + 719468;
    let era = z.div_euclid(146097);
    let doe = z - era * 146097;
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let y = yoe + era * 400 + i64::from(m <= 2);
    format!("{y:04}-{m:02}-{d:02}T{:02}:{:02}:{:02}+00:00", rem / 3600, rem / 60 % 60, rem % 60)
}

/// Writes `out/PACK_FILE` (via a .tmp swapped in atomically).
pub fn build(src: &Sources, out: &Path, verbose: bool) -> Result<Built> {
    let t = Instant::now();
    let pairs = &src.pairs;
    let mut rng = PyRandom::new(1);
    let mut sample: Vec<(u32, u32)> =
        pairs.iter().copied().filter(|_| rng.randrange(ZDICT_SAMPLE_EVERY) == 0).collect();
    if sample.is_empty() {
        sample = pairs.iter().take(200).copied().collect();
    }
    let encoded: Vec<Vec<u8>> = sample
        .par_iter()
        .map(|&(t, r)| {
            let mut buf = Vec::new();
            encode(&src.fields_for(t, r), &mut buf);
            buf
        })
        .collect();
    let zdict = train_zdict(&encoded);
    drop(encoded);
    if verbose {
        eprintln!("  zdict {} bytes from {} samples ({:.1}s)", zdict.len(), sample.len(), t.elapsed().as_secs_f64());
    }

    let tmp = out.join(format!("{PACK_FILE}.tmp"));
    let _ = std::fs::remove_file(&tmp);
    let mut conn = Connection::open(&tmp)?;
    conn.execute_batch(
        "PRAGMA journal_mode = OFF;
         PRAGMA synchronous = OFF;
         PRAGMA page_size = 4096;
         PRAGMA cache_size = -32768;
         CREATE TABLE meta (key TEXT PRIMARY KEY, value BLOB NOT NULL);
         CREATE TABLE entries (term TEXT NOT NULL, reading TEXT NOT NULL, data BLOB NOT NULL);",
    )?;
    let mut digest = Sha256::new();
    let format = FORMAT.to_string();
    digest.update(format.as_bytes());
    digest.update(&zdict);
    digest.update(DEFAULTS_JSON.as_bytes());
    digest.update(FALLBACK_KEYS_JSON.as_bytes());

    let tx = conn.transaction()?;
    let mut n = 0usize;
    let mut seen_keys = [false; KEY_ORDER.len()];
    {
        let mut ins = tx.prepare("INSERT INTO entries VALUES (?,?,?)")?;
        let mut done = 0;
        for chunk in pairs.chunks(CHUNK_PAIRS) {
            let blobs: Vec<Option<(Vec<u8>, u8)>> = chunk
                .par_iter()
                .map_init(
                    || (Deflater::new(), Vec::new()),
                    |(d, raw), &(t, r)| {
                        let values = src.fields_for(t, r);
                        if values.is_empty() {
                            return None;
                        }
                        let mut mask = 0u8;
                        for (k, _) in &values {
                            mask |= 1 << KEY_ORDER.iter().position(|x| x == k).unwrap();
                        }
                        encode(&values, raw);
                        let mut blob = Vec::new();
                        d.compress(raw, &zdict, &mut blob);
                        Some((blob, mask))
                    },
                )
                .collect();
            for (&(t, r), b) in chunk.iter().zip(blobs) {
                let Some((blob, mask)) = b else { continue };
                for (i, s) in seen_keys.iter_mut().enumerate() {
                    *s |= mask & (1 << i) != 0;
                }
                let (term, reading) = (src.str(t), src.str(r));
                digest.update(term.as_bytes());
                digest.update(b"\0");
                digest.update(reading.as_bytes());
                digest.update(b"\0");
                digest.update(&blob);
                ins.execute(params![term, reading, blob])?;
                n += 1;
            }
            done += chunk.len();
            if verbose {
                eprint!("\r  rows {done}/{} ({:.1}s)", pairs.len(), t.elapsed().as_secs_f64());
            }
        }
    }
    if verbose {
        eprintln!();
    }
    tx.execute_batch("CREATE INDEX entries_term ON entries (term)")?;
    let version: String = digest.finalize().iter().take(8).map(|b| format!("{b:02x}")).collect();
    let built_at = now_iso();
    let keys: Vec<&str> = KEY_ORDER.iter().zip(seen_keys).filter(|(_, s)| *s).map(|(k, _)| *k).collect();
    let keys_json = format!("[{}]", keys.iter().map(|k| format!("\"{k}\"")).collect::<Vec<_>>().join(", "));
    {
        let mut ins = tx.prepare("INSERT INTO meta VALUES (?,?)")?;
        ins.execute(params!["format", format])?;
        ins.execute(params!["zdict", zdict])?;
        ins.execute(params!["defaults", DEFAULTS_JSON])?;
        ins.execute(params!["fallback_keys", FALLBACK_KEYS_JSON])?;
        ins.execute(params!["version", version])?;
        ins.execute(params!["built_at", built_at])?;
        ins.execute(params!["entries", n.to_string()])?;
        ins.execute(params!["keys", keys_json])?;
    }
    tx.commit()?;
    conn.close().map_err(|(_, e)| e)?;
    std::fs::rename(&tmp, out.join(PACK_FILE))?;
    Ok(Built { version, built_at, entries: n })
}

pub fn file_sha256(path: &Path) -> Result<String> {
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 1 << 20];
    loop {
        let n = f.read(&mut buf)?;
        if n == 0 {
            break;
        }
        h.update(&buf[..n]);
    }
    Ok(h.finalize().iter().map(|b| format!("{b:02x}")).collect())
}

/// `_write_manifest`: the pack's own keys, then "sentence" when there is one.
pub fn write_manifest(out: &Path, built: &Built, sentence: Option<Value>) -> Result<Value> {
    let pack = out.join(PACK_FILE);
    let mut m = Map::new();
    m.insert("format".into(), json!(FORMAT));
    m.insert("version".into(), json!(built.version));
    m.insert("file".into(), json!(PACK_FILE));
    m.insert("size".into(), json!(std::fs::metadata(&pack)?.len()));
    m.insert("sha256".into(), json!(file_sha256(&pack)?));
    m.insert("built_at".into(), json!(built.built_at));
    m.insert("entries".into(), json!(built.entries));
    if let Some(s) = sentence {
        m.insert("sentence".into(), s);
    }
    let v = Value::Object(m);
    let tmp = out.join(format!("{MANIFEST_FILE}.tmp"));
    std::fs::write(&tmp, serde_json::to_string_pretty(&v)? + "\n")?;
    std::fs::rename(&tmp, out.join(MANIFEST_FILE))?;
    Ok(v)
}
