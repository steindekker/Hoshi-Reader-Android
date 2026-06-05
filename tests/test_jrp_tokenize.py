import os, shutil
import pytest

IDX = os.path.join(os.path.dirname(__file__), "..",
                   "ankialign", "data", "accent_index.marshal.gz")
pytestmark = pytest.mark.skipif(
    shutil.which("python") is None or not os.path.exists(IDX),
    reason="needs accent index")

def _sudachi_available():
    try:
        import sudachipy  # noqa: F401
        return True
    except Exception:
        return False

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_units_roundtrip_and_accent():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    s = "異世界には魔物がいる"
    us = units(s, acc)
    out = emit_sentence(us)
    # round-trips to the original surface
    assert markup.jrp_surface(out) == s
    # 異世界 is a compound that Mode C should keep whole and pitch
    assert "異世界" in out and "{" in out

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_split_cascade_recovers_overmerge():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    out = emit_sentence(units("前途多難だ", acc))
    assert markup.jrp_surface(out) == "前途多難だ"

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_bound_aux_fragments_not_pitched():
    # する/しれる fragments and single-mora kana verb fragments (し, い, しれ)
    # shredded out of fixed expressions / て-form chains must NOT get a pitch
    # unit — the gold never pitches them. Real auxiliaries (しまう, etc.) do.
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    for s in ["もしかしたら", "かもしれない", "神童と呼ばれていた", "結婚してから"]:
        out = emit_sentence(units(s, acc))
        assert markup.jrp_surface(out) == s          # surface preserved
        assert "{し;" not in out and "{しれ;" not in out and "{い;" not in out
    # a real multi-mora auxiliary is still pitched
    assert "{しまう" in emit_sentence(units("緊張してしまう", acc))

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_counter_compound_merge():
    # number/noun + 接尾辞 that Sudachi splits is re-fused into one pitched unit
    # ONLY when the joined (surface, reading) is in the accent dict (self-validating).
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    # 数日 -> すうじつ IS in the dict: merged, correct reading じつ (not the split にち/か)
    out = emit_sentence(units("数日が経った", acc))
    assert markup.jrp_surface(out) == "数日が経った"
    assert "すうじつ" in out
    # 五人 -> ごにん NOT in dict: not merged, but surface stays intact (no wrong reading)
    out2 = emit_sentence(units("五人いた", acc))
    assert markup.jrp_surface(out2) == "五人いた"
    # 二人 stays one correctly-read unit (Sudachi keeps it whole)
    assert "ふたり" in emit_sentence(units("二人で歩いた", acc))

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_conjugation_merged_into_one_yougen():
    # a conjugated verb absorbs its 助動詞/非自立可能 tail into ONE unit with the
    # dict-form base reading, instead of pitching only the stem
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    out = emit_sentence(units("分からなかった", acc))
    assert out == "{[分|わ]からなかった;Y2|わかる}"
    assert markup.jrp_surface(out) == "分からなかった"
    # full aux chain folds into the head verb's single unit
    out2 = emit_sentence(units("食べさせられたくなかった", acc))
    assert markup.jrp_surface(out2) == "食べさせられたくなかった"
    assert out2.count("{") == 1 and "|たべる" in out2

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_dict_oracle_and_reading_override():
    # 綾: Sudachi reads りょう (not in dict); the dict knows only あや -> oracle adopts it
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    acc = AccentIndex.load(IDX)
    assert "[綾|あや]" in emit_sentence(units("言葉の綾", acc))
    # the oracle must not leak into compounds where Sudachi is already right
    assert "あやと" in emit_sentence(units("綾取り", acc))
    # 私 is ambiguous in the dict (わたし & わたくし) -> explicit common-reading override
    assert "[私|わたし]" in emit_sentence(units("私はそう思う", acc))
    assert "しりつ" in emit_sentence(units("私立大学", acc))  # override stays whole-surface

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_lexicalized_form_beats_conjugation_merge():
    # 思わず is a dict adverb (おもわず); Sudachi splits it as 思わ(思う)+ず. The whole
    # form must win over the verb-conjugation merge, so it keeps its OWN accent and
    # is NOT marked 用言 (no Y, no 思う base reading).
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    acc = AccentIndex.load(IDX)
    out = emit_sentence(units("思わず顔をしかめた", acc))
    assert "{[思|おも]わず;2}" in out
    assert "おもう" not in out and "Y2|" not in out.split("わず")[0]
    # a genuinely-conjugated verb the dict does NOT list whole still merges as 用言
    assert "{[断|ことわ]られた;Y3|ことわる}" in emit_sentence(units("断られた", acc))
