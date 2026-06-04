import os
import pytest
from ankialign.jrp_fill.accent import AccentIndex

IDX = os.path.join(os.path.dirname(__file__), "..",
                   "ankialign", "data", "accent_index.marshal.gz")
pytestmark = pytest.mark.skipif(not os.path.exists(IDX), reason="no accent index")

def test_known_word_returns_positions():
    acc = AccentIndex.load(IDX)
    # 世界(せかい) is a high-frequency entry; expect a non-empty position list
    pos = acc.lookup("世界", "せかい")
    assert isinstance(pos, list) and len(pos) >= 1

def test_unknown_returns_none():
    acc = AccentIndex.load(IDX)
    assert acc.lookup("ボンクラ存在しない語", "ぼんくらそんざいしないご") is None

def test_default_path_loads():
    acc = AccentIndex.load()
    assert acc.lookup("世界", "せかい") is not None
