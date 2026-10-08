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

Fills empty `Term Audio` on in-scope Kinomoto notes; `overwrite=True` instead
re-asserts store audio over every card, mined clips included. No `aqt`, no
add-on coupling.
"""
import os
import re
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass

import jaconv

from . import loudness
from .. import config
from ..collection import notetype_search

# Source preference, highest first (Hoshi Reader's BuiltInPriority). Sources absent
# from this list sort last, then alphabetically.
SOURCE_PRIORITY = ["nhk16", "daijisen", "shinmeikai8", "jpod", "jpod_alternate",
                   "taas", "ozk5", "forvo", "forvo_ext", "forvo_ext2"]
SUPPORTED_EXT = {"mp3", "opus", "ogg"}

_TAG = re.compile(r"<[^>]+>")
_SOUND = re.compile(r"\[sound:([^\]]+)\]")  # group 1 = the media filename


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

def select_targets(col, *, overwrite: bool = False, note_ids=None) -> list:
    """In-scope Kinomoto note ids with an empty Term Audio field — or every
    in-scope note when `overwrite`, so store audio supersedes mined clips."""
    if col.models.by_name(config.NOTETYPE) is None:
        return []
    if overwrite:
        return list(col.find_notes(notetype_search(note_ids)))
    # `"Term Audio:"` matches notes whose Term Audio field is empty.
    return list(col.find_notes(f'{notetype_search(note_ids)} "{config.TERM_AUDIO}:"'))


def final_name(pick) -> str:
    """The media filename this pick ends up under.

    Store clips are levelled before they are written, so the levelled name is
    the only name they ever get — no second file, no repointing. Without ffmpeg
    the raw clip is the best we can do, and the bare name is its final form.
    """
    return loudness.norm_name(media_name(pick)) if loudness.available() \
        else media_name(pick)


def already_picked(note, pick) -> bool:
    """True when Term Audio already holds this pick's clip in its final form.

    A bare `la_…` where ffmpeg is available is deliberately NOT a match: it is
    an unlevelled leftover (an interrupted overwrite, or a clip written before
    levelling existed), so saying no here is what repairs it on the next run.
    """
    clips = _SOUND.findall(note[config.TERM_AUDIO] or "")
    return len(clips) == 1 and clips[0] == final_name(pick)


def plan_backfill(col, conn, *, overwrite: bool = False, note_ids=None) -> dict:
    """Read-only. For every target note, resolve a Pick and classify it.

    Returns {'total', 'by_source', 'filled', 'nothing', 'missing_file',
    'samples', 'picks'}. `missing_file` counts picks whose clip blob is absent.
    `nothing` counts notes the store cannot serve — under `overwrite` those keep
    whatever audio they already have rather than being blanked.
    """
    by_source = Counter()
    nothing = missing_file = 0
    samples = []
    picks = {}
    for nid in select_targets(col, overwrite=overwrite, note_ids=note_ids):
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


def _level(job):
    """Level one store clip's bytes. Runs in a worker thread — pure bytes in,
    bytes out, no sqlite and no collection access. Reports whether levelling
    actually happened, so the clip is never filed under a name claiming more
    than was done to it; an unlevellable clip keeps the bare name and is retried
    on the next run."""
    nid, pick, data = job
    levelled = loudness.normalize_clip(data, _ext(pick.file))
    if levelled is None:
        return nid, pick, data, False
    return nid, pick, levelled[0], True


def run_backfill(col, conn, *, overwrite: bool = False, note_ids=None) -> dict:
    """Write each picked recording's bytes into collection.media and set
    `[sound:...]` on the note's Term Audio. Caller backs up first.

    The clip is levelled on the way in, while we still hold its bytes, and
    written under its levelled name — so a store clip lands as exactly one media
    file and the loudness pass has nothing left to do with it. (Clips already on
    disk, mined elsewhere, are a different story: that pass has to read, level,
    rename and repoint, because there is no earlier point to intercept.)

    Without `overwrite` only empty fields are filled (re-run safe). With it,
    store audio supersedes what is there: the field is *set*, not appended, so a
    card carrying several mined clips collapses to the one store recording. A
    note the store cannot serve keeps its existing audio — there is no clip to
    swap in, and blanking it would only lose the card its sound.

    ffmpeg is a subprocess, so levelling is threaded a batch at a time; media
    and note writes stay on this thread. Per-card error isolation.
    Returns {'filled': {src: n}, 'unchanged': n, 'errors': [(nid, repr)]}.
    """
    plan = plan_backfill(col, conn, overwrite=overwrite, note_ids=note_ids)
    filled = Counter()
    unchanged = 0
    errors = []

    pending = []
    for nid, pick in plan["picks"].items():
        try:
            note = col.get_note(nid)
            if overwrite:
                if already_picked(note, pick):
                    unchanged += 1
                    continue
            elif _SOUND.search(note[config.TERM_AUDIO] or ""):
                continue  # already has audio (race / re-run)
            pending.append((nid, pick, read_clip(conn, pick)))
        except Exception as e:
            errors.append((nid, repr(e)))

    for batch in [pending[i:i + config.AUDIO_NORM_BATCH]
                  for i in range(0, len(pending), config.AUDIO_NORM_BATCH)]:
        if loudness.available():
            with ThreadPoolExecutor(max_workers=config.AUDIO_NORM_WORKERS) as pool:
                batch = list(pool.map(_level, batch))
        else:
            batch = [(nid, pick, data, False) for nid, pick, data in batch]
        for nid, pick, data, levelled in batch:
            try:
                name = loudness.norm_name(media_name(pick)) if levelled \
                    else media_name(pick)
                fname = col.media.write_data(name, data)
                note = col.get_note(nid)
                value = f"[sound:{fname}]"
                if note[config.TERM_AUDIO] == value:
                    unchanged += 1     # retried clip landed where it already was
                    continue
                note[config.TERM_AUDIO] = value
                col.update_note(note)
                filled[pick.source] += 1
            except Exception as e:
                errors.append((nid, repr(e)))

    return {"filled": filled, "unchanged": unchanged, "errors": errors}
