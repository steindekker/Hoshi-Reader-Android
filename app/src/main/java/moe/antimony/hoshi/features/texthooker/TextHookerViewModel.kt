package moe.antimony.hoshi.features.texthooker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.manhhao.hoshi.KanjiResult
import de.manhhao.hoshi.LookupResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.anki.AnkiMiningContext
import moe.antimony.hoshi.features.anki.AnkiScreenshotRequest
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.DictionarySearchRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import moe.antimony.hoshi.features.dictionary.lookupOptions
import moe.antimony.hoshi.features.reader.ReaderSelectionData
import moe.antimony.hoshi.ui.UiText

internal const val TextHookerRootPopupIdPrefix = "texthooker-root-"

internal fun textHookerRootPopupId(lineId: Long): String = "$TextHookerRootPopupIdPrefix$lineId"

internal fun isTextHookerRootPopupId(popupId: String): Boolean = popupId.startsWith(TextHookerRootPopupIdPrefix)

/** Root lookup state for the displayed line (embedded results under the tappable sentence). */
internal data class TextHookerLookupState(
    val results: List<LookupResult> = emptyList(),
    val sentenceOffset: Int? = null,
    val backCount: Int = 0,
    val forwardCount: Int = 0,
    /** Recursive popups opened from the root results. */
    val popups: List<LookupPopupItem> = emptyList(),
    val clearSelectionSignal: Int = 0,
) {
    val isActive: Boolean get() = results.isNotEmpty() || popups.isNotEmpty()
}

internal sealed interface TextHookerScreenshotPreviewState {
    data object Idle : TextHookerScreenshotPreviewState

    data class Loading(val lineId: Long?) : TextHookerScreenshotPreviewState

    data class Ready(val lineId: Long?, val path: String) : TextHookerScreenshotPreviewState

    data class Failed(val lineId: Long?) : TextHookerScreenshotPreviewState
}

internal data class TextHookerUiState(
    val settings: TextHookerSettings = TextHookerSettings(),
    val connection: TextHookerConnectionState = TextHookerConnectionState.Idle,
    val serverStatus: TextHookerServerStatus? = null,
    val lines: List<TextHookerLine> = emptyList(),
    val selection: TextHookerLineSelection = TextHookerLineSelection(),
    val lookup: TextHookerLookupState = TextHookerLookupState(),
    val dictionaryStyles: Map<String, String> = emptyMap(),
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val audioSettings: AudioSettings = AudioSettings(),
    val screenshotPreview: TextHookerScreenshotPreviewState = TextHookerScreenshotPreviewState.Idle,
    val message: UiText? = null,
) {
    val displayedLine: TextHookerLine? get() = selection.displayedLine
    val hasNewerLine: Boolean get() = selection.hasNewerLine(lines)
    val rootPopupId: String? get() = displayedLine?.let { textHookerRootPopupId(it.id) }

    /** `{document-title}` for VN cards: the Deck's reported hostname, else the configured host. */
    val documentTitle: String
        get() = serverStatus?.hostname?.takeIf { it.isNotBlank() } ?: settings.host.trim()
}

private data class TextHookerLocalState(
    val selection: TextHookerLineSelection = TextHookerLineSelection(),
    val lookup: TextHookerLookupState = TextHookerLookupState(),
    val dictionaryStyles: Map<String, String> = emptyMap(),
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val audioSettings: AudioSettings = AudioSettings(),
    val screenshotPreview: TextHookerScreenshotPreviewState = TextHookerScreenshotPreviewState.Idle,
    val message: UiText? = null,
)

@HiltViewModel
internal class TextHookerViewModel internal constructor(
    private val repository: TextHookerRepository,
    private val lookupRepository: DictionarySearchRepository,
    private val ioDispatcher: CoroutineDispatcher,
    private val injectedScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        repository: TextHookerRepository,
        lookupRepository: DictionarySearchRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(repository, lookupRepository, ioDispatcher, null)

    private val scope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    private val local = MutableStateFlow(TextHookerLocalState())
    private var observedProfileId: String? = null
    private var screenshotJob: Job? = null

    init {
        collectSettings()
    }

    /** Collecting this (while the VN tab is visible) is what keeps the Deck connection open. */
    val uiState: StateFlow<TextHookerUiState> = combine(
        repository.state.onEach { state -> reconcile(state.lines) },
        local,
    ) { remote, local ->
        TextHookerUiState(
            settings = remote.settings,
            connection = remote.connection,
            serverStatus = remote.serverStatus,
            lines = remote.lines,
            selection = local.selection,
            lookup = local.lookup,
            dictionaryStyles = local.dictionaryStyles,
            dictionarySettings = local.dictionarySettings,
            audioSettings = local.audioSettings,
            screenshotPreview = local.screenshotPreview,
            message = local.message,
        )
    }.stateIn(scope, SharingStarted.WhileSubscribed(TextHookerRepository.StopTimeoutMillis), TextHookerUiState())

    private fun collectSettings() {
        scope.launch {
            withContext(ioDispatcher) { runCatching { lookupRepository.rebuildLookupQuery() } }
        }
        scope.launch {
            lookupRepository.dictionarySettings.collect { settings ->
                local.update { it.copy(dictionarySettings = settings) }
            }
        }
        scope.launch {
            lookupRepository.audioSettings.collect { settings ->
                local.update { it.copy(audioSettings = settings) }
            }
        }
    }

    private fun reconcile(lines: List<TextHookerLine>) {
        local.update { state ->
            state.withSelection(state.selection.reconcile(lines, state.lookup.isActive))
        }
    }

    private fun TextHookerLocalState.withSelection(next: TextHookerLineSelection): TextHookerLocalState =
        if (next.displayedLine?.id == selection.displayedLine?.id) {
            copy(selection = next)
        } else {
            copy(selection = next, lookup = TextHookerLookupState())
        }

    private val lines: List<TextHookerLine> get() = repository.state.value.lines

    fun selectLine(lineId: Long) {
        val line = lines.firstOrNull { it.id == lineId } ?: return
        local.update { it.withSelection(it.selection.select(line, lines)).copy(lookup = TextHookerLookupState()) }
    }

    fun jumpToLatest() {
        local.update { it.withSelection(it.selection.jumpToLatest(lines)).copy(lookup = TextHookerLookupState()) }
    }

    fun retryConnection() = repository.retryNow()

    fun onEffectiveProfileChanged(profileId: String) {
        val previous = observedProfileId
        observedProfileId = profileId
        if (previous == null || previous == profileId) return
        local.update { it.copy(lookup = TextHookerLookupState(), dictionaryStyles = emptyMap()) }
        scope.launch {
            withContext(ioDispatcher) { runCatching { lookupRepository.rebuildLookupQuery() } }
        }
    }

    /** Looks up a suffix of the displayed line from a source-text tap; returns the result count. */
    fun lookupRootRedirect(query: String): List<LookupResult> {
        val line = local.value.selection.displayedLine ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val settings = local.value.dictionarySettings.normalized()
        val results = runCatching {
            lookupRepository.lookup(query, settings.maxResults, settings.scanLength, settings.lookupOptions())
        }.getOrElse { return emptyList() }
        if (results.isNotEmpty()) {
            val styles = local.value.dictionaryStyles.ifEmpty { runCatching { lookupRepository.dictionaryStyles() }.getOrDefault(emptyMap()) }
            local.update { state ->
                state.copy(
                    dictionaryStyles = styles,
                    lookup = state.lookup.copy(
                        results = results,
                        sentenceOffset = if (line.text.endsWith(query)) line.text.length - query.length else null,
                        popups = emptyList(),
                        backCount = state.lookup.backCount + 1,
                        forwardCount = 0,
                    ),
                )
            }
        }
        return results
    }

    fun lookupRedirect(query: String): List<LookupResult> {
        val settings = local.value.dictionarySettings.normalized()
        return runCatching {
            lookupRepository.lookup(query, settings.maxResults, settings.scanLength, settings.lookupOptions())
        }.getOrDefault(emptyList())
    }

    fun lookupKanji(kanji: String): KanjiResult = lookupRepository.lookupKanji(kanji)

    fun recordRootRedirected() {
        updateLookup { it.copy(backCount = it.backCount + 1, forwardCount = 0) }
    }

    fun navigateRootBack() {
        updateLookup {
            if (it.backCount <= 0) it else it.copy(backCount = it.backCount - 1, forwardCount = it.forwardCount + 1)
        }
    }

    fun navigateRootForward() {
        updateLookup {
            if (it.forwardCount <= 0) it else it.copy(backCount = it.backCount + 1, forwardCount = it.forwardCount - 1)
        }
    }

    fun restoreRootSourceHistory(sentenceOffset: Int?) {
        val text = local.value.selection.displayedLine?.text ?: return
        if (sentenceOffset != null && sentenceOffset !in 0..text.length) return
        updateLookup { it.copy(sentenceOffset = sentenceOffset) }
    }

    fun entryForPopup(popupId: String, index: Int): LookupResult? {
        if (index < 0) return null
        val lookup = local.value.lookup
        return if (isTextHookerRootPopupId(popupId)) {
            lookup.results.getOrNull(index)
        } else {
            lookup.popups.firstOrNull { it.id == popupId }?.state?.results?.getOrNull(index)
        }
    }

    fun createPopup(selection: ReaderSelectionData, options: LookupPopupOptions): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            options = options,
            dictionaryStyles = local.value.dictionaryStyles,
            lookup = { text, maxResults, scanLength ->
                lookupRepository.lookup(text, maxResults, scanLength, options.dictionarySettings.lookupOptions())
            },
        )

    /** Opens a recursive popup for a word tapped inside the root results; returns the highlight length. */
    fun openRootPopup(selection: ReaderSelectionData, options: LookupPopupOptions): Int? {
        val (popup, highlightCount) = createPopup(selection, options) ?: run {
            setPopups(emptyList())
            return null
        }
        setPopups(listOf(popup))
        return highlightCount
    }

    fun setPopups(popups: List<LookupPopupItem>) {
        updateLookup { it.copy(popups = popups) }
    }

    fun dismissRootPopup() {
        updateLookup { it.copy(popups = emptyList(), clearSelectionSignal = it.clearSelectionSignal + 1) }
    }

    /** Mining context for the game sentence: carries the line's screenshot request for `{image}`. */
    fun rootMiningContext(): AnkiMiningContext? {
        val state = uiState.value
        val line = local.value.selection.displayedLine ?: return null
        return AnkiMiningContext(
            sentence = line.text,
            sentenceOffset = local.value.lookup.sentenceOffset,
            documentTitle = state.documentTitle.ifBlank { null },
            screenshot = AnkiScreenshotRequest(lineId = line.id),
        )
    }

    /** Fetches a preview of the Deck screenshot for the mining sheet. */
    fun requestScreenshotPreview(lineId: Long?) {
        screenshotJob?.cancel()
        local.update { it.copy(screenshotPreview = TextHookerScreenshotPreviewState.Loading(lineId)) }
        screenshotJob = scope.launch {
            val next = when (val result = repository.fetchScreenshotPreview(lineId)) {
                is TextHookerScreenshotPreviewResult.Ready -> TextHookerScreenshotPreviewState.Ready(lineId, result.path)
                is TextHookerScreenshotPreviewResult.Failed -> {
                    showMessage(result.message)
                    TextHookerScreenshotPreviewState.Failed(lineId)
                }
            }
            local.update { it.copy(screenshotPreview = next) }
        }
    }

    fun clearScreenshotPreview() {
        screenshotJob?.cancel()
        local.update { it.copy(screenshotPreview = TextHookerScreenshotPreviewState.Idle) }
    }

    fun showMessage(message: UiText) {
        local.update { it.copy(message = message) }
    }

    fun consumeMessage(message: UiText) {
        local.update { if (it.message == message) it.copy(message = null) else it }
    }

    private fun updateLookup(transform: (TextHookerLookupState) -> TextHookerLookupState) {
        local.update { it.copy(lookup = transform(it.lookup)) }
    }
}
