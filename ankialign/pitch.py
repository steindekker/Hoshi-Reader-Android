"""Pitch-graph backfill engine. Uses the anki `col` API; no aqt (GUI) imports
so it can be tested headless. Mirrors convert.normalize_media: idempotent,
writes only changed notes."""
import os
from . import config, pitch_svg
from .pitch_index import PitchIndex

_INDEX_PATH = os.path.join(os.path.dirname(__file__), "data", "pitch_index.marshal.gz")
_FIELD = "Pitch Accent"


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
        pos = idx.lookup(note["Term"].strip(), reading)
        if pos is None:
            yield note, "not_found", None
            continue
        yield note, "filled", pitch_svg.render_svg(reading, pos)


def build_pitch_plan(col) -> dict:
    """Read-only: counts + a few (term, reading, svg) samples. No mutation."""
    idx = PitchIndex.load(_INDEX_PATH)
    counts = {"filled": 0, "not_found": 0, "skipped": 0}
    samples = []
    for note, action, svg in _iter_targets(col, idx):
        counts[action] += 1
        if action == "filled" and len(samples) < config.SAMPLE_N:
            samples.append((note["Term"], note["Reading"], svg))
    return {"counts": counts, "samples": samples}


def backfill_pitch(col) -> dict:
    """Write SVG graphs into empty Pitch Accent fields. Returns action counts."""
    idx = PitchIndex.load(_INDEX_PATH)
    res = {"filled": 0, "not_found": 0, "skipped": 0}
    for note, action, svg in _iter_targets(col, idx):
        res[action] += 1
        if action == "filled":
            note[_FIELD] = svg
            col.update_note(note)
    return res
