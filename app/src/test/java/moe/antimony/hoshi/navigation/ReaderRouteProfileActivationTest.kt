package moe.antimony.hoshi.navigation

import java.io.File
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReaderRouteProfileActivationTest {
    @Test
    fun readyReaderLoadActivatesBookProfile() {
        var activatedMetadata: BookMetadata? = null
        var clearCount = 0

        readerReadyState(profileId = "profile-en").publishProfileActivation(
            activateForBook = { metadata -> activatedMetadata = metadata },
            clearLoadedProfile = { clearCount += 1 },
        )

        assertEquals("profile-en", activatedMetadata?.profileId)
        assertEquals(0, clearCount)
    }

    @Test
    fun readerLoadErrorClearsLoadedProfile() {
        var activatedMetadata: BookMetadata? = null
        var clearCount = 0

        ReaderRouteLoadState.Error("Book not found.").publishProfileActivation(
            activateForBook = { metadata -> activatedMetadata = metadata },
            clearLoadedProfile = { clearCount += 1 },
        )

        assertNull(activatedMetadata)
        assertEquals(1, clearCount)
    }

    private fun readerReadyState(profileId: String): ReaderRouteLoadState.Ready {
        val metadata = BookMetadata(
            id = "book-a",
            title = "Book A",
            cover = null,
            folder = "book-a",
            lastAccess = 0.0,
            profileId = profileId,
        )
        return ReaderRouteLoadState.Ready(
            entry = BookEntry(root = File("book-a"), metadata = metadata),
            bookRoot = File("book-a"),
            book = EpubBook(
                title = "Book A",
                chapters = listOf(
                    EpubChapter(
                        id = "chapter-1",
                        href = "chapter-1.xhtml",
                        mediaType = "application/xhtml+xml",
                        html = "<p>Book A</p>",
                    ),
                ),
            ),
            bookCoverFile = null,
            bookmark = null,
        )
    }
}
