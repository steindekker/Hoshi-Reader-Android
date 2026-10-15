package moe.antimony.hoshi.features.anki.enrich.jrp

/** One furigana segment: [reading] is null for kana/other runs (rendered bare), hiragana for kanji runs. */
internal data class JrpFuriganaSegment(val text: String, val reading: String?)

/**
 * Align a surface string against its hiragana reading into furigana segments (port of
 * `jrp_fill/align.py`). Any failure to align cleanly returns a single (surface, reading) segment,
 * which always round-trips.
 *
 * Indices are UTF-16 throughout; runs are split on whole code points and [JrpKana.hira] maps BMP
 * characters one-to-one, so every index lands on a code point boundary and the results match
 * Python's code point indexing.
 */
internal object JrpAlign {
    /** Split into maximal (isKanji, text) runs. */
    private fun runs(surface: String): List<Pair<Boolean, String>> {
        val out = mutableListOf<Pair<Boolean, String>>()
        var i = 0
        while (i < surface.length) {
            val end = i + Character.charCount(surface.codePointAt(i))
            val ch = surface.substring(i, end)
            val k = JrpKana.isKanji(ch)
            if (out.isNotEmpty() && out.last().first == k) {
                out[out.lastIndex] = k to out.last().second + ch
            } else {
                out.add(k to ch)
            }
            i = end
        }
        return out
    }

    fun furiganaSegments(surface: String, reading: String): List<JrpFuriganaSegment> {
        if (surface.isEmpty()) return emptyList()
        val runs = runs(surface)
        if (runs.none { it.first }) return listOf(JrpFuriganaSegment(surface, null)) // no kanji -> all bare

        val fallback = listOf(JrpFuriganaSegment(surface, reading))
        val hread = JrpKana.hira(reading)
        val segs = mutableListOf<JrpFuriganaSegment>()
        var ri = 0
        for ((i, run) in runs.withIndex()) {
            val (isK, text) = run
            if (!isK) {
                val anchor = JrpKana.hira(text)
                if (!hread.startsWith(anchor, ri)) return fallback // mismatch -> safe fallback
                segs.add(JrpFuriganaSegment(text, null))
                ri += anchor.length
            } else if (i + 1 < runs.size) {
                // next run is kana: find it to delimit
                val nxt = JrpKana.hira(runs[i + 1].second)
                val j = hread.indexOf(nxt, ri)
                if (j < 0 || j == ri) return fallback
                segs.add(JrpFuriganaSegment(text, hread.substring(ri, j)))
                ri = j
            } else {
                // last run: take the rest
                if (ri >= hread.length) return fallback
                segs.add(JrpFuriganaSegment(text, hread.substring(ri)))
                ri = hread.length
            }
        }
        if (ri != hread.length) return fallback // leftover reading -> fallback
        return segs
    }
}
