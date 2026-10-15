package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Test

class JrpAlignTest {
    private fun fs(surface: String, reading: String) =
        JrpAlign.furiganaSegments(surface, reading).map { it.text to it.reading }

    @Test
    fun pureKanaIsBare() {
        assertEquals(listOf("それ" to null), fs("それ", "それ"))
    }

    @Test
    fun noKanjiNeverGetsFurigana() {
        assertEquals(listOf("チャイナ" to null), fs("チャイナ", "ちゃいな"))
    }

    @Test
    fun leadingKanjiWithOkurigana() {
        assertEquals(listOf("食" to "た", "べる" to null), fs("食べる", "たべる"))
    }

    @Test
    fun compoundKanjiRun() {
        assertEquals(listOf("気付" to "きづ"), fs("気付", "きづ"))
        assertEquals(listOf("気付" to "きづ", "く" to null), fs("気付く", "きづく"))
    }

    @Test
    fun kanjiKanaKanjiKana() {
        assertEquals(
            listOf("持" to "も", "ち" to null, "掛" to "か", "ける" to null),
            fs("持ち掛ける", "もちかける"),
        )
    }

    @Test
    fun trailingKanjiTakesRest() {
        assertEquals(listOf("お" to null, "茶" to "ちゃ"), fs("お茶", "おちゃ"))
    }

    @Test
    fun mismatchFallsBackToWholeSegment() {
        // reading that cannot be aligned -> single segment, still round-trips
        assertEquals(listOf("大人気" to "だいにんき"), fs("大人気", "だいにんき"))
    }

    @Test
    fun emptyReadingIsBare() {
        assertEquals(listOf("！" to null), fs("！", ""))
    }

    @Test
    fun kanjiWithEmptyReadingFallsBack() {
        // last-run kanji guard: ri=0 >= len("")=0 -> whole-segment fallback
        assertEquals(listOf("食" to ""), fs("食", ""))
    }

    @Test
    fun kanjiKanaWithEmptyReadingFallsBack() {
        // next anchor not found in "" -> fallback
        assertEquals(listOf("食べる" to ""), fs("食べる", ""))
    }

    @Test
    fun kanjiThenKatakanaRun() {
        // katakana run is non-kanji (bare); ー stays consistent on both sides
        assertEquals(listOf("東京" to "とうきょう", "タワー" to null), fs("東京タワー", "とうきょうたわー"))
    }

    @Test
    fun emptySurfaceHasNoSegments() {
        assertEquals(emptyList<Pair<String, String?>>(), fs("", "あ"))
    }

    @Test
    fun katakanaReadingIsNormalizedButFallbackKeepsOriginal() {
        assertEquals(listOf("食" to "た", "べる" to null), fs("食べる", "タベル"))
        assertEquals(listOf("大人気" to "だいにんき"), fs("大人気", "ダイニンキ"))
        assertEquals(listOf("食べる" to "タベ"), fs("食べる", "タベ"))
    }

    @Test
    fun supplementaryPlaneKanjiIsOneKanjiCodePoint() {
        // 𠮷 (U+20BB7) / 𩸽 (U+29E3D) are surrogate pairs in UTF-16 but single kanji
        assertEquals(listOf("𠮷野" to "よしの"), fs("𠮷野", "よしの"))
        assertEquals(listOf("𠮷" to "よし", "い" to null), fs("𠮷い", "よしい"))
        assertEquals(listOf("お" to null, "𩸽" to "ほっけ", "を" to null), fs("お𩸽を", "おほっけを"))
    }
}
