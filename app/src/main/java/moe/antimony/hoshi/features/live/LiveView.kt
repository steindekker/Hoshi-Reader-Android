package moe.antimony.hoshi.features.live

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LiveTv
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.VerticalAlignBottom
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import moe.antimony.hoshi.LocalHoshiUiDependencies
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.AnkiMiningPayload
import moe.antimony.hoshi.features.anki.AnkiViewModel
import moe.antimony.hoshi.features.audio.WordAudioPlayer
import moe.antimony.hoshi.features.audio.withLocalizedSourceNames
import moe.antimony.hoshi.features.dictionary.DictionaryImageRequestHandler
import moe.antimony.hoshi.features.dictionary.DictionarySearchSession
import moe.antimony.hoshi.features.dictionary.DictionarySearchSessionWebView
import moe.antimony.hoshi.features.dictionary.LookupPopupAssets
import moe.antimony.hoshi.features.dictionary.LookupPopupHtml
import moe.antimony.hoshi.features.dictionary.LookupPopupIframeWebViewClient
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.closeChildPopups
import moe.antimony.hoshi.features.dictionary.closeChildPopupsAndClearSelection
import moe.antimony.hoshi.features.dictionary.closeChildPopupsForScrolledParent
import moe.antimony.hoshi.features.dictionary.dictionarySearchIframePopupsAfterSwipeDismiss
import moe.antimony.hoshi.features.dictionary.dictionarySearchPopupOptions
import moe.antimony.hoshi.features.dictionary.dictionarySearchRootFramePayload
import moe.antimony.hoshi.features.dictionary.lookupPopupIframeHostHtml
import moe.antimony.hoshi.features.dictionary.openPopupExternalLink
import moe.antimony.hoshi.features.dictionary.withLookupPopupVisualOptions
import moe.antimony.hoshi.features.reader.MineScreenshotPreview
import moe.antimony.hoshi.features.reader.MineSentenceMode
import moe.antimony.hoshi.features.reader.MineWithOptionsRequest
import moe.antimony.hoshi.features.reader.MineWithOptionsSheetHost
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeCallbacks
import moe.antimony.hoshi.features.reader.ReaderLookupPopupBridgeMessage
import moe.antimony.hoshi.features.reader.ReaderLookupPopupFramePayload
import moe.antimony.hoshi.features.reader.ReaderLookupPopupIframeSync
import moe.antimony.hoshi.features.reader.ReaderLookupPopupResourceHandler
import moe.antimony.hoshi.features.reader.ReaderLookupPopupViewport
import moe.antimony.hoshi.features.reader.ReaderLookupPopupWebBridge
import moe.antimony.hoshi.features.reader.ReaderPopupHistoryCounts
import moe.antimony.hoshi.features.reader.ReaderSettings
import moe.antimony.hoshi.features.reader.readerLookupPopupIframeUrl
import moe.antimony.hoshi.features.reader.readerPopupBooleanMapJson
import moe.antimony.hoshi.ui.HoshiDropdownMenu as DropdownMenu
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.resolve
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import org.json.JSONObject.quote

/** Builds the iframe stack: the embedded root (tappable cue + results) followed by recursive popups. */
internal fun liveIframePayloads(
    rootPopupId: String?,
    cue: LiveCue?,
    lookup: LiveLookupState,
    childPopups: List<LookupPopupItem>,
    childHistories: Map<String, ReaderPopupHistoryCounts>,
    viewport: ReaderLookupPopupViewport,
    darkMode: Boolean,
    eInkMode: Boolean,
    iframeUrl: String,
): List<ReaderLookupPopupFramePayload> {
    if (cue == null || rootPopupId == null) return emptyList()
    val root = dictionarySearchRootFramePayload(
        results = lookup.results,
        viewport = viewport,
        searchBarBottomDp = 0.0,
        darkMode = darkMode,
        eInkMode = eInkMode,
        iframeUrl = iframeUrl,
        clearSelectionSignal = lookup.clearSelectionSignal,
        rootHistory = ReaderPopupHistoryCounts(lookup.backCount, lookup.forwardCount),
        sourceText = cue.text,
        sourceSentenceOffset = lookup.sentenceOffset,
        // A per-cue id makes the popup host render the new sentence even when it has no results yet.
    ).copy(id = rootPopupId)
    return listOf(root) + childPopups.mapIndexed { index, popup ->
        val history = childHistories[popup.id] ?: ReaderPopupHistoryCounts()
        ReaderLookupPopupFramePayload.fromPopup(
            popup = popup,
            popupIndex = index + 1,
            viewport = viewport,
            backCount = history.backCount,
            forwardCount = history.forwardCount,
            iframeUrl = iframeUrl,
        )
    }
}

@Composable
internal fun LiveView(
    session: DictionarySearchSession,
    readerSettings: ReaderSettings,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val appContainer = LocalHoshiUiDependencies.current
    val viewModel: LiveViewModel = hiltViewModel()
    val ankiViewModel: AnkiViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val ankiUiState by ankiViewModel.uiState.collectAsStateWithLifecycle()
    val profileState by appContainer.profileRepository.state.collectAsStateWithLifecycle()
    val assets = remember(context) { LookupPopupAssets.load(context) }
    val dictionaryRepository = appContainer.dictionaryRepository
    val fontManager = appContainer.readerFontManager
    val fontLibraryState by fontManager.libraryState.collectAsStateWithLifecycle()
    val fontFaceCss = remember(fontManager, fontLibraryState.revision) { fontManager.popupFontFaceCss() }
    val contentLanguageProfile = profileState.effectiveContentLanguageProfile
    var childHistories by session.childHistories
    var iframeHostWebView by remember(session) { mutableStateOf(session.webView) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var mineWithOptionsRequest by remember { mutableStateOf<MineWithOptionsRequest?>(null) }
    var transcriptExpanded by rememberSaveable { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }
    val popupDarkMode = hoshiSurfaces.page.luminance() < 0.5f
    val cue = uiState.focusedCue
    val lookup = uiState.lookup
    val popupOptions = dictionarySearchPopupOptions(
        readerSettings = readerSettings,
        dictionarySettings = uiState.dictionarySettings,
        darkMode = popupDarkMode,
        audioSettings = uiState.audioSettings,
        contentLanguageProfile = contentLanguageProfile,
        topInset = 0.0,
    )
    val viewport = remember(viewportSize, density) {
        ReaderLookupPopupViewport(
            width = with(density) { viewportSize.width.toDp().value.toDouble() },
            height = with(density) { viewportSize.height.toDp().value.toDouble() },
        )
    }
    val themedPopups = remember(lookup.popups, popupDarkMode, readerSettings.eInkMode, uiState.audioSettings, readerSettings.popupScale) {
        lookup.popups.withLookupPopupVisualOptions(
            darkMode = popupDarkMode,
            eInkMode = readerSettings.eInkMode,
            audioSettings = uiState.audioSettings,
            popupScale = readerSettings.popupScale,
        )
    }
    val noAudioFoundText = stringResource(R.string.audio_no_audio_found)
    val audioLoadingText = stringResource(R.string.loading)
    val popupAudioSettings = uiState.audioSettings.withLocalizedSourceNames()
    val iframeDocument = remember(
        uiState.dictionaryStyles,
        uiState.dictionarySettings,
        readerSettings.popupSwipeToDismiss,
        readerSettings.popupSwipeThreshold,
        readerSettings.popupReducedMotionScrolling,
        readerSettings.popupReducedMotionScrollPercent,
        readerSettings.popupReducedMotionSwipeThreshold,
        popupDarkMode,
        readerSettings.eInkMode,
        popupAudioSettings,
        ankiUiState.popupSettings,
        fontFaceCss,
        readerSettings.popupScale,
        contentLanguageProfile,
        noAudioFoundText,
        audioLoadingText,
    ) {
        LookupPopupHtml.renderIframeDocument(
            assets = null,
            dictionaryStyles = uiState.dictionaryStyles,
            settings = uiState.dictionarySettings,
            swipeToDismiss = readerSettings.popupSwipeToDismiss,
            swipeThreshold = readerSettings.popupSwipeThreshold,
            reducedMotionScrolling = readerSettings.popupReducedMotionScrolling,
            reducedMotionScrollPercent = readerSettings.popupReducedMotionScrollPercent,
            reducedMotionSwipeThreshold = readerSettings.popupReducedMotionSwipeThreshold,
            darkMode = popupDarkMode,
            eInkMode = readerSettings.eInkMode,
            audioSettings = popupAudioSettings,
            noAudioFoundText = noAudioFoundText,
            audioLoadingText = audioLoadingText,
            ankiSettings = ankiUiState.popupSettings,
            fontFaceCss = fontFaceCss,
            popupScale = readerSettings.popupScale,
            contentLanguageProfile = contentLanguageProfile,
        )
    }
    val currentIframeDocument = rememberUpdatedState(iframeDocument)
    val iframeUrl = remember(iframeDocument) { readerLookupPopupIframeUrl(iframeDocument.hashCode()) }
    val resourceHandler = remember(context, assets, fontManager, appContainer.audioRequestHandler, dictionaryRepository) {
        ReaderLookupPopupResourceHandler(
            context = context.applicationContext,
            assets = assets,
            fontManager = fontManager,
            audioRequestHandler = appContainer.audioRequestHandler,
            imageRequestHandler = DictionaryImageRequestHandler(dictionaryRepository::dictionaryMedia),
            iframeDocument = { currentIframeDocument.value },
        )
    }
    val rootPopupId = uiState.rootPopupId
    val iframePayloads = remember(rootPopupId, cue, lookup, themedPopups, childHistories, viewport, popupDarkMode, readerSettings.eInkMode, iframeUrl) {
        liveIframePayloads(
            rootPopupId = rootPopupId,
            cue = cue,
            lookup = lookup,
            childPopups = themedPopups,
            childHistories = childHistories,
            viewport = viewport,
            darkMode = popupDarkMode,
            eInkMode = readerSettings.eInkMode,
            iframeUrl = iframeUrl,
        )
    }

    LifecycleResumeEffect(iframeHostWebView) {
        iframeHostWebView?.onResume()
        onPauseOrDispose { iframeHostWebView?.onPause() }
    }
    LaunchedEffect(profileState.effectiveProfile.id) {
        if (session.profileId != profileState.effectiveProfile.id) {
            session.profileId = profileState.effectiveProfile.id
            childHistories = emptyMap()
        }
        viewModel.onEffectiveProfileChanged(profileState.effectiveProfile.id)
    }
    LaunchedEffect(uiState.rootPopupId) { childHistories = emptyMap() }
    LaunchedEffect(uiState.message) {
        val message = uiState.message ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(message.resolve(context))
        viewModel.consumeMessage(message)
    }

    fun setIframePopups(next: List<LookupPopupItem>) {
        val activeIds = next.mapTo(mutableSetOf()) { it.id }
        childHistories = childHistories.filterKeys(activeIds::contains)
        viewModel.setPopups(next)
    }
    fun popupIndex(popupId: String): Int = uiState.lookup.popups.indexOfFirst { it.id == popupId }
    fun popupById(popupId: String): LookupPopupItem? = uiState.lookup.popups.firstOrNull { it.id == popupId }
    fun evaluate(script: String) {
        iframeHostWebView?.evaluateJavascript(script, null)
    }
    fun replyIframeMessage(popupId: String, messageId: String, bodyJson: String) {
        evaluate(
            "window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.resolveMessage(${quote(popupId)}, ${quote(messageId)}, $bodyJson)",
        )
    }
    fun highlightIframeSelection(popupId: String, highlightCount: Int) {
        evaluate("window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.highlightSelection(${quote(popupId)}, $highlightCount)")
    }
    fun bumpChildHistory(popupId: String, transform: (ReaderPopupHistoryCounts) -> ReaderPopupHistoryCounts?) {
        val next = transform(childHistories[popupId] ?: ReaderPopupHistoryCounts()) ?: return
        childHistories = childHistories + (popupId to next)
    }
    fun handleBridgeMessage(message: ReaderLookupPopupBridgeMessage) {
        val isRoot = isLiveRootPopupId(message.popupId)
        if (isRoot && message.popupId != uiState.rootPopupId) return
        when (message) {
            is ReaderLookupPopupBridgeMessage.OpenLink -> context.openPopupExternalLink(message.url)
            is ReaderLookupPopupBridgeMessage.TapOutside -> if (isRoot) {
                childHistories = emptyMap()
                viewModel.dismissRootPopup()
            } else {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                setIframePopups(closeChildPopupsAndClearSelection(uiState.lookup.popups, index))
            }
            is ReaderLookupPopupBridgeMessage.SwipeDismiss -> if (isRoot) {
                viewModel.dismissRootPopup()
            } else {
                val dismissal = dictionarySearchIframePopupsAfterSwipeDismiss(uiState.lookup.popups, message.popupId)
                if (dismissal.clearRootSelection) {
                    childHistories = emptyMap()
                    viewModel.dismissRootPopup()
                } else {
                    setIframePopups(dismissal.popups)
                }
            }
            is ReaderLookupPopupBridgeMessage.TextSelected -> if (isRoot) {
                highlightIframeSelection(message.popupId, viewModel.openRootPopup(message.selection, popupOptions) ?: 0)
            } else {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                val next = closeChildPopups(uiState.lookup.popups, index)
                val created = viewModel.createPopup(message.selection, popupOptions)
                if (created == null) {
                    highlightIframeSelection(message.popupId, 0)
                } else {
                    setIframePopups(next + created.first)
                    highlightIframeSelection(message.popupId, created.second)
                }
            }
            is ReaderLookupPopupBridgeMessage.PlayWordAudio -> WordAudioPlayer.get(context).play(message.url, message.mode)
            is ReaderLookupPopupBridgeMessage.MineEntry -> {
                val messageId = message.messageId ?: return
                if (isRoot) {
                    val miningContext = viewModel.rootMiningContext() ?: return
                    ankiViewModel.mineEntryAsync(
                        formatId = message.formatId,
                        rawPayload = message.payloadJson,
                        context = miningContext,
                        onWarning = viewModel::showMessage,
                    ) { mined -> replyIframeMessage(message.popupId, messageId, mined.toString()) }
                } else {
                    val miningContext = popupById(message.popupId)?.state?.ankiContext ?: return
                    ankiViewModel.mineEntryAsync(message.formatId, message.payloadJson, miningContext) { mined ->
                        replyIframeMessage(message.popupId, messageId, mined.toString())
                    }
                }
            }
            is ReaderLookupPopupBridgeMessage.MineWithOptions -> {
                val messageId = message.messageId ?: return
                val baseContext = if (isRoot) viewModel.rootMiningContext() else popupById(message.popupId)?.state?.ankiContext
                val term = runCatching { AnkiMiningPayload.fromJson(message.payloadJson).expression }.getOrNull().orEmpty()
                if (baseContext == null || term.isBlank()) {
                    replyIframeMessage(message.popupId, messageId, false.toString())
                    return
                }
                baseContext.screenshot?.let(viewModel::requestPreview)
                mineWithOptionsRequest = MineWithOptionsRequest(
                    popupId = message.popupId,
                    messageId = messageId,
                    payloadJson = message.payloadJson,
                    baseContext = baseContext,
                    term = term,
                )
            }
            is ReaderLookupPopupBridgeMessage.DuplicateCheck -> {
                val messageId = message.messageId ?: return
                ankiViewModel.duplicateStatesAsync(message.valuesByHandlebar) { states ->
                    replyIframeMessage(message.popupId, messageId, readerPopupBooleanMapJson(states))
                }
            }
            is ReaderLookupPopupBridgeMessage.ShowNotes -> {
                val messageId = message.messageId ?: return
                ankiViewModel.showNotesAsync(message.formatId, message.valuesByHandlebar) { shown ->
                    replyIframeMessage(message.popupId, messageId, shown.toString())
                }
            }
            is ReaderLookupPopupBridgeMessage.LookupRedirect -> {
                val messageId = message.messageId ?: return
                val results = if (isRoot) {
                    childHistories = emptyMap()
                    viewModel.lookupRootRedirect(message.query)
                } else {
                    val popup = popupById(message.popupId) ?: return
                    viewModel.lookupRedirect(message.query).also { redirected ->
                        if (redirected.isNotEmpty()) {
                            setIframePopups(
                                uiState.lookup.popups.map { existing ->
                                    if (existing.id == popup.id) existing.copy(state = existing.state.copy(results = redirected)) else existing
                                },
                            )
                            bumpChildHistory(message.popupId) { it.copy(backCount = it.backCount + 1, forwardCount = 0) }
                        }
                    }
                }
                replyIframeMessage(message.popupId, messageId, results.size.toString())
            }
            is ReaderLookupPopupBridgeMessage.KanjiRedirect -> {
                val messageId = message.messageId ?: return
                val result = viewModel.lookupKanji(message.kanji)
                replyIframeMessage(
                    message.popupId,
                    messageId,
                    if (result.entries.isEmpty()) "null" else LookupPopupHtml.kanjiJsonString(result),
                )
            }
            is ReaderLookupPopupBridgeMessage.KanjiRedirectCommitted -> if (isRoot) {
                viewModel.recordRootRedirected()
            } else {
                bumpChildHistory(message.popupId) { it.copy(backCount = it.backCount + 1, forwardCount = 0) }
            }
            is ReaderLookupPopupBridgeMessage.GetEntry -> replyIframeMessage(
                popupId = message.popupId,
                messageId = message.messageId ?: return,
                bodyJson = viewModel.entryForPopup(message.popupId, message.index)
                    ?.let(LookupPopupHtml::entryJsonString) ?: "null",
            )
            is ReaderLookupPopupBridgeMessage.PopupScrolled -> if (isRoot) {
                if (uiState.lookup.popups.isNotEmpty()) {
                    childHistories = emptyMap()
                    viewModel.dismissRootPopup()
                }
            } else {
                val index = popupIndex(message.popupId).takeIf { it >= 0 } ?: return
                setIframePopups(closeChildPopupsForScrolledParent(uiState.lookup.popups, index))
            }
            is ReaderLookupPopupBridgeMessage.NavigateBack -> if (isRoot) {
                viewModel.navigateRootBack()
            } else {
                bumpChildHistory(message.popupId) {
                    if (it.backCount > 0) it.copy(backCount = it.backCount - 1, forwardCount = it.forwardCount + 1) else null
                }
            }
            is ReaderLookupPopupBridgeMessage.NavigateForward -> if (isRoot) {
                viewModel.navigateRootForward()
            } else {
                bumpChildHistory(message.popupId) {
                    if (it.forwardCount > 0) it.copy(backCount = it.backCount + 1, forwardCount = it.forwardCount - 1) else null
                }
            }
            is ReaderLookupPopupBridgeMessage.SourceHistoryRestored -> if (isRoot) {
                viewModel.restoreRootSourceHistory(message.sentenceOffset)
            }
            is ReaderLookupPopupBridgeMessage.ContentReady,
            is ReaderLookupPopupBridgeMessage.ScrollState,
            is ReaderLookupPopupBridgeMessage.SasayakiReplayCue,
            is ReaderLookupPopupBridgeMessage.SasayakiTogglePlayback,
            is ReaderLookupPopupBridgeMessage.SasayakiPlayForward,
            -> Unit
        }
    }
    session.bridge.callbacks = ReaderLookupPopupBridgeCallbacks(::handleBridgeMessage)

    BackHandler(enabled = lookup.popups.isNotEmpty() || lookup.backCount > 0) {
        val rootId = uiState.rootPopupId
        if (lookup.popups.isNotEmpty()) {
            childHistories = emptyMap()
            viewModel.dismissRootPopup()
        } else if (rootId != null) {
            evaluate("window.hoshiReaderPopupHost && window.hoshiReaderPopupHost.navigateBack(${quote(rootId)})")
        }
    }

    Box(modifier = modifier.fillMaxSize().background(hoshiSurfaces.page)) {
        Column(modifier = Modifier.fillMaxSize()) {
            LiveHeader(
                state = uiState,
                onRetry = viewModel::retryConnection,
                onTogglePlayback = viewModel::togglePlayback,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.statusBarsPadding(),
            )
            LiveSessionBar(
                state = uiState,
                onSelectSession = { key ->
                    childHistories = emptyMap()
                    viewModel.selectSession(key)
                },
                onBackToPlaying = {
                    childHistories = emptyMap()
                    viewModel.backToPlaying()
                },
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewportSize = it },
            ) {
                // The retained WebView stays attached even without a cue so its state survives.
                LiveIframeHost(
                    session = session,
                    resourceHandler = resourceHandler,
                    onWebViewChanged = { iframeHostWebView = it },
                    modifier = Modifier.fillMaxSize(),
                )
                if (cue == null) {
                    LiveEmptyState(
                        configured = uiState.settings.isConfigured,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.fillMaxSize().background(hoshiSurfaces.page),
                    )
                }
                if (iframePayloads.isEmpty() || (viewport.width > 0 && viewport.height > 0)) {
                    ReaderLookupPopupIframeSync(
                        webView = iframeHostWebView,
                        payloads = iframePayloads,
                        rootHighlight = null,
                    )
                }
            }
            val active = uiState.activeSession
            if (active != null && active.cues.isNotEmpty()) {
                LiveTranscript(
                    session = active,
                    playingCueId = uiState.playingCueId,
                    focusedCueId = cue?.id,
                    expanded = transcriptExpanded,
                    onToggleExpanded = { transcriptExpanded = !transcriptExpanded },
                    onFocus = { cueId ->
                        childHistories = emptyMap()
                        viewModel.focusCue(cueId)
                    },
                    onSeek = viewModel::seekToCue,
                    modifier = if (transcriptExpanded) Modifier.fillMaxHeight(TranscriptHeightFraction) else Modifier,
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
        val preview = when (val state = uiState.preview) {
            LivePreviewState.Idle -> MineScreenshotPreview(path = null, loading = false)
            LivePreviewState.Loading -> MineScreenshotPreview(path = null, loading = true)
            is LivePreviewState.Ready -> MineScreenshotPreview(path = state.path, loading = false)
            LivePreviewState.Failed -> MineScreenshotPreview(path = null, loading = false)
        }
        MineWithOptionsSheetHost(
            request = mineWithOptionsRequest,
            mine = { payloadJson, miningContext, onResult ->
                ankiViewModel.mineEntryAsync(
                    formatId = null,
                    rawPayload = payloadJson,
                    context = miningContext,
                    onWarning = viewModel::showMessage,
                    onResult = onResult,
                )
            },
            reply = ::replyIframeMessage,
            onClose = {
                mineWithOptionsRequest = null
                viewModel.clearPreview()
            },
            sentenceMode = MineSentenceMode.InBookSentence,
            screenshotPreview = preview,
        )
    }
}

private const val TranscriptHeightFraction = 0.4f
private const val ManualScrollHoldMillis = 4_000L

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun LiveIframeHost(
    session: DictionarySearchSession,
    resourceHandler: ReaderLookupPopupResourceHandler,
    onWebViewChanged: (WebView) -> Unit,
    modifier: Modifier = Modifier,
) {
    DictionarySearchSessionWebView(
        session = session,
        modifier = modifier,
        factory = { viewContext ->
            WebView(viewContext).apply {
                applyHoshiWebViewSecurityDefaults()
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                ReaderLookupPopupWebBridge.install(this, session.bridge)
                webViewClient = LookupPopupIframeWebViewClient(resourceHandler)
                loadDataWithBaseURL(
                    "https://appassets.androidplatform.net/live/iframe-host.html",
                    lookupPopupIframeHostHtml(),
                    "text/html",
                    "UTF-8",
                    null,
                )
                onWebViewChanged(this)
            }
        },
        update = { webView ->
            webView.webViewClient = LookupPopupIframeWebViewClient(resourceHandler)
            onWebViewChanged(webView)
        },
    )
}

/** The extrapolated playback position of [session], refreshed while it plays. */
@Composable
private fun rememberPlaybackPosition(session: LiveSessionState?): Long? {
    val position by produceState(session?.positionAt(LiveClock.System.elapsedMillis()), session) {
        while (true) {
            value = session?.positionAt(LiveClock.System.elapsedMillis())
            if (session?.playback?.paused != false) break
            delay(500)
        }
    }
    return position
}

@Composable
private fun LiveHeader(
    state: LiveUiState,
    onRetry: () -> Unit,
    onTogglePlayback: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = liveStatusPresentation(state)
    val indicatorColor = when (presentation.tone) {
        LiveStatusTone.Connected -> MaterialTheme.colorScheme.primary
        LiveStatusTone.Pending -> MaterialTheme.colorScheme.tertiary
        LiveStatusTone.Problem -> MaterialTheme.colorScheme.error
        LiveStatusTone.Inactive -> MaterialTheme.colorScheme.outline
    }
    val session = state.activeSession
    val position = rememberPlaybackPosition(session?.takeIf { it.kind.isTimed })
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(indicatorColor, CircleShape),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = session?.let { liveSessionTitle(it, state) } ?: presentation.title.asString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val details = when {
                session == null -> UiText.Joined(presentation.details, separator = " · ").asString()
                position != null -> stringResource(R.string.live_session_on_host, state.hostName(session.hostId), liveClockText(position))
                else -> state.hostName(session.hostId)
            }
            if (details.isNotEmpty()) {
                Text(
                    text = details,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.canControl) {
            IconButton(onClick = onTogglePlayback) {
                Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, contentDescription = null)
            }
        }
        if (presentation.canRetry) {
            IconButton(onClick = onRetry) {
                Icon(Icons.Rounded.Refresh, contentDescription = null)
            }
        }
        IconButton(onClick = onOpenSettings) {
            Icon(Icons.Rounded.Settings, contentDescription = null)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

@Composable
private fun liveSessionTitle(session: LiveSessionState, state: LiveUiState): String =
    session.info.title.ifBlank {
        stringResource(
            when (session.kind) {
                LiveSessionKind.Vn -> R.string.live_session_kind_vn
                LiveSessionKind.Video -> R.string.live_session_kind_video
                LiveSessionKind.Web -> R.string.live_session_kind_web
            },
        )
    }

/** Session switcher chip (with several sessions) and the "back to playing" chip. */
@Composable
private fun LiveSessionBar(
    state: LiveUiState,
    onSelectSession: (String?) -> Unit,
    onBackToPlaying: () -> Unit,
) {
    val active = state.activeSession ?: return
    val showSwitcher = state.sessions.size > 1 || state.sessionPinned
    if (!showSwitcher && !state.showBackToPlaying) return
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSwitcher) {
            Box {
                AssistChip(
                    onClick = { menuOpen = true },
                    label = {
                        Text(
                            text = stringResource(R.string.live_session_on_host, liveSessionTitle(active, state), state.hostName(active.hostId)),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.widthIn(max = 260.dp),
                        )
                    },
                    trailingIcon = { Icon(Icons.Rounded.ArrowDropDown, contentDescription = null) },
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.live_session_auto)) },
                        onClick = {
                            menuOpen = false
                            onSelectSession(null)
                        },
                    )
                    state.sessions.forEach { session ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = stringResource(R.string.live_session_on_host, liveSessionTitle(session, state), state.hostName(session.hostId)),
                                    fontWeight = if (session.key == active.key) FontWeight.SemiBold else null,
                                )
                            },
                            onClick = {
                                menuOpen = false
                                onSelectSession(session.key)
                            },
                        )
                    }
                }
            }
        }
        if (state.showBackToPlaying) {
            AssistChip(
                onClick = onBackToPlaying,
                label = {
                    Text(
                        stringResource(if (active.kind.isTimed) R.string.live_back_to_playing else R.string.live_show_latest),
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.VerticalAlignBottom, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun LiveEmptyState(
    configured: Boolean,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Rounded.LiveTv,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(if (configured) R.string.live_empty_waiting else R.string.live_empty_not_configured),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(if (configured) R.string.live_empty_waiting_hint else R.string.live_empty_not_configured_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!configured) {
            Spacer(Modifier.size(16.dp))
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.live_action_set_up))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun LiveTranscript(
    session: LiveSessionState,
    playingCueId: Long?,
    focusedCueId: Long?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onFocus: (Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val cues = session.cues
    val timed = session.kind.isTimed
    val canSeek = session.info.has(LiveCapability.Control)
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.SHORT) }
    val listState = rememberLazyListState()
    var manualScrollAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) manualScrollAt = System.currentTimeMillis()
        }
    }
    // Follow the playing cue unless the user scrolled the transcript a moment ago.
    LaunchedEffect(playingCueId, session.key, expanded) {
        if (!expanded) return@LaunchedEffect
        val index = cues.indexOfFirst { it.id == playingCueId }.takeIf { it >= 0 } ?: return@LaunchedEffect
        if (System.currentTimeMillis() - manualScrollAt < ManualScrollHoldMillis) return@LaunchedEffect
        autoScrolling = true
        try {
            listState.animateScrollToItem((index - 2).coerceAtLeast(0))
        } finally {
            autoScrolling = false
        }
    }
    Surface(
        color = hoshiSurfaces.group,
        border = hoshiContainerBorder(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded)
                    .padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.live_transcript_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pluralStringResource(R.plurals.live_transcript_count, cues.size, cues.size),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                    contentDescription = null,
                )
            }
            if (expanded) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().weight(1f)) {
                    itemsIndexed(cues, key = { _, item -> item.id }) { _, item ->
                        val playing = item.id == playingCueId
                        val focused = item.id == focusedCueId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    when {
                                        playing -> MaterialTheme.colorScheme.secondaryContainer
                                        focused -> MaterialTheme.colorScheme.surfaceVariant
                                        else -> Color.Transparent
                                    },
                                )
                                .clickable { onFocus(item.id) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            val start = item.startMs
                            if (timed && start != null) {
                                Text(
                                    text = liveClockText(start),
                                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .combinedClickable(
                                            onClick = { onFocus(item.id) },
                                            onLongClick = if (canSeek) ({ onSeek(item.id) }) else null,
                                        )
                                        .padding(top = 2.dp),
                                )
                            }
                            Text(
                                text = item.text,
                                style = MaterialTheme.typography.bodyMedium.copy(localeList = JapaneseLocale),
                                fontWeight = if (focused) FontWeight.SemiBold else null,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (!timed && item.at > 0) {
                                Text(
                                    text = timeFormat.format(Date(item.at)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private val JapaneseLocale = LocaleList("ja")
