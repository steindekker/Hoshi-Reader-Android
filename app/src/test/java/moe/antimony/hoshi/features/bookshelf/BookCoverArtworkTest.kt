package moe.antimony.hoshi.features.bookshelf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BookCoverArtworkTest {
    private val source = BookCoverSource(
        path = "/tmp/cover.jpg",
        cacheKey = "cover-key",
    )

    @Test
    fun fallbackHashMatchesIosUtf8Fnv1aValues() {
        assertEquals(0xf7bdcea7c8207cb8uL, coverFallbackHash("Book"))
        assertEquals(0x525ef99fca9dbfcduL, coverFallbackHash("屍人荘の殺人"))
    }

    @Test
    fun showKeepsAvailableCoverWithoutBlur() {
        val artwork = resolveBookCoverArtwork(BookshelfCoverMode.Show, apiLevel = 26, source)

        assertSame(source, artwork.coverSource)
        assertFalse(artwork.blur)
    }

    @Test
    fun hideSuppressesCoverOnEverySupportedApi() {
        listOf(26, 31, 36).forEach { apiLevel ->
            val artwork = resolveBookCoverArtwork(BookshelfCoverMode.Hide, apiLevel, source)

            assertNull(artwork.coverSource)
            assertFalse(artwork.blur)
        }
    }

    @Test
    fun blurSafelyHidesCoverBeforeAndroid12() {
        val artwork = resolveBookCoverArtwork(BookshelfCoverMode.Blur, apiLevel = 30, source)

        assertNull(artwork.coverSource)
        assertFalse(artwork.blur)
    }

    @Test
    fun blurKeepsAndBlursCoverOnAndroid12AndLater() {
        val artwork = resolveBookCoverArtwork(BookshelfCoverMode.Blur, apiLevel = 31, source)

        assertSame(source, artwork.coverSource)
        assertTrue(artwork.blur)
    }

    @Test
    fun missingCoverAlwaysUsesFallbackArtwork() {
        BookshelfCoverMode.entries.forEach { mode ->
            val artwork = resolveBookCoverArtwork(mode, apiLevel = 36, coverSource = null)

            assertNull(artwork.coverSource)
            assertFalse(artwork.blur)
        }
    }
}
