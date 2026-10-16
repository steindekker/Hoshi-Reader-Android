//! Port of `ankienrich/defs`: structured content -> HTML (`render.render_sc`),
//! JMdict gloss extraction (`build._glosses`), and the definition chosen for a
//! (term, reading) (`render.choose_jj`, `numbered_glosses`, `assemble_meaning`).

use crate::json::{J, NULL};

use crate::py;

pub const JJ_CHAIN: [&str; 4] = ["sankoku8", "koujien7", "jitsuyo", "daijisen"];
pub const JE_DICT: &str = "jmdict";
pub const GLOSS_CAP: usize = 4;

pub fn label(dict_id: &str) -> &str {
    match dict_id {
        "sankoku8" => "三省堂",
        "koujien7" => "広辞苑",
        "jitsuyo" => "実用",
        "daijisen" => "大辞泉",
        "jmdict" => "JMdict",
        other => other,
    }
}

/// `re.sub(r"[A-Z]", lambda m: "-" + m.group(0).lower(), k)`.
fn kebab_into(out: &mut String, k: &str) {
    for c in k.chars() {
        if c.is_ascii_uppercase() {
            out.push('-');
            out.push(c.to_ascii_lowercase());
        } else {
            out.push(c);
        }
    }
}

/// `render_sc(node)`, appended to `out`.
pub fn render_sc(out: &mut String, node: &J) {
    match node {
        J::Str(s) => py::escape_br_into(out, s),
        J::Arr(a) => a.iter().for_each(|c| render_sc(out, c)),
        J::Obj(_) => {
            let content = node.get("content").unwrap_or(&NULL);
            match node.get("type").and_then(J::as_str) {
                Some("structured-content") => return render_sc(out, content),
                Some("text") => {
                    if let Some(J::Str(t)) = node.get("text") {
                        py::escape_br_into(out, t);
                    }
                    return;
                }
                Some("image") => return,
                _ => {}
            }
            let tag = match node.get("tag") {
                None | Some(J::Null) => return render_sc(out, content),
                Some(J::Str(t)) => *t,
                Some(_) => return,
            };
            if tag == "img" {
                return;
            }
            let mut attrs = String::new();
            if let Some(J::Obj(style)) = node.get("style") {
                if !style.is_empty() {
                    let mut decls = String::new();
                    for (i, (k, v)) in style.iter().enumerate() {
                        if i > 0 {
                            decls.push(';');
                        }
                        kebab_into(&mut decls, k);
                        decls.push(':');
                        py::str_into(&mut decls, v);
                    }
                    attrs.push_str(" style=\"");
                    py::escape_into(&mut attrs, &decls);
                    attrs.push('"');
                }
            }
            if let Some(J::Obj(data)) = node.get("data") {
                for (k, v) in data.iter() {
                    attrs.push_str(" data-");
                    kebab_into(&mut attrs, k);
                    attrs.push_str("=\"");
                    match v {
                        J::Str(s) => py::escape_into(&mut attrs, s),
                        v => py::escape_into(&mut attrs, &py::py_str(v)),
                    }
                    attrs.push('"');
                }
            }
            if tag == "a" {
                let href = node.get("href").and_then(J::as_str).unwrap_or("");
                if !href.contains("://") {
                    return render_sc(out, content);
                }
                attrs.push_str(" href=\"");
                py::escape_into(&mut attrs, href);
                attrs.push('"');
            }
            out.push('<');
            out.push_str(tag);
            out.push_str(&attrs);
            out.push('>');
            if tag == "br" || tag == "hr" {
                return;
            }
            render_sc(out, content);
            out.push_str("</");
            out.push_str(tag);
            out.push('>');
        }
        _ => {}
    }
}

/// `!render_sc(node).is_empty()`, without rendering.
pub fn renders_nonempty(node: &J) -> bool {
    match node {
        J::Str(s) => !s.is_empty(),
        J::Arr(a) => a.iter().any(renders_nonempty),
        J::Obj(_) => {
            let content = node.get("content").unwrap_or(&NULL);
            match node.get("type").and_then(J::as_str) {
                Some("structured-content") => return renders_nonempty(content),
                Some("text") => return matches!(node.get("text"), Some(J::Str(t)) if !t.is_empty()),
                Some("image") => return false,
                _ => {}
            }
            match node.get("tag") {
                None | Some(J::Null) => renders_nonempty(content),
                Some(J::Str(t)) if *t == "img" => false,
                Some(J::Str(t)) if *t == "a" && !node.get("href").and_then(J::as_str).unwrap_or("").contains("://") => {
                    renders_nonempty(content)
                }
                Some(J::Str(_)) => true,
                Some(_) => false,
            }
        }
        _ => false,
    }
}

/// `build._glosses(definitions)`.
pub fn glosses<'a>(definitions: &'a J<'a>) -> Vec<&'a str> {
    fn walk<'a>(n: &'a J<'a>, out: &mut Vec<&'a str>) {
        match n {
            J::Obj(_) => {
                let is_glossary = matches!(n.get("data"), Some(d @ J::Obj(_))
                    if d.get("content").and_then(J::as_str) == Some("glossary"));
                if is_glossary {
                    let c = n.get("content").unwrap_or(&NULL);
                    let items: &[J] = match c {
                        J::Arr(a) => a,
                        other => std::slice::from_ref(other),
                    };
                    for li in items {
                        if matches!(li, J::Obj(_)) && li.get("tag").and_then(J::as_str) == Some("li") {
                            if let Some(J::Str(s)) = li.get("content") {
                                out.push(s);
                            }
                        }
                    }
                } else if let Some(c) = n.get("content") {
                    walk(c, out);
                }
            }
            J::Arr(a) => a.iter().for_each(|x| walk(x, out)),
            _ => {}
        }
    }
    let mut out = Vec::new();
    walk(definitions, &mut out);
    out
}

/// `build._sense_ord(defTags, fallback)`.
pub fn sense_ord(def_tags: Option<&str>, fallback: i64) -> i64 {
    let tok = def_tags.unwrap_or("").split(' ').next().unwrap_or("");
    if py::is_digits(tok) { tok.parse().unwrap_or(fallback) } else { fallback }
}

/// One definition row as `defs.sqlite` holds it; rows of a term are kept in
/// insertion (rowid) order.
pub struct DefRow<'a> {
    pub reading: &'a str,
    pub dict: u8,
    pub sense_ord: i64,
    pub html: &'a str,
}

/// `dict` value for JMdict rows; JJ rows use their chain index.
pub const JE: u8 = JJ_CHAIN.len() as u8;

/// Index of the row `dbutil.by_reading(rows, reading)[0]` picks, where `rows` is
/// what `SELECT ... WHERE term=?` returns through the (term, reading) index:
/// ordered by reading, then rowid.
fn first_by_reading<'a>(rows: impl Iterator<Item = &'a DefRow<'a>> + Clone, reading: &str) -> Option<&'a DefRow<'a>> {
    if let Some(r) = rows.clone().find(|r| r.reading == reading) {
        return Some(r);
    }
    if let Some(r) = rows.clone().find(|r| r.reading.is_empty()) {
        return Some(r);
    }
    let mut best: Option<&DefRow> = None;
    for r in rows {
        if best.is_none_or(|b| r.reading.as_bytes() < b.reading.as_bytes()) {
            best = Some(r);
        }
    }
    best
}

/// `defs.enrich._compute`: (meaning, source), or None on a no-hit.
pub fn definition_for(rows: &[DefRow], reading: &str) -> Option<(String, String)> {
    let mut jj = None;
    for d in 0..JJ_CHAIN.len() as u8 {
        let of_dict = rows.iter().filter(move |r| r.dict == d);
        if let Some(r) = first_by_reading(of_dict, reading) {
            jj = Some((JJ_CHAIN[d as usize], r.html));
            break;
        }
    }
    let je = numbered_glosses(rows, reading);
    if jj.is_none() && je.is_empty() {
        return None;
    }
    let mut meaning = String::new();
    if let Some((id, html)) = jj {
        meaning.push_str("<div class=\"aa-jj\" data-dict=\"");
        py::escape_into(&mut meaning, id);
        meaning.push_str("\">");
        meaning.push_str(html);
        meaning.push_str("</div>");
    }
    if !je.is_empty() {
        meaning.push_str("<div class=\"aa-je\">");
        meaning.push_str(&je);
        meaning.push_str("</div>");
    }
    let source = match jj {
        Some((id, _)) => label(id),
        None => label(JE_DICT),
    };
    Some((store_norm(meaning), store_norm(source.to_string())))
}

fn numbered_glosses(rows: &[DefRow], reading: &str) -> String {
    let je: Vec<&DefRow> = rows.iter().filter(|r| r.dict == JE).collect();
    let mut picked: Vec<&DefRow> = if je.iter().any(|r| r.reading == reading) {
        je.into_iter().filter(|r| r.reading == reading).collect()
    } else if je.iter().any(|r| r.reading.is_empty()) {
        je.into_iter().filter(|r| r.reading.is_empty()).collect()
    } else {
        je
    };
    // Index order is (reading, rowid); Python then stable-sorts on sense_ord.
    picked.sort_by(|a, b| a.sense_ord.cmp(&b.sense_ord).then_with(|| a.reading.as_bytes().cmp(b.reading.as_bytes())));
    let mut out = String::new();
    for (i, r) in picked.iter().take(GLOSS_CAP).enumerate() {
        if i > 0 {
            out.push_str("<br>");
        }
        out.push_str(&(i + 1).to_string());
        out.push_str(". ");
        py::escape_into(&mut out, r.html);
    }
    out
}

/// `_store_norm`: NFC, no carriage returns.
pub fn store_norm(s: String) -> String {
    use unicode_normalization::{IsNormalized, UnicodeNormalization, is_nfc_quick};
    let s = if is_nfc_quick(s.chars()) == IsNormalized::Yes { s } else { s.nfc().collect() };
    if s.contains('\r') { s.replace('\r', "") } else { s }
}

#[cfg(test)]
mod tests {
    use super::*;
    fn render(v: &str) -> String {
        let bump = bumpalo::Bump::new();
        let mut s = String::new();
        render_sc(&mut s, &crate::json::parse(v, &bump));
        s
    }

    #[test]
    fn renders_structured_content() {
        let v = r#"[{"type": "structured-content", "content": [
            {"tag": "span", "style": {"fontWeight": "bold", "marginLeft": 0.5, "fontWeight": "normal"}, "data": {"sense": 1}, "content": "a<b\nc"},
            {"tag": "a", "href": "?query=x", "content": "link"},
            {"tag": "a", "href": "https://x.y/?a=1&b", "content": "ext"},
            {"tag": "img", "path": "x.png"},
            {"tag": "br"},
            {"type": "image", "path": "y"},
            {"content": ["plain"]},
            7
        ]}]"#;
        assert_eq!(
            render(v),
            "<span style=\"font-weight:normal;margin-left:0.5\" data-sense=\"1\">a&lt;b<br>c</span>link\
             <a href=\"https://x.y/?a=1&amp;b\">ext</a><br>plain"
        );
    }

    #[test]
    fn nonempty_matches_render() {
        for v in [
            r#"[]"#,
            r#"[""]"#,
            r#"["x"]"#,
            r#"[{"type": "image", "path": "a"}]"#,
            r#"[{"type": "text", "text": ""}]"#,
            r#"[{"type": "structured-content", "content": [{"tag": "img"}, {"tag": "a", "href": "?q", "content": ""}]}]"#,
            r#"[{"type": "structured-content", "content": {"tag": "a", "href": "?q", "content": "x"}}]"#,
            r#"[{"tag": "span"}]"#,
            r#"[{"tag": "br"}]"#,
            r#"[{"content": [7, null, true]}]"#,
            r#"[{"tag": 3, "content": "x"}]"#,
        ] {
            let bump = bumpalo::Bump::new();
            let j = crate::json::parse(v, &bump);
            assert_eq!(renders_nonempty(&j), !render(v).is_empty(), "{v}");
        }
    }

    #[test]
    fn chooses_like_the_index() {
        let rows = [
            DefRow { reading: "b", dict: 1, sense_ord: 0, html: "k-b" },
            DefRow { reading: "a", dict: 1, sense_ord: 0, html: "k-a" },
            DefRow { reading: "x", dict: JE, sense_ord: 2, html: "two" },
            DefRow { reading: "x", dict: JE, sense_ord: 1, html: "one & more" },
        ];
        let (meaning, source) = definition_for(&rows, "z").unwrap();
        assert_eq!(source, "広辞苑");
        assert_eq!(
            meaning,
            "<div class=\"aa-jj\" data-dict=\"koujien7\">k-a</div><div class=\"aa-je\">1. one &amp; more<br>2. two</div>"
        );
    }
}
