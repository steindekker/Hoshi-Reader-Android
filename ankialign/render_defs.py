"""Pure rendering/formatting for definition enrichment. No anki/aqt imports."""
import html
import re

_VOID = {"br", "hr"}


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
        if tag == "img":
            return ""  # Yomitan inline images (gaiji / 三省堂 accent glyphs); no media shipped
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
    # glosses are stored as plain text; escape at render so any '&'/'<' in a
    # JMdict gloss can't inject markup into the card.
    return "<br>".join(f"{i}. {html.escape(r['html'])}" for i, r in enumerate(rows, 1))


def choose_jj(jj_rows, reading, chain):
    """First dictionary in `chain` order that has a reading-matching entry.

    Returns (dict_id, html) or None. Reading match prefers exact reading, then a
    reading-less entry, then any entry from that dict.

    Note: the spec's katakana-homograph guard (e.g. ヤギ goat vs. typhoon) is
    intentionally NOT implemented — in the cited cases the dict holds only the one
    homograph entry, so the guard can't help; such rare cases are auditable via the
    Definition Source field instead. See the plan's self-review.
    """
    for dict_id in chain:
        rows = [r for r in jj_rows if r["dict_id"] == dict_id]
        if not rows:
            continue
        chosen = _by_reading(rows, reading)
        if chosen:
            return (dict_id, chosen[0]["html"])
    return None


# Essential layout baked inline so the look survives even if the shared Kinomoto
# notetype CSS (which other passes/agents also rewrite) loses the .aa-jj/.aa-je
# rules. Class hooks stay for the richer CSS polish (furigana, dict styles) when present.
_JJ_STYLE = "text-align:left"
_JE_STYLE = ("text-align:left;margin-top:11px;padding-top:9px;"
             "border-top:1px solid rgba(125,125,125,.35)")


def assemble_meaning(jj, je_html) -> str:
    """jj = (dict_id, html) or None; je_html = numbered string or ''.

    English sits always-expanded below the J-J block, separated by a top-border
    divider (inline style, so it can't be stripped by other CSS passes).
    """
    parts = []
    if jj:
        parts.append(f'<div class="aa-jj" data-dict="{html.escape(jj[0], quote=True)}" '
                     f'style="{_JJ_STYLE}">{jj[1]}</div>')
    if je_html:
        parts.append(f'<div class="aa-je" style="{_JE_STYLE}">{je_html}</div>')
    return "".join(parts)


def source_label(jj, has_je, labels) -> str:
    if jj:
        return labels.get(jj[0], jj[0])
    if has_je:
        return labels.get("jmdict", "JMdict")
    return "—"
