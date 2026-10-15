import sqlite3

import pytest

anki = pytest.importorskip("anki")
from anki.collection import Collection

from ankienrich import config, fill, pack, pitch_svg
from ankienrich.jrp_fill.accent import AccentIndex

FIELDS = [
    config.TERM, config.READING, config.SENTENCE, config.DEFINITION, config.EXPANDED_MEANING,
    config.PITCH, config.FREQUENCY, config.EXAMPLES, config.ORIGINAL_MEANING,
    config.DEFINITION_SOURCE,
]


def _sources(tmp_path):
    defs = sqlite3.connect(tmp_path / "defs.sqlite")
    defs.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, kind TEXT, "
                 "sense_ord INTEGER, html TEXT)")
    defs.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", [
        ("迷惑", "めいわく", "sankoku8", "jj", 0, "<b>めいわく</b>"),
        ("迷惑", "めいわく", "jmdict", "je", 1, "trouble; bother"),
        ("駐車場", "ちゅうしゃじょう", "jmdict", "je", 1, "parking lot"),
        ("片仮", "かたかな", "jmdict", "je", 1, "katakana"),
    ])
    defs.commit()
    defs.close()
    ex = sqlite3.connect(tmp_path / "examples.sqlite")
    ex.execute("CREATE TABLE examples (term TEXT, reading TEXT, dict_id TEXT, "
               "ord INTEGER, jp TEXT, en TEXT, plain TEXT)")
    ex.execute("INSERT INTO examples VALUES ('迷惑','めいわく','tatoeba',0,'<jp>迷惑だ</jp>','','迷惑だ')")
    ex.commit()
    ex.close()
    freq = sqlite3.connect(tmp_path / "freq.sqlite")
    freq.execute("CREATE TABLE freq (key TEXT, reading TEXT, value INTEGER, display TEXT)")
    freq.execute("INSERT INTO freq VALUES ('迷惑','めいわく',4321,'4321')")
    freq.commit()
    freq.close()


@pytest.fixture
def packed(tmp_path):
    _sources(tmp_path)
    src = pack.Sources(tmp_path / "defs.sqlite", tmp_path / "examples.sqlite",
                       tmp_path / "freq.sqlite", accent=AccentIndex({"迷惑\tめいわく": [1]}))
    try:
        pack.build(tmp_path / "pack", src=src)
    finally:
        src.close()
    conn = sqlite3.connect(tmp_path / "pack" / pack.PACK_FILE)
    yield conn
    conn.close()


@pytest.fixture
def col(tmp_path):
    c = Collection(str(tmp_path / "c.anki2"))
    m = c.models.new(config.NOTETYPE)
    for name in FIELDS:
        c.models.add_field(m, c.models.new_field(name))
    t = c.models.new_template("Card 1")
    t["qfmt"], t["afmt"] = "{{Term}}", "{{Reading}}"
    c.models.add_template(m, t)
    c.models.add(m)
    yield c
    c.close()


def _add(col, term, reading, tags=(), **fields):
    m = col.models.by_name(config.NOTETYPE)
    n = col.new_note(m)
    n[config.TERM] = term
    n[config.READING] = reading
    for name, value in fields.items():
        n[name] = value
    for tag in tags:
        n.add_tag(tag)
    col.add_note(n, col.decks.id("Default"))
    return n.id


MEIWAKU = ('<div class="aa-jj" data-dict="sankoku8"><b>めいわく</b></div>'
           '<div class="aa-je">1. trouble; bother</div>')


def test_first_run_fills_every_field_and_stashes_the_mined_meaning(col, packed):
    nid = _add(col, "迷惑", "めいわく", Meaning="mined gloss", **{config.EXPANDED_MEANING: "x"})
    res = fill.run(col, packed, apply=True)
    n = col.get_note(nid)
    assert n[config.DEFINITION] == MEIWAKU
    assert n[config.DEFINITION_SOURCE] == "三省堂"
    assert n[config.ORIGINAL_MEANING] == "mined gloss"
    assert n[config.EXPANDED_MEANING] == ""
    assert n[config.PITCH] == pitch_svg.render_multi("めいわく", [1])
    assert n[config.FREQUENCY] == "4321"
    assert "迷惑だ" in n[config.EXAMPLES]
    assert {config.ENRICHED_TAG, config.EXAMPLES_TAG} <= set(n.tags)
    assert res["terms"] == ["迷惑"]
    assert (res["defs"], res["examples"], res["pitch"], res["frequency"]) == (1, 1, 1, 1)

    again = fill.run(col, packed, apply=True)
    assert again["notes"] == 0 and again["terms"] == []
    assert col.get_note(nid)[config.ORIGINAL_MEANING] == "mined gloss"


def test_a_card_the_app_filled_from_the_pack_is_already_settled(col, packed):
    values = pack.lookup(packed, "迷惑", "めいわく")
    _add(col, "迷惑", "めいわく", tags=values[pack.TAGS].split(), **{
        config.DEFINITION: values[pack.DEFINITION],
        config.DEFINITION_SOURCE: values[pack.DEFINITION_SOURCE],
        config.PITCH: values[pack.PITCH],
        config.FREQUENCY: values[pack.FREQUENCY],
        config.EXAMPLES: values[pack.EXAMPLES],
    })
    assert fill.run(col, packed, apply=True)["notes"] == 0
    assert fill.run(col, packed, apply=True, overwrite=True)["notes"] == 0


def test_hand_edits_are_kept_until_overwrite(col, packed):
    nid = _add(col, "迷惑", "めいわく", Meaning="OLD")
    fill.run(col, packed, apply=True)
    n = col.get_note(nid)
    n[config.DEFINITION] = "MY EDIT"
    n[config.EXAMPLES] = "MINE"
    col.update_note(n)

    assert fill.run(col, packed, apply=True)["notes"] == 0
    assert col.get_note(nid)[config.DEFINITION] == "MY EDIT"

    plan = fill.run(col, packed, apply=False, overwrite=True)
    assert (plan["defs"], plan["examples"]) == (1, 1)
    assert col.get_note(nid)[config.DEFINITION] == "MY EDIT"  # dry run wrote nothing

    fill.run(col, packed, apply=True, overwrite=True)
    n = col.get_note(nid)
    assert n[config.DEFINITION] == MEIWAKU
    assert "迷惑だ" in n[config.EXAMPLES]
    assert n[config.ORIGINAL_MEANING] == "OLD"  # stashed once, never re-stashed


def test_pitch_and_frequency_are_always_reasserted(col, packed):
    stale = _add(col, "迷惑", "めいわく", tags=[config.ENRICHED_TAG, config.EXAMPLES_TAG],
                 **{config.PITCH: "<svg>STALE</svg>", config.FREQUENCY: "1"})
    junk = _add(col, "片仮", "かたかな", **{config.PITCH: "No pitch accent data"})
    ours = _add(col, "駐車場", "ちゅうしゃじょう", **{config.PITCH: "<svg>kept</svg>"})
    res = fill.run(col, packed, apply=True)
    assert col.get_note(stale)[config.PITCH] == pitch_svg.render_multi("めいわく", [1])
    assert col.get_note(stale)[config.FREQUENCY] == "4321"
    assert col.get_note(junk)[config.PITCH] == ""
    assert col.get_note(ours)[config.PITCH] == "<svg>kept</svg>"
    assert (res["pitch"], res["pitch_cleared"], res["frequency"]) == (1, 1, 1)


def test_word_without_examples_is_tagged_and_rechecked(col, packed):
    nid = _add(col, "駐車場", "ちゅうしゃじょう")
    res = fill.run(col, packed, apply=True)
    assert config.NO_EXAMPLE_TAG in col.get_note(nid).tags
    assert res["no_example"] == 1
    assert fill.run(col, packed, apply=True)["no_example"] == 0


def test_examples_replace_the_no_example_tag_once_found(col, packed):
    nid = _add(col, "迷惑", "めいわく", tags=[config.ENRICHED_TAG, config.NO_EXAMPLE_TAG])
    fill.run(col, packed, apply=True)
    n = col.get_note(nid)
    assert "迷惑だ" in n[config.EXAMPLES]
    assert config.EXAMPLES_TAG in n.tags and config.NO_EXAMPLE_TAG not in n.tags


def test_a_few_unknown_terms_are_dropped(col, packed):
    keep = _add(col, "迷惑", "めいわく")
    gone = _add(col, "謎語", "なぞご")
    res = fill.run(col, packed, apply=True)
    assert (res["nohit"], res["dropped"]) == (1, 1)
    assert col.find_notes(f"nid:{gone}") == []
    assert col.find_notes(f"nid:{keep}") != []


def test_many_unknown_terms_are_tagged_not_dropped(col, packed, monkeypatch):
    monkeypatch.setattr(config, "NO_HIT_DROP_THRESHOLD", 1)
    nid = _add(col, "謎語", "なぞご")
    res = fill.run(col, packed, apply=True)
    assert res["dropped"] == 0
    assert config.NO_DEF_TAG in col.get_note(nid).tags


def test_dry_run_counts_without_writing(col, packed):
    nid = _add(col, "迷惑", "めいわく", Meaning="mined")
    gone = _add(col, "謎語", "なぞご")
    res = fill.run(col, packed, apply=False)
    assert (res["defs"], res["dropped"], res["terms"]) == (1, 1, ["迷惑"])
    assert col.get_note(nid)[config.DEFINITION] == "mined"
    assert col.find_notes(f"nid:{gone}") != []


def test_note_ids_limit_the_pass(col, packed):
    one = _add(col, "迷惑", "めいわく")
    other = _add(col, "駐車場", "ちゅうしゃじょう")
    fill.run(col, packed, apply=True, note_ids=[one])
    assert config.ENRICHED_TAG in col.get_note(one).tags
    assert config.ENRICHED_TAG not in col.get_note(other).tags


def test_ensure_fields_adds_missing_fields_once(tmp_path):
    c = Collection(str(tmp_path / "bare.anki2"))
    try:
        m = c.models.new(config.NOTETYPE)
        for name in (config.TERM, config.READING, config.DEFINITION):
            c.models.add_field(m, c.models.new_field(name))
        t = c.models.new_template("Card 1")
        t["qfmt"], t["afmt"] = "{{Term}}", "{{Reading}}"
        c.models.add_template(m, t)
        c.models.add(m)
        fill.ensure_fields(c)
        fill.ensure_fields(c)
        names = [f["name"] for f in c.models.by_name(config.NOTETYPE)["flds"]]
        for name in (config.ORIGINAL_MEANING, config.DEFINITION_SOURCE, config.EXAMPLES,
                     config.FREQUENCY):
            assert names.count(name) == 1
    finally:
        c.close()


def test_pack_ensure_rebuilds_only_when_a_source_is_newer(tmp_path, monkeypatch):
    import os
    from ankienrich import paths
    _sources(tmp_path)
    monkeypatch.setenv("ANKI_ENRICH_VAR", str(tmp_path))
    built = []
    monkeypatch.setattr(pack, "build", lambda out_dir=None: built.append(out_dir))
    refreshed = []
    monkeypatch.setattr(pack, "refresh_sentence_model", lambda out_dir: refreshed.append(out_dir))
    assert pack.ensure() == "built"
    target = pack.pack_path()
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(b"")
    newest = max(os.path.getmtime(p) for p in (paths.DEFS_DB, paths.ACCENT_INDEX))
    os.utime(target, (newest + 10, newest + 10))
    assert pack.ensure() == "built"  # no manifest yet
    (target.parent / pack.MANIFEST_FILE).write_text("{}")
    assert pack.ensure() == "present"
    assert refreshed == [target.parent]  # a present pack still refreshes its sentence model
    os.utime(paths.DEFS_DB, (newest + 20, newest + 20))
    assert pack.ensure() == "built"
    assert len(built) == 3
