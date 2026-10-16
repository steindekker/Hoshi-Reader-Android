//! Port of `ankienrich/freq`: JPDB rank per (term, reading).

use std::collections::HashMap;

use crate::json::J;

use crate::py;
use crate::strtab::StrTable;

/// `build._parse`: (reading, rank) from one `term_meta` value.
fn parse(meta: &J) -> Option<(String, i64)> {
    match meta {
        J::Obj(_) => {
            if let Some(f) = meta.get("frequency") {
                f.as_obj()?;
                let v = f.get("value")?.as_i64()?;
                let reading = meta.get("reading").map(py::py_str).unwrap_or_default();
                return Some((reading, v));
            }
            Some((String::new(), meta.get("value")?.as_i64()?))
        }
        J::Int(_) | J::UInt(_) => Some((String::new(), meta.as_i64()?)),
        J::Str(s) if py::is_digits(s) => Some((String::new(), s.parse().ok()?)),
        _ => None,
    }
}

/// (key, reading, rank) from one `term_meta` bank entry.
pub fn parse_entry(e: &[J]) -> Option<(String, String, i64)> {
    if e.len() < 3 || e[1].as_str() != Some("freq") {
        return None;
    }
    let key = e[0].as_str()?;
    let (reading, value) = parse(&e[2])?;
    Some((key.to_string(), reading, value))
}

/// key -> [(reading, smallest rank)] (`freq.sqlite`; only ranks are ever read).
#[derive(Default)]
pub struct Freq {
    keys: StrTable,
    readings: StrTable,
    best: HashMap<(u32, u32), i64>,
    /// (key, reading, rank) sorted by key once `finish`ed.
    rows: Vec<(u32, u32, i64)>,
    start: Vec<u32>,
}

impl Freq {
    /// One (key, reading, rank) from `parse_entry`; the smallest rank wins.
    pub fn add(&mut self, key: &str, reading: &str, value: i64) {
        let k = (self.keys.intern(key), self.readings.intern(reading));
        let v = self.best.entry(k).or_insert(value);
        *v = (*v).min(value);
    }

    pub fn finish(&mut self) {
        let mut rows: Vec<(u32, u32, i64)> = self.best.drain().map(|((k, r), v)| (k, r, v)).collect();
        self.best = HashMap::new();
        rows.sort_unstable();
        let mut start = vec![0u32; self.keys.len() + 1];
        for &(k, _, _) in &rows {
            start[k as usize + 1] += 1;
        }
        for i in 0..self.keys.len() {
            start[i + 1] += start[i];
        }
        self.rows = rows;
        self.start = start;
    }

    pub fn len(&self) -> usize {
        self.rows.len()
    }

    fn rows_of(&self, key: &str) -> Option<&[(u32, u32, i64)]> {
        let k = self.keys.get(key)? as usize;
        Some(&self.rows[self.start[k] as usize..self.start[k + 1] as usize]).filter(|r| !r.is_empty())
    }

    /// `index.lookup(...)[0]`.
    pub fn lookup(&self, term: &str, reading: &str) -> Option<i64> {
        if let Some(rows) = self.rows_of(term) {
            let rd = self.readings.get(reading);
            let exact = rows.iter().filter(|x| Some(x.1) == rd).map(|x| x.2).min();
            return exact.or_else(|| rows.iter().map(|x| x.2).min());
        }
        if !reading.is_empty() {
            return self.rows_of(reading)?.iter().map(|x| x.2).min();
        }
        None
    }
}
