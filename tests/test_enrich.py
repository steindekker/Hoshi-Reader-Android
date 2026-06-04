import sqlite3
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankialign import enrich, config


def _make_db(path):
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, "
                 "kind TEXT, sense_ord INTEGER, html TEXT)")
    conn.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", [
        ("迷惑", "めいわく", "sankoku8", "jj", 0, "<b>めいわく</b>"),
        ("迷惑", "めいわく", "jmdict", "je", 1, "trouble; bother"),
        ("駐車場", "ちゅうしゃじょう", "jmdict", "je", 1, "parking lot"),
    ])
    conn.commit()
    conn.close()


def test_lookup_returns_jj_and_je(tmp_path):
    db = str(tmp_path / "defs.sqlite")
    _make_db(db)
    conn = enrich.open_index(db)
    jj, je = enrich.lookup(conn, "迷惑")
    assert jj == [{"term": "迷惑", "reading": "めいわく", "dict_id": "sankoku8",
                   "kind": "jj", "sense_ord": 0, "html": "<b>めいわく</b>"}]
    assert je[0]["html"] == "trouble; bother"
    assert enrich.lookup(conn, "駐車場") == ([], [{"term": "駐車場", "reading": "ちゅうしゃじょう",
        "dict_id": "jmdict", "kind": "je", "sense_ord": 1, "html": "parking lot"}])
    conn.close()


from tests.test_convert import _new_notetype, KINOMOTO_FIELDS


@pytest.fixture
def kcol(tmp_path):
    c = Collection(str(tmp_path / "k.anki2"))
    _new_notetype(c, config.TARGET_NOTETYPE, KINOMOTO_FIELDS)
    yield c
    c.close()


def test_ensure_fields_adds_and_is_idempotent(kcol):
    enrich.ensure_fields(kcol)
    names = [f["name"] for f in kcol.models.by_name(config.TARGET_NOTETYPE)["flds"]]
    assert config.ORIGINAL_MEANING_FIELD in names
    assert config.DEFINITION_SOURCE_FIELD in names
    n_before = len(names)
    enrich.ensure_fields(kcol)  # second call: no duplicate fields
    assert len(kcol.models.by_name(config.TARGET_NOTETYPE)["flds"]) == n_before


def test_apply_dict_styles_injects_once(kcol, tmp_path):
    css = tmp_path / "dict_styles.css"
    css.write_text('.aa-jj[data-dict="daijisen"] x { color: red }', encoding="utf-8")
    enrich.apply_dict_styles(kcol, str(css))
    enrich.apply_dict_styles(kcol, str(css))
    full = kcol.models.by_name(config.TARGET_NOTETYPE)["css"]
    assert full.count("AnkiAlign dict styles") == 1   # injected exactly once
    assert "color: red" in full


def _add_kino(col, term, reading, meaning=""):
    m = col.models.by_name(config.TARGET_NOTETYPE)
    col.models.set_current(m)
    n = col.new_note(m)
    n["Term"] = term; n["Reading"] = reading; n["Meaning"] = meaning
    col.add_note(n, col.decks.id("言葉文章"))
    return n.id


def test_build_plan_classifies(kcol, tmp_path):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    _add_kino(kcol, "迷惑", "めいわく")        # has J-J
    _add_kino(kcol, "駐車場", "ちゅうしゃじょう")  # english-only
    _add_kino(kcol, "謎語", "なぞご")           # no hit
    conn = enrich.open_index(db)
    plan = enrich.build_plan(kcol, conn)
    assert plan["counts"] == {"enrich": 1, "english": 1, "nohit": 1}
    assert len(plan["nohit_nids"]) == 1
    assert plan["samples"]                       # at least one before/after sample
    conn.close()


def test_enrich_notes_writes_stashes_and_is_idempotent(kcol, tmp_path):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    enrich.ensure_fields(kcol)
    nid = _add_kino(kcol, "迷惑", "めいわく", meaning="OLD CLUTTER")
    conn = enrich.open_index(db)

    res = enrich.enrich_notes(kcol, conn)
    n = kcol.get_note(nid)
    from ankialign import render_defs as rd
    assert n["Meaning"] == (
        f'<div class="aa-jj" data-dict="sankoku8" style="{rd._JJ_STYLE}"><b>めいわく</b></div>'
        f'<div class="aa-je" style="{rd._JE_STYLE}">1. trouble; bother</div>')
    assert n[config.ORIGINAL_MEANING_FIELD] == "OLD CLUTTER"
    assert n[config.DEFINITION_SOURCE_FIELD] == "三省堂"
    assert config.ENRICHED_TAG in n.tags
    assert res["written"] == 1

    # idempotent: re-run writes nothing and does not re-stash
    res2 = enrich.enrich_notes(kcol, conn)
    assert res2["written"] == 0
    assert kcol.get_note(nid)[config.ORIGINAL_MEANING_FIELD] == "OLD CLUTTER"
    conn.close()


def test_enrich_notes_drops_few_nohit(kcol, tmp_path):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    enrich.ensure_fields(kcol)
    keep = _add_kino(kcol, "迷惑", "めいわく")
    gone = _add_kino(kcol, "謎語", "なぞご")     # no hit, < threshold -> dropped
    conn = enrich.open_index(db)
    res = enrich.enrich_notes(kcol, conn)
    assert res["dropped"] == 1
    assert kcol.find_notes("nid:%d" % gone) == []
    assert kcol.find_notes("nid:%d" % keep) != []
    conn.close()


def test_enrich_notes_applies_both_css_layers(kcol, tmp_path):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    styles = tmp_path / "dict_styles.css"
    styles.write_text('.aa-jj[data-dict="daijisen"] x { color: red }', encoding="utf-8")
    enrich.ensure_fields(kcol)
    _add_kino(kcol, "迷惑", "めいわく")
    conn = enrich.open_index(db)
    enrich.enrich_notes(kcol, conn, styles_path=str(styles))
    css = kcol.models.by_name(config.TARGET_NOTETYPE)["css"]
    assert ".aa-jj" in css            # layer 2 (kinomoto.css via template.apply)
    assert "color: red" in css        # layer 1 (scoped dict styles, appended after)
    conn.close()


def test_enrich_notes_tags_when_nohit_at_or_above_threshold(kcol, tmp_path, monkeypatch):
    db = str(tmp_path / "defs.sqlite"); _make_db(db)
    enrich.ensure_fields(kcol)
    monkeypatch.setattr(config, "NO_HIT_DROP_THRESHOLD", 1)  # count(1) not < 1 -> tag, don't drop
    nid = _add_kino(kcol, "謎語", "なぞご")
    conn = enrich.open_index(db)
    res = enrich.enrich_notes(kcol, conn)
    assert res["dropped"] == 0 and res["nohit_tagged"] == 1
    n = kcol.get_note(nid)
    assert config.NO_DEF_TAG in n.tags
    assert kcol.find_notes("nid:%d" % nid) != []   # preserved, not dropped
    conn.close()
