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
from .defs import enrich as defs_enrich
from .examples import enrich as ex_enrich
from .audio import enrich as audio_enrich
from . import pitch as _pitch, pitch_svg as _pitch_svg
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


@dataclass
class Fields:
    term: str
    reading: str
    furigana: str
    pitch_svg: str
    meaning: str
    definition_source: str
    examples: str
    audio_pick: object | None
    audio_clip: bytes | None

    @property
    def has_definition(self) -> bool:
        return bool(self.meaning)


def build_fields(term: str, reading: str, *, defs_conn, ex_conn, audio_conn,
                 acc) -> Fields:
    """Compute every card field derivable from (term, reading). Pure read."""
    meaning, source, _kind = defs_enrich.definition_for(defs_conn, term, reading)
    examples = ex_enrich.examples_for(ex_conn, term, reading)

    accents = _pitch.accents_for(acc, term, reading)
    pitch = _pitch_svg.render_multi(reading, accents) if accents else ""

    pick = audio_enrich.lookup(audio_conn, term, reading)
    clip = audio_enrich.read_clip(audio_conn, pick) if pick else None
    if clip is None:
        pick = None

    return Fields(
        term=term, reading=reading, furigana=term_furigana(term, reading),
        pitch_svg=pitch, meaning=meaning, definition_source=source,
        examples=examples, audio_pick=pick, audio_clip=clip)
