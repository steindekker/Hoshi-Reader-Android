# tests/test_pitch_svg.py
from ankienrich import pitch_svg


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


def test_is_high_patterns():
    # n moras; index n is the particle slot
    # heiban (0): mora0 low, everything else high
    assert [pitch_svg._is_high(i, 0, 3) for i in range(4)] == [False, True, True, True]
    # atamadaka (1): mora0 high, rest low
    assert [pitch_svg._is_high(i, 1, 3) for i in range(4)] == [True, False, False, False]
    # nakadaka (2) on 3 moras: low, high, low, low(particle)
    assert [pitch_svg._is_high(i, 2, 3) for i in range(4)] == [False, True, False, False]
    # odaka (2) on 2 moras: low, high, low(particle)
    assert [pitch_svg._is_high(i, 2, 2) for i in range(3)] == [False, True, False]


def test_render_svg_atamadaka_kami():
    svg = pitch_svg.render_svg("かみ", 1)
    assert svg == (
        '<svg xmlns="http://www.w3.org/2000/svg" width="61.2px" height="45px" '
        'viewBox="0 0 102 75">'
        '<text x="5" y="67.5" style="font-size:20px;font-family:sans-serif;fill:currentColor;">か</text>'
        '<text x="40" y="67.5" style="font-size:20px;font-family:sans-serif;fill:currentColor;">み</text>'
        '<path d="m 16,5 35,25" style="fill:none;stroke:currentColor;stroke-width:1.5;" />'
        '<path d="m 51,30 35,0" style="fill:none;stroke:currentColor;stroke-width:1.5;" />'
        '<circle r="5" cx="16" cy="5" style="opacity:1;fill:currentColor;"></circle>'
        '<circle r="5" cx="51" cy="30" style="opacity:1;fill:currentColor;"></circle>'
        '<circle r="4" cx="86" cy="30" stroke="currentColor" style="opacity:1;fill:none;"></circle>'
        '</svg>'
    )


def test_render_svg_heiban_dimensions():
    svg = pitch_svg.render_svg("いへん", 0)
    assert 'viewBox="0 0 137 75"' in svg
    assert 'width="82.2px"' in svg
    # heiban: first mora low (cy=30), second mora high (cy=5)
    assert '<circle r="5" cx="16" cy="30"' in svg
    assert '<circle r="5" cx="51" cy="5"' in svg
    # particle slot is the hollow r=4 circle, high for heiban
    assert '<circle r="4" cx="121" cy="5"' in svg


def test_render_svg_empty_reading():
    assert pitch_svg.render_svg("", 0) == ""


def test_render_multi_stacks_one_graph_per_accent():
    multi = pitch_svg.render_multi("ゆうふく", [0, 1])
    assert multi.count("<svg") == 2
    assert "</svg><br><svg" in multi
    assert multi == (pitch_svg.render_svg("ゆうふく", 0) + "<br>"
                     + pitch_svg.render_svg("ゆうふく", 1))
    # single accent -> no separator
    assert pitch_svg.render_multi("かみ", [1]) == pitch_svg.render_svg("かみ", 1)
