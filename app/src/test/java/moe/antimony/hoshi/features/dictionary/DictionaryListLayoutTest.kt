package moe.antimony.hoshi.features.dictionary

import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryListLayoutTest {
    @Test
    fun errorMessageDoesNotReserveAListRow() {
        val layout = DictionaryListLayout.from(errorMessage = UiText.Literal("Failed to import"))

        assertEquals(1, layout.dictionaryStartGlobalIndex)
        assertTrue(layout.showErrorDialog)
    }
}
