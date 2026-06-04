"""Term-audio backfill (goal #3).

Fills empty `Term Audio` on in-scope Kinomoto notes from the local-audio-yomichan
offline DB. Decided lookup chain (research/HANDOFF-audio-backfill.md):

    nhk16 → shinmeikai8 → forvo[strawberrybrown] → jpod   (stop at first hit)

nhk16/shinmeikai8/jpod are matched on exact (term, reading) so homographs get the
right accent; forvo is term-only and only fires when both dictionaries miss (the
long tail), preferring the strawberrybrown voice.

No `aqt` imports — the entries.db connection and the local-audio user_files dir are
passed in, so this is unit-testable headless (mirrors convert.py).
"""
import os
import re
from dataclasses import dataclass
from typing import Optional

from . import config

# Reading-keyed, accent-authoritative dictionaries (tried first, in this order).
DICT_SOURCES = ("nhk16", "shinmeikai8")
# Term-only crowd source (tail fallback) and its preferred curated voice.
FORVO_SPEAKER = "strawberrybrown"

_TAG = re.compile(r"<[^>]+>")
_SOUND = re.compile(r"\[sound:[^\]]+\]")
_KATA_TO_HIRA = {cp: cp - 0x60 for cp in range(0x30A1, 0x30F7)}


def _clean(s: str) -> str:
    return _TAG.sub("", s or "").replace("​", "").replace("&nbsp;", "").strip()


def _to_hira(s: str) -> str:
    return s.translate(_KATA_TO_HIRA)


@dataclass
class Pick:
    """A chosen recording. `file` is relative to `<source>_files/`."""
    source: str
    file: str
    speaker: Optional[str] = None


def lookup(conn, term: str, reading: str) -> Optional[Pick]:
    """Run the decided chain against an entries.db connection. Returns the best
    Pick or None. Pure read — does not touch the filesystem."""
    term = _clean(term)
    reading = _to_hira(_clean(reading))
    if not term:
        return None

    if reading:
        for src in DICT_SOURCES:
            row = conn.execute(
                "SELECT file FROM entries WHERE expression=? AND reading=? AND source=? LIMIT 1",
                (term, reading, src),
            ).fetchone()
            if row:
                return Pick(src, row[0])

    rows = conn.execute(
        "SELECT file, speaker FROM entries WHERE expression=? AND source='forvo'",
        (term,),
    ).fetchall()
    if rows:
        rows.sort(key=lambda r: r[1] != FORVO_SPEAKER)  # strawberrybrown first
        return Pick("forvo", rows[0][0], rows[0][1])

    if reading:
        row = conn.execute(
            "SELECT file FROM entries WHERE expression=? AND reading=? AND source='jpod' LIMIT 1",
            (term, reading),
        ).fetchone()
        if row:
            return Pick("jpod", row[0])

    return None


def source_file_path(user_files_dir: str, pick: Pick) -> str:
    """Absolute path to the picked recording on disk."""
    return os.path.join(user_files_dir, f"{pick.source}_files", pick.file)


def locate(addons_folder: str) -> Optional[tuple]:
    """Find the local-audio-yomichan install. Returns (entries_db, user_files_dir)
    or None if the add-on / index isn't present. `addons_folder` is Anki's
    addons21 dir (mw.addonManager.addonsFolder())."""
    user_files = os.path.join(addons_folder, config.LOCAL_AUDIO_ADDON_ID, "user_files")
    db = os.path.join(user_files, "entries.db")
    return (db, user_files) if os.path.isfile(db) else None


# --- collection-level pass (anki, no aqt) ---------------------------------------

def _search() -> str:
    excl = " ".join(f"-deck:*{sub}*" for sub in config.EXCLUDE_DECK_SUBSTRINGS)
    # `"Term Audio:"` matches notes whose Term Audio field is empty.
    return f'note:"{config.TARGET_NOTETYPE}" "Term Audio:" {excl}'.strip()


def select_targets(col) -> list:
    """In-scope Kinomoto note ids with an empty Term Audio field."""
    if col.models.by_name(config.TARGET_NOTETYPE) is None:
        return []
    return list(col.find_notes(_search()))


def plan_backfill(col, conn, user_files_dir: str) -> dict:
    """Read-only. For every target note, resolve a Pick and classify it.

    Returns {'total', 'by_source': {src: n}, 'nothing', 'missing_file',
    'samples': [(term, reading, source, speaker)], 'picks': {nid: Pick}}.
    """
    by_source = {}
    nothing = missing_file = 0
    samples = []
    picks = {}
    for nid in select_targets(col):
        note = col.get_note(nid)
        term, reading = note["Term"], note["Reading"]
        pick = lookup(conn, term, reading)
        if pick is None:
            nothing += 1
            continue
        if not os.path.isfile(source_file_path(user_files_dir, pick)):
            missing_file += 1
            continue
        picks[nid] = pick
        by_source[pick.source] = by_source.get(pick.source, 0) + 1
        if len(samples) < config.SAMPLE_N * 3:
            samples.append((_clean(term), _clean(reading), pick.source, pick.speaker))
    return {
        "total": len(picks) + nothing + missing_file,
        "by_source": by_source,
        "filled": len(picks),
        "nothing": nothing,
        "missing_file": missing_file,
        "samples": samples,
        "picks": picks,
    }


def run_backfill(col, conn, user_files_dir: str) -> dict:
    """Copy each picked recording into collection.media and write `[sound:...]`
    into the note's Term Audio. Caller is responsible for backup. Only writes
    notes that are still empty (re-run safe). Per-card errors are collected, not
    raised, so one bad recording can't strand the rest of the batch.
    Returns {'filled': {src: n}, 'errors': [(nid, repr)]}.
    """
    plan = plan_backfill(col, conn, user_files_dir)
    filled = {}
    errors = []
    for nid, pick in plan["picks"].items():
        try:
            note = col.get_note(nid)
            if _SOUND.search(note["Term Audio"] or ""):
                continue  # already has audio (race / re-run)
            fname = col.media.add_file(source_file_path(user_files_dir, pick))
            note["Term Audio"] = f"[sound:{fname}]"
            col.update_note(note)
            filled[pick.source] = filled.get(pick.source, 0) + 1
        except Exception as e:
            errors.append((nid, repr(e)))
    return {"filled": filled, "errors": errors}
