"""Align a surface string against its hiragana reading into furigana segments.

Returns a list of (text, reading|None). reading is None for kana/other runs
(rendered bare); a hiragana string for kanji runs. Any failure to align
cleanly returns a single (surface, reading) segment, which always round-trips.
"""
from __future__ import annotations
import re

# same kanji ranges as ankialign/markup.py, as a per-character class
_KANJI = re.compile(
    r"[㐀-鿿豈-﫿々\U00020000-\U0002A6DF\U0002A700-\U0002CEAF"
    r"\U0002CEB0-\U0002EBEF]"
)
_KATA = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}

def _hira(s: str) -> str:
    return "".join(_KATA.get(c, c) for c in s)

def _is_kanji(ch: str) -> bool:
    return bool(_KANJI.match(ch))

def _runs(surface: str) -> list[tuple[bool, str]]:
    """Split into maximal (is_kanji, text) runs."""
    out: list[tuple[bool, str]] = []
    for ch in surface:
        k = _is_kanji(ch)
        if out and out[-1][0] == k:
            out[-1] = (k, out[-1][1] + ch)
        else:
            out.append((k, ch))
    return out

def furigana_segments(surface: str, reading: str) -> list[tuple[str, str | None]]:
    if not surface:
        return []
    runs = _runs(surface)
    if not any(k for k, _ in runs):           # no kanji at all -> all bare
        return [(surface, None)]

    hread = _hira(reading)
    segs: list[tuple[str, str | None]] = []
    ri = 0
    for i, (is_k, text) in enumerate(runs):
        if not is_k:
            anchor = _hira(text)
            if hread[ri:ri + len(anchor)] != anchor:
                return [(surface, reading)]    # mismatch -> safe fallback
            segs.append((text, None))
            ri += len(anchor)
        else:
            if i + 1 < len(runs):              # next run is kana: find it to delimit
                nxt = _hira(runs[i + 1][1])
                j = hread.find(nxt, ri)
                if j < 0 or j == ri:
                    return [(surface, reading)]
                segs.append((text, hread[ri:j]))
                ri = j
            else:                              # last run: take the rest
                if ri >= len(hread):
                    return [(surface, reading)]
                segs.append((text, hread[ri:]))
                ri = len(hread)
    if ri != len(hread):                       # leftover reading -> fallback
        return [(surface, reading)]
    return segs
