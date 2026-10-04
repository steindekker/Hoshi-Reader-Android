package moe.antimony.hoshi.features.texthooker

import android.annotation.SuppressLint
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SportsEsports
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
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
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.resolve
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import moe.antimony.hoshi.webview.applyHoshiWebViewSecurityDefaults
import org.json.JSONObject.quote

/** Builds the iframe stack: the embedded root (tappable line + results) followed by recursive popups. */
internal fun textHookerIframePayloads(
    line: TextHookerLine?,
    lookup: TextHookerLookupState,
    childPopups: List<LookupPopupItem>,
    childHistories: Map<String, ReaderPopupHistoryCounts>,
    viewport: ReaderLookupPopupViewport,
    darkMode: Boolean,
    eInkMode: Boolean,
    iframeUrl: String,
): List<ReaderLookupPopupFramePayload> {
    line ?: return emptyList()
    val root = dictionarySearchRootFramePayload(
        results = lookup.results,
        viewport = viewport,
        searchBarBottomDp = 0.0,
        darkMode = darkMode,
        eInkMode = eInkMode,
        iframeUrl = iframeUrl,
        clearSelectionSignal = lookup.clearSelectionSignal,
        rootHistory = ReaderPopupHistoryCounts(lookup.backCount, lookup.forwardCount),
        sourceText = line.text,
        sourceSentenceOffset = lookup.sentenceOffset,
        // A per-line id makes the popup host render the new sentence even when it has no results yet.
    ).copy(id = textHookerRootPopupId(line.id))
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
internal fun TextHookerView(
    session: DictionarySearchSession,
    readerSettings: ReaderSettings,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val appContainer = LocalHoshiUiDependencies.current
    val viewModel: TextHookerViewModel = hiltViewModel()
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
    var logExpanded by rememberSaveable { mutableStateOf(true) }
    val snackbarHostState = remember { SnackbarHostState() }
    val popupDarkMode = hoshiSurfaces.page.luminance() < 0.5f
    val line = uiState.displayedLine
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
    val iframePayloads = remember(line, lookup, themedPopups, childHistories, viewport, popupDarkMode, readerSettings.eInkMode, iframeUrl) {
        textHookerIframePayloads(
            line = line,
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
        val isRoot = isTextHookerRootPopupId(message.popupId)
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
                baseContext.screenshot?.let { viewModel.requestScreenshotPreview(it.lineId) }
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
            TextHookerStatusHeader(
                state = uiState,
                onRetry = viewModel::retryConnection,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.statusBarsPadding(),
            )
            if (uiState.hasNewerLine) {
                TextHookerNewLineBanner(onShowLatest = {
                    childHistories = emptyMap()
                    viewModel.jumpToLatest()
                })
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .onSizeChanged { viewportSize = it },
            ) {
                // The retained WebView stays attached even without a line so its state survives.
                TextHookerIframeHost(
                    session = session,
                    resourceHandler = resourceHandler,
                    onWebViewChanged = { iframeHostWebView = it },
                    modifier = Modifier.fillMaxSize(),
                )
                if (line == null) {
                    TextHookerEmptyState(
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
            if (uiState.lines.isNotEmpty()) {
                TextHookerLineLog(
                    lines = uiState.lines,
                    displayedLineId = line?.id,
                    expanded = logExpanded,
                    onToggleExpanded = { logExpanded = !logExpanded },
                    onSelect = { lineId ->
                        childHistories = emptyMap()
                        viewModel.selectLine(lineId)
                    },
                )
            }
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
        )
        val preview = when (val state = uiState.screenshotPreview) {
            TextHookerScreenshotPreviewState.Idle -> MineScreenshotPreview(path = null, loading = false)
            is TextHookerScreenshotPreviewState.Loading -> MineScreenshotPreview(path = null, loading = true)
            is TextHookerScreenshotPreviewState.Ready -> MineScreenshotPreview(path = state.path, loading = false)
            is TextHookerScreenshotPreviewState.Failed -> MineScreenshotPreview(path = null, loading = false)
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
                viewModel.clearScreenshotPreview()
            },
            sentenceMode = MineSentenceMode.InBookSentence,
            screenshotPreview = preview,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun TextHookerIframeHost(
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
                    "https://appassets.androidplatform.net/texthooker/iframe-host.html",
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

@Composable
private fun TextHookerStatusHeader(
    state: TextHookerUiState,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val presentation = textHookerStatusPresentation(state)
    val indicatorColor = when (presentation.tone) {
        TextHookerStatusTone.Connected -> MaterialTheme.colorScheme.primary
        TextHookerStatusTone.Pending -> MaterialTheme.colorScheme.tertiary
        TextHookerStatusTone.Problem -> MaterialTheme.colorScheme.error
        TextHookerStatusTone.Inactive -> MaterialTheme.colorScheme.outline
    }
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
                text = presentation.title.asString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            presentation.details.forEach { detail ->
                Text(
                    text = detail.asString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
private fun TextHookerNewLineBanner(onShowLatest: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.texthooker_new_line),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onShowLatest) {
                Text(stringResource(R.string.texthooker_show_latest))
            }
        }
    }
}

@Composable
private fun TextHookerEmptyState(
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
            imageVector = Icons.Rounded.SportsEsports,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(48.dp),
        )
        Spacer(Modifier.size(16.dp))
        Text(
            text = stringResource(
                if (configured) R.string.texthooker_empty_waiting else R.string.texthooker_empty_not_configured,
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.size(8.dp))
        Text(
            text = stringResource(
                if (configured) R.string.texthooker_empty_waiting_hint else R.string.texthooker_empty_not_configured_hint,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!configured) {
            Spacer(Modifier.size(16.dp))
            Button(onClick = onOpenSettings) {
                Text(stringResource(R.string.texthooker_action_set_up))
            }
        }
    }
}

@Composable
private fun TextHookerLineLog(
    lines: List<TextHookerLine>,
    displayedLineId: Long?,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onSelect: (Long) -> Unit,
) {
    val newestFirst = remember(lines) { lines.asReversed() }
    val timeFormat = remember { DateFormat.getTimeInstance(DateFormat.MEDIUM) }
    Surface(
        color = hoshiSurfaces.group,
        border = hoshiContainerBorder(),
        shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp),
        modifier = Modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.texthooker_history_title),
                    style = MaterialTheme.typography.labelLarge,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = pluralStringResource(R.plurals.texthooker_history_count, lines.size, lines.size),
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
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                    items(newestFirst, key = { it.id }) { line ->
                        val selected = line.id == displayedLineId
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                )
                                .clickable { onSelect(line.id) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Text(
                                text = line.text,
                                style = MaterialTheme.typography.bodyMedium.copy(localeList = JapaneseLocale),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (line.receivedAt > 0) {
                                Text(
                                    text = timeFormat.format(Date(line.receivedAt)),
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
