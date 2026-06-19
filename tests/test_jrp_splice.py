from ankienrich.jrp_fill.splice import splice

def gen(text):  # stub generator: wraps kanji-bearing runs distinctly
    return "<<" + text + ">>"

def test_fully_plain_is_one_run():
    assert splice("異端である", gen) == "<<異端である>>"

def test_kana_only_run_is_untouched():
    assert splice("そうだね", gen) == "そうだね"

def test_existing_unit_is_preserved():
    s = "{[異変|いへん];0}に気付いた"
    # the whole plain run "に気付いた" (it contains kanji) goes to generate;
    # the real generate keeps に bare via tokenization.
    assert splice(s, gen) == "{[異変|いへん];0}<<に気付いた>>"

def test_bracket_segment_is_preserved():
    s = "[気付|きづ]いて異端"
    assert splice(s, gen) == "[気付|きづ]<<いて異端>>"

def test_fully_marked_is_noop():
    s = "{[異変|いへん];0}に{[気付|きづ]いた;Y2}"
    assert splice(s, gen) == s

def test_newline_is_a_generate_boundary():
    # a newline (was <br>) breaks the run: each line is generated on its own,
    # so generate never sees the newline char
    assert splice("異端\n本当", gen) == "<<異端>>\n<<本当>>"

def test_stoplist_word_in_existing_markup_is_depitched():
    # pre-existing jidoujisho pitch on a stoplist word is demoted to furigana-only
    assert splice("{それ;1}から", gen) == "それから"
    assert splice("{[時|とき];0}が来る", gen) == "[時|とき]<<が来る>>"  # 時=とき dropped
    # but a non-stoplist unit is preserved, and the 三時(じ) counter stays pitched
    assert splice("{[異変|いへん];0}", gen) == "{[異変|いへん];0}"
    assert splice("{[時|じ];1}", gen) == "{[時|じ];1}"
