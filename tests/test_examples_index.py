import sqlite3
import pytest
anki = pytest.importorskip("anki")
from anki.collection import Collection
from ankienrich import config
from ankienrich.examples import index, enrich


KINOMOTO_FIELDS = [
    config.TERM, config.READING, config.TERM_FURIGANA, config.SENTENCE,
    config.SENTENCE_FURIGANA, config.CLOZE_BEFORE, config.CLOZE_INSIDE,
    config.CLOZE_AFTER, config.DEFINITION, "Expanded Meaning", "Collapsed Meaning",
    "Notes", "Context", "Frequency", config.PITCH, "Image",
    config.TERM_AUDIO, "Sentence Audio",
]


def _make_db(path, rows):
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE examples (term TEXT, reading TEXT, dict_id TEXT, "
                 "ord INTEGER, jp TEXT, en TEXT, plain TEXT)")
    conn.executemany("INSERT INTO examples VALUES (?,?,?,?,?,?,?)", rows)
    conn.commit()
    conn.close()


def _row(term, reading, dict_id, ord, plain, en=""):
    return (term, reading, dict_id, ord, f"<jp>{plain}</jp>", en, plain)


def test_select_priority_order_and_cap(tmp_path):
    db = str(tmp_path / "examples.sqlite")
    _make_db(db, [
        _row("愛", "あい", "saito", 0, "S1"),
        _row("愛", "あい", "jitendex", 0, "J1", en="love"),
        _row("愛", "あい", "tatoeba", 0, "T1"),
        _row("愛", "あい", "tatoeba", 1, "T2"),
        _row("愛", "あい", "yourei", 0, "Y1"),
    ])
    conn = index.open_index(db)
    got = [(e["source"], e["jp"]) for e in index.select(conn, "愛", "あい")]
    # jitendex first, then tatoeba (fills the thin word), capped at 3 -> saito/yourei excluded
    assert got == [("jitendex", "<jp>J1</jp>"), ("tatoeba", "<jp>T1</jp>"), ("tatoeba", "<jp>T2</jp>")]
    conn.close()


def test_select_dedupes_across_sources(tmp_path):
    db = str(tmp_path / "examples.sqlite")
    # same sentence in jitendex (with furigana) and tatoeba -> shown once
    _make_db(db, [
        ("反響", "はんきょう", "jitendex", 0, "<jp>こだま。</jp>", "echo", "こだま。"),
        ("反響", "はんきょう", "tatoeba", 0, "<jp>こだま。</jp>", "echo", "こだま。"),
        ("反響", "はんきょう", "tatoeba", 1, "<jp>別の文。</jp>", "other", "別の文。"),
    ])
    conn = index.open_index(db)
    got = [e["jp"] for e in index.select(conn, "反響", "はんきょう")]
    assert got == ["<jp>こだま。</jp>", "<jp>別の文。</jp>"]   # tatoeba dup dropped, leads with jitendex
    conn.close()


def test_select_prefers_reading_match(tmp_path):
    db = str(tmp_path / "examples.sqlite")
    _make_db(db, [
        _row("端", "はし", "tatoeba", 0, "EDGE"),
        _row("端", "はじ", "tatoeba", 1, "WRONG"),
    ])
    conn = index.open_index(db)
    got = [e["jp"] for e in index.select(conn, "端", "はし")]
    assert got == ["<jp>EDGE</jp>"]
    conn.close()


@pytest.fixture
def kcol(tmp_path):
    c = Collection(str(tmp_path / "k.anki2"))
    m = c.models.new(config.NOTETYPE)
    for fname in KINOMOTO_FIELDS:
        c.models.add_field(m, c.models.new_field(fname))
    t = c.models.new_template("Card 1")
    t["qfmt"] = "{{%s}}" % config.TERM
    t["afmt"] = "{{FrontSide}}"
    c.models.add_template(m, t)
    c.models.add(m)
    yield c
    c.close()


def _add_kino(col, term, reading):
    m = col.models.by_name(config.NOTETYPE)
    col.models.set_current(m)
    n = col.new_note(m)
    n[config.TERM] = term
    n[config.READING] = reading
    col.add_note(n, col.decks.id("言葉文章"))
    return n.id


def test_ensure_field_adds_examples_and_is_idempotent(kcol):
    enrich.ensure_field(kcol)
    names = [f["name"] for f in kcol.models.by_name(config.NOTETYPE)["flds"]]
    assert config.EXAMPLES in names
    n_before = len(names)
    enrich.ensure_field(kcol)
    assert len(kcol.models.by_name(config.NOTETYPE)["flds"]) == n_before


def test_enrich_fills_tags_and_is_idempotent(tmp_path, kcol):
    db = str(tmp_path / "examples.sqlite")
    _make_db(db, [_row("愛", "あい", "jitendex", 0, "J1", en="love")])
    enrich.ensure_field(kcol)
    nid = _add_kino(kcol, "愛", "あい")
    miss = _add_kino(kcol, "謎語", "なぞご")     # no example anywhere
    conn = index.open_index(db)

    res = enrich.enrich_notes(kcol, conn)
    assert res["written"] == 1 and res["none_tagged"] == 1
    n = kcol.get_note(nid)
    assert "J1" in n[config.EXAMPLES] and config.EXAMPLES_TAG in n.tags
    assert config.NO_EXAMPLE_TAG in kcol.get_note(miss).tags

    # idempotent re-run writes nothing
    assert enrich.enrich_notes(kcol, conn)["written"] == 0
    conn.close()


def test_overwrite_reasserts_changed_block(tmp_path, kcol):
    db = str(tmp_path / "examples.sqlite")
    _make_db(db, [_row("愛", "あい", "jitendex", 0, "J1")])
    enrich.ensure_field(kcol)
    nid = _add_kino(kcol, "愛", "あい")
    conn = index.open_index(db)
    enrich.enrich_notes(kcol, conn)

    # hand-edit, then default run preserves it
    n = kcol.get_note(nid); n[config.EXAMPLES] = "MINE"; kcol.update_note(n)
    assert enrich.enrich_notes(kcol, conn)["written"] == 0
    assert kcol.get_note(nid)[config.EXAMPLES] == "MINE"
    assert enrich.build_plan(kcol, conn)["to_write"] == 0
    assert enrich.build_plan(kcol, conn, overwrite=True)["to_write"] == 1

    # overwrite clobbers back to the generated block
    assert enrich.enrich_notes(kcol, conn, overwrite=True)["written"] == 1
    assert "J1" in kcol.get_note(nid)[config.EXAMPLES]
    conn.close()
