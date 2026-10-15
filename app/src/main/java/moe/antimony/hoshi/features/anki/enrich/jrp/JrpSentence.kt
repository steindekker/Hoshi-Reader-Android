package moe.antimony.hoshi.features.anki.enrich.jrp

/**
 * JRP fill of one Sentence field value: the single-field equivalent of `jrp_fill/engine.py`'s
 * splice path (`apply_to_fixpoint(regenerate=False)`), which the nightly server ran on every
 * freshly mined card.
 */
internal object JrpSentence {
    private const val MAX_PASSES = 6

    private fun norm(s: String): String =
        s.replace("<br>", " ").replace("<br/>", " ").replace("\n", " ").replace("　", " ")

    /** One `build_plan` step: the new value, or null when the note would not be written. */
    private fun pass(old: String, generate: (String) -> String): String? {
        if (JrpMarkup.pyStrip(old).isEmpty()) return null
        // strip presentational HTML first (<br> -> newline) so JRP markup is the only structure
        // the generator/splice and the guard ever see.
        val cleaned = JrpMarkup.normalizeSpaces(
            JrpMarkup.stripSubtitleLabels(JrpMarkup.stripSentenceHtml(old)),
        )
        val new = JrpSplice.splice(cleaned, generate)
        if (new == old) return null // nothing to write (incl. no HTML to strip)
        // round-trip guard: visible surface must be unchanged by the markup work
        if (norm(JrpMarkup.jrpSurface(new)) != norm(JrpMarkup.jrpSurface(cleaned))) return null
        return new
    }

    /**
     * Plan + apply repeatedly until the value stops changing (or [MAX_PASSES]). A single splice
     * pass isn't a fixpoint: `generate` can emit a bare kanji that splice re-fills next pass.
     */
    fun fill(sentence: String, generate: (String) -> String): String {
        var current = sentence
        repeat(MAX_PASSES) {
            current = pass(current, generate) ?: return current
        }
        return current
    }
}
