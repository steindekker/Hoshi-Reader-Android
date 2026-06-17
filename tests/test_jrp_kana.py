"""kana.hira() now delegates to jaconv. These tests pin its contract and the one
intentional divergence from the previous hand-rolled table, so a jaconv upgrade
that changes katakana->hiragana mapping fails loudly (the output feeds pitch
lookups and furigana alignment string-equality)."""
from ankienrich.jrp_fill.kana import KANJI_RE, hira
from ankienrich.markup import KANJI_RE as MARKUP_KANJI_RE


def test_kanji_re_is_single_source():
    # kana re-exports markup's pattern; no chance of the two drifting apart.
    assert KANJI_RE is MARKUP_KANJI_RE


def test_basic_katakana_to_hiragana():
    assert hira("コーヒー") == "こーひー"      # ー passes through
    assert hira("ジェット") == "じぇっと"
    assert hira("ヴァイオリン") == "ゔぁいおりん"


def test_passthrough_for_non_katakana():
    assert hira("ねこ漢字ABC・") == "ねこ漢字ABC・"


def test_matches_old_table_except_iteration_marks():
    """The previous implementation mapped U+30A1..U+30F6 (cp-0x60) and passed
    everything else through. jaconv matches it across that range and the
    surrounding block — the ONLY difference is the katakana iteration marks
    ヽヾ, which jaconv (more correctly) folds to ゝゞ."""
    old = {chr(c): chr(c - 0x60) for c in range(0x30A1, 0x30F7)}
    expected_diffs = {"ヽ": "ゝ", "ヾ": "ゞ"}
    for c in range(0x3000, 0x3100):
        ch = chr(c)
        old_out = old.get(ch, ch)
        if ch in expected_diffs:
            assert hira(ch) == expected_diffs[ch]
        else:
            assert hira(ch) == old_out, f"unexpected divergence at {hex(c)}"
