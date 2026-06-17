import json
from pathlib import Path

from ankienrich.freq import build as freq_build
from ankienrich.freq import index as freq_index


def _make_jpdb(dicts_dir: Path):
    """Minimal JPDB-shaped dict: surface + kana keys, rank-based freq meta."""
    d = dicts_dir / "jpdb"
    d.mkdir(parents=True)
    (d / "index.json").write_text(
        json.dumps({"title": "JPDBv2", "format": 3, "frequencyMode": "rank-based"}),
        encoding="utf-8")
    bank = [
        ["林檎", "freq", {"value": 1234, "displayValue": "1234"}],
        ["りんご", "freq", {"value": 1234, "displayValue": "1234㋕"}],
        ["箸", "freq", {"value": 50, "displayValue": "50"}],
        ["はし", "freq", {"value": 9, "displayValue": "9㋕"}],
    ]
    (d / "term_meta_bank_1.json").write_text(
        json.dumps(bank, ensure_ascii=False), encoding="utf-8")


def test_build_indexes_surface_and_reading(tmp_path):
    _make_jpdb(tmp_path)
    db = tmp_path / "freq.sqlite"
    n = freq_build.build(str(tmp_path), str(db))
    assert n == 4
    conn = freq_index.open_index(str(db))
    assert freq_index.lookup(conn, "林檎", "りんご") == (1234, "1234")
    conn.close()


def test_lookup_falls_back_surface_then_reading(tmp_path):
    _make_jpdb(tmp_path)
    db = tmp_path / "freq.sqlite"
    freq_build.build(str(tmp_path), str(db))
    conn = freq_index.open_index(str(db))
    # surface hit
    assert freq_index.lookup(conn, "箸", "はし") == (50, "50")
    # surface miss -> reading hit
    assert freq_index.lookup(conn, "未知漢字", "はし") == (9, "9㋕")
    # total miss
    assert freq_index.lookup(conn, "存在しない", "ないよ") is None
    conn.close()
