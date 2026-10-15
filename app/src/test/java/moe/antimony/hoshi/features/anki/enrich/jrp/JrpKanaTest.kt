package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [JrpKana.hira] to `jaconv.kata2hira`: the output feeds pitch lookups and furigana
 * alignment string-equality.
 */
class JrpKanaTest {
    @Test
    fun basicKatakanaToHiragana() {
        assertEquals("こーひー", JrpKana.hira("コーヒー")) // ー passes through
        assertEquals("じぇっと", JrpKana.hira("ジェット"))
        assertEquals("ゔぁいおりん", JrpKana.hira("ヴァイオリン"))
    }

    @Test
    fun passthroughForNonKatakana() {
        assertEquals("ねこ漢字ABC・", JrpKana.hira("ねこ漢字ABC・"))
    }

    @Test
    fun matchesShiftedTableExceptIterationMarks() {
        // U+30A1..U+30F6 map to cp-0x60 and everything else in the block passes through, except
        // the katakana iteration marks ヽヾ, which fold to ゝゞ (ヷヸヹヺ are left alone).
        val expectedDiffs = mapOf('ヽ' to 'ゝ', 'ヾ' to 'ゞ')
        for (c in 0x3000 until 0x3100) {
            val ch = c.toChar()
            val shifted = if (c in 0x30A1..0x30F6) (c - 0x60).toChar() else ch
            val expected = expectedDiffs[ch] ?: shifted
            assertEquals("divergence at ${Integer.toHexString(c)}", expected.toString(), JrpKana.hira(ch.toString()))
        }
    }

    @Test
    fun halfWidthKatakanaPassesThrough() {
        assertEquals("ｶﾀｶﾅ", JrpKana.hira("ｶﾀｶﾅ"))
    }

    @Test
    fun kanjiClassCoversSupplementaryPlanes() {
        fun cp(c: Int) = String(Character.toChars(c))
        assertTrue(JrpKana.isKanji("漢"))
        assertTrue(JrpKana.isKanji("々"))
        assertTrue(JrpKana.isKanji(cp(0x20BB7))) // 𠮷
        assertTrue(JrpKana.isKanji(cp(0x2A6DF)))
        assertFalse(JrpKana.isKanji(cp(0x2A6E0)))
        assertTrue(JrpKana.isKanji(cp(0x2A700)))
        assertTrue(JrpKana.isKanji(cp(0x2EBEF)))
        assertFalse(JrpKana.isKanji(cp(0x2EBF0)))
        assertFalse(JrpKana.isKanji("か"))
        assertFalse(JrpKana.isKanji("〇"))
    }

    @Test
    fun kanjiClassSpansU3400ToUFaffLikePython() {
        // markup.KANJI_RE's second range starts at U+8C48, so Hangul and private use match too
        assertTrue(JrpKana.isKanji("\u3400"))
        assertTrue(JrpKana.isKanji("\uA000")) // Yi
        assertTrue(JrpKana.isKanji("가"))
        assertTrue(JrpKana.isKanji("\uE09A"))
        assertTrue(JrpKana.isKanji("\uFAFF"))
        assertFalse(JrpKana.isKanji("\u33FF"))
        assertFalse(JrpKana.isKanji("\uFB00"))
    }
}
