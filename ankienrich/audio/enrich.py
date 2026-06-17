"""Term-audio backfill from the self-contained var/audio.sqlite store.

Fills empty `Term Audio` on in-scope Kinomoto notes. Lookup chain:

    nhk16 → shinmeikai8 → forvo[strawberrybrown] → jpod   (stop at first hit)

nhk16/shinmeikai8/jpod match on exact (term, reading) so homographs get the right
accent; forvo is term-only and only fires when both dictionaries miss.

No `aqt` imports and no add-on coupling — the connection is to OUR audio.sqlite
(built by audio/build.py), and clip bytes are embedded blobs, so this is headless
and unit-testable. audio.sqlite schema:
    entries(expression, reading, source, speaker, file)   -- lookup index
    clips(source, file, data BLOB, PRIMARY KEY(source, file))  -- bytes once per clip
"""
import os
import re
from collections import Counter
from dataclasses import dataclass

from .. import config

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
    """A chosen recording. `file` is the source-relative filename (the key into
    `clips`, and—via `_media_name`—the media file basename)."""
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
    jpod) against audio.sqlite. Returns the best Pick or None."""
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


def read_clip(conn, pick: Pick) -> bytes | None:
    """The picked recording's bytes from the clips table, or None if absent."""
    row = conn.execute(
        "SELECT data FROM clips WHERE source=? AND file=?", (pick.source, pick.file),
    ).fetchone()
    return row[0] if row else None


def _media_name(pick: Pick) -> str:
    """Stable, collision-resistant media filename for a pick."""
    return f"la_{pick.source}_{os.path.basename(pick.file)}"


# --- collection-level pass (anki, no aqt) ---------------------------------------

def _search() -> str:
    # `"Term Audio:"` matches notes whose Term Audio field is empty.
    return f'note:"{config.NOTETYPE}" "{config.TERM_AUDIO}:"'


def select_targets(col) -> list:
    """In-scope Kinomoto note ids with an empty Term Audio field."""
    if col.models.by_name(config.NOTETYPE) is None:
        return []
    return list(col.find_notes(_search()))


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
            fname = col.media.write_data(_media_name(pick), data)
            note[config.TERM_AUDIO] = f"[sound:{fname}]"
            col.update_note(note)
            filled[pick.source] += 1
        except Exception as e:
            errors.append((nid, repr(e)))
    return {"filled": filled, "errors": errors}


# --- re-pitch pass: replace mined audio with accent-dictionary audio ------------

def _current_sound(field_val: str) -> str | None:
    m = _SOUND.search(field_val or "")
    return m.group(0) if m else None


def _current_sound_file(field_val: str) -> str | None:
    tok = _current_sound(field_val)
    return tok[len("[sound:"):-1] if tok else None


def select_repitch_targets(col) -> list:
    """In-scope Kinomoto nids whose Term Audio is a non-.opus recording (our own
    passes only ever write .opus, so those are excluded)."""
    if col.models.by_name(config.NOTETYPE) is None:
        return []
    search = f'note:"{config.NOTETYPE}" -"{config.TERM_AUDIO}:"'
    out = []
    for nid in col.find_notes(search):
        fn = _current_sound_file(col.get_note(nid)[config.TERM_AUDIO])
        if fn and not fn.lower().endswith(".opus"):
            out.append(nid)
    return out


def plan_repitch(col, conn) -> dict:
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
        if read_clip(conn, pick) is None:
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


def run_repitch(col, conn) -> dict:
    """Replace each target's Term Audio with its dictionary recording. Caller
    backs up first. Non-destructive (old media kept). Per-card error isolation.
    Returns {'replaced': {src: n}, 'errors': [(nid, repr)]}.
    """
    plan = plan_repitch(col, conn)
    replaced = Counter()
    errors = []
    for nid, pick in plan["picks"].items():
        try:
            note = col.get_note(nid)
            data = read_clip(conn, pick)
            fname = col.media.write_data(_media_name(pick), data)
            newval = f"[sound:{fname}]"
            if (note[config.TERM_AUDIO] or "").strip() == newval:
                continue  # already this exact recording
            note[config.TERM_AUDIO] = newval
            col.update_note(note)
            replaced[pick.source] += 1
        except Exception as e:
            errors.append((nid, repr(e)))
    return {"replaced": replaced, "errors": errors}
