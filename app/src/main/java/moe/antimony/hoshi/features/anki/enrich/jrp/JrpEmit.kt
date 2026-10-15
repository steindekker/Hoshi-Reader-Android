package moe.antimony.hoshi.features.anki.enrich.jrp

/** Render [JrpUnit] lists into jidoujisho/JRP default-syntax markup (port of `jrp_fill/emit.py`). */
internal object JrpEmit {
    private fun furi(surface: String, reading: String): String =
        JrpAlign.furiganaSegments(surface, reading).joinToString("") { (b, r) ->
            if (!r.isNullOrEmpty()) "[$b|$r]" else b
        }

    fun emitUnit(u: JrpUnit): String {
        if (!u.isContent) return u.surface
        // reading == surface -> nothing to annotate (e.g. 何 rendered bare, no furigana)
        val body = if (u.reading == u.surface) u.surface else furi(u.surface, u.reading)
        if (u.accents.isEmpty()) return body
        val flag = (if (u.ambiguous) "!" else "") + (if (u.isYougen) "Y" else "")
        val acc = u.accents.joinToString(",")
        // Conjugated yougen: by default the accent is the dict-form downstep, so give the renderer
        // the dict reading as JRP base (`|わかる`); otherwise it would place the downstep on the
        // inflected reading. When the conjugated downstep has been COMPUTED, it already sits on
        // `reading`, so emit no base.
        val base = if (u.isYougen && !u.conjComputed && u.reading != u.lemmaReading) "|" + u.lemmaReading else ""
        return "{$body;$flag$acc$base}"
    }

    fun emitSentence(units: List<JrpUnit>): String = units.joinToString("") { emitUnit(it) }
}
