import json
from pathlib import Path

from ankienrich.freq import build as freq_build
from ankienrich.freq import index as freq_index


def _make_jpdb(dicts_dir: Path):
    """Minimal JPDB-shaped dict with BOTH real shapes: surface-keyed entries that
    nest the per-word rank under `frequency` + carry a reading, and kana-keyed
    entries with a top-level `value`."""
    d = dicts_dir / "jpdb"
    d.mkdir(parents=True)
    (d / "index.json").write_text(
        json.dumps({"title": "JPDBv2", "format": 3, "frequencyMode": "rank-based"}),
        encoding="utf-8")
    bank = [
        # surface-keyed (the bigger half of the real dict)
        ["林檎", "freq", {"reading": "りんご",
                          "frequency": {"value": 13430, "displayValue": "13430, 16980㋕"}}],
        ["方", "freq", {"reading": "ほう", "frequency": {"value": 100, "displayValue": "100"}}],
        ["方", "freq", {"reading": "かた", "frequency": {"value": 5000, "displayValue": "5000"}}],
        # kana-keyed (fallback)
        ["りんご", "freq", {"value": 16980, "displayValue": "16980㋕"}],
        ["はし", "freq", {"value": 9, "displayValue": "9㋕"}],
    ]
    (d / "term_meta_bank_1.json").write_text(
        json.dumps(bank, ensure_ascii=False), encoding="utf-8")


def test_build_indexes_both_shapes(tmp_path):
    _make_jpdb(tmp_path)
    db = tmp_path / "freq.sqlite"
    n = freq_build.build(str(tmp_path), str(db))
    assert n == 5   # (林檎,りんご) (方,ほう) (方,かた) (りんご,'') (はし,'')


def test_surface_word_rank_preferred_over_kana(tmp_path):
    _make_jpdb(tmp_path)
    db = tmp_path / "freq.sqlite"
    freq_build.build(str(tmp_path), str(db))
    conn = freq_index.open_index(str(db))
    # the surface 林檎 rank (13430) wins over the bare-kana りんご rank (16980)
    assert freq_index.lookup(conn, "林檎", "りんご") == (13430, "13430, 16980㋕")
    conn.close()


def test_reading_disambiguation(tmp_path):
    _make_jpdb(tmp_path)
    db = tmp_path / "freq.sqlite"
    freq_build.build(str(tmp_path), str(db))
    conn = freq_index.open_index(str(db))
    # same surface, different readings -> distinct ranks
    assert freq_index.lookup(conn, "方", "ほう") == (100, "100")
    assert freq_index.lookup(conn, "方", "かた") == (5000, "5000")
    conn.close()


def test_kana_fallback_and_miss(tmp_path):
    _make_jpdb(tmp_path)
    db = tmp_path / "freq.sqlite"
    freq_build.build(str(tmp_path), str(db))
    conn = freq_index.open_index(str(db))
    # unknown surface -> bare-kana row keyed by the reading
    assert freq_index.lookup(conn, "未知漢字", "りんご") == (16980, "16980㋕")
    # total miss
    assert freq_index.lookup(conn, "存在しない", "ないよ") is None
    conn.close()
