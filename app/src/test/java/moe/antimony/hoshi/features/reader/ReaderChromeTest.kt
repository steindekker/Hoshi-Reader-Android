package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderChromeTest {
    @Test
    fun formatsProgressLikeIosReaderOverlay() {
        val text = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        ).progressText(ReaderSettings())

        assertEquals("355 / 169325 0.21%", text)
    }

    @Test
    fun hidesProgressPiecesFromAppearanceSettings() {
        val state = ReaderChromeState(
            title = "屍人荘の殺人",
            currentCharacter = 355,
            totalCharacters = 169325,
        )

        assertEquals("0.21%", state.progressText(ReaderSettings(showCharacters = false)))
        assertEquals("355 / 169325", state.progressText(ReaderSettings(showPercentage = false)))
        assertEquals("", state.progressText(ReaderSettings(showCharacters = false, showPercentage = false)))
    }

    @Test
    fun usesThemeMatchedChromeColors() {
        assertEquals(0x40FFFFFFL, readerChromeColors(ReaderSettings(theme = ReaderTheme.Sepia)).buttonContainer)
        assertEquals(0x661A1A1AL, readerChromeColors(ReaderSettings(theme = ReaderTheme.Dark)).buttonContainer)
    }
}
