# tests/test_pitch_svg.py
from ankialign import pitch_svg


def test_split_moras_basic():
    assert pitch_svg.split_moras("いへん") == ["い", "へ", "ん"]
    assert pitch_svg.split_moras("かみ") == ["か", "み"]


def test_split_moras_combines_small_kana():
    # しゃ is one mora; small kana attaches to the い-dan base
    assert pitch_svg.split_moras("しゃしん") == ["しゃ", "し", "ん"]
    assert pitch_svg.split_moras("きょう") == ["きょ", "う"]


def test_split_moras_long_vowel_and_sokuon_are_their_own_mora():
    assert pitch_svg.split_moras("コーヒー") == ["コ", "ー", "ヒ", "ー"]
    assert pitch_svg.split_moras("がっこう") == ["が", "っ", "こ", "う"]


def test_split_moras_preserves_original_script():
    # katakana stays katakana, hiragana stays hiragana
    assert pitch_svg.split_moras("アーカイブ") == ["ア", "ー", "カ", "イ", "ブ"]
