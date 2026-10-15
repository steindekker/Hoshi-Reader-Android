package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Test

class JrpSentenceTest {
    // stub generate: valid JRP whose surface reproduces the input
    private val wrap: (String) -> String = { "{$it;0}" }

    @Test
    fun plainSentenceIsGenerated() {
        assertEquals("{異端である;0}", JrpSentence.fill("異端である", wrap))
    }

    @Test
    fun fullyMarkedSentenceIsUnchanged() {
        assertEquals("{[異変|いへん];0}だ", JrpSentence.fill("{[異変|いへん];0}だ", wrap))
    }

    @Test
    fun blankSentenceIsSkipped() {
        var calls = 0
        val counting: (String) -> String = { calls++; it }
        assertEquals("", JrpSentence.fill("", counting))
        assertEquals("  ", JrpSentence.fill("  ", counting))
        assertEquals(0, calls)
    }

    @Test
    fun fixpointConvergesMultipass() {
        // generate that marks one kanji per pass, leaving the rest plain (the real generator does
        // this when it emits a bare kanji that splice then re-fills next pass)
        val gen: (String) -> String = { mapOf("日本" to "{日;1}本", "本" to "{本;1}", "日" to "{日;1}")[it] ?: it }
        assertEquals("{日;1}本", JrpSplice.splice("日本", gen)) // one pass leaves residue
        assertEquals("{日;1}{本;1}", JrpSentence.fill("日本", gen))
    }

    @Test
    fun fixpointStopsAfterSixPasses() {
        // every pass changes the value while keeping the visible surface (trailing spaces vanish)
        assertEquals("日      ", JrpSentence.fill("日") { "$it " })
    }

    @Test
    fun htmlIsStrippedFromSentence() {
        // bold wrapper + a <br>: tags must be stripped (br -> newline), JRP kept
        assertEquals("{[旅路|たびじ];0,2}を\n{進む;0}", JrpSentence.fill("<b>{[旅路|たびじ];0,2}</b>を<br>進む", wrap))
    }

    @Test
    fun subtitleLabelsAreStripped() {
        assertEquals("すいません\n{本当;0}", JrpSentence.fill("（エレン）すいません<br>本当", wrap))
    }

    @Test
    fun fullwidthSpacesAreNormalized() {
        // identity: surface passes through
        assertEquals("今は そげな話は あとじゃ。", JrpSentence.fill("今は　そげな話は　あとじゃ。") { it })
    }

    @Test
    fun htmlOnlyCleanupStillWrites() {
        // fully marked sentence wrapped in <b>: no JRP work, but the tags must go
        assertEquals("{[異変|いへん];0}だ", JrpSentence.fill("<b>{[異変|いへん];0}だ</b>", wrap))
        assertEquals(" ", JrpSentence.fill("<b> </b>", wrap))
    }

    @Test
    fun guardRejectsSurfaceChangingFill() {
        // generate returns markup whose surface differs from the input -> rejected, value untouched
        val bad: (String) -> String = { "{異なる;0}" }
        assertEquals("異端である", JrpSentence.fill("異端である", bad))
        assertEquals("<b>異端</b>", JrpSentence.fill("<b>異端</b>", bad))
    }
}
