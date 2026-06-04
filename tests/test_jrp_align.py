from ankialign.jrp_fill.align import furigana_segments as fs

def test_pure_kana_is_bare():
    assert fs("それ", "それ") == [("それ", None)]

def test_no_kanji_never_gets_furigana():
    assert fs("チャイナ", "ちゃいな") == [("チャイナ", None)]

def test_leading_kanji_with_okurigana():
    assert fs("食べる", "たべる") == [("食", "た"), ("べる", None)]

def test_compound_kanji_run():
    assert fs("気付", "きづ") == [("気付", "きづ")]
    assert fs("気付く", "きづく") == [("気付", "きづ"), ("く", None)]

def test_kanji_kana_kanji_kana():
    assert fs("持ち掛ける", "もちかける") == [
        ("持", "も"), ("ち", None), ("掛", "か"), ("ける", None)]

def test_trailing_kanji_takes_rest():
    assert fs("お茶", "おちゃ") == [("お", None), ("茶", "ちゃ")]

def test_mismatch_falls_back_to_whole_segment():
    # reading that cannot be aligned -> single segment, still round-trips
    assert fs("大人気", "だいにんき") == [("大人気", "だいにんき")]

def test_empty_reading_is_bare():
    assert fs("！", "") == [("！", None)]
