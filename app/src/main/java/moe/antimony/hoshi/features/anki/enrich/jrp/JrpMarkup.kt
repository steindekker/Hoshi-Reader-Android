package moe.antimony.hoshi.features.anki.enrich.jrp

/**
 * Pure string transforms for jidoujisho/JRP sentence markup (port of the parts of `markup.py` the
 * sentence fill uses).
 *
 * Regexes avoid `\s`, `\b`, `^`/`$` and case-insensitive flags, whose semantics differ between
 * Python `re`, the JVM and Android's ICU engine; the explicit classes below reproduce Python's.
 */
internal object JrpMarkup {
    /** Python `str.isspace()` / `re` `\s` characters, as the body of a regex character class. */
    private const val PY_SPACE = "\\t\\n\\x0B\\f\\r\\x1C-\\x1F \\x85\\xA0\\u1680\\u2000-\\u200A" +
        "\\u2028\\u2029\\u202F\\u205F\\u3000"

    /** Python `re` `\w` characters (Unicode alnum + underscore), as a character class body. */
    private const val PY_WORD = "\\p{L}\\p{N}_"

    // matches an Anki furigana segment: base[reading]
    private val RUBY = Regex("([^$PY_SPACE\\[\\]<>]+)\\[([^\\]]+)\\]")

    private val BR = Regex("<[bB][rR][$PY_SPACE]*/?>")

    // duplicate reading: drop content too
    private val RT = Regex("<[rR][tT](?![$PY_WORD])[^>]*>.*?</[rR][tT]>", RegexOption.DOT_MATCHES_ALL)
    private val TAG = Regex("</?[a-zA-Z][^>]*>")

    // Anime-caption speaker label: a parenthetical at the start of a line or right after
    // sentence-final punctuation, e.g. （長嶺）/（エレン）. An inline gloss always FOLLOWS its term
    // (国際サッカー連盟（ＦＩＦＡ）, さん（28）), and the trailing-content lookahead spares a
    // whole-sentence aside that IS the parenthetical (（…みたいだな）).
    private val SPEAKER = Regex("(?:\\A|(?<=\\n))[ 　]*[（(][^（）()]*[）)][ 　]*(?=[^$PY_SPACE])")
    private val AFTER_BREAK = Regex("([。！？])[ 　]*[（(][^（）()]*[）)][ 　]*(?=[^$PY_SPACE])")

    private val UNIT = Regex("\\{([^{}]*)\\}")
    private val SURFACE_BASE = Regex("\\[([^\\[\\]|=]*)=([^\\[\\]]*)\\]")
    private val KANJI_KANA = Regex("\\[([^\\[\\]|]+)\\|([^\\[\\]]+)\\]")

    fun isPySpace(c: Char): Boolean = when (c) {
        '\t', '\n', '\u000B', '\u000C', '\r', '\u001C', '\u001D', '\u001E', '\u001F', ' ',
        '\u0085', ' ', ' ', ' ', ' ', ' ', ' ', '　' -> true
        else -> c in ' '..' '
    }

    /** Python `str.strip()` with no arguments. */
    fun pyStrip(s: String): String = s.trim(::isPySpace)

    /**
     * Reduce a Sentence field to JRP markup + bare text: `<br>` -> newline, the redundant ruby
     * reading (`<rt>…</rt>`) dropped wholesale, every other tag unwrapped (contents kept).
     */
    fun stripSentenceHtml(s: String): String {
        var out = BR.replace(s, "\n")
        out = RT.replace(out, "")
        return TAG.replace(out, "")
    }

    /** Drop subtitle speaker labels (（name）) left by anime mining. */
    fun stripSubtitleLabels(s: String): String {
        val out = AFTER_BREAK.replace(s) { it.groupValues[1] }
        return SPEAKER.replace(out, "")
    }

    /** Ideographic space (U+3000) -> ASCII space. */
    fun normalizeSpaces(s: String): String = s.replace("　", " ")

    /** Anki furigana notation -> surface (drop readings, join segments). */
    private fun ankiSurface(s: String): String = RUBY.replace(s) { it.groupValues[1] }.replace(" ", "")

    /** jidoujisho/JRP markup -> Anki furigana notation, e.g. `{[一|ひと]つ;2}` -> `一[ひと]つ`. */
    fun jrpToFurigana(s: String): String {
        // drop the accent suffix (last ;…)
        var out = UNIT.replace(s) { m ->
            val body = m.groupValues[1]
            val semi = body.lastIndexOf(';')
            if (semi >= 0) body.substring(0, semi) else body
        }
        out = SURFACE_BASE.replace(out) { it.groupValues[1] } // [surface=base] -> surface
        out = KANJI_KANA.replace(out) { " " + it.groupValues[1] + "[" + it.groupValues[2] + "]" }
        return pyStrip(out)
    }

    /** JRP markup -> plain surface string. */
    fun jrpSurface(s: String): String = ankiSurface(jrpToFurigana(s))
}
