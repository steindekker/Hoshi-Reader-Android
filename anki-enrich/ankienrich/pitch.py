"""Pitch-graph backfill engine. Uses the anki `col` API; no aqt (GUI) imports
so it can be tested headless. Idempotent: writes only changed notes.

Uses the shared, dict-authoritative AccentIndex (NHK2016 + アクセント辞典v2,
gap-filled from 三省堂第八版 — see accent_augment.py).
positions[0] is the primary accent."""
from . import config, pitch_svg
from .collection import notetype_search
from .jrp_fill.accent import AccentIndex
from .jrp_fill.kana import hira
from .paths import ACCENT_INDEX as _INDEX_PATH

_FIELD = config.PITCH

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


def _iter_targets(col, idx):
    """Yield (note, action, value) for every note whose Pitch field needs a write.

    The field is reconciled to the accent dicts on every run — the invariant is
    that it holds our own svg graph or nothing:

      filled    value=svg  — the dicts cover the term and the rendered graph
                             differs from what's there (empty, stale graph, or a
                             non-graph format like Yomitan span markup).
      cleared   value=""   — non-graph content the dicts can't replace (e.g. a
                             "No pitch accent data" placeholder), wiped to empty.
      not_found value=None — empty field, term not in the dicts (no write).
      skipped   value=None — no reading to look up (no write).

    An already-correct graph is a silent no-op; our own graph the dicts can't
    cover is kept (can't be regenerated, so never cleared)."""
    for nid in col.find_notes(notetype_search()):
        note = col.get_note(nid)
        field = note[_FIELD]
        has_graph = "<svg" in field
        reading = note[config.READING].strip()
        accents = accents_for(idx, note[config.TERM], reading) if reading else None
        if accents:
            svg = pitch_svg.render_multi(reading, accents)
            if svg != field:
                yield note, "filled", svg
            continue                       # else already the correct graph
        if has_graph:
            continue                       # keep our graph; dicts can't regenerate it
        if field.strip():
            yield note, "cleared", ""      # non-graph junk we can't replace
        elif reading:
            yield note, "not_found", None
        else:
            yield note, "skipped", None


def plan_backfill(col) -> dict:
    """Read-only: counts + a few (term, reading, svg) samples. No mutation."""
    idx = AccentIndex.load(_INDEX_PATH)
    counts = {"filled": 0, "cleared": 0, "not_found": 0, "skipped": 0}
    samples = []
    for note, action, value in _iter_targets(col, idx):
        counts[action] += 1
        if action == "filled" and len(samples) < config.SAMPLE_N:
            samples.append((note[config.TERM], note[config.READING], value))
    return {"counts": counts, "samples": samples}


def run_backfill(col) -> dict:
    """Reconcile every Pitch field to the accent dicts. Returns action counts.

    Always re-asserts: empty fields and non-graph formats are filled where the
    dicts cover the term, stale/hand-edited graphs are re-rendered, and non-graph
    content the dicts can't cover is cleared. See _iter_targets."""
    idx = AccentIndex.load(_INDEX_PATH)
    res = {"filled": 0, "cleared": 0, "not_found": 0, "skipped": 0}
    for note, action, value in _iter_targets(col, idx):
        res[action] += 1
        if value is not None:
            note[_FIELD] = value
            col.update_note(note)
    return res
