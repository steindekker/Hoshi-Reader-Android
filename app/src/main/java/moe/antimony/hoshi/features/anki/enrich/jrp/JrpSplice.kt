package moe.antimony.hoshi.features.anki.enrich.jrp

/**
 * Splice generated markup into a sentence, preserving existing JRP spans (port of
 * `jrp_fill/splice.py`).
 *
 * Existing `{...}` units and `[...]` furigana segments are kept byte-for-byte, EXCEPT that a
 * `{...}` unit whose word is on the no-pitch stoplist is demoted to furigana-only (keep reading,
 * drop the accent). Plain runs containing at least one kanji are passed to `generate`; kana-only
 * or punctuation-only runs are left untouched.
 */
internal object JrpSplice {
    private val FURI = Regex("\\[([^|\\]]+)\\|([^\\]]+)\\]")

    /** `{body;accent}` -> `body` (furigana kept, color dropped) iff the word is on the stoplist. */
    private fun depitchIfStoplisted(unit: String): String {
        if (!(unit.startsWith("{") && unit.endsWith("}") && ";" in unit)) return unit
        val body = unit.substring(1, unit.length - 1).substringBefore(";") // surface part, before the accent
        val plain = FURI.replace(body) { it.groupValues[1] } // [漢字|かな] -> 漢字
        val read = FURI.replace(body) { it.groupValues[2] } // [漢字|かな] -> かな
        if (plain in JrpLexicon.NO_PITCH_LEMMAS || read in JrpLexicon.NO_PITCH_LEMMAS ||
            (plain to read) in JrpLexicon.NO_PITCH_FORMS
        ) {
            return body
        }
        return unit
    }

    /** Tokenize into (isMarkup, text): markup for `{...}`/`[...]`, plain runs otherwise. */
    private fun spans(s: String): List<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        val buf = StringBuilder()
        fun flush() {
            if (buf.isNotEmpty()) {
                out.add(false to buf.toString())
                buf.clear()
            }
        }
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val close = when (c) {
                '{' -> '}'
                '[' -> ']'
                else -> null
            }
            if (close != null) {
                val j = s.indexOf(close, i)
                if (j >= 0) {
                    flush()
                    out.add(true to s.substring(i, j + 1))
                    i = j + 1
                    continue
                }
            }
            // surrogate halves are copied one by one, so pairs stay intact
            buf.append(c)
            i++
        }
        flush()
        return out
    }

    fun splice(sentence: String, generate: (String) -> String): String {
        val parts = StringBuilder()
        for ((isMarkup, text) in spans(sentence)) {
            if (isMarkup) {
                parts.append(depitchIfStoplisted(text))
            } else {
                // a newline is a structural break (was <br>): generate each line on its own so the
                // marker never lands inside a generated unit.
                text.split("\n").joinTo(parts, "\n") { line ->
                    if (JrpKana.KANJI_RE.containsMatchIn(line)) generate(line) else line
                }
            }
        }
        return parts.toString()
    }
}
