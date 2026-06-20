"""Term-audio backfill from the prebuilt android.db store (var/audio.sqlite).

The store is the local-audio-yomichan / AnkiConnect-Android packaged export:
    entries(id, expression, reading, source, speaker, display, file)  -- lookup index
    android(id, file, source, data BLOB)                              -- clip bytes
all clips .mp3, blobs embedded. We open it read-only.

`lookup` is a direct port of Hoshi Reader's LocalAudioResolver.resolve: gather every
row matching the term or the (kata->hira) reading, drop unsupported audio, then rank
reading-matches first, then by source priority, then source name. (We add one final
tiebreak — exact-expression — so homograph collisions in the same source resolve to
the right word; Hoshi leaves that to DB order.)

Fills empty `Term Audio` on in-scope Kinomoto notes. No `aqt`, no add-on coupling.
"""
import os
import re
from collections import Counter
from dataclasses import dataclass

import jaconv

from .. import config
from ..collection import notetype_search

# Source preference, highest first (Hoshi Reader's BuiltInPriority). Sources absent
# from this list sort last, then alphabetically.
SOURCE_PRIORITY = ["nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
                   "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2"]
SUPPORTED_EXT = {"mp3", "opus", "ogg"}

_TAG = re.compile(r"<[^>]+>")
_SOUND = re.compile(r"\[sound:[^\]]+\]")


def _clean(s: str) -> str:
    return _TAG.sub("", s or "").replace("​", "").replace("&nbsp;", "").strip()


def _to_hira(s: str) -> str:
    return jaconv.kata2hira(s)


def _ext(file: str) -> str:
    return file.rsplit(".", 1)[-1].lower() if "." in file else ""


def _source_rank(source: str) -> int:
    try:
        return SOURCE_PRIORITY.index(source)
    except ValueError:
        return len(SOURCE_PRIORITY)


@dataclass
class Pick:
    """A chosen recording. `file` is the source-relative filename (the key into the
    `android` table, and—via `media_name`—the media file basename)."""
    source: str
    file: str


def lookup(conn, term: str, reading: str) -> Pick | None:
    """Hoshi-port lookup against the android.db store. Returns the best Pick or None.

    Gathers rows where expression == term OR reading == (kata->hira) reading, drops
    unsupported extensions, then takes the row ranked first by:
      1. reading-match (rows whose reading equals the query reading come first),
      2. source priority (SOURCE_PRIORITY),
      3. source name,
      4. exact-expression (the row whose expression IS the term wins same-reading
         same-source ties).
    """
    term = _clean(term)
    reading = _to_hira(_clean(reading))
    if not term:
        return None
    if reading:
        rows = conn.execute(
            "SELECT source, expression, reading, file FROM entries "
            "WHERE expression=? OR reading=?", (term, reading)).fetchall()
    else:
        rows = conn.execute(
            "SELECT source, expression, reading, file FROM entries "
            "WHERE expression=?", (term,)).fetchall()
    cands = [r for r in rows if _ext(r[3]) in SUPPORTED_EXT]
    if not cands:
        return None

    def _key(r):
        src, expr, rd, _file = r
        return (
            0 if (reading and rd == reading) else 1,
            _source_rank(src),
            src,
            0 if expr == term else 1,
        )

    src, _expr, _rd, file = min(cands, key=_key)
    return Pick(src, file)


def read_clip(conn, pick: Pick) -> bytes | None:
    """The picked recording's bytes from the `android` table, or None if absent."""
    row = conn.execute(
        "SELECT data FROM android WHERE source=? AND file=? LIMIT 1",
        (pick.source, pick.file),
    ).fetchone()
    return row[0] if row else None


def media_name(pick: Pick) -> str:
    """Deterministic media filename for a pick. basenames can collide across clips;
    Anki's write_data renames on content conflict, so each card still gets its bytes."""
    return f"la_{pick.source}_{os.path.basename(pick.file)}"


# --- collection-level pass (anki, no aqt) ---------------------------------------

def select_targets(col) -> list:
    """In-scope Kinomoto note ids with an empty Term Audio field."""
    if col.models.by_name(config.NOTETYPE) is None:
        return []
    # `"Term Audio:"` matches notes whose Term Audio field is empty.
    return list(col.find_notes(f'{notetype_search()} "{config.TERM_AUDIO}:"'))


def plan_backfill(col, conn) -> dict:
    """Read-only. For every target note, resolve a Pick and classify it.

    Returns {'total', 'by_source', 'filled', 'nothing', 'missing_file',
    'samples', 'picks'}. `missing_file` counts picks whose clip blob is absent.
    """
    by_source = Counter()
    nothing = missing_file = 0
    samples = []
    picks = {}
    for nid in select_targets(col):
        note = col.get_note(nid)
        term, reading = note[config.TERM], note[config.READING]
        pick = lookup(conn, term, reading)
        if pick is None:
            nothing += 1
            continue
        if read_clip(conn, pick) is None:
            missing_file += 1
            continue
        picks[nid] = pick
        by_source[pick.source] += 1
        if len(samples) < config.SAMPLE_N * 3:
            samples.append((_clean(term), _clean(reading), pick.source))
    return {
        "total": len(picks) + nothing + missing_file,
        "by_source": by_source,
        "filled": len(picks),
        "nothing": nothing,
        "missing_file": missing_file,
        "samples": samples,
        "picks": picks,
    }


def run_backfill(col, conn) -> dict:
    """Write each picked recording's bytes into collection.media and set
    `[sound:...]` on the note's Term Audio. Caller backs up first. Only writes
    notes still empty (re-run safe). Per-card error isolation.
    Returns {'filled': {src: n}, 'errors': [(nid, repr)]}.
    """
    plan = plan_backfill(col, conn)
    filled = Counter()
    errors = []
    for nid, pick in plan["picks"].items():
        try:
            note = col.get_note(nid)
            if _SOUND.search(note[config.TERM_AUDIO] or ""):
                continue  # already has audio (race / re-run)
            data = read_clip(conn, pick)
            fname = col.media.write_data(media_name(pick), data)
            note[config.TERM_AUDIO] = f"[sound:{fname}]"
            col.update_note(note)
            filled[pick.source] += 1
        except Exception as e:
            errors.append((nid, repr(e)))
    return {"filled": filled, "errors": errors}
