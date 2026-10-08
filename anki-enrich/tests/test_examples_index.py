import sqlite3

from ankienrich import config
from ankienrich.examples import index


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


def test_select_skips_examples_over_max_len(tmp_path):
    db = str(tmp_path / "examples.sqlite")
    long_jp = "あ" * (config.EXAMPLE_MAX_LEN + 1)
    _make_db(db, [
        _row("猫", "ねこ", "jitendex", 0, long_jp, en="too long"),
        _row("猫", "ねこ", "tatoeba", 0, "猫がいる。"),
    ])
    conn = index.open_index(db)
    got = [e["jp"] for e in index.select(conn, "猫", "ねこ")]
    assert got == ["<jp>猫がいる。</jp>"]   # over-length jitendex row skipped
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


def test_examples_for_returns_block(tmp_path):
    from ankienrich.examples import index, enrich
    db = str(tmp_path / "examples.sqlite")
    _make_db(db, [_row("愛", "あい", "tatoeba", 0, "愛は強い")])
    conn = index.open_index(db)
    html = enrich.examples_for(conn, "愛", "あい")
    assert "愛は強い" in html
