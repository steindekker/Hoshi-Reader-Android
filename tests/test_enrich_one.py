import sqlite3
import pytest
from ankienrich import enrich_one


def _defs_db(tmp_path, rows):
    db = str(tmp_path / "defs.sqlite")
    conn = sqlite3.connect(db); conn.row_factory = sqlite3.Row
    conn.execute("CREATE TABLE defs (term TEXT, reading TEXT, dict_id TEXT, "
                 "kind TEXT, sense_ord INTEGER, html TEXT)")
    conn.executemany("INSERT INTO defs VALUES (?,?,?,?,?,?)", rows)
    conn.commit()
    return conn


def test_term_furigana_kanji():
    assert enrich_one.term_furigana("部活", "ぶかつ") == "部活[ぶかつ]"

def test_term_furigana_kana_is_plain():
    assert enrich_one.term_furigana("みきき", "みきき") == "みきき"

def test_candidates_single_reading_with_gloss(tmp_path):
    conn = _defs_db(tmp_path, [("箸","はし","jmdict","je",0,"chopsticks")])
    cands = enrich_one.candidates(conn, "箸")
    assert [c.reading for c in cands] == ["はし"]
    assert "chopsticks" in cands[0].gloss

def test_candidates_multiple_readings(tmp_path):
    conn = _defs_db(tmp_path, [
        ("角","かど","jmdict","je",0,"corner"),
        ("角","つの","jmdict","je",0,"horn"),
    ])
    cands = enrich_one.candidates(conn, "角")
    assert {c.reading for c in cands} == {"かど", "つの"}

def test_candidates_empty_when_no_hits(tmp_path):
    conn = _defs_db(tmp_path, [])
    assert enrich_one.candidates(conn, "存在しない") == []

def test_candidates_strips_html_from_jj_gloss(tmp_path):
    conn = _defs_db(tmp_path, [("角","かど","sankoku8","jj",0,"<b>角</b>のこと")])
    cands = enrich_one.candidates(conn, "角")
    assert cands[0].gloss == "角のこと"


def test_build_fields_assembles_everything(tmp_path, monkeypatch):
    conn = _defs_db(tmp_path, [("箸", "はし", "jmdict", "je", 0, "chopsticks")])

    class _Acc:  # stand-in AccentIndex with the .lookup(lemma, reading) interface
        def lookup(self, lemma, reading):
            return [0] if reading == "はし" else None

    from ankienrich.audio.enrich import Pick
    monkeypatch.setattr(enrich_one.audio_enrich, "lookup",
                        lambda c, t, r: Pick("nhk16", "1.opus"))
    monkeypatch.setattr(enrich_one.audio_enrich, "read_clip",
                        lambda c, p: b"OPUSDATA")
    monkeypatch.setattr(enrich_one.ex_enrich, "examples_for",
                        lambda c, t, r: "<div>例</div>")

    f = enrich_one.build_fields(
        "箸", "はし",
        defs_conn=conn, ex_conn=object(), audio_conn=object(), acc=_Acc())

    assert f.term == "箸" and f.reading == "はし"
    assert f.furigana == "箸[はし]"
    assert f.pitch_svg.startswith("<svg")
    assert "chopsticks" in f.meaning
    assert f.definition_source  # non-empty label
    assert f.examples == "<div>例</div>"
    assert f.audio_clip == b"OPUSDATA"
    assert f.audio_pick.source == "nhk16"
    assert f.has_definition is True


def test_build_fields_no_audio(tmp_path, monkeypatch):
    conn = _defs_db(tmp_path, [("箸", "はし", "jmdict", "je", 0, "chopsticks")])
    class _Acc:
        def lookup(self, l, r): return None
    monkeypatch.setattr(enrich_one.audio_enrich, "lookup", lambda c, t, r: None)
    monkeypatch.setattr(enrich_one.ex_enrich, "examples_for", lambda c, t, r: "")
    f = enrich_one.build_fields("箸", "はし", defs_conn=conn,
                                ex_conn=object(), audio_conn=object(), acc=_Acc())
    assert f.audio_clip is None and f.audio_pick is None
    assert f.pitch_svg == ""        # accent not found -> no graph
