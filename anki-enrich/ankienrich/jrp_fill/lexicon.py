"""Linguistic data for JRP tokenization: part-of-speech sets and word stoplists.

Pure data, no logic — kept apart so tokenize.py and splice.py share one source of
truth (and splice no longer has to reach into tokenize's privates)."""
from __future__ import annotations

# --- Part-of-speech sets (matched against Sudachi's pos[0]) ------------------
CONTENT_POS = {"名詞", "代名詞", "動詞", "形容詞", "副詞", "形状詞", "連体詞", "感動詞"}
YOUGEN_POS = {"動詞", "形容詞"}            # inflecting words (verbs/i-adjectives)
NOMINAL_POS = {"名詞", "代名詞", "連体詞", "形状詞"}
AFFIX_POS = {"接頭辞", "接尾辞"}
HON_PREFIXES = {"お", "ご", "御"}          # 美化語/honorific prefixes Sudachi peels off

# Light/auxiliary verbs Sudachi peels off fixed expressions (もしかしたら,
# かもしれない) and て-form chains. Pitching their bound fragments (し, い, しれ)
# looks wrong. Multi-mora real auxiliaries (しまう, くれる, full いる, できる, なる...)
# are unaffected.
LIGHT_LEMMAS = {"する", "しれる"}

# Very common, low-information words: keep furigana but DROP the pitch color —
# it only adds clutter and hurts readability. Keyed by lemma.
# both kana and kanji lemma variants, since Sudachi uses either (言う/いう, 物/もの)
NO_PITCH_LEMMAS = {
    "こと", "事", "言う", "いう", "物", "もの", "ある", "有る",
    "いる", "居る", "なる", "成る", "よう", "様",
    "ない", "無い",                  # standalone/なさ negative only;
    # demonstratives (こそあど) — pure deixis, pitch adds nothing to reading
    "これ", "それ", "あれ", "どれ",                  # 代名詞 (things)
    "ここ", "そこ", "あそこ", "どこ",                # 代名詞 (places)
    "この", "その", "あの", "どの",                  # 連体詞
    "こんな", "そんな", "あんな", "どんな",          # 連体詞/形状詞
    "こう", "そう", "ああ", "どう",                  # 副詞 (manner) — incl. standalone そう
    # formal / dependent nouns: grammaticalized, carry structure not meaning.
    # kanji that share a lemma with a real content word (時=とき/じ, 方=ほう/かた,
    # 内=うち/ない, 訳=わけ/やく) are handled reading-aware in NO_PITCH_FORMS instead.
    "ところ", "所", "とき", "ため", "為", "ほう", "はず",
    "わけ", "うち", "まま", "そのまま",
    # interrogatives — same pure deixis as the demonstratives above
    "何", "何時", "いつ", "誰", "なぜ",
    # giving/receiving auxiliaries: structural, not content (in 〜て form they're
    # absorbed into the main verb, which keeps its pitch — only standalone hits)
    "くれる", "くださる",
    # polite copula/existential, same class as です/ます (ありがとうございます)
    "ござる",
}
# conjugated negatives (分からない, 高くない) absorb ない into the head verb/adjective,
# whose lemma is 分かる/高い -> still pitched.

# (lemma, reading) pairs to de-pitch ONLY for that reading, so polysemous kanji
# don't lose pitch on their content sense (三時 じ / 話し方 かた / 以内 / 翻訳 やく).
NO_PITCH_FORMS = {("時", "とき"), ("方", "ほう"), ("内", "うち"), ("訳", "わけ")}

# こそあど + いう form lexicalized 連体詞 (そういう, こういう...) Sudachi splits as
# 副詞/連体詞 + 動詞; fuse them into one block (dict has them as headwords).
KOSOADO = {"こう", "そう", "ああ", "どう"}

# Common-reading preference for words the dict lists under MULTIPLE readings, so
# the dict oracle can't disambiguate and Sudachi picks the rarer one. Keyed on
# the exact whole surface (never fires inside a compound: 私立=しりつ untouched).
# Reserve this only for genuine frequency calls; wrong single readings the dict
# *can* settle (綾->あや) are handled by the oracle in tokenize, not here.
READING_OVERRIDE = {"私": "わたし"}

# stoplisted words rendered WITHOUT furigana too — 何's reading (なに/なん) is
# ambiguous & low-value; bare kanji preferred
NO_FURIGANA = {"何"}

# Honorific-PERSON nouns: referent-honorific ご-words that keep their own accent
# (ご子息[2,3]), so 美化語平板化 does NOT apply. Unlike the transparent keigo the dict
# omits, these are accented-but-unlisted, so the heiban fallback must skip them.
PERSON_HONORIFICS = frozenset({
    "両親", "家族", "夫婦", "夫妻", "主人", "子息", "令息", "令嬢", "令室", "夫人",
    "母堂", "尊父", "兄弟", "姉妹", "親戚", "親族", "一族", "長男", "長女", "次男",
    "次女", "息女", "友人",
})

DERIV_SUFFIXES = {"れる", "られる", "せる", "させる"}  # passive/potential/causative
# modal auxiliaries that carry their OWN accent — don't fuse them into the verb
# (できないらしい, 難しいでしょう); the verb computes on its own, the modal stays plain
NO_ABSORB = {"らしい", "です", "だ", "べし"}  # べし=べき: modal "should", own unit
