"""Splice generated markup into a sentence, preserving existing JRP spans.

Existing `{...}` units and `[...]` furigana segments are kept byte-for-byte.
Plain runs containing at least one kanji are passed to `generate`; kana-only
or punctuation-only runs are left untouched.
"""
from __future__ import annotations
import re
from collections.abc import Callable

_KANJI = re.compile(
    r"[㐀-鿿豈-﫿々\U00020000-\U0002A6DF\U0002A700-\U0002CEAF"
    r"\U0002CEB0-\U0002EBEF]"
)

def _spans(s: str) -> list[tuple[str, str]]:
    """Tokenize into ('markup', text) for {...}/[...] and ('plain', text) runs."""
    out: list[tuple[str, str]] = []
    i, n = 0, len(s)
    buf: list[str] = []
    def flush():
        if buf:
            out.append(("plain", "".join(buf)))
            buf.clear()
    while i < n:
        c = s[i]
        close = "}" if c == "{" else ("]" if c == "[" else None)
        if close:
            j = s.find(close, i)
            if j >= 0:
                flush()
                out.append(("markup", s[i:j + 1]))
                i = j + 1
                continue
        buf.append(c)
        i += 1
    flush()
    return out

def splice(sentence: str, generate: Callable[[str], str]) -> str:
    parts: list[str] = []
    for kind, text in _spans(sentence):
        if kind == "plain":
            m = _KANJI.search(text)
            if m:
                # Keep leading kana/punctuation prefix verbatim; generate from first kanji onward.
                parts.append(text[: m.start()])
                parts.append(generate(text[m.start() :]))
            else:
                parts.append(text)
        else:
            parts.append(text)
    return "".join(parts)
