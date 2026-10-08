import json
import sqlite3

from ankienrich import config, pack, pitch_svg
from ankienrich.jrp_fill.accent import AccentIndex


def _defs(path):
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, kind TEXT, "
                 "sense_ord INTEGER, html TEXT)")
    conn.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", [
        ("生", "なま", "sankoku8", "jj", 0, "<b>なま</b>"),
        ("生", "せい", "sankoku8", "jj", 0, "<b>せい</b>"),
        ("生", "なま", "jmdict", "je", 1, "raw"),
        ("ある", "", "daijisen", "jj", 0, "存在する"),
    ])
    conn.commit()
    conn.close()


def _examples(path):
    conn = sqlite3.connect(path)
    conn.execute("CREATE TABLE examples (term TEXT, reading TEXT, dict_id TEXT, "
                 "ord INTEGER, jp TEXT, en TEXT, plain TEXT)")
    conn.execute("INSERT INTO examples VALUES ('生','なま','tatoeba',0,'生の魚','raw fish','生の魚')")
    conn.commit()
    conn.close()


def _build(tmp_path):
    _defs(tmp_path / "defs.sqlite")
    _examples(tmp_path / "examples.sqlite")
    accent = AccentIndex({"生\tなま": [1], "ある\tある": [1]})
    src = pack.Sources(tmp_path / "defs.sqlite", tmp_path / "examples.sqlite",
                       tmp_path / "missing-freq.sqlite", accent=accent)
    try:
        manifest = pack.build(tmp_path / "out", src=src)
    finally:
        src.close()
    return manifest, sqlite3.connect(tmp_path / "out" / pack.PACK_FILE)


def test_exact_row_carries_every_field_the_passes_write(tmp_path):
    _, conn = _build(tmp_path)
    values = pack.lookup(conn, "生", "なま")
    assert values[pack.DEFINITION] == ('<div class="aa-jj" data-dict="sankoku8"><b>なま</b></div>'
                                       '<div class="aa-je">1. raw</div>')
    assert values[pack.DEFINITION_SOURCE] == "三省堂"
    assert values[pack.PITCH] == pitch_svg.render_multi("なま", [1])
    assert "生の魚" in values[pack.EXAMPLES]
    assert values[pack.TAGS] == f"{config.ENRICHED_TAG} {config.EXAMPLES_TAG}"
    assert pack.FREQUENCY not in values  # no frequency index: key left out


def test_readingless_entry_is_keyed_under_its_headword(tmp_path):
    _, conn = _build(tmp_path)
    values = pack.lookup(conn, "ある", "ある")
    assert values[pack.DEFINITION] == '<div class="aa-jj" data-dict="daijisen">存在する</div>'
    assert values[pack.PITCH] == pitch_svg.render_multi("ある", [1])
    assert values[pack.TAGS] == f"{config.ENRICHED_TAG} {config.NO_EXAMPLE_TAG}"


def test_unknown_reading_keeps_only_reading_independent_fields(tmp_path):
    _, conn = _build(tmp_path)
    values = pack.lookup(conn, "生", "いき")
    assert set(values) <= set(pack.FALLBACK_KEYS)
    assert "なま" in values[pack.DEFINITION]  # first row in chain order


def test_unknown_term_gets_defaults(tmp_path):
    _, conn = _build(tmp_path)
    assert pack.lookup(conn, "未知", "みち") == {pack.DEFINITION: "{glossary-first}"}


def test_manifest_and_version_are_stable_for_identical_content(tmp_path):
    first, conn = _build(tmp_path)
    conn.close()
    meta = dict(sqlite3.connect(tmp_path / "out" / pack.PACK_FILE).execute("SELECT key, value FROM meta"))
    assert meta["version"] == first["version"]
    assert meta["format"] == "1"
    on_disk = json.loads((tmp_path / "out" / pack.MANIFEST_FILE).read_text())
    assert on_disk == first
    assert first["entries"] == 3

    (tmp_path / "defs.sqlite").unlink()
    (tmp_path / "examples.sqlite").unlink()
    second, _ = _build(tmp_path)
    assert second["version"] == first["version"]


def test_rows_round_trip_through_the_shared_zlib_dictionary():
    zdict = pack.train_zdict([b'<div class="aa-jj">' * 20] * 10)
    blob = pack._compress(b'{"ae-definition":"<div class=\\"aa-jj\\">x</div>"}', zdict)
    assert pack.decompress(blob, zdict) == {"ae-definition": '<div class="aa-jj">x</div>'}
