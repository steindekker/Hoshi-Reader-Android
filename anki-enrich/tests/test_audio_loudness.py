import os
import re as _re
import subprocess

import pytest

from ankienrich import config
from ankienrich.audio import loudness

anki = pytest.importorskip("anki")
from anki.collection import Collection

needs_ffmpeg = pytest.mark.skipif(not loudness.available(),
                                  reason="ffmpeg/ffprobe not on PATH")

_KINOMOTO_FIELDS = [config.TERM, config.READING, config.TERM_AUDIO]


# --- gain (pure) ------------------------------------------------------------------

def test_gain_lifts_a_quiet_clip_to_target():
    # -24 LUFS with peaks way down at -12 dBTP: 8 dB of headroom, 8 dB wanted.
    assert loudness.gain_db(-24.0, -12.0) == pytest.approx(8.0)


def test_gain_clamped_by_peak_ceiling():
    """A quiet clip with a high crest factor gets the boost its peaks allow and
    stops there — the pass never limits its way to the target."""
    # Wants +8 dB, but only 4 dB sits between its peak and the -1 dBTP ceiling.
    assert loudness.gain_db(-24.0, -5.0) == pytest.approx(4.0)


def test_gain_is_negative_for_a_too_loud_clip():
    # 6 LU over target, and its peaks leave room to spare — pull it down by 6.
    assert loudness.gain_db(-10.0, -6.0) == pytest.approx(-6.0)


def test_gain_never_exceeds_the_ceiling_even_when_already_clipping():
    assert loudness.gain_db(-30.0, 0.5) == pytest.approx(-1.5)


# --- naming / marker ---------------------------------------------------------------

def test_norm_name_prefixes_the_basename():
    assert loudness.norm_name("audio/x.mp3") == "norm_x.mp3"


def test_is_normalized_recognises_the_marker():
    assert loudness.is_normalized("norm_x.mp3")
    assert not loudness.is_normalized("x.mp3")


# --- clip-level normalization (needs ffmpeg) ---------------------------------------

def _tone(path, *, seconds=2.0, volume="0.02"):
    """A deliberately quiet sine, encoded like a mined clip."""
    subprocess.run(
        ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
         "-f", "lavfi", "-i", f"sine=frequency=220:duration={seconds}",
         "-af", f"volume={volume}", "-c:a", "libmp3lame", "-b:a", "64k", path],
        check=True)
    with open(path, "rb") as fh:
        return fh.read()


@needs_ffmpeg
def test_normalize_clip_raises_a_quiet_clip(tmp_path):
    data = _tone(str(tmp_path / "quiet.mp3"))
    before = loudness.measure(str(tmp_path / "quiet.mp3"))
    out = loudness.normalize_clip(data, "mp3")
    assert out is not None
    levelled, gain = out
    assert gain > 1.0
    written = tmp_path / "out.mp3"
    written.write_bytes(levelled)
    after = loudness.measure(str(written))
    assert after[0] > before[0]                       # louder than it was
    assert after[1] <= config.AUDIO_PEAK_CEILING_DBTP + 0.5   # and still under the ceiling


@needs_ffmpeg
def test_normalize_clip_keeps_exact_bytes_when_already_level(tmp_path):
    """Within tolerance the pass must not re-encode — that would spend a
    generation of quality on an inaudible change."""
    path = str(tmp_path / "ok.mp3")
    data = _tone(path, volume="0.02")
    # Pre-level it, then feed the result back in: the second pass is a no-op.
    levelled, _ = loudness.normalize_clip(data, "mp3")
    again = loudness.normalize_clip(levelled, "mp3")
    assert again == (levelled, 0.0)


@needs_ffmpeg
def test_normalize_clip_rejects_garbage():
    assert loudness.normalize_clip(b"not audio at all", "mp3") is None


def test_normalize_clip_skips_unsupported_container():
    assert loudness.normalize_clip(b"\x00", "wav") is None


# --- collection pass ----------------------------------------------------------------

@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "test.anki2"))
    m = c.models.new(config.NOTETYPE)
    for fname in _KINOMOTO_FIELDS:
        c.models.add_field(m, c.models.new_field(fname))
    t = c.models.new_template("Card 1")
    t["qfmt"], t["afmt"] = "{{Term}}", "{{Reading}}"
    c.models.add_template(m, t)
    c.models.add(m)
    yield c
    c.close()


def _add(c, term, audio_val):
    n = c.new_note(c.models.by_name(config.NOTETYPE))
    n[config.TERM], n[config.TERM_AUDIO] = term, audio_val
    c.add_note(n, c.decks.id("言葉文章"))
    return n.id


def test_select_targets_skips_empty_normalized_and_unsupported(col):
    quiet = _add(col, "橋", "[sound:a.opus]")
    _add(col, "猫", "[sound:norm_b.opus]")   # already levelled
    _add(col, "犬", "")                       # no audio
    _add(col, "鳥", "[sound:c.wav]")          # container we don't encode
    assert loudness.select_targets(col) == {"a.opus": [quiet]}


def test_select_targets_groups_notes_sharing_one_clip(col):
    """Keyed by file so a shared clip is measured and encoded once, not twice."""
    a = _add(col, "橋", "[sound:same.mp3]")
    b = _add(col, "箸", "[sound:same.mp3]")
    assert loudness.select_targets(col) == {"same.mp3": [a, b]}


@needs_ffmpeg
def test_run_backfill_repoints_notes_and_is_rerun_safe(col, tmp_path):
    data = _tone(str(tmp_path / "src.mp3"))
    fname = col.media.write_data("clip.mp3", data)
    nid = _add(col, "橋", f"[sound:{fname}]")

    res = loudness.run_backfill(col)
    assert res["normalized"] == 1 and res["notes"] == 1 and not res["errors"]

    value = col.get_note(nid)[config.TERM_AUDIO]
    new = _re.search(r"\[sound:([^\]]+)\]", value).group(1)
    assert loudness.is_normalized(new)
    assert col.media.have(new)
    with open(os.path.join(col.media.dir(), new), "rb") as fh:
        assert fh.read() != data          # actually re-encoded, not just renamed

    # The prefix is the marker: a second run finds nothing left to do.
    assert loudness.run_backfill(col)["normalized"] == 0


@needs_ffmpeg
def test_run_backfill_levels_a_shared_clip_once_for_both_notes(col, tmp_path):
    data = _tone(str(tmp_path / "src.mp3"))
    fname = col.media.write_data("shared.mp3", data)
    a, b = (_add(col, "橋", f"[sound:{fname}]"), _add(col, "箸", f"[sound:{fname}]"))

    res = loudness.run_backfill(col)
    assert res["normalized"] == 1 and res["notes"] == 2
    assert (col.get_note(a)[config.TERM_AUDIO]
            == col.get_note(b)[config.TERM_AUDIO])


def test_run_backfill_counts_a_clip_whose_file_vanished(col):
    _add(col, "橋", "[sound:gone.mp3]")
    res = loudness.run_backfill(col)
    assert res["missing"] == 1 and res["normalized"] == 0 and not res["errors"]


def test_run_backfill_isolates_a_clip_ffmpeg_cannot_read(col, monkeypatch):
    col.media.write_data("bad.mp3", b"not audio")
    _add(col, "橋", "[sound:bad.mp3]")
    monkeypatch.setattr(loudness, "normalize_clip", lambda data, ext: None)
    res = loudness.run_backfill(col)
    assert len(res["errors"]) == 1 and res["normalized"] == 0
