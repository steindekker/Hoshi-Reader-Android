package moe.antimony.hoshi.epub

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderTextFilterTest {
    @Test
    fun numericHtmlEntitiesDoNotContributeEncodedDigitsToReaderOffsets() {
        val html = "<html><body>𠮟&#12354;猫&#x3042;犬&#X2000B;A</body></html>"

        val filtered = html.filteredReaderText()

        assertEquals("𠮟猫犬A", filtered)
        assertEquals(4, filtered.codePointCount(0, filtered.length))
    }
}
