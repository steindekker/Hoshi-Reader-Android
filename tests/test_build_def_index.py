import json
import sqlite3

from ankienrich.defs import build as b


def _write_dict(root, dict_id, entries, styles=None):
    d = root / dict_id
    d.mkdir(parents=True)
    (d / "index.json").write_text(json.dumps({"title": dict_id, "format": 3}), encoding="utf-8")
    (d / "term_bank_1.json").write_text(json.dumps(entries, ensure_ascii=False), encoding="utf-8")
    if styles:
        (d / "styles.css").write_text(styles, encoding="utf-8")


def test_build_indexes_jj_entries(tmp_path):
    dicts = tmp_path / "dicts"
    _write_dict(dicts, "sankoku8", [
        ["西瓜", "すいか", "", "", 0, [{"type": "structured-content", "content": "うり"}], 1, ""],
    ])
    db = tmp_path / "defs.sqlite"
    b.build(str(dicts), str(db), str(tmp_path / "dict_styles.css"))
    rows = sqlite3.connect(db).execute(
        "SELECT term, reading, dict_id, kind, sense_ord, html FROM defs").fetchall()
    assert rows == [("西瓜", "すいか", "sankoku8", "jj", 0, "うり")]


def _gloss_entry(expr, reading, defTags, score, glosses):
    sc = {"type": "structured-content", "content": {
        "tag": "ul", "data": {"content": "glossary"},
        "content": [{"tag": "li", "content": g} for g in glosses]}}
    return [expr, reading, defTags, "", score, [sc], 1, ""]


def test_build_indexes_jmdict_senses(tmp_path):
    dicts = tmp_path / "dicts"
    _write_dict(dicts, "jmdict", [
        _gloss_entry("交渉", "こうしょう", "1 n vs", 1999800, ["negotiations", "bargaining"]),
        _gloss_entry("交渉", "こうしょう", "2 n", 1999799, ["connection", "relations"]),
    ])
    db = tmp_path / "defs.sqlite"
    b.build(str(dicts), str(db), str(tmp_path / "dict_styles.css"))
    rows = sqlite3.connect(db).execute(
        "SELECT reading, sense_ord, html FROM defs WHERE kind='je' ORDER BY sense_ord").fetchall()
    assert rows == [("こうしょう", 1, "negotiations; bargaining"),
                    ("こうしょう", 2, "connection; relations")]


def test_build_scopes_dict_styles(tmp_path):
    dicts = tmp_path / "dicts"
    _write_dict(dicts, "daijisen",
                [["猫", "ねこ", "", "", 0, [{"type": "structured-content", "content": "ねこ"}], 1, ""]],
                styles='[data-sc-content="extra"] { color: red; }')
    styles_out = tmp_path / "dict_styles.css"
    b.build(str(dicts), str(tmp_path / "defs.sqlite"), str(styles_out))
    css = styles_out.read_text(encoding="utf-8")
    assert '.aa-jj[data-dict="daijisen"] [data-sc-content="extra"]' in css
    assert "color: red" in css


def test_scope_css_handles_commas_media_and_comments():
    scoped = b._scope_css(
        '/* note { brace } */\n'
        ':not(a, b) span, .x { color: red }\n'
        '@media (max-width: 600px) { .y { color: blue } }\n'
        '@import url("x.css");',
        ".S")
    # comma inside :not() is NOT a selector boundary; the top-level comma is
    assert ".S :not(a, b) span, .S .x" in scoped
    # @media body scoped recursively; the @media prelude itself is untouched
    assert "@media (max-width: 600px)" in scoped
    assert ".S .y" in scoped
    # statement at-rule passes through; comment with a stray brace doesn't break it
    assert '@import url("x.css");' in scoped
