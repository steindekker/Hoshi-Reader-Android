# tests/test_pitch_index.py
import gzip, marshal
from ankialign.pitch_index import PitchIndex, to_hira


def test_to_hira():
    assert to_hira("アーカイブ") == "あーかいぶ"
    assert to_hira("いへん") == "いへん"


def _make(tmp_path, mapping):
    p = tmp_path / "idx.marshal.gz"
    with gzip.open(p, "wb") as f:
        f.write(marshal.dumps(mapping))
    return PitchIndex.load(str(p))


def test_lookup_exact_term_reading(tmp_path):
    idx = _make(tmp_path, {"異変\tいへん": 0})
    assert idx.lookup("異変", "いへん") == 0


def test_lookup_normalizes_katakana_reading(tmp_path):
    # index stored with hiragana-normalized reading; query gives katakana
    idx = _make(tmp_path, {"アーカイブ\tあーかいぶ": 3})
    assert idx.lookup("アーカイブ", "アーカイブ") == 3


def test_lookup_kana_headword_fallback(tmp_path):
    # term differs (kanji on card) but dict headword is kana == reading
    idx = _make(tmp_path, {"ある\tある": 1})
    assert idx.lookup("有る", "ある") == 1


def test_lookup_miss_returns_none(tmp_path):
    idx = _make(tmp_path, {"異変\tいへん": 0})
    assert idx.lookup("存在しない", "そんざい") is None
