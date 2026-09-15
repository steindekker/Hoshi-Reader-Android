package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DictionaryCustomCssResetTest {
    @Test
    fun requestingResetOnlyShowsConfirmation() {
        val state = dictionaryCustomCssResetStateAfter(
            state = DictionaryCustomCssResetState(),
            action = DictionaryCustomCssResetAction.RequestConfirmation,
        )

        assertTrue(state.isConfirmationVisible)
        assertFalse(state.shouldClearCss)
    }

    @Test
    fun dismissingResetKeepsCssAndHidesConfirmation() {
        val state = dictionaryCustomCssResetStateAfter(
            state = DictionaryCustomCssResetState(isConfirmationVisible = true),
            action = DictionaryCustomCssResetAction.Dismiss,
        )

        assertFalse(state.isConfirmationVisible)
        assertFalse(state.shouldClearCss)
    }

    @Test
    fun confirmingResetHidesConfirmationAndClearsCss() {
        val state = dictionaryCustomCssResetStateAfter(
            state = DictionaryCustomCssResetState(isConfirmationVisible = true),
            action = DictionaryCustomCssResetAction.Confirm,
        )

        assertFalse(state.isConfirmationVisible)
        assertTrue(state.shouldClearCss)
    }
}
