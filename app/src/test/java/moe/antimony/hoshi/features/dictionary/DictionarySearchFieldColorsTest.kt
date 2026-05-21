package moe.antimony.hoshi.features.dictionary

import androidx.compose.ui.graphics.Color
import moe.antimony.hoshi.ui.hoshiTextFieldCursorColor
import org.junit.Assert.assertEquals
import org.junit.Test

class DictionarySearchFieldColorsTest {
    @Test
    fun cursorUsesReadableSearchFieldForegroundColor() {
        val darkModeForeground = Color(0xFFECE6F0)

        assertEquals(darkModeForeground, hoshiTextFieldCursorColor(darkModeForeground))
    }

    @Test
    fun searchKeyboardHintsJapaneseInputAndShowsOnFocus() {
        val options = dictionarySearchKeyboardOptions()

        assertEquals("ja-JP", options.hintLocales?.single()?.toLanguageTag())
        assertEquals(true, options.showKeyboardOnFocus)
    }
}
