from ankienrich.examples import parse, render
from ankienrich.examples.build import _TOK


def test_norm_strips_whitespace_and_punct():
    assert parse.norm("私は、愛した。") == "私は愛した"
    assert parse.norm("A B「C」") == "ABC"


def test_highlight_wraps_first_occurrence_and_escapes():
    assert parse.highlight("猫が好き", "猫") == '<b class="aa-ex-kw">猫</b>が好き'
    assert parse.highlight("a<x>b", "") == "a&lt;x&gt;b"      # no keyword -> just escaped
    assert parse.highlight("もう一度", "猫") == "もう一度"        # absent keyword -> escaped text


def test_jitendex_extracts_jp_ruby_keyword_and_en():
    sc = [{"tag": "div", "data": {"content": "example-sentence"}, "content": [
        {"tag": "div", "data": {"content": "example-sentence-a"}, "content": {"tag": "span", "content": [
            {"tag": "ruby", "content": ["私", {"tag": "rt", "content": "わたし"}]}, "は",
            {"tag": "span", "data": {"content": "example-keyword"}, "content": "愛"}, "した。"]}},
        {"tag": "div", "data": {"content": "example-sentence-b"},
         "content": {"tag": "span", "content": "I loved."}}]}]
    exs = parse.jitendex(sc)
    assert len(exs) == 1
    assert exs[0]["jp"] == '<ruby>私<rt>わたし</rt></ruby>は<b class="aa-ex-kw">愛</b>した。'
    assert exs[0]["en"] == "I loved."
    assert exs[0]["plain"] == "私は愛した。"            # rt dropped from plain


def test_saito_pairs_diamond_jp_with_70pct_en():
    sc = [{"tag": "ul", "content": [
        {"tag": "li", "style": {"listStyleType": "'◆'"}, "content": "猫が好き"},
        {"tag": "li", "style": {"fontSize": "70%"}, "content": "I like cats."},
        {"tag": "li", "style": {"listStyleType": "'◆'"}, "content": "犬も好き"}]}]  # trailing JP, no EN
    exs = parse.saito(sc, "猫")
    assert exs[0] == {"jp": '<b class="aa-ex-kw">猫</b>が好き', "en": "I like cats.", "plain": "猫が好き"}
    assert exs[1] == {"jp": "犬も好き", "en": "", "plain": "犬も好き"}   # term absent -> no highlight


def test_yourei_keeps_only_headword_sentence_drops_anchor():
    # passage = 3 sentences; headword 自然 sits in the middle one -> only that is kept
    sc = [{"tag": "div", "content": [{"tag": "ul", "content": [
        {"tag": "li", "content": [{"tag": "span", "content": [
            {"tag": "span", "content": "前の文。とても"},
            {"tag": "span", "style": {"color": "red", "fontWeight": "bold"}, "content": "自然"},
            {"tag": "span", "content": "だ。次の文。"},
            {"tag": "a", "href": "x", "content": "出典本"}]}]}]}]}]
    exs = parse.yourei(sc)
    assert exs == [{"jp": 'とても<b class="aa-ex-kw">自然</b>だ。', "en": "", "plain": "とても自然だ。"}]


def test_yourei_single_sentence_passes_through():
    sc = [{"tag": "ul", "content": [{"tag": "li", "content": [{"tag": "span", "content": [
        {"tag": "span", "content": "これは"},
        {"tag": "span", "style": {"color": "red"}, "content": "猫"},
        {"tag": "span", "content": "だ"}]}]}]}]
    assert parse.yourei(sc) == [{"jp": 'これは<b class="aa-ex-kw">猫</b>だ', "en": "", "plain": "これは猫だ"}]


def test_tatoeba_token_regex():
    cases = {
        "は": ("は", None, None),
        "二十歳(はたち){２０歳}": ("二十歳", "はたち", "２０歳"),
        "為る(する){する}": ("為る", "する", "する"),
        "になる[01]{になりました}": ("になる", None, "になりました"),
        "直ぐに{すぐに}": ("直ぐに", None, "すぐに"),
    }
    for tok, expected in cases.items():
        m = _TOK.match(tok)
        assert m and (m.group(1), m.group(2), m.group(3)) == expected


def test_render_block_empty_and_populated():
    assert render.render_block([]) == ""
    out = render.render_block([
        {"jp": "猫が好き", "en": "I like cats.", "source": "tatoeba", "label": "Tatoeba"},
        {"jp": "犬も好き", "en": "", "source": "saito", "label": "斎藤"}])
    assert out.startswith('<details class="aa-ex"><summary>例文</summary>')
    assert '<span class="aa-ex-en">I like cats.</span>' in out
    assert out.count('class="aa-ex-en"') == 1          # second item has no EN
    assert out.count('class="aa-ex-item"') == 2
    assert "<ol" not in out and "aa-ex-src" not in out  # no numbered list, no source label
