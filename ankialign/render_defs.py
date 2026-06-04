"""Pure rendering/formatting for definition enrichment. No anki/aqt imports."""
import html
import re

_VOID = {"br", "hr", "img"}


def _kebab(k: str) -> str:
    return re.sub(r"[A-Z]", lambda m: "-" + m.group(0).lower(), k)


def render_sc(node) -> str:
    """Yomitan structured-content node -> HTML.

    Preserves inline `style` (camelCase->kebab) and `data-*` attributes so each
    dictionary's native look survives; renders ruby/rt and lists; drops images.
    """
    if node is None:
        return ""
    if isinstance(node, str):
        return html.escape(node).replace("\n", "<br>")
    if isinstance(node, list):
        return "".join(render_sc(c) for c in node)
    if isinstance(node, dict):
        t = node.get("type")
        if t == "structured-content":
            return render_sc(node.get("content"))
        if t == "text":
            return html.escape(node.get("text", "")).replace("\n", "<br>")
        if t == "image":
            return ""
        tag = node.get("tag")
        if tag is None:
            return render_sc(node.get("content"))
        attrs = ""
        style = node.get("style")
        if isinstance(style, dict):
            decls = [f"{_kebab(k)}:{v}" for k, v in style.items()]
            if decls:
                attrs += f' style="{html.escape(";".join(decls), quote=True)}"'
        data = node.get("data")
        if isinstance(data, dict):
            for k, v in data.items():
                attrs += f' data-{_kebab(k)}="{html.escape(str(v), quote=True)}"'
        if tag == "a" and node.get("href"):
            attrs += f' href="{html.escape(node["href"], quote=True)}"'
        if tag in _VOID:
            return f"<{tag}{attrs}>"
        return f"<{tag}{attrs}>{render_sc(node.get('content'))}</{tag}>"
    return ""


def _by_reading(rows, reading):
    """Rows matching the note reading; fall back to reading-less, then all."""
    exact = [r for r in rows if r["reading"] == reading]
    if exact:
        return exact
    blank = [r for r in rows if r["reading"] == ""]
    return blank or rows


def numbered_glosses(je_rows, reading, cap=4) -> str:
    rows = sorted(_by_reading(je_rows, reading), key=lambda r: r["sense_ord"])[:cap]
    return "<br>".join(f"{i}. {r['html']}" for i, r in enumerate(rows, 1))


def choose_jj(jj_rows, reading, chain):
    """First dictionary in `chain` order that has a reading-matching entry.

    Returns (dict_id, html) or None. Reading match prefers exact reading, then a
    reading-less entry, then any entry from that dict.
    """
    for dict_id in chain:
        rows = [r for r in jj_rows if r["dict_id"] == dict_id]
        if not rows:
            continue
        chosen = _by_reading(rows, reading)
        if chosen:
            return (dict_id, chosen[0]["html"])
    return None


def assemble_meaning(jj, je_html, english_open=True) -> str:
    """jj = (dict_id, html) or None; je_html = numbered string or ''.

    English is a collapsible <details> (tap-to-toggle on desktop + AnkiDroid).
    english_open bakes the `open` attribute (default-visible vs default-collapsed).
    """
    parts = []
    if jj:
        parts.append(f'<div class="aa-jj" data-dict="{jj[0]}">{jj[1]}</div>')
    if je_html:
        op = " open" if english_open else ""
        parts.append(f'<details class="aa-je"{op}><summary>English</summary>{je_html}</details>')
    return "".join(parts)


def source_label(jj, has_je, labels) -> str:
    if jj:
        return labels.get(jj[0], jj[0])
    if has_je:
        return labels.get("jmdict", "JMdict")
    return "—"
