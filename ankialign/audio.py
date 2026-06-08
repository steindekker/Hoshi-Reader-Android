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

# Full fill order (mirrors lookup() below) + display labels, for the sources legend.
FILL_ORDER = ("nhk16", "shinmeikai8", "forvo", "jpod")
SOURCE_LABELS = {"nhk16": "NHK", "shinmeikai8": "Shinmeikai",
                 "forvo": "Forvo", "jpod": "JPod"}

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


def lookup_dict(conn, term: str, reading: str) -> Optional[Pick]:
    """Reading-keyed accent-dictionary lookup only (nhk16 → shinmeikai8). Never
    returns forvo/jpod. This is the authoritative-pitch source used by the
    replace/re-pitch pass. Pure read."""
    term = _clean(term)
    reading = _to_hira(_clean(reading))
    if not term or not reading:
        return None
    for src in DICT_SOURCES:
        row = conn.execute(
            "SELECT file FROM entries WHERE expression=? AND reading=? AND source=? LIMIT 1",
            (term, reading, src),
        ).fetchone()
        if row:
            return Pick(src, row[0])
    return None


def lookup(conn, term: str, reading: str) -> Optional[Pick]:
    """Run the full fill chain (nhk16 → shinmeikai8 → forvo[strawberrybrown] →
    jpod) against an entries.db connection. Returns the best Pick or None."""
    dict_pick = lookup_dict(conn, term, reading)
    if dict_pick:
        return dict_pick

    t = _clean(term)
    if not t:
        return None

    rows = conn.execute(
        "SELECT file, speaker FROM entries WHERE expression=? AND source='forvo'",
        (t,),
    ).fetchall()
    if rows:
        rows.sort(key=lambda r: r[1] != FORVO_SPEAKER)  # strawberrybrown first
        return Pick("forvo", rows[0][0], rows[0][1])

    r = _to_hira(_clean(reading))
    if r:
        row = conn.execute(
            "SELECT file FROM entries WHERE expression=? AND reading=? AND source='jpod' LIMIT 1",
            (t, r),
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


# --- re-pitch pass: replace mined audio with accent-dictionary audio ------------
# Separate from the fill pass. Targets in-scope Kinomoto notes whose Term Audio is
# a NON-.opus recording (the pre-existing jidoujisho-mined mp3/ogg — likely jpod,
# with frequent wrong pitch) and replaces it ONLY when an accent dictionary
# (nhk16/shinmeikai8) has a reading-correct hit. Never replaces with forvo/jpod.
# Non-destructive: the old media file is left in the collection (only the field
# reference changes); caller backs up first. See research/HANDOFF-audio-backfill.md.


def _current_sound(field_val: str) -> Optional[str]:
    m = _SOUND.search(field_val or "")
    return m.group(0) if m else None  # full "[sound:...]" token


def _current_sound_file(field_val: str) -> Optional[str]:
    tok = _current_sound(field_val)
    return tok[len("[sound:"):-1] if tok else None


def select_repitch_targets(col) -> list:
    """In-scope Kinomoto nids whose Term Audio is a non-.opus recording.

    `.opus` files are only ever written by our own local-audio passes (already a
    best-available source), so they're excluded; everything else is pre-existing
    mined audio eligible for a dictionary upgrade."""
    if col.models.by_name(config.TARGET_NOTETYPE) is None:
        return []
    excl = " ".join(f"-deck:*{sub}*" for sub in config.EXCLUDE_DECK_SUBSTRINGS)
    search = f'note:"{config.TARGET_NOTETYPE}" -"Term Audio:" {excl}'.strip()
    out = []
    for nid in col.find_notes(search):
        fn = _current_sound_file(col.get_note(nid)["Term Audio"])
        if fn and not fn.lower().endswith(".opus"):
            out.append(nid)
    return out


def plan_repitch(col, conn, user_files_dir: str) -> dict:
    """Read-only. For every non-opus target, resolve a dictionary Pick.

    Returns {'targets', 'replaceable', 'no_dict', 'by_source', 'samples', 'picks'}.
    """
    by_source = {}
    no_dict = 0
    samples = []
    picks = {}
    for nid in select_repitch_targets(col):
        note = col.get_note(nid)
        pick = lookup_dict(conn, note["Term"], note["Reading"])
        if pick is None:
            no_dict += 1
            continue
        if not os.path.isfile(source_file_path(user_files_dir, pick)):
            continue
        picks[nid] = pick
        by_source[pick.source] = by_source.get(pick.source, 0) + 1
        if len(samples) < config.SAMPLE_N * 3:
            samples.append((_clean(note["Term"]), _clean(note["Reading"]),
                            _current_sound_file(note["Term Audio"]), pick.source))
    return {
        "targets": len(picks) + no_dict,
        "replaceable": len(picks),
        "no_dict": no_dict,
        "by_source": by_source,
        "samples": samples,
        "picks": picks,
    }


def run_repitch(col, conn, user_files_dir: str) -> dict:
    """Replace each target's Term Audio with its dictionary recording. Caller backs
    up first. Non-destructive (old media file kept). Per-card error isolation.
    Returns {'replaced': {src: n}, 'errors': [(nid, repr)]}.
    """
    plan = plan_repitch(col, conn, user_files_dir)
    replaced = {}
    errors = []
    for nid, pick in plan["picks"].items():
        try:
            note = col.get_note(nid)
            fname = col.media.add_file(source_file_path(user_files_dir, pick))
            newval = f"[sound:{fname}]"
            if (note["Term Audio"] or "").strip() == newval:
                continue  # already this exact recording
            note["Term Audio"] = newval
            col.update_note(note)
            replaced[pick.source] = replaced.get(pick.source, 0) + 1
        except Exception as e:
            errors.append((nid, repr(e)))
    return {"replaced": replaced, "errors": errors}
