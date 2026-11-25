package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.LookupResult
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.ui.UiText

internal data class DictionarySearchUiState(
    val query: String = "",
    val lastQuery: String = "",
    val sentenceOffset: Int? = null,
    val results: List<LookupResult> = emptyList(),
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,
    val errorMessage: UiText? = null,
    val dictionaryStyles: Map<String, String> = emptyMap(),
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val audioSettings: AudioSettings = AudioSettings(),
    val popups: List<LookupPopupItem> = emptyList(),
    val resultClearSelectionSignal: Int = 0,
    val backCount: Int = 0,
    val forwardCount: Int = 0,
    val backSignal: Int = 0,
    val forwardSignal: Int = 0,
) {
    val hasResults: Boolean get() = results.isNotEmpty()
    val hasLookupContent: Boolean get() = hasResults ||
        (hasSearched && !isSearching && lastQuery.isNotBlank() && errorMessage == null)
    val showLookupHistory: Boolean get() = query.isBlank() && lastQuery.isBlank() &&
        !hasResults && popups.isEmpty() && !isSearching && errorMessage == null
}

internal data class DictionarySearchRenderState(
    val lastQuery: String,
    val results: List<LookupResult>,
    val hasResults: Boolean,
    val dictionaryStyles: Map<String, String>,
)

internal object DictionarySearchContent {
    fun runLookup(
        query: String,
        lookup: (String) -> List<LookupResult>,
        dictionaryStyles: Map<String, String> = emptyMap(),
    ): DictionarySearchRenderState {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return DictionarySearchRenderState(
                lastQuery = "",
                results = emptyList(),
                hasResults = false,
                dictionaryStyles = emptyMap(),
            )
        }
        val results = lookup(trimmed)
        return DictionarySearchRenderState(
            lastQuery = trimmed,
            results = results,
            hasResults = results.isNotEmpty(),
            dictionaryStyles = dictionaryStyles,
        )
    }
}
