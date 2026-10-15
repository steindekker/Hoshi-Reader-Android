package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Test

class JrpSpliceTest {
    // stub generator: wraps kanji-bearing runs distinctly
    private val gen: (String) -> String = { "<<$it>>" }

    @Test
    fun fullyPlainIsOneRun() {
        assertEquals("<<異端である>>", JrpSplice.splice("異端である", gen))
    }

    @Test
    fun kanaOnlyRunIsUntouched() {
        assertEquals("そうだね", JrpSplice.splice("そうだね", gen))
    }

    @Test
    fun existingUnitIsPreserved() {
        // the whole plain run に気付いた (it contains kanji) goes to generate; the real generate
        // keeps に bare via tokenization.
        assertEquals("{[異変|いへん];0}<<に気付いた>>", JrpSplice.splice("{[異変|いへん];0}に気付いた", gen))
    }

    @Test
    fun bracketSegmentIsPreserved() {
        assertEquals("[気付|きづ]<<いて異端>>", JrpSplice.splice("[気付|きづ]いて異端", gen))
    }

    @Test
    fun fullyMarkedIsNoop() {
        val s = "{[異変|いへん];0}に{[気付|きづ]いた;Y2}"
        assertEquals(s, JrpSplice.splice(s, gen))
    }

    @Test
    fun newlineIsAGenerateBoundary() {
        // each line is generated on its own, so generate never sees the newline char
        assertEquals("<<異端>>\n<<本当>>", JrpSplice.splice("異端\n本当", gen))
        assertEquals("\n<<異端>>\nかな\n", JrpSplice.splice("\n異端\nかな\n", gen))
    }

    @Test
    fun unclosedBracketIsPlainText() {
        assertEquals("<<{異端>>", JrpSplice.splice("{異端", gen))
        assertEquals("[気付|きづ]<<[異端>>", JrpSplice.splice("[気付|きづ][異端", gen))
    }

    @Test
    fun supplementaryKanjiTriggersGenerate() {
        assertEquals("<<𠮷さん>>", JrpSplice.splice("𠮷さん", gen))
    }

    @Test
    fun stoplistWordInExistingMarkupIsDepitched() {
        // pre-existing jidoujisho pitch on a stoplist word is demoted to furigana-only
        assertEquals("それから", JrpSplice.splice("{それ;1}から", gen))
        assertEquals("[時|とき]<<が来る>>", JrpSplice.splice("{[時|とき];0}が来る", gen)) // 時=とき dropped
        // but a non-stoplist unit is preserved, and the 三時(じ) counter stays pitched
        assertEquals("{[異変|いへん];0}", JrpSplice.splice("{[異変|いへん];0}", gen))
        assertEquals("{[時|じ];1}", JrpSplice.splice("{[時|じ];1}", gen))
    }

    @Test
    fun unitWithoutAccentIsKeptVerbatim() {
        assertEquals("{それ}", JrpSplice.splice("{それ}", gen))
    }
}
