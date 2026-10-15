package moe.antimony.hoshi.features.anki.enrich.jrp

/**
 * Linguistic data for JRP tokenization: part-of-speech sets and word stoplists.
 * Port of `jrp_fill/lexicon.py`; keep the two in sync, the comments there explain each entry.
 */
internal object JrpLexicon {
    /** Matched against Sudachi's pos[0]. */
    val CONTENT_POS = setOf("名詞", "代名詞", "動詞", "形容詞", "副詞", "形状詞", "連体詞", "感動詞")
    val YOUGEN_POS = setOf("動詞", "形容詞")
    val NOMINAL_POS = setOf("名詞", "代名詞", "連体詞", "形状詞")
    val AFFIX_POS = setOf("接頭辞", "接尾辞")
    val HON_PREFIXES = setOf("お", "ご", "御")

    val LIGHT_LEMMAS = setOf("する", "しれる")

    val NO_PITCH_LEMMAS = setOf(
        "こと", "事", "言う", "いう", "物", "もの", "ある", "有る",
        "いる", "居る", "なる", "成る", "よう", "様",
        "ない", "無い",
        "これ", "それ", "あれ", "どれ",
        "ここ", "そこ", "あそこ", "どこ",
        "この", "その", "あの", "どの",
        "こんな", "そんな", "あんな", "どんな",
        "こう", "そう", "ああ", "どう",
        "ところ", "所", "とき", "ため", "為", "ほう", "はず",
        "わけ", "うち", "まま", "そのまま",
        "何", "何時", "いつ", "誰", "なぜ",
        "くれる", "くださる",
        "ござる",
    )

    /** (lemma, reading) pairs de-pitched only for that reading. */
    val NO_PITCH_FORMS = setOf("時" to "とき", "方" to "ほう", "内" to "うち", "訳" to "わけ")

    val KOSOADO = setOf("こう", "そう", "ああ", "どう")

    val READING_OVERRIDE = mapOf("私" to "わたし")

    val NO_FURIGANA = setOf("何")

    val PERSON_HONORIFICS = setOf(
        "両親", "家族", "夫婦", "夫妻", "主人", "子息", "令息", "令嬢", "令室", "夫人",
        "母堂", "尊父", "兄弟", "姉妹", "親戚", "親族", "一族", "長男", "長女", "次男",
        "次女", "息女", "友人",
    )

    /** Passive/potential/causative. */
    val DERIV_SUFFIXES = setOf("れる", "られる", "せる", "させる")

    /** Modal auxiliaries that carry their own accent and stay out of the verb unit. */
    val NO_ABSORB = setOf("らしい", "です", "だ", "べし")
}
