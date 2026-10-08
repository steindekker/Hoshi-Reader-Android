"""Pitch-accent lookup for the pack's graph (rendered by pitch_svg).

Uses the shared, dict-authoritative AccentIndex (NHK2016 + アクセント辞典v2,
gap-filled from 三省堂第八版 — see accent_augment.py).
positions[0] is the primary accent."""
from .jrp_fill.accent import AccentIndex
from .jrp_fill.kana import hira

# Provenance baked into accent_index.marshal.gz.
SOURCES = ("NHK2016", "アクセント辞典v2", "三省堂第八版")


def accents_for(idx: AccentIndex, term: str, reading: str) -> list[int] | None:
    """All accepted downstep positions for (term, reading), or None.

    AccentIndex is keyed `lemma\\treading_hira` -> preference-ordered list[int].
    Falls back to the kana headword (lemma == reading) for kanji surfaces whose
    dictionary entry is keyed by the reading itself."""
    r = hira(reading.strip())
    t = term.strip()
    for lemma, rd in ((t, r), (reading.strip(), r), (r, r)):
        positions = idx.lookup(lemma, rd)
        if positions:
            return positions
    return None


def primary_accent(idx: AccentIndex, term: str, reading: str) -> int | None:
    """Preferred (first) downstep position for (term, reading), or None."""
    positions = accents_for(idx, term, reading)
    return positions[0] if positions else None
