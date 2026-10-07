package moe.antimony.hoshi.features.live

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import de.manhhao.hoshi.KanjiResult
import de.manhhao.hoshi.LookupResult
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.anki.AnkiLiveCueRef
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

internal const val LiveRootPopupIdPrefix = "live-root-"

internal fun liveRootPopupId(sessionKey: String, cueId: Long): String =
    "$LiveRootPopupIdPrefix${sessionKey.hashCode().toUInt()}-$cueId"

internal fun isLiveRootPopupId(popupId: String): Boolean = popupId.startsWith(LiveRootPopupIdPrefix)

/** Root lookup state for the focused cue (embedded results under the tappable sentence). */
internal data class LiveLookupState(
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

internal sealed interface LivePreviewState {
    data object Idle : LivePreviewState

    data object Loading : LivePreviewState

    data class Ready(val path: String) : LivePreviewState

    data object Failed : LivePreviewState
}

/**
 * A card was just added from Live. [id] is unique per mine so repeated mines of the same term
 * restart the celebration; [count] is the number of cards added since the Live screen opened.
 */
internal data class LiveCelebration(
    val id: Long,
    val term: String,
    val count: Int,
)

internal data class LiveUiState(
    val settings: LiveSettings = LiveSettings(),
    val hosts: List<LiveHostState> = emptyList(),
    /** All sessions across hosts, most recently active first. */
    val sessions: List<LiveSessionState> = emptyList(),
    val activeSession: LiveSessionState? = null,
    /** The user picked [activeSession] instead of following the most recent one. */
    val sessionPinned: Boolean = false,
    val playingCueId: Long? = null,
    val focus: LiveFocus = LiveFocus(),
    val lookup: LiveLookupState = LiveLookupState(),
    val dictionaryStyles: Map<String, String> = emptyMap(),
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val audioSettings: AudioSettings = AudioSettings(),
    val preview: LivePreviewState = LivePreviewState.Idle,
    val message: UiText? = null,
    val celebration: LiveCelebration? = null,
) {
    val focusedCue: LiveCue? get() = focus.cueId?.let { id -> activeSession?.cues?.firstOrNull { it.id == id } }
    val showBackToPlaying: Boolean get() = playingCueId != null && focusedCue != null && focusedCue?.id != playingCueId
    val rootPopupId: String? get() = activeSession?.let { session -> focusedCue?.let { liveRootPopupId(session.key, it.id) } }
    val canControl: Boolean get() = activeSession?.info?.has(LiveCapability.Control) == true
    val isPlaying: Boolean get() = activeSession?.playback?.paused == false

    fun hostName(hostId: String): String =
        hosts.firstOrNull { it.host.id == hostId }?.displayName ?: settings.host(hostId)?.displayName.orEmpty()
}

private data class LiveLocalState(
    val pinnedSessionKey: String? = null,
    val playingCueId: Long? = null,
    val focus: LiveFocus = LiveFocus(),
    val lookup: LiveLookupState = LiveLookupState(),
    val dictionaryStyles: Map<String, String> = emptyMap(),
    val dictionarySettings: DictionarySettings = DictionarySettings(),
    val audioSettings: AudioSettings = AudioSettings(),
    val preview: LivePreviewState = LivePreviewState.Idle,
    /** Snackbar queue; consecutive duplicates are collapsed so the head always changes on consume. */
    val messages: List<UiText> = emptyList(),
    val celebration: LiveCelebration? = null,
    val minedCount: Int = 0,
)

@HiltViewModel
internal class LiveViewModel internal constructor(
    private val repository: LiveRepository,
    private val lookupRepository: DictionarySearchRepository,
    private val ioDispatcher: CoroutineDispatcher,
    private val injectedScope: CoroutineScope?,
    private val clock: LiveClock,
) : ViewModel() {
    @Inject
    constructor(
        repository: LiveRepository,
        lookupRepository: DictionarySearchRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(repository, lookupRepository, ioDispatcher, null, LiveClock.System)

    private val scope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    private val local = MutableStateFlow(LiveLocalState())
    private val ticks = MutableStateFlow(0L)
    private var observedProfileId: String? = null
    private var previewJob: Job? = null

    init {
        collectSettings()
    }

    /** Collecting this (while the Live tab is visible) is what keeps the bridge connections open. */
    val uiState: StateFlow<LiveUiState> = channelFlow {
        // Playback is extrapolated locally, so the playing cue is re-evaluated a few times a second.
        launch {
            while (true) {
                delay(TickMillis)
                ticks.value = clock.elapsedMillis()
            }
        }
        launch { combine(repository.state, ticks) { remote, _ -> remote }.collect(::reconcile) }
        combine(repository.state, local) { remote, local -> buildState(remote, local) }.collect { send(it) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(LiveRepository.StopTimeoutMillis), LiveUiState())

    private fun buildState(remote: LiveState, local: LiveLocalState): LiveUiState {
        val sessions = remote.sessions.sortedByDescending { it.activityAt }
        val active = liveActiveSession(sessions, local.pinnedSessionKey)
        return LiveUiState(
            settings = remote.settings,
            hosts = remote.hosts,
            sessions = sessions,
            activeSession = active,
            sessionPinned = local.pinnedSessionKey != null && active?.key == local.pinnedSessionKey,
            playingCueId = local.playingCueId,
            focus = local.focus,
            lookup = local.lookup,
            dictionaryStyles = local.dictionaryStyles,
            dictionarySettings = local.dictionarySettings,
            audioSettings = local.audioSettings,
            preview = local.preview,
            message = local.messages.firstOrNull(),
            celebration = local.celebration,
        )
    }

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

    private fun activeSession(remote: LiveState = repository.state.value, state: LiveLocalState = local.value): LiveSessionState? =
        liveActiveSession(remote.sessions, state.pinnedSessionKey)

    private fun reconcile(remote: LiveState) {
        local.update { state ->
            val session = activeSession(remote, state)
            val playing = livePlayingCueId(session, clock.elapsedMillis())
            state.withFocus(state.focus.reconcile(session, playing, state.lookup.isActive)).copy(playingCueId = playing)
        }
    }

    /** A different focused cue (or session) starts a fresh lookup. */
    private fun LiveLocalState.withFocus(next: LiveFocus): LiveLocalState =
        if (next.cueId == focus.cueId && next.sessionKey == focus.sessionKey) {
            copy(focus = next)
        } else {
            copy(focus = next, lookup = LiveLookupState())
        }

    fun selectSession(sessionKey: String?) {
        local.update { it.copy(pinnedSessionKey = sessionKey) }
        reconcile(repository.state.value)
    }

    fun focusCue(cueId: Long) {
        local.update { state ->
            state.withFocus(state.focus.select(cueId, state.playingCueId)).copy(lookup = LiveLookupState())
        }
    }

    fun backToPlaying() {
        local.update { state ->
            state.withFocus(state.focus.backToPlaying(state.playingCueId)).copy(lookup = LiveLookupState())
        }
    }

    fun retryConnection() = repository.retryNow()

    fun togglePlayback() {
        val session = activeSession() ?: return
        if (!session.info.has(LiveCapability.Control)) return
        val action = if (session.playback?.paused == false) "pause" else "play"
        sendControl(session.key, action, null)
    }

    /** Long-press on a transcript timestamp: seek the player to the cue. */
    fun seekToCue(cueId: Long) {
        val session = activeSession() ?: return
        if (!session.info.has(LiveCapability.Control)) return
        val start = session.cues.firstOrNull { it.id == cueId }?.startMs ?: return
        sendControl(session.key, "seek", start)
    }

    private fun sendControl(sessionKey: String, action: String, positionMs: Long?) {
        scope.launch {
            val result = repository.control(sessionKey, action, positionMs)
            if (result is LiveCommandResult.Failure) showMessage(result.message)
        }
    }

    /** Opening a lookup pauses a playing, controllable session (setting, default on). */
    private fun autoPause() {
        val session = activeSession() ?: return
        if (!repository.state.value.settings.autoPause) return
        if (!session.info.has(LiveCapability.Control) || session.playback?.paused != false) return
        sendControl(session.key, "pause", null)
    }

    fun onEffectiveProfileChanged(profileId: String) {
        val previous = observedProfileId
        observedProfileId = profileId
        if (previous == null || previous == profileId) return
        local.update { it.copy(lookup = LiveLookupState(), dictionaryStyles = emptyMap()) }
        scope.launch {
            withContext(ioDispatcher) { runCatching { lookupRepository.rebuildLookupQuery() } }
        }
    }

    private fun focusedCue(): LiveCue? {
        val id = local.value.focus.cueId ?: return null
        return activeSession()?.cues?.firstOrNull { it.id == id }
    }

    /** Looks up a suffix of the focused cue from a source-text tap. */
    fun lookupRootRedirect(query: String): List<LookupResult> {
        val cue = focusedCue() ?: return emptyList()
        if (query.isBlank()) return emptyList()
        val settings = local.value.dictionarySettings.normalized()
        val results = runCatching {
            lookupRepository.lookup(query, settings.maxResults, settings.scanLength, settings.lookupOptions())
        }.getOrElse { return emptyList() }
        if (results.isNotEmpty()) {
            val styles = local.value.dictionaryStyles.ifEmpty {
                runCatching { lookupRepository.dictionaryStyles() }.getOrDefault(emptyMap())
            }
            local.update { state ->
                state.copy(
                    dictionaryStyles = styles,
                    lookup = state.lookup.copy(
                        results = results,
                        sentenceOffset = if (cue.text.endsWith(query)) cue.text.length - query.length else null,
                        popups = emptyList(),
                        backCount = state.lookup.backCount + 1,
                        forwardCount = 0,
                    ),
                )
            }
            autoPause()
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
        val text = focusedCue()?.text ?: return
        if (sentenceOffset != null && sentenceOffset !in 0..text.length) return
        updateLookup { it.copy(sentenceOffset = sentenceOffset) }
    }

    fun entryForPopup(popupId: String, index: Int): LookupResult? {
        if (index < 0) return null
        val lookup = local.value.lookup
        return if (isLiveRootPopupId(popupId)) {
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
        updateLookup { LiveLookupState(clearSelectionSignal = it.clearSelectionSignal + 1) }
        reconcile(repository.state.value)
    }

    /**
     * Mining context for the focused cue: the cue text as the sentence, the session title
     * (with the cue time for timed sessions) as `{document-title}`, and the bridge media the
     * session can produce: an animated clip or a screenshot for `{image}`, and the cue's audio
     * for `{sentence-audio}`.
     */
    fun rootMiningContext(): AnkiMiningContext? {
        val state = uiState.value
        val session = activeSession() ?: return null
        val cue = focusedCue() ?: return null
        val title = session.info.title.ifBlank { state.hostName(session.hostId) }
        val ref = AnkiLiveCueRef(session.hostId, session.info.id, session.info.trackId, cue.id)
        val timed = session.kind.isTimed && cue.isTimed
        val screenshot = when {
            timed && session.info.has(LiveCapability.Clip) -> AnkiScreenshotRequest(ref, animated = true)
            session.info.has(LiveCapability.Screenshot) -> AnkiScreenshotRequest(ref, animated = false)
            else -> null
        }
        return AnkiMiningContext(
            sentence = cue.text,
            sentenceOffset = local.value.lookup.sentenceOffset,
            documentTitle = (if (timed) liveTimestampedTitle(title, cue.startMs ?: 0) else title).ifBlank { null },
            screenshot = screenshot,
            sentenceAudio = ref.takeIf { timed && session.info.has(LiveCapability.Audio) },
        )
    }

    /** Fetches a preview of the cue's picture for the mining sheet. */
    fun requestPreview(request: AnkiScreenshotRequest) {
        previewJob?.cancel()
        local.update { it.copy(preview = LivePreviewState.Loading) }
        previewJob = scope.launch {
            val next = when (val result = repository.fetchPreview(request)) {
                is LivePreviewResult.Ready -> LivePreviewState.Ready(result.path)
                is LivePreviewResult.Failed -> {
                    showMessage(result.message)
                    LivePreviewState.Failed
                }
            }
            local.update { it.copy(preview = next) }
        }
    }

    fun clearPreview() {
        previewJob?.cancel()
        local.update { it.copy(preview = LivePreviewState.Idle) }
    }

    /**
     * Reports the outcome of mining [term]: a success celebrates the card (and counts it), a
     * failure queues an error snackbar. Media warnings stay on the snackbar queue via [showMessage].
     */
    fun onCardMined(mined: Boolean, term: String) {
        if (!mined) {
            showMessage(UiText.Resource(R.string.live_card_failed))
            return
        }
        local.update { state ->
            val count = state.minedCount + 1
            state.copy(
                minedCount = count,
                celebration = LiveCelebration(id = count.toLong(), term = term.trim(), count = count),
            )
        }
    }

    fun consumeCelebration(id: Long) {
        local.update { if (it.celebration?.id == id) it.copy(celebration = null) else it }
    }

    fun showMessage(message: UiText) {
        local.update { if (it.messages.lastOrNull() == message) it else it.copy(messages = it.messages + message) }
    }

    fun consumeMessage(message: UiText) {
        local.update { if (it.messages.firstOrNull() == message) it.copy(messages = it.messages.drop(1)) else it }
    }

    private fun updateLookup(transform: (LiveLookupState) -> LiveLookupState) {
        local.update { it.copy(lookup = transform(it.lookup)) }
    }

    internal companion object {
        const val TickMillis = 250L
    }
}
