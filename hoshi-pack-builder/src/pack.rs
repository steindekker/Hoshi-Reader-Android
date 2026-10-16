//! Pack format 2 (see README): `ankienrich/pack.py` `build` with format-2 key names and no tags.

use std::collections::HashMap;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::time::Instant;

use anyhow::Result;
use rayon::prelude::*;
use rusqlite::{Connection, params};
use serde_json::{Map, Value, json};
use crate::sha::Sha256;

use crate::py::{self, PyRandom};
use crate::sources::Sources;
use crate::z::PrimedDeflater;

pub const FORMAT: u32 = 2;
pub const PACK_FILE: &str = "hoshi-pack.sqlite";
pub const MANIFEST_FILE: &str = "pack.json";

pub const DEFINITION: &str = "definition";
pub const DEFINITION_SOURCE: &str = "definition-source";
pub const PITCH: &str = "pitch-graph";
pub const EXAMPLES: &str = "example-sentences";
pub const FREQUENCY: &str = "frequency-rank";
pub const KEY_ORDER: [&str; 5] = [DEFINITION, DEFINITION_SOURCE, PITCH, EXAMPLES, FREQUENCY];

/// Format-1 (anki-enrich) key -> format-2 key; `ae-tags` (anki-enrich's own
/// bookkeeping tags) has no format-2 counterpart and is dropped.
pub const LEGACY_KEYS: [(&str, Option<&str>); 6] = [
    ("ae-definition", Some(DEFINITION)),
    ("ae-definition-source", Some(DEFINITION_SOURCE)),
    ("ae-pitch", Some(PITCH)),
    ("ae-examples", Some(EXAMPLES)),
    ("ae-frequency", Some(FREQUENCY)),
    ("ae-tags", None),
];

/// Values for a term with no row, and the keys still valid when the term is
/// known under another reading (the reading-dependent pitch graph and rank are not).
pub const DEFAULTS_JSON: &str = r#"{"definition": "{glossary-first}"}"#;
pub const FALLBACK_KEYS_JSON: &str = r#"["definition", "definition-source", "example-sentences"]"#;

static FIELDS_NS: AtomicU64 = AtomicU64::new(0);
static DEFLATE_NS: AtomicU64 = AtomicU64::new(0);
static WRITE_NS: AtomicU64 = AtomicU64::new(0);

const ZDICT_SIZE: usize = 32 * 1024;
const ZDICT_SAMPLE_EVERY: u32 = 50;
const ZDICT_CHUNK: usize = 64;
const CHUNK_PAIRS: usize = 2048;

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
    // Every chunk position as (sample, offset), in Counter insertion order; sorting
    // them by content groups equal chunks without a hash map of slices.
    let mut at: Vec<(u32, u32)> = Vec::new();
    for (si, raw) in samples.iter().enumerate() {
        let end = raw.len().saturating_sub(ZDICT_CHUNK);
        at.extend((0..end).step_by(ZDICT_CHUNK / 4).map(|i| (si as u32, i as u32)));
    }
    let chunk = |&(si, i): &(u32, u32)| &samples[si as usize][i as usize..i as usize + ZDICT_CHUNK];
    // (count, first seen) per distinct chunk; most_common() is a stable sort on count.
    let mut order: Vec<u32> = (0..at.len() as u32).collect();
    order.par_sort_by(|&x, &y| chunk(&at[x as usize]).cmp(chunk(&at[y as usize])).then(x.cmp(&y)));
    let mut common: Vec<(u32, u32)> = Vec::new();
    let mut i = 0;
    while i < order.len() {
        let first = order[i];
        let mut j = i + 1;
        while j < order.len() && chunk(&at[order[j] as usize]) == chunk(&at[first as usize]) {
            j += 1;
        }
        let n = (j - i) as u32;
        if n > 3 {
            common.push((n, first));
        }
        i = j;
    }
    drop(order);
    common.sort_by(|a, b| b.0.cmp(&a.0).then(a.1.cmp(&b.1)));
    let mut picked = Vec::new();
    let mut total = 0;
    for &(_, first) in &common {
        if total + ZDICT_CHUNK > ZDICT_SIZE {
            break;
        }
        picked.push(chunk(&at[first as usize]));
        total += ZDICT_CHUNK;
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

thread_local! {
    /// This thread's deflater, primed with the zdict it was made for.
    static DEFLATER: std::cell::RefCell<Option<(Vec<u8>, PrimedDeflater)>> = const { std::cell::RefCell::new(None) };
}

/// One row's encoded values and the mask of keys it carries, or None.
fn row_raw(src: &Sources, t: u32, r: u32) -> Option<(Vec<u8>, u8)> {
    let t0 = Instant::now();
    let values = src.fields_for(t, r);
    FIELDS_NS.fetch_add(t0.elapsed().as_nanos() as u64, Relaxed);
    if values.is_empty() {
        return None;
    }
    let mut mask = 0u8;
    for (k, _) in &values {
        mask |= 1 << KEY_ORDER.iter().position(|x| x == k).unwrap();
    }
    let mut raw = Vec::new();
    encode(&values, &mut raw);
    Some((raw, mask))
}

fn deflate(raw: &[u8], zdict: &[u8]) -> Vec<u8> {
    let t1 = Instant::now();
    let mut blob = Vec::new();
    DEFLATER.with_borrow_mut(|d| {
        if d.as_ref().is_none_or(|(z, _)| z.as_slice() != zdict) {
            *d = Some((zdict.to_vec(), PrimedDeflater::new(zdict)));
        }
        d.as_mut().unwrap().1.compress(raw, &mut blob);
    });
    DEFLATE_NS.fetch_add(t1.elapsed().as_nanos() as u64, Relaxed);
    blob
}

/// The blobs of a chunk of headwords, computed on one thread. Neighbouring
/// headwords often carry the same values (a term's readings without a pitch
/// graph), and the same input always deflates to the same bytes, so each
/// distinct row is compressed once.
fn chunk_blobs(src: &Sources, chunk: &[(u32, u32)], zdict: &[u8]) -> Vec<Option<(Vec<u8>, u8)>> {
    let raws: Vec<Option<(Vec<u8>, u8)>> = chunk.iter().map(|&(t, r)| row_raw(src, t, r)).collect();
    let mut first: HashMap<&[u8], usize> = HashMap::new();
    let mut blobs: Vec<Vec<u8>> = Vec::new();
    let mut out = Vec::with_capacity(raws.len());
    for row in &raws {
        out.push(row.as_ref().map(|(raw, mask)| {
            let k = *first.entry(raw.as_slice()).or_insert_with(|| {
                blobs.push(deflate(raw, zdict));
                blobs.len() - 1
            });
            (blobs[k].clone(), *mask)
        }));
    }
    out
}

/// Hands out chunk indices in order to the workers and lets a worker run at
/// most `ahead` chunks past the writer, bounding the reorder buffer.
struct Queue {
    next: std::sync::Mutex<(usize, usize)>, // (next to hand out, next to be written)
    moved: std::sync::Condvar,
    ahead: usize,
    stop: std::sync::atomic::AtomicBool,
}

impl Queue {
    fn take(&self, n: usize) -> Option<usize> {
        let mut g = self.next.lock().unwrap();
        loop {
            if self.stop.load(Relaxed) || g.0 >= n {
                return None;
            }
            if g.0 < g.1 + self.ahead {
                g.0 += 1;
                return Some(g.0 - 1);
            }
            g = self.moved.wait(g).unwrap();
        }
    }

    fn written(&self, upto: usize) {
        self.next.lock().unwrap().1 = upto;
        self.moved.notify_all();
    }

    fn halt(&self) {
        self.stop.store(true, Relaxed);
        self.moved.notify_all();
    }
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
    crate::prof::log("zdict", t);
    drop(encoded);
    crate::sources::trim();
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
         PRAGMA cache_size = -8192;
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
    // Workers each take the next chunk and compress it alone (no barrier per
    // chunk); this thread puts chunks back in order, inserts and hashes them.
    let chunks: Vec<&[(u32, u32)]> = pairs.chunks(CHUNK_PAIRS).collect();
    let workers = rayon::current_num_threads();
    let queue = Queue {
        next: std::sync::Mutex::new((0, 0)),
        moved: std::sync::Condvar::new(),
        ahead: 2 * workers + 2,
        stop: std::sync::atomic::AtomicBool::new(false),
    };
    type Done = (usize, Vec<Option<(Vec<u8>, u8)>>);
    let (send, recv) = std::sync::mpsc::channel::<Done>();
    let result = std::thread::scope(|scope| -> Result<()> {
        let (zdict, queue, chunks) = (&zdict, &queue, &chunks);
        scope.spawn(move || {
            rayon::scope(|s| {
                for _ in 0..workers {
                    let send = send.clone();
                    s.spawn(move |_| {
                        while let Some(i) = queue.take(chunks.len()) {
                            if send.send((i, chunk_blobs(src, chunks[i], zdict))).is_err() {
                                break;
                            }
                        }
                    });
                }
            });
        });
        let mut write = || -> Result<()> {
        let mut ins = tx.prepare("INSERT INTO entries VALUES (?,?,?)")?;
        let mut pending: std::collections::BTreeMap<usize, Vec<Option<(Vec<u8>, u8)>>> = Default::default();
        let (mut next, mut done) = (0, 0);
        while next < chunks.len() {
            let Ok((i, blobs)) = recv.recv() else { break };
            pending.insert(i, blobs);
            while let Some(blobs) = pending.remove(&next) {
                let tw = Instant::now();
                for (&(t, r), b) in chunks[next].iter().zip(blobs) {
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
                WRITE_NS.fetch_add(tw.elapsed().as_nanos() as u64, Relaxed);
                done += chunks[next].len();
                next += 1;
                queue.written(next);
                if verbose {
                    eprint!("\r  rows {done}/{} ({:.1}s)", pairs.len(), t.elapsed().as_secs_f64());
                }
            }
        }
        Ok(())
        };
        let r = write();
        // A failed writer must release workers waiting for it before the scope joins them.
        if r.is_err() {
            queue.halt();
        }
        r
    });
    result?;
    if verbose {
        eprintln!();
    }
    let ti = Instant::now();
    tx.execute_batch("CREATE INDEX entries_term ON entries (term)")?;
    crate::prof::log("create index", ti);
    if crate::prof::on() {
        let f = |a: &AtomicU64| a.load(Relaxed) as f64 / 1e9;
        eprintln!(
            "  [prof] thread-summed fields_for {:.2}s, deflate {:.2}s; writer insert+digest {:.2}s",
            f(&FIELDS_NS),
            f(&DEFLATE_NS),
            f(&WRITE_NS)
        );
    }
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
    let tc = Instant::now();
    tx.commit()?;
    conn.close().map_err(|(_, e)| e)?;
    crate::prof::log("commit+close", tc);
    std::fs::rename(&tmp, out.join(PACK_FILE))?;
    Ok(Built { version, built_at, entries: n })
}

pub fn file_sha256(path: &Path) -> Result<String> {
    use std::io::Read;
    let mut f = std::fs::File::open(path)?;
    let mut h = Sha256::new();
    let mut buf = vec![0u8; 4 << 20];
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
