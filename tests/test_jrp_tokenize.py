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
    # conj_accent=False -> the dict-form-base path (--no-conj-accent)
    out = emit_sentence(units("分からなかった", acc, conj_accent=False))
    assert out == "{[分|わ]からなかった;Y2|わかる}"
    assert markup.jrp_surface(out) == "分からなかった"
    # full aux chain folds into the head verb's single unit
    out2 = emit_sentence(units("食べさせられたくなかった", acc, conj_accent=False))
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
    # (dict-form base path; computed-pitch path is covered elsewhere)
    assert "{[断|ことわ]られた;Y3|ことわる}" in emit_sentence(units("断られた", acc, conj_accent=False))

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_kosoado_iu_and_no_pitch_stoplist():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    out = emit_sentence(units("そういうことか、と合点がいった。", acc))
    assert markup.jrp_surface(out) == "そういうことか、と合点がいった。"
    assert "{そういう;0}" in out          # one block, not そう + いう
    assert "{こと" not in out             # こと unpitched
    assert "いった;" not in out and "{いう" not in out  # 言う/いう unpitched
    assert "{どういう;1}" in emit_sentence(units("どういうこと", acc))

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_conditional_and_masu_negative_conj():
    # ば conditional, negative-conditional なければ, and ます/ません computed pitch
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    acc = AccentIndex.load(IDX)
    g = lambda s: emit_sentence(units(s, acc, conj_accent=True))
    assert "{[差|さ]し[支|つか]えなければ;Y6}" in g("差し支えなければ教えてください")  # さしつかえな\ければ
    assert "{[教|おし]えていただけません;Y10}" in g("教えていただけませんか")  # 〜ませ\ん
    assert "{[読|よ]めば;Y1}" in g("読めばわかる")                        # 読む atamadaka stays 1
    # それ/これ are unpitched demonstratives now
    assert "{それ" not in emit_sentence(units("それを見た", acc))

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_modals_not_absorbed_and_mashita():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    acc = AccentIndex.load(IDX)
    g = lambda s: emit_sentence(units(s, acc))
    # modal らしい/でしょう/だろう stay out of the verb unit (verb computes, modal plain)
    assert g("できないらしいね。") == "{できない;Y2}らしいね。"
    assert "{[難|むずか]しい;Y0,4}でしょう" in g("難しいでしょう。")
    assert "{[行|い]く;Y0}だろう" in g("行くだろう。")
    # ました polite past -> downstep on ま (chain-safe)
    assert "{[食|た]べました;Y3}" in g("食べました。")
    assert "{[教|おし]えてくれました;Y7}" in g("教えてくれました。")

@pytest.mark.skipif(not _sudachi_available(), reason="sudachipy not installed")
def test_sou_dropped_everywhere_but_kosoado_iu_kept():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    acc = AccentIndex.load(IDX)
    g = lambda s: emit_sentence(units(s, acc))
    assert "そう;" not in g("もう帰ったそうです。")   # 形状詞/助動詞語幹 evidential -> plain
    assert "そう;" not in g("雨が降りそうだ。")
    assert "そう;" not in g("そう思う。")             # 副詞 adverb -> now dropped too
    assert "{そういう;0}" in g("そういうこと。")      # but こそあど+いう block still pitched

def test_demonstratives_and_formal_nouns_dropped():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    from ankialign import markup
    acc = AccentIndex.load(IDX)
    g = lambda s: emit_sentence(units(s, acc))
    # (sentence, the token that must be emitted UNPITCHED). Other content words
    # in the sentence (verbs etc.) stay pitched as normal.
    cases = [("ここで待つ", "ここ"), ("そこにある", "そこ"), ("この本", "この"),
             ("その方がいい", "[方|ほう]"), ("あんな人", "あんな"), ("こう思う", "こう"),
             ("どこへ行く", "どこ"), ("食べるとき", "とき"), ("読むため", "ため"),
             ("はずがない", "はず"), ("うちに帰る", "うち"), ("そのまま置く", "そのまま")]
    for s, tok in cases:
        out = g(s)
        assert markup.jrp_surface(out) == s   # surface preserved (furigana kept)
        assert tok in out and "{" + tok not in out   # present but never inside a pitch unit
    # reading-aware: 時=とき -> furigana only; the 三時(じ) counter stays pitched
    assert "[時|とき]" in g("行く時。") and "{[時" not in g("行く時。")
    assert "{" in g("三時に会う。")

def test_interrogatives_and_giving_verbs_dropped():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    acc = AccentIndex.load(IDX)
    g = lambda s: emit_sentence(units(s, acc))
    assert "{[何" not in g("何がある") and "[何|なん]" in g("何がある")
    assert "{[誰" not in g("誰がいる")
    assert "いつ;" not in g("いつ来る") and "なぜ;" not in g("なぜだ")
    # standalone giving verbs unpitched; in 〜て form the MAIN verb keeps its pitch
    assert g("くれた") == "くれた" and g("ください。") == "ください。"
    assert "{[教|おし]えてください;Y0|おしえる}" in g("教えてください")
    assert "{[買|か]ってくれた" in g("買ってくれた")

def test_honorific_prefix_merge():
    from ankialign.jrp_fill.accent import AccentIndex
    from ankialign.jrp_fill.tokenize import units
    from ankialign.jrp_fill.emit import emit_sentence
    acc = AccentIndex.load(IDX)
    g = lambda s: emit_sentence(units(s, acc))
    # お/ご split off by Sudachi but a dict headword -> ONE unit with whole-word accent
    assert "{お[話|はなし];0}" in g("お話をする")          # not the split はなし[3]
    assert "{お[出|で]まし;0}" in g("お出ましか")          # noun, no 用言 marking
