from ankienrich import markup

def test_jrp_surface():
    assert markup.jrp_surface("{[異変|いへん];0}") == "異変"
    assert markup.jrp_surface("{[一|ひと]つ;2}") == "一つ"
    assert markup.jrp_surface("{[気付|きづ][いた=く];Y2}") == "気付いた"
    assert markup.jrp_surface("{くださ[い=る];Y3}") == "ください"
    assert markup.jrp_surface("{それ;1}") == "それ"
    assert markup.jrp_surface("一") == "一"
    assert markup.jrp_surface("それ") == "それ"

def test_jrp_to_furigana():
    assert markup.jrp_to_furigana("{[異変|いへん];0}") == "異変[いへん]"
    assert markup.jrp_to_furigana("{[一|ひと]つ;2}") == "一[ひと]つ"
    assert markup.jrp_to_furigana("{[気付|きづ][いた=く];Y2}") == "気付[きづ]いた"
    assert markup.jrp_to_furigana("一") == "一"
    assert markup.jrp_to_furigana("{[気付|きづ];0}{[いた=く];Y2}") == "気付[きづ]いた"

def test_strip_sentence_html_unwraps_inline_tags():
    # <b> etc. are unwrapped (contents kept), JRP markup left intact
    assert markup.strip_sentence_html(
        "この{[果|は]てし;0,3}ない<b>{[旅路|たびじ];0,2}</b>を。"
    ) == "この{[果|は]てし;0,3}ない{[旅路|たびじ];0,2}を。"

def test_strip_sentence_html_drops_rt_with_content():
    # <rt> holds a duplicate reading; tag AND its text must go, base kept
    assert markup.strip_sentence_html(
        "<ruby>{[今度|こんど];1}<rt>こんど</rt></ruby>は"
    ) == "{[今度|こんど];1}は"

def test_strip_sentence_html_br_becomes_newline():
    assert markup.strip_sentence_html("一<br>二<br/>三<br />四") == "一\n二\n三\n四"

def test_strip_sentence_html_leaves_plain_untouched():
    assert markup.strip_sentence_html("{猫;1}が歩いた") == "{猫;1}が歩いた"


def test_strip_subtitle_labels_drops_leading_speaker():
    # anime-caption speaker label at the start of the line
    assert markup.strip_subtitle_labels("（{トール;1}）{カンナ;1}何を") == "{カンナ;1}何を"
    assert markup.strip_subtitle_labels("（[聖哉|きよちか]）{こいつ;0}ら") == "{こいつ;0}ら"
    assert markup.strip_subtitle_labels("（マルロ）{[護送|ごそう];0}") == "{[護送|ごそう];0}"

def test_strip_subtitle_labels_drops_after_sentence_break():
    # a label right after sentence-final punctuation is still a speaker label
    assert markup.strip_subtitle_labels("言ってみろ！（{トール;1}）はい") == "言ってみろ！はい"
    # ...including across a <br>-derived newline
    assert markup.strip_subtitle_labels("一行目\n（エレン）すいません") == "一行目\nすいません"

def test_strip_subtitle_labels_keeps_inline_glosses():
    # a gloss FOLLOWS its term (not line-start / sentence-break) -> kept
    assert markup.strip_subtitle_labels(
        "{[国際|こくさい]サッカー[連盟|れんめい];9}（ＦＩＦＡ）が"
    ) == "{[国際|こくさい]サッカー[連盟|れんめい];9}（ＦＩＦＡ）が"
    assert markup.strip_subtitle_labels("「イカゲーム」（SquidGame）が") == "「イカゲーム」（SquidGame）が"
    assert markup.strip_subtitle_labels("さん（28）は") == "さん（28）は"          # age
    assert markup.strip_subtitle_labels("{イルカ;0}({[海豚|いるか];0})") == "{イルカ;0}({[海豚|いるか];0})"

def test_strip_subtitle_labels_keeps_whole_sentence_aside():
    # the parenthetical IS the entire line (no content after) -> not a label;
    # stripping it would empty the card
    s = "({ディープ;1}{ステート;2}、{[影|かげ];1}の{[国家|こっか];1}）"
    assert markup.strip_subtitle_labels(s) == s

def test_strip_subtitle_labels_noop_without_labels():
    assert markup.strip_subtitle_labels("{猫;1}が歩いた") == "{猫;1}が歩いた"


def test_normalize_spaces_fullwidth_to_ascii():
    # ideographic space (U+3000) -> ASCII space; markup left intact
    assert markup.normalize_spaces("今は　そげな話は　あとじゃ。") == "今は そげな話は あとじゃ。"
    assert markup.normalize_spaces("{[今|いま];1}は　{あと;1}じゃ") == "{[今|いま];1}は {あと;1}じゃ"
    # no other whitespace touched (ASCII space, newline preserved)
    assert markup.normalize_spaces("a b\nc") == "a b\nc"
    # idempotent
    assert markup.normalize_spaces("今は そげな話は") == "今は そげな話は"


def test_fix_furigana_spacing_splits_particle_off_base():
    # を is a particle, not part of 逃's reading のが -> needs a delimiter space
    assert markup.fix_furigana_spacing("難[なん]を逃[のが]れる") == "難[なん]を 逃[のが]れる"
    assert markup.fix_furigana_spacing("引[ひ]けを取[と]らない") == "引[ひ]けを 取[と]らない"
    assert markup.fix_furigana_spacing("代[か]わる代[が]わる") == "代[か]わる 代[が]わる"
    assert markup.fix_furigana_spacing("お忍[しの]び") == "お 忍[しの]び"
    assert markup.fix_furigana_spacing("フライ返[がえ]し") == "フライ 返[がえ]し"

def test_fix_furigana_spacing_keeps_compound_reading():
    # シャボン's kana IS the prefix of しゃぼんだま -> one ruby group, leave alone
    assert markup.fix_furigana_spacing("シャボン玉[しゃぼんだま]") == "シャボン玉[しゃぼんだま]"
    assert markup.fix_furigana_spacing("ドヤ顔[どやがお]") == "ドヤ顔[どやがお]"

def test_fix_furigana_spacing_leaves_clean_notation():
    assert markup.fix_furigana_spacing("部活[ぶかつ]") == "部活[ぶかつ]"
    assert markup.fix_furigana_spacing("難[なん]を 逃[のが]れる") == "難[なん]を 逃[のが]れる"  # idempotent
    assert markup.fix_furigana_spacing("みきき") == "みきき"


def test_jrp_reading():
    assert markup.jrp_reading("{[異変|いへん];0}") == "いへん"
    assert markup.jrp_reading("{[一|ひと]つ;2}") == "ひとつ"
    assert markup.jrp_reading("{[気付|きづ][いた=く];Y2}") == "きづいた"
    assert markup.jrp_reading("それ") == "それ"
    assert markup.jrp_reading("一") == ""
    assert markup.jrp_reading("{[気付|きづ];0}{[いた=く];Y2}") == "きづいた"
