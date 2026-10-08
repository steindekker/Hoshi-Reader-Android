import sqlite3
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich import config
from ankienrich.defs import enrich, index


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


KINOMOTO_FIELDS = [
    config.TERM, config.READING, config.TERM_FURIGANA, config.SENTENCE,
    config.SENTENCE_FURIGANA, config.CLOZE_BEFORE, config.CLOZE_INSIDE,
    config.CLOZE_AFTER, config.DEFINITION, "Expanded Meaning", "Collapsed Meaning",
    "Notes", "Context", "Frequency", config.PITCH, "Image",
    config.TERM_AUDIO, "Sentence Audio",
]


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
    conn = index.open_index(db)
    jj, je = index.lookup(conn, "迷惑")
    assert jj == [{"term": "迷惑", "reading": "めいわく", "dict_id": "sankoku8",
                   "kind": "jj", "sense_ord": 0, "html": "<b>めいわく</b>"}]
    assert je[0]["html"] == "trouble; bother"
    assert index.lookup(conn, "駐車場") == ([], [{"term": "駐車場", "reading": "ちゅうしゃじょう",
        "dict_id": "jmdict", "kind": "je", "sense_ord": 1, "html": "parking lot"}])
    conn.close()


@pytest.fixture
def kcol(tmp_path):
    c = Collection(str(tmp_path / "k.anki2"))
    _new_notetype(c, config.NOTETYPE, KINOMOTO_FIELDS)
    yield c
    c.close()


def test_apply_dict_styles_injects_once(kcol, tmp_path):
    css = tmp_path / "dict_styles.css"
    css.write_text('.aa-jj[data-dict="daijisen"] x { color: red }', encoding="utf-8")
    enrich.apply_dict_styles(kcol, str(css))
    enrich.apply_dict_styles(kcol, str(css))
    full = kcol.models.by_name(config.NOTETYPE)["css"]
    assert full.count("AnkiAlign dict styles") == 1   # injected exactly once
    assert "color: red" in full


def test_definition_for_returns_meaning_source_kind(tmp_path):
    from ankienrich.defs import index, enrich
    db = str(tmp_path / "defs.sqlite")
    conn = sqlite3.connect(db); conn.row_factory = sqlite3.Row
    conn.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, "
                 "kind TEXT, sense_ord INTEGER, html TEXT)")
    conn.execute("INSERT INTO defs VALUES ('愛','あい','jmdict','je',0,'love')")
    conn.commit()
    meaning, source, kind = enrich.definition_for(conn, "愛", "あい")
    assert "love" in meaning
    assert kind == "english"
