"""Pitch-graph backfill engine. Uses the anki `col` API; no aqt (GUI) imports
so it can be tested headless. Mirrors convert.normalize_media: idempotent,
writes only changed notes.

Uses the shared, dict-authoritative AccentIndex (gold -> NHK2016 -> アクセント辞典v2,
so the dictionaries own the accent/order for words they cover and unverified gold only
fills the words they lack). positions[0] is the primary accent."""
import os
from . import config, pitch_svg
from .jrp_fill.accent import AccentIndex
from .jrp_fill._utils import hira

_INDEX_PATH = os.path.join(os.path.dirname(__file__), "data", "accent_index.marshal.gz")
_FIELD = "Pitch Accent"

# Provenance baked into accent_index.marshal.gz (verified gold first, then dicts).
SOURCES = ("NHK2016", "アクセント辞典v2")


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


def _iter_targets(col, idx, *, overwrite=False):
    """Yield (note, action, svg) for every target note that needs a write.

    action ∈ {"filled","not_found","skipped"}; svg is set only for "filled".
    By default notes that already have a graph are left alone (idempotent no-op).
    With overwrite=True a note with an existing graph is re-rendered from the
    accent dicts and yielded as "filled" only when the render differs (so an
    already-correct graph isn't rewritten); a note the dicts can't cover keeps
    its existing graph untouched (never cleared)."""
    for nid in col.find_notes(f'note:"{config.TARGET_NOTETYPE}"'):
        note = col.get_note(nid)
        has_graph = bool(note[_FIELD].strip())
        if has_graph and not overwrite:
            continue
        reading = note["Reading"].strip()
        if not reading:
            if not has_graph:
                yield note, "skipped", None
            continue
        accents = accents_for(idx, note["Term"], reading)
        if not accents:
            if not has_graph:
                yield note, "not_found", None
            continue
        svg = pitch_svg.render_multi(reading, accents)
        if has_graph and svg == note[_FIELD]:
            continue                       # already the correct graph
        yield note, "filled", svg


def build_pitch_plan(col, *, overwrite=False) -> dict:
    """Read-only: counts + a few (term, reading, svg) samples. No mutation."""
    idx = AccentIndex.load(_INDEX_PATH)
    counts = {"filled": 0, "not_found": 0, "skipped": 0}
    samples = []
    for note, action, svg in _iter_targets(col, idx, overwrite=overwrite):
        counts[action] += 1
        if action == "filled" and len(samples) < config.SAMPLE_N:
            samples.append((note["Term"], note["Reading"], svg))
    return {"counts": counts, "samples": samples}


def backfill_pitch(col, *, overwrite=False) -> dict:
    """Write SVG graphs into Pitch Accent fields. Returns action counts.

    overwrite=True re-renders graphs that already exist (clobbering hand-edited
    or stale ones) where the accent dicts cover the term; otherwise only empty
    fields are filled."""
    idx = AccentIndex.load(_INDEX_PATH)
    res = {"filled": 0, "not_found": 0, "skipped": 0}
    for note, action, svg in _iter_targets(col, idx, overwrite=overwrite):
        res[action] += 1
        if action == "filled":
            note[_FIELD] = svg
            col.update_note(note)
    return res
