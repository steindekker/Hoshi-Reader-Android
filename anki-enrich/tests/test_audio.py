import os
import re as _re
import sqlite3

import pytest

from ankienrich import audio

anki = pytest.importorskip("anki")
from anki.collection import Collection

from ankienrich import config

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
    clips: (source, file, data). Mirrors the android.db store schema
    (entries has extra id/display; clip bytes live in the `android` table)."""
    con = sqlite3.connect(path)
    con.execute("CREATE TABLE entries (id integer primary key, expression text, "
                "reading text, source text, speaker text, display text, file text)")
    con.execute("CREATE TABLE android (id integer primary key, file text, "
                "source text, data blob)")
    con.executemany(
        "INSERT INTO entries (expression, reading, source, speaker, file) VALUES (?,?,?,?,?)",
        rows)
    con.executemany("INSERT INTO android (file, source, data) VALUES (?,?,?)",
                    [(f, s, d) for (s, f, d) in clips])
    con.commit()
    return con


# --- lookup ranking (Hoshi port) -----------------------------------------------

def test_lookup_prefers_nhk16_by_priority(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("橋", "はし", "nhk16", None, "audio/n.mp3"),
        ("橋", "はし", "daijisen", None, "audio/d.mp3"),
        ("橋", "はし", "shinmeikai8", None, "media/s.mp3"),
        ("橋", "はし", "jpod", None, "media/j.mp3"),
    ])
    assert audio.lookup(con, "橋", "はし") == audio.Pick("nhk16", "audio/n.mp3")


def test_daijisen_beats_shinmeikai(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("橋", "はし", "daijisen", None, "audio/d.mp3"),
        ("橋", "はし", "shinmeikai8", None, "media/s.mp3"),
    ])
    assert audio.lookup(con, "橋", "はし") == audio.Pick("daijisen", "audio/d.mp3")


def test_reading_match_ranked_first_within_source(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("角", "かど", "nhk16", None, "audio/kado.mp3"),
        ("角", "つの", "nhk16", None, "audio/tsuno.mp3"),
    ])
    assert audio.lookup(con, "角", "つの") == audio.Pick("nhk16", "audio/tsuno.mp3")


def test_reading_match_beats_higher_priority_expression_only(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("辛い", "からい", "nhk16", None, "audio/karai.mp3"),
        ("辛い", "つらい", "jpod", None, "media/tsurai.mp3"),
    ])
    assert audio.lookup(con, "辛い", "つらい") == audio.Pick("jpod", "media/tsurai.mp3")


def test_exact_expression_breaks_homograph_tie(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("箸", "はし", "nhk16", None, "audio/chopsticks.mp3"),
        ("橋", "はし", "nhk16", None, "audio/bridge.mp3"),
    ])
    assert audio.lookup(con, "橋", "はし") == audio.Pick("nhk16", "audio/bridge.mp3")


def test_unsupported_extension_filtered(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("画像", "がぞう", "nhk16", None, "audio/x.aac"),
        ("画像", "がぞう", "jpod", None, "media/j.mp3"),
    ])
    assert audio.lookup(con, "画像", "がぞう") == audio.Pick("jpod", "media/j.mp3")


def test_lookup_none_when_absent(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [])
    assert audio.lookup(con, "存在しない", "x") is None


def test_lookup_strips_html_and_normalizes_katakana(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("食べる", "たべる", "nhk16", None, "audio/n.mp3"),
    ])
    assert audio.lookup(con, "<b>食べる</b>", "タベル") == audio.Pick("nhk16", "audio/n.mp3")


def test_blank_reading_uses_expression_only_by_priority(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"), [
        ("謎", "なぞ", "nhk16", None, "audio/n.mp3"),
        ("謎", None, "forvo", "skent", "skent/n.mp3"),
    ])
    assert audio.lookup(con, "謎", "") == audio.Pick("nhk16", "audio/n.mp3")


# --- read_clip (android table) --------------------------------------------------

def test_read_clip_from_android_table(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"),
                    [("橋", "はし", "nhk16", None, "audio/n.mp3")],
                    clips=[("nhk16", "audio/n.mp3", b"MP3-nhk")])
    assert audio.read_clip(con, audio.Pick("nhk16", "audio/n.mp3")) == b"MP3-nhk"


def test_read_clip_none_when_blob_absent(tmp_path):
    con = _audio_db(str(tmp_path / "a.db"),
                    [("橋", "はし", "nhk16", None, "audio/n.mp3")])
    assert audio.read_clip(con, audio.Pick("nhk16", "audio/n.mp3")) is None


def test_media_name_is_public():
    from ankienrich.audio import enrich
    pick = enrich.Pick("nhk16", "audio/123.mp3")
    assert enrich.media_name(pick) == "la_nhk16_123.mp3"


# --- collection-level backfill --------------------------------------------------

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
        "empty_nhk": add("橋", "はし", ""),
        "empty_forvo": add("筋トレ", "きんとれ", ""),
        "empty_none": add("造語", "ぞうご", ""),
        "filled": add("猫", "ねこ", "[sound:cat.mp3]"),
    }
    yield c, ids
    c.close()


@pytest.fixture
def laudio(tmp_path):
    rows = [
        ("橋", "はし", "nhk16", None, "audio/n.mp3"),
        ("筋トレ", None, "forvo", "strawberrybrown", "sb/b.mp3"),
    ]
    clips = [
        ("nhk16", "audio/n.mp3", b"MP3-nhk"),
        ("forvo", "sb/b.mp3", b"MP3-forvo"),
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
    assert n[config.TERM_AUDIO].startswith("[sound:") and n[config.TERM_AUDIO].endswith(".mp3]")
    fname = _re.search(r"\[sound:([^\]]+)\]", n[config.TERM_AUDIO]).group(1)
    assert c.media.have(fname)
    with open(os.path.join(c.media.dir(), fname), "rb") as fh:
        assert fh.read() == b"MP3-nhk"
    assert c.get_note(ids["empty_none"])[config.TERM_AUDIO] == ""
    assert c.get_note(ids["filled"])[config.TERM_AUDIO] == "[sound:cat.mp3]"


def test_run_backfill_is_rerun_safe(col, laudio):
    c, ids = col
    audio.run_backfill(c, laudio)
    assert audio.run_backfill(c, laudio)["filled"] == {}


# --- overwrite: store audio supersedes mined clips ---------------------------------

def test_select_targets_overwrite_takes_every_note(col, laudio):
    c, ids = col
    assert set(audio.select_targets(c, overwrite=True)) == set(ids.values())


def test_overwrite_replaces_existing_mined_audio(col, laudio):
    """The whole point: a card mined with its own clip gets the store recording."""
    c, ids = col
    n = c.get_note(ids["filled"])
    n[config.TERM] = "橋"; n[config.READING] = "はし"
    c.update_note(n)

    res = audio.run_backfill(c, laudio, overwrite=True)

    value = c.get_note(ids["filled"])[config.TERM_AUDIO]
    assert value != "[sound:cat.mp3]"
    fname = _re.search(r"\[sound:([^\]]+)\]", value).group(1)
    with open(os.path.join(c.media.dir(), fname), "rb") as fh:
        assert fh.read() == b"MP3-nhk"
    assert res["filled"]["nhk16"] >= 1


def test_overwrite_collapses_a_multi_clip_field_to_one(col, laudio):
    c, ids = col
    n = c.get_note(ids["filled"])
    n[config.TERM] = "橋"; n[config.READING] = "はし"
    n[config.TERM_AUDIO] = "[sound:a.mp3][sound:b.mp3][sound:c.mp3]"
    c.update_note(n)

    audio.run_backfill(c, laudio, overwrite=True)

    value = c.get_note(ids["filled"])[config.TERM_AUDIO]
    assert len(_re.findall(r"\[sound:", value)) == 1


def test_overwrite_keeps_audio_the_store_cannot_serve(col, laudio):
    """No store hit means no clip to swap in — blanking would only lose the
    card its sound."""
    c, ids = col
    n = c.get_note(ids["filled"])          # 猫/ねこ is not in the fixture store
    assert audio.lookup(laudio, "猫", "ねこ") is None
    audio.run_backfill(c, laudio, overwrite=True)
    assert c.get_note(ids["filled"])[config.TERM_AUDIO] == "[sound:cat.mp3]"


@pytest.fixture
def levelling(monkeypatch):
    """Pretend ffmpeg is present and levels every clip, so the naming rules can
    be tested without depending on a real encoder."""
    from ankienrich.audio import loudness
    monkeypatch.setattr(loudness, "available", lambda: True)
    monkeypatch.setattr(loudness, "normalize_clip",
                        lambda data, ext: (data + b"-levelled", 3.0))


def test_store_clip_is_levelled_on_the_way_in_as_one_file(col, laudio, levelling):
    """No second file, no repointing: the clip is levelled while we still hold
    its bytes and written straight under its levelled name."""
    c, ids = col
    audio.run_backfill(c, laudio)
    value = c.get_note(ids["empty_nhk"])[config.TERM_AUDIO]
    fname = _re.search(r"\[sound:([^\]]+)\]", value).group(1)
    assert fname == f"{config.AUDIO_NORM_PREFIX}la_nhk16_n.mp3"
    with open(os.path.join(c.media.dir(), fname), "rb") as fh:
        assert fh.read() == b"MP3-nhk-levelled"
    # the unlevelled name was never created
    assert not c.media.have("la_nhk16_n.mp3")


def test_overwrite_leaves_its_own_levelled_clip_alone(col, laudio, levelling):
    c, ids = col
    audio.run_backfill(c, laudio, overwrite=True)
    before = c.get_note(ids["empty_nhk"])[config.TERM_AUDIO]

    res = audio.run_backfill(c, laudio, overwrite=True)

    assert res["unchanged"] >= 1
    assert c.get_note(ids["empty_nhk"])[config.TERM_AUDIO] == before


def test_overwrite_repairs_an_unlevelled_leftover(col, laudio, levelling):
    """A bare `la_…` is what an interrupted overwrite leaves behind; the next
    run must redo it rather than mistake it for finished work."""
    c, ids = col
    n = c.get_note(ids["empty_nhk"])
    n[config.TERM_AUDIO] = "[sound:la_nhk16_n.mp3]"
    c.update_note(n)

    audio.run_backfill(c, laudio, overwrite=True)

    assert c.get_note(ids["empty_nhk"])[config.TERM_AUDIO] == \
        f"[sound:{config.AUDIO_NORM_PREFIX}la_nhk16_n.mp3]"


def test_unlevellable_clip_keeps_the_bare_name(col, laudio, monkeypatch):
    """ffmpeg present but the clip unreadable: the file must not be filed under
    a name claiming it was levelled."""
    from ankienrich.audio import loudness
    monkeypatch.setattr(loudness, "available", lambda: True)
    monkeypatch.setattr(loudness, "normalize_clip", lambda data, ext: None)
    c, ids = col
    audio.run_backfill(c, laudio)
    assert c.get_note(ids["empty_nhk"])[config.TERM_AUDIO] == "[sound:la_nhk16_n.mp3]"


def test_non_overwrite_still_leaves_filled_cards_alone(col, laudio):
    c, ids = col
    audio.run_backfill(c, laudio)
    assert c.get_note(ids["filled"])[config.TERM_AUDIO] == "[sound:cat.mp3]"


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
