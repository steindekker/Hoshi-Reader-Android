import os
import re as _re
import sqlite3

import pytest

from ankienrich import audio

anki = pytest.importorskip("anki")
from anki.collection import Collection

from ankienrich import config

# Field list matching the real Kinomoto note type.
_KINOMOTO_FIELDS = [
    config.TERM, config.READING, config.TERM_FURIGANA, config.SENTENCE,
    config.SENTENCE_FURIGANA, config.CLOZE_BEFORE, config.CLOZE_INSIDE,
    config.CLOZE_AFTER, config.DEFINITION, config.PITCH, config.TERM_AUDIO,
]


def _new_notetype(col, name, fields):
    m = col.models.new(name)
    for fname in fields:
        col.models.add_field(m, col.models.new_field(fname))
    t = col.models.new_template("Card 1")
    t["qfmt"] = "{{%s}}" % fields[0]
    t["afmt"] = "{{FrontSide}}<hr>{{%s}}" % fields[1] if len(fields) > 1 else "{{FrontSide}}"
    col.models.add_template(m, t)
    col.models.add(m)
    return col.models.by_name(name)


def _audio_db(path, rows, clips=()):
    """rows: (expression, reading, source, speaker, file).
    clips: (source, file, data) — embedded blobs. Mirrors audio.sqlite."""
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE entries (expression text, reading text, "
                "source text, speaker text, file text)")
    con.execute("CREATE TABLE clips (source text, file text, data blob, "
                "PRIMARY KEY (source, file))")
    con.executemany("INSERT INTO entries VALUES (?,?,?,?,?)", rows)
    con.executemany("INSERT INTO clips VALUES (?,?,?)", clips)
    con.commit()
    return con


# --- lookup chain ---------------------------------------------------------------

def test_lookup_prefers_nhk16_then_shinmeikai(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("橋", "はし", "nhk16", None, "audio/n.opus"),
        ("橋", "はし", "shinmeikai8", None, "media/s.opus"),
        ("橋", "はし", "jpod", None, "media/j.opus"),
        ("橋", None, "forvo", "skent", "skent/h.opus"),
    ])
    assert audio.lookup(con, "橋", "はし") == audio.Pick("nhk16", "audio/n.opus")


def test_lookup_falls_to_shinmeikai_when_no_nhk(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("端", "はし", "shinmeikai8", None, "media/s.opus"),
        ("端", "はし", "jpod", None, "media/j.opus"),
    ])
    assert audio.lookup(con, "端", "はし") == audio.Pick("shinmeikai8", "media/s.opus")


def test_forvo_beats_jpod_and_prefers_strawberrybrown(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("筋トレ", None, "forvo", "skent", "skent/a.opus"),
        ("筋トレ", None, "forvo", "strawberrybrown", "strawberrybrown/b.opus"),
        ("筋トレ", "きんとれ", "jpod", None, "media/j.opus"),
    ])
    pick = audio.lookup(con, "筋トレ", "きんとれ")
    assert pick == audio.Pick("forvo", "strawberrybrown/b.opus", "strawberrybrown")


def test_never_falls_to_forvo_when_dict_hits(tmp_path):
    # dict hit must win even though forvo also has the term (homograph safety)
    con = _audio_db(str(tmp_path / "a.db"), [
        ("箸", "はし", "nhk16", None, "audio/n.opus"),
        ("箸", None, "forvo", "strawberrybrown", "strawberrybrown/x.opus"),
    ])
    assert audio.lookup(con, "箸", "はし").source == "nhk16"


def test_jpod_is_last_resort(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("難語", "なんご", "jpod", None, "media/j.opus"),
    ])
    assert audio.lookup(con, "難語", "なんご") == audio.Pick("jpod", "media/j.opus")


def test_lookup_none_when_absent(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [])
    assert audio.lookup(con, "存在しない", "x") is None


def test_lookup_strips_html_and_normalizes_katakana(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("食べる", "たべる", "nhk16", None, "audio/n.opus"),
    ])
    # Term wrapped in markup, Reading in katakana
    assert audio.lookup(con, "<b>食べる</b>", "タベル") == audio.Pick("nhk16", "audio/n.opus")


def test_empty_reading_skips_dicts_uses_forvo(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("謎", "なぞ", "nhk16", None, "audio/n.opus"),   # would match only with reading
        ("謎", None, "forvo", "skent", "skent/n.opus"),
    ])
    assert audio.lookup(con, "謎", "") == audio.Pick("forvo", "skent/n.opus", "skent")


# --- collection-level pass ------------------------------------------------------

@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "test.anki2"))
    _new_notetype(c, config.NOTETYPE, _KINOMOTO_FIELDS)
    kino = c.models.by_name(config.NOTETYPE)
    c.models.set_current(kino)
    mined = c.decks.id("言葉文章")

    def add(term, reading, audio_val):
        n = c.new_note(kino)
        n[config.TERM] = term
        n[config.READING] = reading
        n[config.TERM_AUDIO] = audio_val
        c.add_note(n, mined)
        return n.id

    ids = {
        "empty_nhk": add("橋", "はし", ""),       # -> nhk16
        "empty_forvo": add("筋トレ", "きんとれ", ""),  # -> forvo
        "empty_none": add("造語", "ぞうご", ""),    # -> nothing
        "filled": add("猫", "ねこ", "[sound:cat.mp3]"),  # skip (has audio)
    }
    yield c, ids
    c.close()


@pytest.fixture
def laudio(tmp_path):
    rows = [
        ("橋", "はし", "nhk16", None, "audio/n.opus"),
        ("筋トレ", None, "forvo", "strawberrybrown", "strawberrybrown/b.opus"),
    ]
    clips = [
        ("nhk16", "audio/n.opus", b"OggS-nhk"),
        ("forvo", "strawberrybrown/b.opus", b"OggS-forvo"),
    ]
    return _audio_db(str(tmp_path / "a.sqlite"), rows, clips)


def test_select_targets_only_empty(col, laudio):
    c, ids = col
    targets = set(audio.select_targets(c))
    assert ids["empty_nhk"] in targets
    assert ids["empty_forvo"] in targets
    assert ids["empty_none"] in targets
    assert ids["filled"] not in targets


def test_plan_backfill_classifies(col, laudio):
    c, ids = col
    plan = audio.plan_backfill(c, laudio)
    assert plan["by_source"] == {"nhk16": 1, "forvo": 1}
    assert plan["filled"] == 2
    assert plan["nothing"] == 1
    assert plan["missing_file"] == 0


def test_run_backfill_writes_sound_and_copies_media(col, laudio):
    c, ids = col
    res = audio.run_backfill(c, laudio)
    assert res["filled"] == {"nhk16": 1, "forvo": 1}
    n = c.get_note(ids["empty_nhk"])
    assert n[config.TERM_AUDIO].startswith("[sound:") and n[config.TERM_AUDIO].endswith(".opus]")
    fname = _re.search(r"\[sound:([^\]]+)\]", n[config.TERM_AUDIO]).group(1)
    assert c.media.have(fname)
    # the bytes written into media are the nhk16 clip blob, not some other clip
    with open(os.path.join(c.media.dir(), fname), "rb") as fh:
        assert fh.read() == b"OggS-nhk"
    assert c.get_note(ids["empty_none"])[config.TERM_AUDIO] == ""
    assert c.get_note(ids["filled"])[config.TERM_AUDIO] == "[sound:cat.mp3]"


def test_run_backfill_is_rerun_safe(col, laudio):
    c, ids = col
    audio.run_backfill(c, laudio)
    assert audio.run_backfill(c, laudio)["filled"] == {}


def test_run_backfill_one_bad_card_does_not_strand_batch(col, laudio, monkeypatch):
    c, ids = col
    real_write = c.media.write_data
    calls = {"n": 0}

    def flaky_write(name, data):
        calls["n"] += 1
        if calls["n"] == 1:
            raise RuntimeError("simulated media failure")
        return real_write(name, data)

    monkeypatch.setattr(c.media, "write_data", flaky_write)
    res = audio.run_backfill(c, laudio)
    assert len(res["errors"]) == 1
    assert sum(res["filled"].values()) == 1


# --- re-pitch (replace mined audio with dictionary) -----------------------------

def test_lookup_dict_only_returns_dictionaries(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("橋", "はし", "shinmeikai8", None, "media/s.opus"),
        ("筋トレ", None, "forvo", "strawberrybrown", "x.opus"),
        ("難語", "なんご", "jpod", None, "media/j.opus"),
    ])
    assert audio.lookup_dict(con, "橋", "はし") == audio.Pick("shinmeikai8", "media/s.opus")
    assert audio.lookup_dict(con, "筋トレ", "きんとれ") is None   # forvo not a dict
    assert audio.lookup_dict(con, "難語", "なんご") is None       # jpod not a dict
    assert audio.lookup_dict(con, "橋", "") is None              # needs a reading


@pytest.fixture
def repitch_col(tmp_path):
    c = Collection(str(tmp_path / "rp.anki2"))
    _new_notetype(c, config.NOTETYPE, _KINOMOTO_FIELDS)
    kino = c.models.by_name(config.NOTETYPE)
    c.models.set_current(kino)
    mined = c.decks.id("言葉文章")

    def add(term, reading, audio_val):
        n = c.new_note(kino)
        n[config.TERM] = term
        n[config.READING] = reading
        n[config.TERM_AUDIO] = audio_val
        c.add_note(n, mined)
        return n.id

    ids = {
        "mp3_dict": add("橋", "はし", "[sound:old1.mp3]"),     # replace -> nhk16
        "ogg_dict": add("食べる", "たべる", "[sound:old2.ogg]"),  # replace -> shinmeikai8
        "mp3_nodict": add("造語", "ぞうご", "[sound:old3.mp3]"),  # no dict -> keep
        "opus_already": add("猫", "ねこ", "[sound:la_nhk16_x.opus]"),  # skip (opus)
        "empty": add("空", "から", ""),                        # skip (no audio)
    }
    yield c, ids
    c.close()


@pytest.fixture
def repitch_audio(tmp_path):
    rows = [
        ("橋", "はし", "nhk16", None, "audio/h.opus"),
        ("食べる", "たべる", "shinmeikai8", None, "media/t.opus"),
        ("猫", "ねこ", "nhk16", None, "audio/c.opus"),
    ]
    clips = [
        ("nhk16", "audio/h.opus", b"OggS-h"),
        ("shinmeikai8", "media/t.opus", b"OggS-t"),
        ("nhk16", "audio/c.opus", b"OggS-c"),
    ]
    return _audio_db(str(tmp_path / "rp.sqlite"), rows, clips)


def test_select_repitch_targets_nonopus(repitch_col, repitch_audio):
    c, ids = repitch_col
    t = set(audio.select_repitch_targets(c))
    assert ids["mp3_dict"] in t and ids["ogg_dict"] in t and ids["mp3_nodict"] in t
    assert ids["opus_already"] not in t
    assert ids["empty"] not in t


def test_plan_repitch_only_dict_hits(repitch_col, repitch_audio):
    c, ids = repitch_col
    plan = audio.plan_repitch(c, repitch_audio)
    assert plan["by_source"] == {"nhk16": 1, "shinmeikai8": 1}
    assert plan["replaceable"] == 2
    assert plan["no_dict"] == 1


def test_run_repitch_replaces_and_keeps_old_media(repitch_col, repitch_audio):
    c, ids = repitch_col
    res = audio.run_repitch(c, repitch_audio)
    assert res["replaced"] == {"nhk16": 1, "shinmeikai8": 1}
    assert c.get_note(ids["mp3_dict"])[config.TERM_AUDIO].endswith(".opus]")
    assert c.get_note(ids["ogg_dict"])[config.TERM_AUDIO].endswith(".opus]")
    assert c.get_note(ids["mp3_nodict"])[config.TERM_AUDIO] == "[sound:old3.mp3]"
    assert c.get_note(ids["opus_already"])[config.TERM_AUDIO] == "[sound:la_nhk16_x.opus]"
    assert audio.run_repitch(c, repitch_audio)["replaced"] == {}
