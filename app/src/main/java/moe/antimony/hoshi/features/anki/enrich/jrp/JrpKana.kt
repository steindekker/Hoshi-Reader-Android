package moe.antimony.hoshi.features.anki.enrich.jrp

/** Port of `jrp_fill/kana.py` and `markup.KANJI_RE`. */
internal object JrpKana {
    /**
     * Kanji character class, identical to `markup.KANJI_RE`. The Python source spells the second
     * range start as U+8C48 (the unified 豈, not compatibility U+F900), so it effectively spans
     * U+3400..U+FAFF (Hangul and private use included). Escaped so editors can't NFC-normalize it.
     */
    val KANJI_RE = Regex("[\\u3400-\\u9FFF\\u8C48-\\uFAFF\\u3005\\x{20000}-\\x{2A6DF}\\x{2A700}-\\x{2CEAF}\\x{2CEB0}-\\x{2EBEF}]")

    private const val FROM = "ァアィイゥウェエォオカガキギクグケゲコゴサザシジスズセゼソ" +
        "ゾタダチヂッツヅテデトドナニヌネノハバパヒビピフブプヘベペ" +
        "ホボポマミムメモャヤュユョヨラリルレロワヲンヮヰヱヵヶヴヽヾ"
    private const val TO = "ぁあぃいぅうぇえぉおかがきぎくぐけげこごさざしじすずせぜそ" +
        "ぞただちぢっつづてでとどなにぬねのはばぱひびぴふぶぷへべぺ" +
        "ほぼぽまみむめもゃやゅゆょよらりるれろわをんゎゐゑゕゖゔゝゞ"

    private val table: Map<Char, Char> = FROM.zip(TO).toMap()

    /**
     * Katakana to hiragana exactly as `jaconv.kata2hira`: ー and non-katakana pass through,
     * ヽヾ become ゝゞ, and ヷヸヹヺ are left alone.
     */
    fun hira(s: String): String {
        if (s.none { it in table }) return s
        val out = StringBuilder(s.length)
        for (c in s) out.append(table[c] ?: c)
        return out.toString()
    }

    fun isKanji(ch: String): Boolean = KANJI_RE.matches(ch)
}
