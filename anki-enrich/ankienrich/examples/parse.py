"""Pure structured-content parsing per source -> {jp, en, plain} example dicts.

No anki/sqlite imports. Each source encodes examples differently:
  jitendex  data.content 'example-sentence' box: -a (JP, ruby + 'example-keyword'
            highlight) / -b (EN). Tatoeba-sourced, ~1/word.
  saito     <ul> of paired <li>: listStyleType '◆' = JP, next fontSize 70% = EN.
  yourei    <li> list of literary passages (multi-sentence); headword = red-bold
            span, trailing <a> = book attribution (dropped). Only the single
            sentence containing the headword is kept (passages are paragraph-long).
            JP only.
jp is rendered HTML (ruby kept where present, keyword wrapped in <b class=aa-ex-kw>);
plain is the bare base text (rt dropped) used for dedupe."""
import html
import re


def norm(s: str) -> str:
    """Collapse a JP sentence to a dedupe key: drop whitespace + sentence punctuation."""
    return re.sub(r"[\s。、，．！？!?「」『』（）()…・]", "", s)


def _esc(s: str) -> str:
    return html.escape(s)


def highlight(text: str, keyword: str) -> str:
    """Escape text, wrapping the first occurrence of `keyword` as the target word."""
    if not keyword or keyword not in text:
        return _esc(text)
    i = text.index(keyword)
    return _esc(text[:i]) + f'<b class="aa-ex-kw">{_esc(keyword)}</b>' + _esc(text[i + len(keyword):])


# ---- generic node helpers (Yomitan structured content) --------------------

def _walk(node):
    """Yield every dict node in a structured-content tree."""
    if isinstance(node, dict):
        yield node
        yield from _walk(node.get("content"))
    elif isinstance(node, list):
        for x in node:
            yield from _walk(x)


def _data_content(node) -> str:
    d = node.get("data")
    return d.get("content", "") if isinstance(d, dict) else ""


def _children(node) -> list:
    """node's content as a list (single child wrapped, None -> [])."""
    c = node.get("content")
    if c is None:
        return []
    return c if isinstance(c, list) else [c]


def _plain(node) -> str:
    """Base text of a node, dropping ruby readings (<rt>)."""
    if isinstance(node, str):
        return node
    if isinstance(node, list):
        return "".join(_plain(c) for c in node)
    if isinstance(node, dict):
        if node.get("tag") == "rt":
            return ""
        return _plain(node.get("content"))
    return ""


def _render_ruby(node, in_keyword=False) -> str:
    """JP HTML preserving <ruby>/<rt>; 'example-keyword' spans -> <b class=aa-ex-kw>."""
    if isinstance(node, str):
        return _esc(node)
    if isinstance(node, list):
        return "".join(_render_ruby(c, in_keyword) for c in node)
    if isinstance(node, dict):
        tag = node.get("tag")
        if _data_content(node) == "example-keyword":
            return f'<b class="aa-ex-kw">{_render_ruby(node.get("content"), True)}</b>'
        if tag in ("ruby", "rt"):
            return f"<{tag}>{_render_ruby(node.get('content'), in_keyword)}</{tag}>"
        return _render_ruby(node.get("content"), in_keyword)
    return ""


# ---- per-source extractors ------------------------------------------------

def jitendex(sc) -> list[dict]:
    out = []
    for box in _walk(sc):
        if _data_content(box) != "example-sentence":
            continue
        a = next((n for n in _walk(box) if _data_content(n) == "example-sentence-a"), None)
        b = next((n for n in _walk(box) if _data_content(n) == "example-sentence-b"), None)
        if a is None:
            continue
        jp = _render_ruby(a.get("content"))
        en = _plain(b.get("content")).strip() if b is not None else ""
        plain = _plain(a.get("content"))
        if plain.strip():
            out.append({"jp": jp, "en": en, "plain": plain})
    return out


def _li_text(li) -> str:
    return _plain(li.get("content")).strip()


def _style(node, key) -> str:
    s = node.get("style")
    return s.get(key, "") if isinstance(s, dict) else ""


def saito(sc, keyword: str) -> list[dict]:
    out = []
    for ul in _walk(sc):
        if ul.get("tag") != "ul":
            continue
        pending = None
        for li in _children(ul):
            if not isinstance(li, dict) or li.get("tag") != "li":
                continue
            if _style(li, "listStyleType") == "'◆'":
                if pending is not None:
                    out.append({"jp": highlight(pending, keyword), "en": "", "plain": pending})
                pending = _li_text(li)
            elif _style(li, "fontSize") == "70%" and pending is not None:
                out.append({"jp": highlight(pending, keyword), "en": _li_text(li), "plain": pending})
                pending = None
        if pending is not None:
            out.append({"jp": highlight(pending, keyword), "en": "", "plain": pending})
    return out


_SENT_END = "。！？!?"


def _sentence_at(text: str, offset: int) -> str:
    """The sentence of `text` (split on 。！？) covering char `offset`, trimmed."""
    start, pos = 0, 0
    for ch in text:
        pos += 1
        if ch in _SENT_END:
            if start <= offset < pos:
                return text[start:pos].strip()
            start = pos
    return text[start:].strip()


def _yourei_li(li) -> dict | None:
    """One 用例.jp passage -> the single sentence around the red-bold headword (<a> dropped)."""
    segs = []  # (text, is_keyword)
    for span in _children(li):
        if isinstance(span, str):
            segs.append((span, False))
            continue
        if not isinstance(span, dict):
            continue
        for s in _children(span):
            if not isinstance(s, dict) or s.get("tag") == "a":
                continue  # <a> = book attribution link
            txt = _plain(s.get("content"))
            if txt:
                segs.append((txt, _style(s, "color") == "red"))
    full = "".join(t for t, _ in segs)
    kw_off, keyword, pos = -1, "", 0
    for txt, is_kw in segs:
        if is_kw and not keyword:
            kw_off, keyword = pos, txt
        pos += len(txt)
    if not keyword:
        return None
    sent = _sentence_at(full, kw_off)
    if not sent:
        return None
    return {"jp": highlight(sent, keyword), "en": "", "plain": sent}


def yourei(sc) -> list[dict]:
    out = []
    for ul in _walk(sc):
        if ul.get("tag") != "ul":
            continue
        for li in _children(ul):
            if isinstance(li, dict) and li.get("tag") == "li":
                ex = _yourei_li(li)
                if ex:
                    out.append(ex)
    return out
