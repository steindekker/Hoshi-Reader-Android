package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Test

class JrpEmitTest {
    private fun u(
        surface: String = "",
        lemma: String = "",
        reading: String = "",
        lemmaReading: String = "",
        isContent: Boolean = true,
        isYougen: Boolean = false,
        accents: List<Int> = emptyList(),
        ambiguous: Boolean = false,
        conjComputed: Boolean = false,
    ) = JrpUnit(surface, lemma, reading, lemmaReading, isContent, isYougen, accents, ambiguous, conjComputed)

    @Test
    fun bareParticle() {
        assertEquals("は", JrpEmit.emitUnit(u(surface = "は", reading = "は", isContent = false)))
    }

    @Test
    fun kanjiNoAccentGetsFuriganaOnly() {
        val unit = u(surface = "異端", reading = "いたん", lemma = "異端", lemmaReading = "いたん")
        assertEquals("[異端|いたん]", JrpEmit.emitUnit(unit))
    }

    @Test
    fun kanjiWithAccentIsWrapped() {
        val unit = u(surface = "異変", reading = "いへん", lemma = "異変", lemmaReading = "いへん", accents = listOf(0))
        assertEquals("{[異変|いへん];0}", JrpEmit.emitUnit(unit))
    }

    @Test
    fun yougenFlagAndOkurigana() {
        // conjugated yougen -> dict-form base reading after the accent so the graph places the
        // downstep on the lemma, not the inflected reading
        val unit = u(
            surface = "気付いた", reading = "きづいた", lemma = "気付く",
            lemmaReading = "きづく", isYougen = true, accents = listOf(2),
        )
        assertEquals("{[気付|きづ]いた;Y2|きづく}", JrpEmit.emitUnit(unit))
    }

    @Test
    fun yougenDictionaryFormNoBase() {
        // reading == lemmaReading (dict form) -> no redundant base
        val unit = u(
            surface = "届く", reading = "とどく", lemma = "届く",
            lemmaReading = "とどく", isYougen = true, accents = listOf(2),
        )
        assertEquals("{[届|とど]く;Y2}", JrpEmit.emitUnit(unit))
    }

    @Test
    fun conjComputedYougenEmitsNoBase() {
        val unit = u(
            surface = "気付いた", reading = "きづいた", lemma = "気付く",
            lemmaReading = "きづく", isYougen = true, accents = listOf(3), conjComputed = true,
        )
        assertEquals("{[気付|きづ]いた;Y3}", JrpEmit.emitUnit(unit))
    }

    @Test
    fun ambiguousFlagPrecedesYougenFlag() {
        val unit = u(
            surface = "分かる", reading = "わかる", lemma = "分かる",
            lemmaReading = "わかる", isYougen = true, accents = listOf(2, 0), ambiguous = true,
        )
        assertEquals("{[分|わ]かる;!Y2,0}", JrpEmit.emitUnit(unit))
    }

    @Test
    fun multipleAccents() {
        val unit = u(surface = "世界", reading = "せかい", lemma = "世界", lemmaReading = "せかい", accents = listOf(1, 2))
        assertEquals("{[世界|せかい];1,2}", JrpEmit.emitUnit(unit))
    }

    @Test
    fun kanaContentWithAccent() {
        val unit = u(surface = "それ", reading = "それ", lemma = "それ", lemmaReading = "それ", accents = listOf(1))
        assertEquals("{それ;1}", JrpEmit.emitUnit(unit))
    }

    @Test
    fun readingEqualToSurfaceIsRenderedBare() {
        // e.g. 何 rendered bare, no furigana
        val unit = u(surface = "何", reading = "何", lemma = "何", lemmaReading = "なに")
        assertEquals("何", JrpEmit.emitUnit(unit))
    }

    @Test
    fun emptyReadingFallbackIsBare() {
        assertEquals("{食;0}", JrpEmit.emitUnit(u(surface = "食", reading = "", accents = listOf(0))))
    }

    @Test
    fun emitSentenceRoundtripsSurface() {
        val units = listOf(
            u(surface = "異変", reading = "いへん", lemma = "異変", lemmaReading = "いへん", accents = listOf(0)),
            u(surface = "に", reading = "に", isContent = false),
            u(
                surface = "気付いた", reading = "きづいた", lemma = "気付く",
                lemmaReading = "きづく", isYougen = true, accents = listOf(2),
            ),
        )
        val out = JrpEmit.emitSentence(units)
        assertEquals("{[異変|いへん];0}に{[気付|きづ]いた;Y2|きづく}", out)
        assertEquals("異変に気付いた", JrpMarkup.jrpSurface(out))
    }
}
