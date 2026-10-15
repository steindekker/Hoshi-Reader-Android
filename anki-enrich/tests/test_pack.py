import json
import sqlite3

from ankienrich import config, pack, pitch_svg, sentence_model
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


def _dictionary(tmp_path, content=b"fake system.dic"):
    path = tmp_path / "system.dic"
    path.write_bytes(content)
    return path, "sudachidict_full test"


def _build(tmp_path):
    _defs(tmp_path / "defs.sqlite")
    _examples(tmp_path / "examples.sqlite")
    accent = AccentIndex({"生\tなま": [1], "ある\tある": [1]})
    src = pack.Sources(tmp_path / "defs.sqlite", tmp_path / "examples.sqlite",
                       tmp_path / "missing-freq.sqlite", accent=accent)
    try:
        manifest = pack.build(tmp_path / "out", src=src, dictionary=_dictionary(tmp_path))
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
    # no frequency index in this fixture, so that key is never offered
    assert json.loads(meta["keys"]) == [pack.DEFINITION, pack.DEFINITION_SOURCE, pack.PITCH,
                                        pack.EXAMPLES, pack.TAGS]
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


# --- sentence model -----------------------------------------------------------

_ACCENT = {
    "橋\tはし": [2], "箸\tはし": [1],          # homophones that disagree -> no by_reading
    "開放感\tかいほうかん": [3], "解放感\tかいほうかん": [3],
    "その人\tそのひと": [4, 2], "其の人\tそのひと": [2, 4],  # same set, other order -> agree
    "綾\tあや": [1], "綾\tりょう": [0, 1],       # one lemma, two readings, in index order
    "生\tなま": [1],
}


def _accent_db(tmp_path, table=_ACCENT):
    acc = AccentIndex(dict(table))
    model = sentence_model.build(tmp_path / "model", acc, _dictionary(tmp_path))
    return acc, model, sqlite3.connect(tmp_path / "model" / sentence_model.ACCENT_FILE)


def _ints(text):
    return [int(p) for p in text.split(",")] if text else []


def test_accent_tables_answer_exactly_what_accent_index_answers(tmp_path):
    acc, _, conn = _accent_db(tmp_path)
    for key in _ACCENT:
        lemma, reading = key.split("\t")
        row = conn.execute("SELECT positions FROM accent WHERE lemma=? AND reading=?",
                           (lemma, reading)).fetchone()
        assert _ints(row[0]) == acc.lookup(lemma, reading)
        rows = conn.execute("SELECT reading, positions FROM accent WHERE lemma=? ORDER BY rowid",
                            (lemma,)).fetchall()
        assert [(rd, _ints(p)) for rd, p in rows] == list(acc.readings_for(lemma).items())
    for reading in {k.split("\t")[1] for k in _ACCENT} | {"みち"}:
        row = conn.execute("SELECT positions FROM by_reading WHERE reading=?", (reading,)).fetchone()
        assert (None if row is None else _ints(row[0])) == acc.by_reading(reading)
    # spot-check the semantics the app relies on
    assert acc.by_reading("はし") is None
    assert acc.by_reading("そのひと") == [4, 2]
    assert list(acc.readings_for("綾")) == ["あや", "りょう"]


def test_sentence_model_versions_track_content_only(tmp_path):
    _, first, conn = _accent_db(tmp_path)
    conn.close()
    meta = dict(sqlite3.connect(tmp_path / "model" / sentence_model.ACCENT_FILE)
                .execute("SELECT key, value FROM meta"))
    assert meta == {"format": "1", "version": first["accent"]["version"], "entries": str(len(_ACCENT))}
    assert first["format"] == sentence_model.FORMAT
    assert first["dictionary"]["file"] == sentence_model.DICTIONARY_FILE
    assert (tmp_path / "model" / sentence_model.DICTIONARY_FILE).read_bytes() == b"fake system.dic"
    assert first["dictionary"]["size"] == len(b"fake system.dic")
    assert first["dictionary"]["sha256"] == sentence_model.file_sha256(tmp_path / "system.dic")

    _, same, _ = _accent_db(tmp_path)
    assert same == first

    changed = sentence_model.build(tmp_path / "model", AccentIndex({**_ACCENT, "生\tなま": [0]}),
                                   _dictionary(tmp_path))
    assert changed["accent"]["version"] != first["accent"]["version"]
    assert changed["dictionary"] == first["dictionary"]

    redict = sentence_model.build(tmp_path / "model", AccentIndex(dict(_ACCENT)),
                                  _dictionary(tmp_path, b"new dic"))
    assert redict["accent"] == first["accent"]
    assert redict["dictionary"]["version"] != first["dictionary"]["version"]


def test_manifest_lists_the_sentence_model_beside_the_format_1_pack(tmp_path):
    manifest, _ = _build(tmp_path)
    out = tmp_path / "out"
    assert {"format", "version", "file", "size", "sha256", "entries"} <= set(manifest)
    assert manifest["format"] == 1
    sentence = manifest["sentence"]
    for part in ("accent", "dictionary"):
        f = out / sentence[part]["file"]
        assert f.stat().st_size == sentence[part]["size"]
        assert sentence_model.file_sha256(f) == sentence[part]["sha256"]
    assert json.loads((out / pack.MANIFEST_FILE).read_text()) == manifest


def test_refresh_relists_the_sentence_model_without_touching_the_pack(tmp_path):
    manifest, _ = _build(tmp_path)
    out = tmp_path / "out"
    refreshed = pack.refresh_sentence_model(out, AccentIndex({"生\tせい": [1]}),
                                            _dictionary(tmp_path))
    assert {k: v for k, v in refreshed.items() if k != "sentence"} == \
        {k: v for k, v in manifest.items() if k != "sentence"}
    assert refreshed["sentence"]["accent"]["version"] != manifest["sentence"]["accent"]["version"]
    assert json.loads((out / pack.MANIFEST_FILE).read_text()) == refreshed


def test_no_sudachi_dictionary_leaves_the_sentence_model_out(tmp_path, monkeypatch):
    monkeypatch.setattr(sentence_model, "dictionary_source", lambda: None)
    assert sentence_model.build(tmp_path / "model", AccentIndex(dict(_ACCENT))) is None
    assert not (tmp_path / "model").exists()
