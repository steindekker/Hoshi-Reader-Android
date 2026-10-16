//! The accent index (`ankienrich/data/accent_index.marshal.gz`, a marshalled
//! `dict[str, list[int]]`), `pitch.accents_for` and `pitch_svg.render_multi`.

use std::io::Read;
use std::path::Path;

use anyhow::{Context, Result, bail};

use crate::py;
use crate::strtab::StrTable;

pub struct AccentIndex {
    /// "lemma\treading_hira", id = the dict's insertion order.
    keys: StrTable,
    pos_start: Vec<u32>,
    pos: Vec<i64>,
}

impl AccentIndex {
    pub fn load(path: &Path) -> Result<Self> {
        let mut raw = Vec::new();
        flate2::read::GzDecoder::new(std::fs::File::open(path).with_context(|| format!("open {}", path.display()))?)
            .read_to_end(&mut raw)?;
        let mut idx = AccentIndex { keys: StrTable::default(), pos_start: vec![0], pos: Vec::new() };
        let mut m = Marshal { b: &raw, pos: 0, refs: Vec::new() };
        m.read_dict(|k, v| {
            let (Obj::Str(k), Obj::List(v)) = (k, v) else { bail!("accent index entry is not str -> list") };
            let id = idx.keys.intern(&k);
            if id as usize + 1 != idx.pos_start.len() {
                bail!("duplicate accent key {k:?}");
            }
            for x in v {
                let Obj::Int(i) = x else { bail!("accent position is not an int") };
                idx.pos.push(i);
            }
            idx.pos_start.push(idx.pos.len() as u32);
            Ok(())
        })
        .context("accent index marshal")?;
        Ok(idx)
    }

    pub fn len(&self) -> usize {
        self.keys.len()
    }

    /// (key, positions) in index order.
    pub fn iter(&self) -> impl Iterator<Item = (&str, &[i64])> {
        (0..self.len() as u32).map(|id| (self.keys.str(id), self.positions(id)))
    }

    fn positions(&self, id: u32) -> &[i64] {
        &self.pos[self.pos_start[id as usize] as usize..self.pos_start[id as usize + 1] as usize]
    }

    pub fn lookup(&self, lemma: &str, reading_hira: &str) -> Option<&[i64]> {
        let mut key = String::with_capacity(lemma.len() + reading_hira.len() + 1);
        key.push_str(lemma);
        key.push('\t');
        key.push_str(reading_hira);
        self.keys.get(&key).map(|id| self.positions(id))
    }

    /// `pitch.accents_for`.
    pub fn accents_for(&self, term: &str, reading: &str) -> Option<&[i64]> {
        let r = py::kata2hira(py::strip(reading));
        let t = py::strip(term);
        for lemma in [t, py::strip(reading), r.as_str()] {
            if let Some(p) = self.lookup(lemma, &r).filter(|p| !p.is_empty()) {
                return Some(p);
            }
        }
        None
    }
}

#[derive(Debug, Clone)]
#[allow(dead_code)] // decoded for completeness; the index only holds str/list/int
enum Obj {
    Null,
    None,
    Bool(bool),
    Int(i64),
    Float(f64),
    Str(String),
    Bytes(Vec<u8>),
    List(Vec<Obj>),
    Dict(Vec<(Obj, Obj)>),
}

/// Just enough of CPython's marshal format (version 4) for plain data.
struct Marshal<'a> {
    b: &'a [u8],
    pos: usize,
    refs: Vec<Option<Obj>>,
}

impl Marshal<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8]> {
        let s = self.b.get(self.pos..self.pos + n).context("truncated marshal")?;
        self.pos += n;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_le_bytes(self.take(4)?.try_into().unwrap()))
    }

    /// The top-level dict, item by item, so the index never exists as one tree.
    fn read_dict(&mut self, mut f: impl FnMut(Obj, Obj) -> Result<()>) -> Result<()> {
        let code = self.u8()?;
        if code & 0x7f != b'{' {
            bail!("not a marshalled dict");
        }
        if code & 0x80 != 0 {
            self.refs.push(None);
        }
        loop {
            let k = self.read()?;
            if matches!(k, Obj::Null) {
                return Ok(());
            }
            let v = self.read()?;
            f(k, v)?;
        }
    }

    fn read(&mut self) -> Result<Obj> {
        let code = self.u8()?;
        let flag = code & 0x80 != 0;
        let ty = code & 0x7f;
        // Containers reserve their ref slot before their items, scalars after.
        let slot = if flag { self.refs.push(None); Some(self.refs.len() - 1) } else { None };
        let obj = match ty {
            b'0' => Obj::Null,
            b'N' => Obj::None,
            b'F' => Obj::Bool(false),
            b'T' => Obj::Bool(true),
            b'i' => Obj::Int(self.i32()? as i64),
            b'l' => {
                let n = self.i32()?;
                let mut v: i64 = 0;
                for i in 0..n.unsigned_abs() {
                    let d = u16::from_le_bytes(self.take(2)?.try_into().unwrap()) as i64;
                    v += d << (15 * i);
                }
                Obj::Int(if n < 0 { -v } else { v })
            }
            b'g' => Obj::Float(f64::from_le_bytes(self.take(8)?.try_into().unwrap())),
            b'u' | b't' | b'a' | b'A' => {
                let n = self.i32()? as usize;
                Obj::Str(String::from_utf8(self.take(n)?.to_vec())?)
            }
            b'z' | b'Z' => {
                let n = self.u8()? as usize;
                Obj::Str(String::from_utf8(self.take(n)?.to_vec())?)
            }
            b's' => {
                let n = self.i32()? as usize;
                Obj::Bytes(self.take(n)?.to_vec())
            }
            b'[' | b'(' | b'<' | b'>' => {
                let n = self.i32()? as usize;
                Obj::List((0..n).map(|_| self.read()).collect::<Result<_>>()?)
            }
            b')' => {
                let n = self.u8()? as usize;
                Obj::List((0..n).map(|_| self.read()).collect::<Result<_>>()?)
            }
            b'{' => {
                let mut items = Vec::new();
                loop {
                    let k = self.read()?;
                    if matches!(k, Obj::Null) {
                        break;
                    }
                    let v = self.read()?;
                    items.push((k, v));
                }
                Obj::Dict(items)
            }
            b'r' => {
                let i = self.i32()? as usize;
                self.refs.get(i).cloned().flatten().context("bad marshal ref")?
            }
            other => bail!("unsupported marshal type {:?}", other as char),
        };
        if let Some(s) = slot {
            self.refs[s] = Some(obj.clone());
        }
        Ok(obj)
    }
}

const I_DAN: &str = "キギシジチヂニヒビピミリ";
const E_COMP: &str = "イウキギクグシジチツニヒビピフミリヴ";

/// `pitch_svg._combines`.
fn combines(ck: char, nk: char) -> bool {
    let in_ = |c: char, s: &str| s.contains(c);
    (in_(ck, I_DAN) && in_(nk, "ャュョ"))
        || (nk == 'ヮ' && in_(ck, "クグ"))
        || (nk == 'ァ' && in_(ck, "ツフヴ"))
        || (nk == 'ィ' && in_(ck, "クグスズテツデフイウヴ"))
        || (nk == 'ゥ' && in_(ck, "トドホウ"))
        || (nk == 'ェ' && in_(ck, E_COMP))
        || (nk == 'ォ' && in_(ck, "クグツフウヴ"))
        || (nk == 'ャ' && in_(ck, "フヴ"))
        || (nk == 'ュ' && in_(ck, "テデフウヴ"))
        || (nk == 'ョ' && in_(ck, "フヴ"))
}

/// `pitch_svg.split_moras`.
fn split_moras(reading: &str) -> Vec<String> {
    let chars: Vec<char> = reading.chars().collect();
    let kata: Vec<char> = chars.iter().map(|&c| py::hira2kata(c)).collect();
    let mut out = Vec::new();
    let mut i = 0;
    while i < chars.len() {
        if i + 1 < chars.len() && combines(kata[i], kata[i + 1]) {
            out.push(chars[i..i + 2].iter().collect());
            i += 2;
        } else {
            out.push(chars[i].to_string());
            i += 1;
        }
    }
    out
}

fn is_high(i: i64, accent: i64) -> bool {
    match accent {
        0 => i != 0,
        1 => i == 0,
        _ => 1 <= i && i < accent,
    }
}

/// `pitch_svg.render_svg`.
fn render_svg(out: &mut String, reading: &str, accent: i64) {
    use std::fmt::Write;
    let moras = split_moras(reading);
    let n = moras.len() as i64;
    if n == 0 {
        return;
    }
    let pts: Vec<(i64, i64)> = (0..=n).map(|i| (16 + 35 * i, if is_high(i, accent) { 5 } else { 30 })).collect();
    let vb_w = 35 * n + 32;
    let _ = write!(
        out,
        "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"{}px\" height=\"45px\" viewBox=\"0 0 {vb_w} 75\">",
        py::format_g(vb_w as f64 * 0.6)
    );
    for (i, m) in moras.iter().enumerate() {
        let _ = write!(
            out,
            "<text x=\"{}\" y=\"67.5\" style=\"font-size:20px;font-family:sans-serif;fill:currentColor;\">{m}</text>",
            5 + 35 * i
        );
    }
    for i in 0..n as usize {
        let ((x0, y0), (_, y1)) = (pts[i], pts[i + 1]);
        let _ = write!(
            out,
            "<path d=\"m {x0},{y0} 35,{}\" style=\"fill:none;stroke:currentColor;stroke-width:1.5;\" />",
            y1 - y0
        );
    }
    for &(x, y) in &pts[..n as usize] {
        let _ = write!(out, "<circle r=\"5\" cx=\"{x}\" cy=\"{y}\" style=\"opacity:1;fill:currentColor;\"></circle>");
    }
    let (px, py_) = pts[n as usize];
    let _ = write!(
        out,
        "<circle r=\"4\" cx=\"{px}\" cy=\"{py_}\" stroke=\"currentColor\" style=\"opacity:1;fill:none;\"></circle>"
    );
    out.push_str("</svg>");
}

/// `pitch_svg.render_multi`.
pub fn render_multi(reading: &str, accents: &[i64]) -> String {
    let mut out = String::new();
    for (i, &a) in accents.iter().enumerate() {
        if i > 0 {
            out.push_str("<br>");
        }
        render_svg(&mut out, reading, a);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn splits_moras() {
        assert_eq!(split_moras("きょうと"), ["きょ", "う", "と"]);
        assert_eq!(split_moras("ファイル"), ["ファ", "イ", "ル"]);
    }
}
