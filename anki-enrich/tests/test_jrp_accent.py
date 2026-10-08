import os
import pytest
from ankienrich.jrp_fill.accent import AccentIndex

IDX = os.path.join(os.path.dirname(__file__), "..",
                   "ankienrich", "data", "accent_index.marshal.gz")
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

def test_by_reading_agreement_is_order_insensitive():
    # The index is preference-ordered, so two homophone surfaces with the same
    # accent SET but different order ([0,3] vs [3,0]) must still count as agreeing.
    acc = AccentIndex(table={"甲\tこう": [0, 3], "乙\tこう": [3, 0]})
    assert acc.by_reading("こう") is not None        # agree -> confident
    # genuinely different accents stay ambiguous (no wrong guess)
    acc2 = AccentIndex(table={"橋\tはし": [2], "箸\tはし": [1]})
    assert acc2.by_reading("はし") is None
