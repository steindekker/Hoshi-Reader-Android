"""Pitch-graph backfill engine. Uses the anki `col` API; no aqt (GUI) imports
so it can be tested headless. Mirrors convert.normalize_media: idempotent,
writes only changed notes.

Uses a DICT-AUTHORITATIVE accent index (research/pitch-pipeline/build_pitch_index.py:
gold -> NHK2016 -> アクセント辞典v2, so the dictionaries own the accent/order for words
they cover and unverified gold only fills the words they lack). This is deliberately
NOT the shared JRP index (which lets gold win) — a term graph wants the dictionary's
canonical accents for a word in isolation. positions[0] is the primary accent."""
import os
from . import config, pitch_svg
from .jrp_fill.accent import AccentIndex
from .jrp_fill._utils import hira

_INDEX_PATH = os.path.join(os.path.dirname(__file__), "data", "pitch_accent_index.marshal.gz")
_FIELD = "Pitch Accent"


def accents_for(idx: AccentIndex, term: str, reading: str):
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


def primary_accent(idx: AccentIndex, term: str, reading: str):
    """Preferred (first) downstep position for (term, reading), or None."""
    positions = accents_for(idx, term, reading)
    return positions[0] if positions else None


def _iter_targets(col, idx):
    """Yield (note, action, svg) for every target note.

    action ∈ {"filled","not_found","skipped"}; svg is set only for "filled".
    Notes that already have a graph are not yielded (idempotent no-op)."""
    for nid in col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'):
        note = col.get_note(nid)
        if note[_FIELD].strip():
            continue
        reading = note["Reading"].strip()
        if not reading:
            yield note, "skipped", None
            continue
        accents = accents_for(idx, note["Term"], reading)
        if not accents:
            yield note, "not_found", None
            continue
        yield note, "filled", pitch_svg.render_multi(reading, accents)


def build_pitch_plan(col) -> dict:
    """Read-only: counts + a few (term, reading, svg) samples. No mutation."""
    idx = AccentIndex.load(_INDEX_PATH)
    counts = {"filled": 0, "not_found": 0, "skipped": 0}
    samples = []
    for note, action, svg in _iter_targets(col, idx):
        counts[action] += 1
        if action == "filled" and len(samples) < config.SAMPLE_N:
            samples.append((note["Term"], note["Reading"], svg))
    return {"counts": counts, "samples": samples}


def backfill_pitch(col) -> dict:
    """Write SVG graphs into empty Pitch Accent fields. Returns action counts."""
    idx = AccentIndex.load(_INDEX_PATH)
    res = {"filled": 0, "not_found": 0, "skipped": 0}
    for note, action, svg in _iter_targets(col, idx):
        res[action] += 1
        if action == "filled":
            note[_FIELD] = svg
            col.update_note(note)
    return res
