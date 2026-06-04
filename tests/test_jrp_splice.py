from ankialign.jrp_fill.splice import splice

def gen(text):  # stub generator: wraps kanji-bearing runs distinctly
    return "<<" + text + ">>"

def test_fully_plain_is_one_run():
    assert splice("異端である", gen) == "<<異端である>>"

def test_kana_only_run_is_untouched():
    assert splice("そうだね", gen) == "そうだね"

def test_existing_unit_is_preserved():
    s = "{[異変|いへん];0}に気付いた"
    assert splice(s, gen) == "{[異変|いへん];0}に<<気付いた>>"

def test_bracket_segment_is_preserved():
    s = "[気付|きづ]いて異端"
    assert splice(s, gen) == "[気付|きづ]いて<<異端>>"

def test_fully_marked_is_noop():
    s = "{[異変|いへん];0}に{[気付|きづ]いた;Y2}"
    assert splice(s, gen) == s
