//! Port of `ankienrich/examples`: per-source sentence extraction (`parse`), the
//! Tatoeba index (`build._index_tatoeba`), priority-fill selection
//! (`index.select`) and the card block (`render.render_block`).

use std::collections::{HashMap, HashSet};
use std::path::Path;

use anyhow::Result;
use regex::Regex;
use serde_json::{Map, Value};

use crate::py;

/// Selection order (config.EXAMPLE_SOURCES); the index is the row's `src`.
pub const SOURCES: [&str; 4] = ["jitendex", "tatoeba", "saito", "yourei"];
pub const JITENDEX: u8 = 0;
pub const TATOEBA: u8 = 1;
pub const SAITO: u8 = 2;
pub const YOUREI: u8 = 3;
pub const CAP: usize = 3;
pub const BUILD_CAP: usize = 12;
pub const MAX_LEN: usize = 60;

pub struct Example {
    pub jp: String,
    pub en: String,
    pub plain: String,
}

/// `parse.norm`: the dedupe key.
pub fn norm(s: &str) -> String {
    s.chars()
        .filter(|&c| !py::is_space(c) && !"。、，．！？!?「」『』（）()…・".contains(c))
        .collect()
}

/// `parse.highlight`.
pub fn highlight(text: &str, keyword: &str) -> String {
    let mut out = String::with_capacity(text.len() + 32);
    match (!keyword.is_empty()).then(|| text.find(keyword)).flatten() {
        Some(i) => {
            py::escape_into(&mut out, &text[..i]);
            out.push_str("<b class=\"aa-ex-kw\">");
            py::escape_into(&mut out, keyword);
            out.push_str("</b>");
            py::escape_into(&mut out, &text[i + keyword.len()..]);
        }
        None => py::escape_into(&mut out, text),
    }
    out
}

/// `_walk`: every dict node, preorder, descending through "content".
fn walk<'a>(node: &'a Value, out: &mut Vec<&'a Map<String, Value>>) {
    match node {
        Value::Object(m) => {
            out.push(m);
            if let Some(c) = m.get("content") {
                walk(c, out);
            }
        }
        Value::Array(a) => a.iter().for_each(|x| walk(x, out)),
        _ => {}
    }
}

fn walked(node: &Value) -> Vec<&Map<String, Value>> {
    let mut v = Vec::new();
    walk(node, &mut v);
    v
}

fn data_content<'a>(node: &'a Map<String, Value>) -> Option<&'a Value> {
    match node.get("data") {
        Some(Value::Object(d)) => d.get("content"),
        _ => None,
    }
}

fn data_is(node: &Map<String, Value>, want: &str) -> bool {
    data_content(node).and_then(Value::as_str) == Some(want)
}

fn content(node: &Map<String, Value>) -> &Value {
    node.get("content").unwrap_or(&Value::Null)
}

fn children(node: &Map<String, Value>) -> &[Value] {
    match node.get("content") {
        None | Some(Value::Null) => &[],
        Some(Value::Array(a)) => a,
        Some(other) => std::slice::from_ref(other),
    }
}

fn tag_is(node: &Map<String, Value>, want: &str) -> bool {
    node.get("tag").and_then(Value::as_str) == Some(want)
}

fn style_is(node: &Map<String, Value>, key: &str, want: &str) -> bool {
    match node.get("style") {
        Some(Value::Object(s)) => s.get(key).and_then(Value::as_str) == Some(want),
        _ => false,
    }
}

/// `_plain`: base text without <rt>.
fn plain_into(out: &mut String, node: &Value) {
    match node {
        Value::String(s) => out.push_str(s),
        Value::Array(a) => a.iter().for_each(|c| plain_into(out, c)),
        Value::Object(m) => {
            if !tag_is(m, "rt") {
                plain_into(out, content(m));
            }
        }
        _ => {}
    }
}

fn plain(node: &Value) -> String {
    let mut s = String::new();
    plain_into(&mut s, node);
    s
}

/// `_render_ruby`.
fn render_ruby(out: &mut String, node: &Value) {
    match node {
        Value::String(s) => py::escape_into(out, s),
        Value::Array(a) => a.iter().for_each(|c| render_ruby(out, c)),
        Value::Object(m) => {
            if data_is(m, "example-keyword") {
                out.push_str("<b class=\"aa-ex-kw\">");
                render_ruby(out, content(m));
                out.push_str("</b>");
                return;
            }
            match m.get("tag").and_then(Value::as_str) {
                Some(t @ ("ruby" | "rt")) => {
                    out.push('<');
                    out.push_str(t);
                    out.push('>');
                    render_ruby(out, content(m));
                    out.push_str("</");
                    out.push_str(t);
                    out.push('>');
                }
                _ => render_ruby(out, content(m)),
            }
        }
        _ => {}
    }
}

pub fn jitendex(sc: &Value) -> Vec<Example> {
    let mut out = Vec::new();
    for bx in walked(sc) {
        if !data_is(bx, "example-sentence") {
            continue;
        }
        let inner = walked(content(bx));
        // _walk(box) yields box itself first.
        let find = |want: &str| {
            if data_is(bx, want) { Some(bx) } else { inner.iter().copied().find(|n| data_is(n, want)) }
        };
        let Some(a) = find("example-sentence-a") else { continue };
        let b = find("example-sentence-b");
        let mut jp = String::new();
        render_ruby(&mut jp, content(a));
        let en = b.map(|b| py::strip(&plain(content(b))).to_string()).unwrap_or_default();
        let p = plain(content(a));
        if !py::strip(&p).is_empty() {
            out.push(Example { jp, en, plain: p });
        }
    }
    out
}

fn li_text(li: &Map<String, Value>) -> String {
    py::strip(&plain(content(li))).to_string()
}

pub fn saito(sc: &Value, keyword: &str) -> Vec<Example> {
    let mut out = Vec::new();
    let push = |out: &mut Vec<Example>, p: String, en: String| {
        out.push(Example { jp: highlight(&p, keyword), en, plain: p });
    };
    for ul in walked(sc) {
        if !tag_is(ul, "ul") {
            continue;
        }
        let mut pending: Option<String> = None;
        for li in children(ul) {
            let Value::Object(li) = li else { continue };
            if !tag_is(li, "li") {
                continue;
            }
            if style_is(li, "listStyleType", "'◆'") {
                if let Some(p) = pending.take() {
                    push(&mut out, p, String::new());
                }
                pending = Some(li_text(li));
            } else if style_is(li, "fontSize", "70%") && pending.is_some() {
                push(&mut out, pending.take().unwrap(), li_text(li));
            }
        }
        if let Some(p) = pending {
            push(&mut out, p, String::new());
        }
    }
    out
}

/// `_sentence_at`: the 。！？-delimited sentence covering char `offset`, stripped.
fn sentence_at(text: &str, offset: usize) -> &str {
    let (mut start_b, mut start, mut pos) = (0usize, 0usize, 0usize);
    for (bi, ch) in text.char_indices() {
        pos += 1;
        if "。！？!?".contains(ch) {
            let end_b = bi + ch.len_utf8();
            if start <= offset && offset < pos {
                return py::strip(&text[start_b..end_b]);
            }
            start = pos;
            start_b = end_b;
        }
    }
    py::strip(&text[start_b..])
}

fn yourei_li(li: &Map<String, Value>) -> Option<Example> {
    let mut segs: Vec<(String, bool)> = Vec::new();
    for span in children(li) {
        match span {
            Value::String(s) => segs.push((s.clone(), false)),
            Value::Object(span) => {
                for s in children(span) {
                    let Value::Object(s) = s else { continue };
                    if tag_is(s, "a") {
                        continue;
                    }
                    let txt = plain(content(s));
                    if !txt.is_empty() {
                        let red = style_is(s, "color", "red");
                        segs.push((txt, red));
                    }
                }
            }
            _ => {}
        }
    }
    let full: String = segs.iter().map(|(t, _)| t.as_str()).collect();
    let (mut kw_off, mut keyword, mut pos) = (0usize, None, 0usize);
    for (txt, is_kw) in &segs {
        if *is_kw && keyword.is_none() {
            kw_off = pos;
            keyword = Some(txt.as_str());
        }
        pos += txt.chars().count();
    }
    // `if not keyword`: an empty keyword never gets in (segments are non-empty).
    let keyword = keyword?;
    let sent = sentence_at(&full, kw_off);
    if sent.is_empty() {
        return None;
    }
    Some(Example { jp: highlight(sent, keyword), en: String::new(), plain: sent.to_string() })
}

pub fn yourei(sc: &Value) -> Vec<Example> {
    let mut out = Vec::new();
    for ul in walked(sc) {
        if !tag_is(ul, "ul") {
            continue;
        }
        for li in children(ul) {
            if let Value::Object(li) = li {
                if tag_is(li, "li") {
                    out.extend(yourei_li(li));
                }
            }
        }
    }
    out
}

/// Lines as Python's text-mode file iteration yields them (universal newlines),
/// without the line break.
fn lines(text: &str) -> impl Iterator<Item = &str> {
    let mut rest = text;
    std::iter::from_fn(move || {
        if rest.is_empty() {
            return None;
        }
        let end = rest.find(['\n', '\r']).unwrap_or(rest.len());
        let line = &rest[..end];
        let skip = if rest[end..].starts_with("\r\n") { 2 } else if end < rest.len() { 1 } else { 0 };
        rest = &rest[end + skip..];
        Some(line)
    })
}

/// `_load_tsv`, streamed: only the `keep` ids are ever held.
fn load_tsv(path: &Path, keep: &HashSet<&str>) -> Result<HashMap<String, String>> {
    use std::io::BufRead;
    let mut r = std::io::BufReader::with_capacity(1 << 20, std::fs::File::open(path)?);
    let mut out = HashMap::new();
    let mut raw = Vec::new();
    loop {
        raw.clear();
        if r.read_until(b'\n', &mut raw)? == 0 {
            break;
        }
        let chunk = std::str::from_utf8(&raw)?;
        // Universal newlines: a lone \r also ends a line.
        for line in lines(chunk) {
            let mut parts = line.split('\t');
            let (Some(id), Some(_), Some(t)) = (parts.next(), parts.next(), parts.next()) else { continue };
            if keep.contains(id) {
                out.insert(id.to_string(), t.to_string());
            }
        }
    }
    Ok(out)
}

/// Rows of the Tatoeba index: ((headword, reading), examples in ord order).
pub fn tatoeba(dir: &Path) -> Result<Vec<((String, String), Vec<Example>)>> {
    let idx_path = dir.join("jpn_indices.csv");
    if !idx_path.exists() {
        return Ok(Vec::new());
    }
    let idx_text = std::fs::read_to_string(&idx_path)?;
    let mut rows = Vec::new();
    let (mut jp_ids, mut eng_ids) = (HashSet::new(), HashSet::new());
    for line in lines(&idx_text) {
        let parts: Vec<&str> = line.split('\t').collect();
        if parts.len() < 3 {
            continue;
        }
        rows.push((parts[0], parts[1], parts[2]));
        jp_ids.insert(parts[0]);
        if py::is_digits(parts[1]) {
            eng_ids.insert(parts[1]);
        }
    }
    let (jp_text, en_text) = rayon::join(
        || load_tsv(&dir.join("jpn_sentences.tsv"), &jp_ids),
        || load_tsv(&dir.join("eng_sentences.tsv"), &eng_ids),
    );
    let (jp_text, en_text) = (jp_text?, en_text?);

    let tok = Regex::new(r"^([^\s(\[{~]+)(?:\(([^)]*)\))?(?:\[[^\]]*\])?(?:\{([^}]*)\})?~?$").unwrap();
    let kana = Regex::new(r"^[\u{3040}-\u{30FF}ー]+$").unwrap();
    let mut order: Vec<(String, String)> = Vec::new();
    let mut buckets: HashMap<(String, String), Vec<Example>> = HashMap::new();
    for (jp_id, eng_id, tokens) in rows {
        let Some(jp) = jp_text.get(jp_id).filter(|s| !s.is_empty()) else { continue };
        let en = en_text.get(eng_id).map(String::as_str).unwrap_or("");
        let mut seen_here: HashSet<(&str, &str)> = HashSet::new();
        for t in tokens.split(' ') {
            let Some(m) = tok.captures(t) else { continue };
            let hw = m.get(1).unwrap().as_str();
            let mut reading = m.get(2).map_or("", |x| x.as_str());
            let surface = m.get(3).map_or("", |x| x.as_str());
            if reading.is_empty() && kana.is_match(hw) {
                reading = hw;
            }
            if !seen_here.insert((hw, reading)) {
                continue;
            }
            let key = (hw.to_string(), reading.to_string());
            let buf = match buckets.get_mut(&key) {
                Some(b) => b,
                None => {
                    order.push(key.clone());
                    buckets.entry(key).or_default()
                }
            };
            if buf.len() >= BUILD_CAP {
                continue;
            }
            let kw = if surface.is_empty() { hw } else { surface };
            buf.push(Example { jp: highlight(jp, kw), en: en.to_string(), plain: jp.clone() });
        }
    }
    Ok(order.into_iter().map(|k| { let v = buckets.remove(&k).unwrap(); (k, v) }).collect())
}

/// One `examples.sqlite` row of a term, kept in rowid order; its text (jp, en,
/// plain back to back from `off`) lives in the source store.
pub struct ExRow {
    pub reading: u32,
    pub src: u8,
    pub ord: u8,
    pub off: u64,
    pub jp: u32,
    pub en: u32,
    pub plain: u32,
}

/// `index.select` + `render.render_block`: the Examples value, "" when none.
pub fn examples_for<'a>(
    rows: &'a [ExRow],
    reading: u32,
    text: impl Fn(&'a ExRow) -> (&'a str, &'a str, &'a str),
    str_of: impl Fn(u32) -> &'a str,
) -> String {
    let mut chosen: Vec<(&str, &str)> = Vec::new();
    let mut seen: HashSet<String> = HashSet::new();
    'outer: for src in 0..SOURCES.len() as u8 {
        let mut src_rows: Vec<&ExRow> = rows.iter().filter(|r| r.src == src).collect();
        if src_rows.is_empty() {
            continue;
        }
        // Index order (reading, rowid), then Python's stable sort on ord.
        src_rows.sort_by(|a, b| a.ord.cmp(&b.ord).then_with(|| str_of(a.reading).as_bytes().cmp(str_of(b.reading).as_bytes())));
        let picked: Vec<&ExRow> = if src_rows.iter().any(|r| r.reading == reading) {
            src_rows.into_iter().filter(|r| r.reading == reading).collect()
        } else if src_rows.iter().any(|r| r.reading == 0) {
            src_rows.into_iter().filter(|r| r.reading == 0).collect()
        } else {
            src_rows
        };
        for r in picked {
            let (jp, en, plain) = text(r);
            if plain.chars().count() > MAX_LEN {
                continue;
            }
            let key = norm(plain);
            if key.is_empty() || seen.contains(&key) {
                continue;
            }
            seen.insert(key);
            chosen.push((jp, en));
            if chosen.len() >= CAP {
                break 'outer;
            }
        }
    }
    if chosen.is_empty() {
        return String::new();
    }
    let mut out = String::from("<details class=\"aa-ex\"><summary>例文</summary><div class=\"aa-ex-list\">");
    for (jp, en) in chosen {
        out.push_str("<div class=\"aa-ex-item\"><span class=\"aa-ex-jp\" lang=\"ja\">");
        out.push_str(jp);
        out.push_str("</span>");
        if !en.is_empty() {
            out.push_str("<span class=\"aa-ex-en\">");
            py::escape_into(&mut out, en);
            out.push_str("</span>");
        }
        out.push_str("</div>");
    }
    out.push_str("</div></details>");
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;

    #[test]
    fn yourei_keeps_the_headword_sentence() {
        let li = json!({"tag": "ul", "content": [{"tag": "li", "content": [
            {"tag": "span", "content": [
                {"tag": "span", "content": "前の文。ここで"},
                {"tag": "span", "style": {"color": "red"}, "content": "猫"},
                {"tag": "span", "content": "が鳴いた。後の文"},
                {"tag": "a", "content": "本"}
            ]}
        ]}]});
        let ex = yourei(&li);
        assert_eq!(ex.len(), 1);
        assert_eq!(ex[0].plain, "ここで猫が鳴いた。");
        assert_eq!(ex[0].jp, "ここで<b class=\"aa-ex-kw\">猫</b>が鳴いた。");
    }

    #[test]
    fn norm_drops_space_and_punctuation() {
        assert_eq!(norm("「猫」が 鳴いた。"), "猫が鳴いた");
    }
}
