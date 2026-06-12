"""Term-audio backfill.

Fills empty `Term Audio` on in-scope Kinomoto notes from the local-audio-yomichan
offline DB. Lookup chain:

    nhk16 → shinmeikai8 → forvo[strawberrybrown] → jpod   (stop at first hit)

nhk16/shinmeikai8/jpod are matched on exact (term, reading) so homographs get the
right accent; forvo is term-only and only fires when both dictionaries miss (the
long tail), preferring the strawberrybrown voice.

No `aqt` imports — the entries.db connection and the local-audio user_files dir are
passed in, so this is unit-testable headless.
"""
import os
import re
from collections import Counter
from dataclasses import dataclass

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
    speaker: str | None = None


def lookup_dict(conn, term: str, reading: str) -> Pick | None:
    """Reading-keyed accent-dictionary lookup only (nhk16 → shinmeikai8). Never
    returns forvo/jpod. Used by the re-pitch pass. Pure read."""
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


def lookup(conn, term: str, reading: str) -> Pick | None:
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


def locate(addons_folder: str) -> tuple[str, str] | None:
    """Find the local-audio-yomichan install. Returns (entries_db, user_files_dir)
    or None if the add-on / index isn't present. `addons_folder` is Anki's
    addons21 dir."""
    user_files = os.path.join(addons_folder, config.LOCAL_AUDIO_ADDON_ID, "user_files")
    db = os.path.join(user_files, "entries.db")
    return (db, user_files) if os.path.isfile(db) else None


# --- collection-level pass (anki, no aqt) ---------------------------------------

def _search() -> str:
    # `"Term Audio:"` matches notes whose Term Audio field is empty.
    return f'note:"{config.NOTETYPE}" "{config.TERM_AUDIO}:"'


def select_targets(col) -> list:
    """In-scope Kinomoto note ids with an empty Term Audio field."""
    if col.models.by_name(config.NOTETYPE) is None:
        return []
    return list(col.find_notes(_search()))


def plan_backfill(col, conn, user_files_dir: str) -> dict:
    """Read-only. For every target note, resolve a Pick and classify it.

    Returns {'total', 'by_source': {src: n}, 'nothing', 'missing_file',
    'samples': [(term, reading, source, speaker)], 'picks': {nid: Pick}}.
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
        if not os.path.isfile(source_file_path(user_files_dir, pick)):
            missing_file += 1
            continue
        picks[nid] = pick
        by_source[pick.source] += 1
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
    filled = Counter()
    errors = []
    for nid, pick in plan["picks"].items():
        try:
            note = col.get_note(nid)
            if _SOUND.search(note[config.TERM_AUDIO] or ""):
                continue  # already has audio (race / re-run)
            fname = col.media.add_file(source_file_path(user_files_dir, pick))
            note[config.TERM_AUDIO] = f"[sound:{fname}]"
            col.update_note(note)
            filled[pick.source] += 1
        except Exception as e:
            errors.append((nid, repr(e)))
    return {"filled": filled, "errors": errors}


# --- re-pitch pass: replace mined audio with accent-dictionary audio ------------
# Targets in-scope Kinomoto notes whose Term Audio is a NON-.opus recording (the
# pre-existing jidoujisho-mined mp3/ogg — likely jpod, with frequent wrong pitch)
# and replaces it ONLY when an accent dictionary (nhk16/shinmeikai8) has a
# reading-correct hit. Never replaces with forvo/jpod. Non-destructive: the old
# media file is left in the collection (only the field reference changes); caller
# backs up first.


def _current_sound(field_val: str) -> str | None:
    m = _SOUND.search(field_val or "")
    return m.group(0) if m else None  # full "[sound:...]" token


def _current_sound_file(field_val: str) -> str | None:
    tok = _current_sound(field_val)
    return tok[len("[sound:"):-1] if tok else None


def select_repitch_targets(col) -> list:
    """In-scope Kinomoto nids whose Term Audio is a non-.opus recording.

    `.opus` files are only ever written by our own local-audio passes (already a
    best-available source), so they're excluded; everything else is pre-existing
    mined audio eligible for a dictionary upgrade."""
    if col.models.by_name(config.NOTETYPE) is None:
        return []
    search = f'note:"{config.NOTETYPE}" -"{config.TERM_AUDIO}:"'
    out = []
    for nid in col.find_notes(search):
        fn = _current_sound_file(col.get_note(nid)[config.TERM_AUDIO])
        if fn and not fn.lower().endswith(".opus"):
            out.append(nid)
    return out


def plan_repitch(col, conn, user_files_dir: str) -> dict:
    """Read-only. For every non-opus target, resolve a dictionary Pick.

    Returns {'targets', 'replaceable', 'no_dict', 'by_source', 'samples', 'picks'}.
    """
    by_source = Counter()
    no_dict = 0
    samples = []
    picks = {}
    for nid in select_repitch_targets(col):
        note = col.get_note(nid)
        pick = lookup_dict(conn, note[config.TERM], note[config.READING])
        if pick is None:
            no_dict += 1
            continue
        if not os.path.isfile(source_file_path(user_files_dir, pick)):
            continue
        picks[nid] = pick
        by_source[pick.source] += 1
        if len(samples) < config.SAMPLE_N * 3:
            samples.append((_clean(note[config.TERM]), _clean(note[config.READING]),
                            _current_sound_file(note[config.TERM_AUDIO]), pick.source))
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
    replaced = Counter()
    errors = []
    for nid, pick in plan["picks"].items():
        try:
            note = col.get_note(nid)
            fname = col.media.add_file(source_file_path(user_files_dir, pick))
            newval = f"[sound:{fname}]"
            if (note[config.TERM_AUDIO] or "").strip() == newval:
                continue  # already this exact recording
            note[config.TERM_AUDIO] = newval
            col.update_note(note)
            replaced[pick.source] += 1
        except Exception as e:
            errors.append((nid, repr(e)))
    return {"replaced": replaced, "errors": errors}
