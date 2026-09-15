package moe.antimony.hoshi.features.dictionary

internal enum class DictionaryCustomCssResetAction {
    RequestConfirmation,
    Confirm,
    Dismiss,
}

internal data class DictionaryCustomCssResetState(
    val isConfirmationVisible: Boolean = false,
    val shouldClearCss: Boolean = false,
)

internal fun dictionaryCustomCssResetStateAfter(
    state: DictionaryCustomCssResetState,
    action: DictionaryCustomCssResetAction,
): DictionaryCustomCssResetState = when (action) {
    DictionaryCustomCssResetAction.RequestConfirmation -> state.copy(
        isConfirmationVisible = true,
        shouldClearCss = false,
    )
    DictionaryCustomCssResetAction.Confirm -> state.copy(
        isConfirmationVisible = false,
        shouldClearCss = true,
    )
    DictionaryCustomCssResetAction.Dismiss -> state.copy(
        isConfirmationVisible = false,
        shouldClearCss = false,
    )
}
