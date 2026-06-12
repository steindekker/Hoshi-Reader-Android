"""Align a surface string against its hiragana reading into furigana segments.

Returns a list of (text, reading|None). reading is None for kana/other runs
(rendered bare); a hiragana string for kanji runs. Any failure to align
cleanly returns a single (surface, reading) segment, which always round-trips.
"""
from __future__ import annotations
from .kana import KANJI_RE, hira

def _is_kanji(ch: str) -> bool:
    return bool(KANJI_RE.match(ch))

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

    hread = hira(reading)
    segs: list[tuple[str, str | None]] = []
    ri = 0
    for i, (is_k, text) in enumerate(runs):
        if not is_k:
            anchor = hira(text)
            if hread[ri:ri + len(anchor)] != anchor:
                return [(surface, reading)]    # mismatch -> safe fallback
            segs.append((text, None))
            ri += len(anchor)
        else:
            if i + 1 < len(runs):              # next run is kana: find it to delimit
                nxt = hira(runs[i + 1][1])
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
