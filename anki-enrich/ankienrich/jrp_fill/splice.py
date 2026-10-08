"""Splice generated markup into a sentence, preserving existing JRP spans.

Existing `{...}` units and `[...]` furigana segments are kept byte-for-byte,
EXCEPT that a `{...}` unit whose word is on the no-pitch stoplist is demoted to
furigana-only (keep reading, drop the accent) so そう/それ/formal nouns aren't
left colored on the handful of cards that already carried jidoujisho pitch.
Plain runs containing at least one kanji are passed to `generate`; kana-only
or punctuation-only runs are left untouched.
"""
from __future__ import annotations
import re
from collections.abc import Callable
from .kana import KANJI_RE
from .lexicon import NO_PITCH_LEMMAS, NO_PITCH_FORMS

_FURI = re.compile(r'\[([^|\]]+)\|([^\]]+)\]')

def _depitch_if_stoplisted(unit: str) -> str:
    """`{body;accent}` -> `body` (furigana kept, color dropped) iff the word is
    on the stoplist; otherwise the unit is returned unchanged."""
    if not (unit.startswith("{") and unit.endswith("}") and ";" in unit):
        return unit
    body = unit[1:-1].partition(";")[0]          # surface part, before the accent
    plain = _FURI.sub(lambda m: m.group(1), body)  # [漢字|かな] -> 漢字
    read = _FURI.sub(lambda m: m.group(2), body)   # [漢字|かな] -> かな
    if (plain in NO_PITCH_LEMMAS or read in NO_PITCH_LEMMAS
            or (plain, read) in NO_PITCH_FORMS):
        return body
    return unit

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
        if kind == "markup":
            parts.append(_depitch_if_stoplisted(text))
        else:
            # a newline is a structural break (was <br>): generate each line on
            # its own so the marker never lands inside a generated unit.
            parts.append("\n".join(
                generate(line) if KANJI_RE.search(line) else line
                for line in text.split("\n")))
    return "".join(parts)
