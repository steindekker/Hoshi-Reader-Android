package moe.antimony.hoshi.epub

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EpubBookModelTest {
    @Test
    fun exposesOnlyReaderResourcesNeededByWebView() {
        val css = "body {}".toByteArray()
        val book = EpubBook(
            title = "Title",
            chapters = listOf(
                EpubChapter(
                    id = "reading-order-0",
                    href = "item/xhtml/p-001.xhtml",
                    mediaType = "application/xhtml+xml",
                    html = "<html></html>",
                ),
            ),
            resources = mapOf("item/style/book.css" to EpubResource("text/css", css)),
        )

        assertEquals("Title", book.title)
        assertEquals("item/xhtml/p-001.xhtml", book.chapters.single().href)
        assertArrayEquals(css, book.readResource("/item/style/book.css"))
        assertEquals("text/css", book.mediaType("item/style/book.css"))
        assertNull(book.readResource("missing.css"))
    }
}
