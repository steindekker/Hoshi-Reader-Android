package moe.antimony.hoshi.features.bookshelf

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange
import moe.antimony.hoshi.ui.replaceTextAndSelectStart
import org.junit.Assert.assertEquals
import org.junit.Test

class BookshelfRenameTextFieldTest {
    @Test
    fun resetRenameTextFieldKeepsCursorAtTitleStart() {
        val state = TextFieldState("Previous title", TextRange("Previous title".length))
        val title = "A very long title that extends beyond the rename field width"

        state.replaceTextAndSelectStart(title)

        assertEquals(title, state.text.toString())
        assertEquals(TextRange.Zero, state.selection)
    }
}
