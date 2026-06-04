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
