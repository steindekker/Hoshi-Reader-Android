"""Loudness leveling for card audio (ffmpeg).

Term Audio reaches a card from several places — clips mined by jidoujisho on the
phone (opus), clips the backfill pulls from the local-audio store (mp3), the
occasional ogg — and nobody masters them together, so their integrated loudness
spans ~9 LU and review volume lurches from card to card. This pass levels them.

Method: measure integrated loudness and true peak (ffmpeg's EBU R128 `loudnorm`
analysis), then apply one linear gain. No compression, no limiting — the gain is
clamped so the true peak stays under the ceiling, which leaves the waveform's
shape, and with it the pitch contour, exactly as recorded. A clip with too
little headroom therefore lands short of the target on purpose: buying those
last few LU would mean reshaping audio these cards use as pitch-accent reference.

The leveled clip is written under a `norm_` filename and the note repointed at
it. A new name is unavoidable — Anki's media API renames on a same-name content
conflict — so the prefix does double duty as the "already leveled" marker. State
therefore lives in the collection, which is what makes the pass idempotent, and
what lets it stay correct across a restored backup or a swapped audio store.

Runs after the store backfill in the pipeline, so clips filled in this very run
get leveled by the same pass, whichever store they came from.
"""
import json
import os
import re
import shutil
import statistics
import subprocess
import tempfile
from collections import defaultdict
from concurrent.futures import ThreadPoolExecutor

from .. import config
from ..collection import notetype_search

_SOUND = re.compile(r"\[sound:([^\]]+)\]")

# Container extension -> encoder. Re-encoding in the source codec keeps the
# filename honest and playback behaviour identical; anything else is left alone.
ENCODERS = {"mp3": "libmp3lame", "opus": "libopus", "ogg": "libvorbis"}

# Encode above the source rate: these clips are already lossy (mined opus runs
# ~32 kbps), and matching that rate would stack a second generation of loss on
# top. The headroom costs a few KB a clip and keeps the encoder inaudible.
_BITRATE_HEADROOM = 1.5
_BITRATE_FLOOR = 64_000
_BITRATE_CEILING = 192_000

# A word clip is ~1 s. Anything near this is a hung ffmpeg, not a slow one.
_TIMEOUT = 60


def available() -> bool:
    """Both ffmpeg binaries on PATH. Absent them the pass skips, never fails."""
    return bool(shutil.which("ffmpeg") and shutil.which("ffprobe"))


def _run(cmd: list[str]) -> subprocess.CompletedProcess | None:
    """Run a helper, or None if it failed, timed out, or isn't installed."""
    try:
        proc = subprocess.run(cmd, capture_output=True, text=True, timeout=_TIMEOUT)
    except (OSError, subprocess.SubprocessError):
        return None
    return proc if proc.returncode == 0 else None


def _ext(fname: str) -> str:
    return fname.rsplit(".", 1)[-1].lower() if "." in fname else ""


# --- measurement + gain ----------------------------------------------------------

def measure(path: str) -> tuple[float, float] | None:
    """(integrated LUFS, true peak dBTP) for a clip, or None if unmeasurable.

    loudnorm's analysis pass prints its measurements as JSON on stderr; we keep
    those and throw the filtered audio away. Digital silence measures as -inf,
    which parses as unmeasurable — no gain makes silence louder.
    """
    proc = _run([
        "ffmpeg", "-hide_banner", "-nostats", "-i", path,
        "-af", (f"loudnorm=I={config.AUDIO_TARGET_LUFS}"
                f":TP={config.AUDIO_PEAK_CEILING_DBTP}:print_format=json"),
        "-f", "null", "-",
    ])
    if proc is None:
        return None
    start, end = proc.stderr.rfind("{"), proc.stderr.rfind("}")
    if start < 0 or end < start:
        return None
    try:
        report = json.loads(proc.stderr[start:end + 1])
        return float(report["input_i"]), float(report["input_tp"])
    except (ValueError, KeyError):  # includes the "-inf" of a silent clip
        return None


def gain_db(integrated: float, true_peak: float) -> float:
    """dB of gain to bring a clip to target, clamped by the true-peak ceiling.

    Negative for a clip that is too loud. The clamp is what keeps this a pure
    gain: a quiet clip with a high crest factor gets all the boost its peaks
    allow and stops there, instead of being limited into hitting the target.
    """
    return min(config.AUDIO_TARGET_LUFS - integrated,
               config.AUDIO_PEAK_CEILING_DBTP - true_peak)


def _target_bitrate(path: str) -> int:
    """Encode bitrate for a clip: the source rate plus headroom, bounded."""
    proc = _run(["ffprobe", "-v", "error", "-show_entries", "format=bit_rate",
                 "-of", "csv=p=0", path])
    try:
        source = int(proc.stdout.strip())
    except (AttributeError, ValueError):
        source = _BITRATE_FLOOR
    return int(min(max(source * _BITRATE_HEADROOM, _BITRATE_FLOOR), _BITRATE_CEILING))


def normalize_clip(data: bytes, ext: str) -> tuple[bytes, float] | None:
    """(clip bytes, gain applied) for one clip, or None if ffmpeg can't read it.

    Returns the original bytes with a gain of 0.0 when the clip is already
    within tolerance: a re-encode that changes nothing audible still costs a
    generation of quality, so a clip needing no gain keeps its exact bytes.
    """
    if ext not in ENCODERS:
        return None
    with tempfile.TemporaryDirectory() as tmp:
        src = os.path.join(tmp, f"in.{ext}")
        with open(src, "wb") as fh:
            fh.write(data)
        measured = measure(src)
        if measured is None:
            return None
        gain = gain_db(*measured)
        if abs(gain) < config.AUDIO_NORM_TOLERANCE_DB:
            return data, 0.0
        dst = os.path.join(tmp, f"out.{ext}")
        encoded = _run([
            "ffmpeg", "-hide_banner", "-nostats", "-y", "-i", src,
            "-af", f"volume={gain:.2f}dB",
            "-c:a", ENCODERS[ext], "-b:a", str(_target_bitrate(src)),
            "-map_metadata", "-1", dst,
        ])
        if encoded is None:
            return None
        with open(dst, "rb") as fh:
            return fh.read(), gain


# --- collection-level pass -------------------------------------------------------

def norm_name(fname: str) -> str:
    return config.AUDIO_NORM_PREFIX + os.path.basename(fname)


def is_normalized(fname: str) -> bool:
    return os.path.basename(fname).startswith(config.AUDIO_NORM_PREFIX)


def select_targets(col, *, note_ids=None) -> dict[str, list]:
    """{clip filename: [note id, …]} for every in-scope clip not yet leveled.

    Keyed by file rather than note, so a clip two cards share is measured and
    encoded once.
    """
    if col.models.by_name(config.NOTETYPE) is None:
        return {}
    targets = defaultdict(list)
    # `-"Term Audio:"` matches notes whose Term Audio field is NOT empty.
    for nid in col.find_notes(f'{notetype_search(note_ids)} -"{config.TERM_AUDIO}:"'):
        note = col.get_note(nid)
        for fname in _SOUND.findall(note[config.TERM_AUDIO] or ""):
            if not is_normalized(fname) and _ext(fname) in ENCODERS:
                targets[fname].append(nid)
    return dict(targets)


def plan_backfill(col, *, note_ids=None) -> dict:
    """Read-only. {'to_normalize': clips, 'notes': cards they sit on}."""
    targets = select_targets(col, note_ids=note_ids)
    return {"to_normalize": len(targets),
            "notes": len({nid for nids in targets.values() for nid in nids})}


def _prepare(media_dir: str, fname: str) -> tuple[str, bytes, float]:
    """Read + level one clip off the media dir. Runs in a worker thread, so it
    touches no collection state. ('ok'|'missing'|'error', bytes, gain)."""
    try:
        with open(os.path.join(media_dir, fname), "rb") as fh:
            data = fh.read()
    except OSError:
        return "missing", b"", 0.0
    result = normalize_clip(data, _ext(fname))
    if result is None:
        return "error", b"", 0.0
    return "ok", result[0], result[1]


def _batched(items: list, size: int):
    for i in range(0, len(items), size):
        yield items[i:i + size]


def run_backfill(col, *, note_ids=None) -> dict:
    """Level every un-leveled Term Audio clip and repoint the notes at it.

    Caller backs up first. ffmpeg runs in a thread pool, a batch at a time so
    pending clips don't pile up in memory; the collection writes stay on this
    thread. Per-clip and per-note error isolation. Returns
    {'normalized', 'notes', 'unchanged', 'missing', 'median_gain', 'errors'}.
    """
    targets = select_targets(col, note_ids=note_ids)
    media_dir = col.media.dir()
    gains: list[float] = []
    touched: set = set()
    normalized = unchanged = missing = 0
    errors: list[tuple] = []

    for batch in _batched(sorted(targets), config.AUDIO_NORM_BATCH):
        with ThreadPoolExecutor(max_workers=config.AUDIO_NORM_WORKERS) as pool:
            prepared = list(pool.map(lambda f: _prepare(media_dir, f), batch))
        for fname, (status, data, gain) in zip(batch, prepared):
            if status == "missing":
                missing += 1
                continue
            if status == "error":
                errors.append((fname, "ffmpeg could not process the clip"))
                continue
            try:
                written = col.media.write_data(norm_name(fname), data)
            except Exception as exc:  # noqa: BLE001 — one bad clip, not the batch
                errors.append((fname, repr(exc)))
                continue
            for nid in targets[fname]:
                try:
                    note = col.get_note(nid)
                    before = note[config.TERM_AUDIO]
                    after = before.replace(f"[sound:{fname}]", f"[sound:{written}]")
                    if after == before:
                        continue
                    note[config.TERM_AUDIO] = after
                    col.update_note(note)
                    touched.add(nid)
                except Exception as exc:  # noqa: BLE001
                    errors.append((nid, repr(exc)))
            normalized += 1
            gains.append(gain)
            unchanged += gain == 0.0

    applied = [g for g in gains if g]
    return {"normalized": normalized, "notes": len(touched),
            "unchanged": unchanged, "missing": missing,
            "median_gain": round(statistics.median(applied), 1) if applied else 0.0,
            "errors": errors}
