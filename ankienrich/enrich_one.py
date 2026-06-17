"""Single-note enrichment: build the field set for one (term, reading) by reusing
each pass's per-note lookup. Used by the Telegram mining flow for BOTH the chat
preview and the committed card, so preview == written value. Pure reads (no col
mutation): audio bytes are returned, not written; the writer (mine.create_card)
persists them.
"""
from __future__ import annotations

import html as _html
import re
from dataclasses import dataclass

from .defs import index as defs_index
from .jrp_fill.align import furigana_segments

_TAG = re.compile(r"<[^>]+>")


@dataclass(frozen=True)
class Candidate:
    reading: str
    gloss: str  # short plain-text gloss for the picker button/label


def term_furigana(term: str, reading: str) -> str:
    """Anki furigana notation, e.g. ('部活','ぶかつ') -> '部活[ぶかつ]'; kana-only plain."""
    segs = furigana_segments(term, reading)
    return "".join(f"{b}[{r}]" if r else b for b, r in segs)


def _plain_gloss(rows) -> str:
    """First sense's text, tags stripped, clipped — for the disambiguation label."""
    if not rows:
        return ""
    text = _html.unescape(_TAG.sub("", rows[0]["html"] or "")).strip()
    return text[:40]


def candidates(defs_conn, term: str) -> list[Candidate]:
    """Distinct readings for `term` across the dict index, each with a 1-line gloss.
    Empty list = unknown word (caller refuses)."""
    jj, je = defs_index.lookup(defs_conn, term)
    by_reading: dict[str, list] = {}
    for r in (*je, *jj):  # prefer JE gloss for the label
        by_reading.setdefault(r["reading"], []).append(r)
    out = []
    for reading, rows in by_reading.items():
        out.append(Candidate(reading=reading, gloss=_plain_gloss(rows)))
    return out
