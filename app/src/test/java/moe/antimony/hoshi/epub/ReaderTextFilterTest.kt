package moe.antimony.hoshi.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextFilterTest {
    @Test
    fun koreanRangesMatchWebNormalizationWithoutChangingOtherScripts() {
        // Keep this literal and expected text paired with reader-text-semantics.test.mjs.
        val text = "가힣ㄱㆎ 한글 日本語 Aｚ9、! 𠮟🙂\uABFF\uD7A4\u3130\u318F\u1100\u1161"
        val filtered = text.filteredReaderText()

        assertEquals("가힣ㄱㆎ한글日本語Aｚ9𠮟", filtered)
        assertEquals(13, filtered.codePointCount(0, filtered.length))
        for (codePoint in listOf(0xAC00, 0xD7A3, 0x3131, 0x318E)) {
            assertEquals(true, codePoint.isReaderMatchableCodePoint())
        }
        for (codePoint in listOf(0xABFF, 0xD7A4, 0x3130, 0x318F, 0x1100, 0x1161)) {
            assertEquals(false, codePoint.isReaderMatchableCodePoint())
        }
    }

    @Test
    fun visibleTextExcludesRubyReadingsAndFallbackContents() {
        val html = "<body>가 <ruby>漢<rp class=\"fallback\">fallback</rp>" +
            "<rt>かん</rt><rp>한글</rp></ruby>、𠮟A</body>"

        assertEquals("가 漢、𠮟A", html.visibleReaderText())
        assertEquals("가漢𠮟A", html.filteredReaderText())
    }

    @Test
    fun numericHtmlEntitiesDoNotContributeEncodedDigitsToReaderOffsets() {
        val html = "<html><body>𠮟&#12354;猫&#x3042;犬&#X2000B;A</body></html>"

        val filtered = html.filteredReaderText()

        assertEquals("𠮟猫犬A", filtered)
        assertEquals(4, filtered.codePointCount(0, filtered.length))
    }
}
