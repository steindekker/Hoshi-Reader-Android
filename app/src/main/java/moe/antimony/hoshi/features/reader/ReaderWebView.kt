package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.features.dictionary.DictionarySettingsStore
import moe.antimony.hoshi.features.dictionary.LookupPopupItem
import moe.antimony.hoshi.features.dictionary.LookupPopupOptions
import moe.antimony.hoshi.features.dictionary.LookupPopupStackView
import moe.antimony.hoshi.features.dictionary.createLookupPopupItem
import java.io.File

data class ReaderSelectionData(
    val text: String,
    val sentence: String,
    val rect: ReaderSelectionRect,
    val normalizedOffset: Int?,
)

data class ReaderSelectionRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderWebView(
    book: EpubBook,
    initialChapterIndex: Int = 0,
    initialProgress: Double = 0.0,
    readerSettings: ReaderSettings = ReaderSettings(),
    onReaderSettingsChange: (ReaderSettings) -> Unit = {},
    onSaveBookmark: (chapterIndex: Int, progress: Double) -> Unit = { _, _ -> },
    onTextSelected: (ReaderSelectionData) -> Int? = { null },
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var effectiveSettings by remember(readerSettings) { mutableStateOf(readerSettings) }
    var showAppearance by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showReaderMenu by remember { mutableStateOf(false) }
    var lookupPopups by remember { mutableStateOf<List<LookupPopupItem>>(emptyList()) }
    val context = LocalContext.current
    val fontManager = remember { ReaderFontManager(context.filesDir) }
    val dictionarySettingsStore = remember { DictionarySettingsStore(context) }
    val view = LocalView.current
    val systemDarkTheme = isSystemInDarkTheme()
    fun lookupRootPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            options = LookupPopupOptions(
                isVertical = effectiveSettings.verticalWriting,
                dictionarySettings = dictionarySettingsStore.load(),
            ),
        )
    fun lookupChildPopup(selection: ReaderSelectionData): Pair<LookupPopupItem, Int>? =
        createLookupPopupItem(
            selection = selection,
            options = LookupPopupOptions(
                isVertical = false,
                dictionarySettings = dictionarySettingsStore.load(),
            ),
        )
    val handleTextSelected: (ReaderSelectionData) -> Int? = { selection ->
        lookupPopups = emptyList()
        val lookup = lookupRootPopup(selection)
        if (lookup != null) {
            val (popup, highlightCount) = lookup
            lookupPopups = listOf(popup)
            onTextSelected(selection) ?: highlightCount
        } else {
            onTextSelected(selection)
        }
    }
    val clampedInitialIndex = initialChapterIndex.coerceIn(0, book.chapters.lastIndex)
    var chapterPosition by remember(book) {
        mutableStateOf(
            ReaderChapterPosition(
                index = clampedInitialIndex,
                progress = initialProgress.coerceIn(0.0, 1.0),
            ),
        )
    }
    var displayedChapterPosition by remember(book) { mutableStateOf(chapterPosition) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    val chromeState = remember(book, displayedChapterPosition) {
        ReaderChromeState(
            title = book.title,
            currentCharacter = book.characterCountAt(displayedChapterPosition.index, displayedChapterPosition.progress),
            totalCharacters = book.bookInfo.characterCount,
        )
    }

    BackHandler(onBack = onClose)
    val useLightSystemBars = when (effectiveSettings.theme) {
        ReaderTheme.Dark -> false
        ReaderTheme.System -> !systemDarkTheme
        ReaderTheme.Light, ReaderTheme.Sepia -> true
    }
    DisposableEffect(context, view, useLightSystemBars, systemDarkTheme) {
        val activity = context.findActivity()
        val controller = activity?.window?.let { window ->
            WindowCompat.getInsetsController(window, view)
        }
        controller?.isAppearanceLightStatusBars = useLightSystemBars
        controller?.isAppearanceLightNavigationBars = useLightSystemBars
        onDispose {
            controller?.isAppearanceLightStatusBars = !systemDarkTheme
            controller?.isAppearanceLightNavigationBars = !systemDarkTheme
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(effectiveSettings.backgroundColor)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(top = ReaderWebViewTopPadding, bottom = ReaderWebViewBottomPadding),
        ) {
            ChapterWebView(
                book = book,
                chapterPosition = chapterPosition,
                onWebViewReady = { webView = it },
                onNextChapter = {
                    val next = chapterPosition.nextOrNull(book.chapters.lastIndex)
                    if (next != null) {
                        chapterPosition = next
                        displayedChapterPosition = next
                        onSaveBookmark(next.index, next.progress)
                        true
                    } else {
                        false
                    }
                },
                onPreviousChapter = {
                    val previous = chapterPosition.previousOrNull()
                    if (previous != null) {
                        chapterPosition = previous
                        displayedChapterPosition = previous
                        onSaveBookmark(previous.index, previous.progress)
                        true
                    } else {
                        false
                    }
                },
                onSaveBookmark = { progress ->
                    displayedChapterPosition = chapterPosition.copy(progress = progress)
                    onSaveBookmark(chapterPosition.index, progress)
                },
                readerSettings = effectiveSettings,
                onTextSelected = handleTextSelected,
                onClearLookupPopup = { lookupPopups = emptyList() },
                fontManager = fontManager,
                modifier = Modifier.fillMaxSize(),
            )
            LookupPopupStackView(
                popups = lookupPopups,
                onPopupsChange = { lookupPopups = it },
                lookupChildPopup = ::lookupChildPopup,
                modifier = Modifier.fillMaxSize(),
            )
        }
        ReaderTopInfo(
            state = chromeState,
            colors = readerChromeColors(effectiveSettings),
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 22.dp, start = 96.dp, end = 96.dp),
        )
        ReaderBottomChrome(
            settings = effectiveSettings,
            onClose = onClose,
            onMenu = { showReaderMenu = true },
            menuExpanded = showReaderMenu,
            onDismissMenu = { showReaderMenu = false },
            onChapters = {
                showReaderMenu = false
                showChapters = true
            },
            onAppearance = {
                showReaderMenu = false
                showAppearance = true
            },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
        webView?.let { _ -> Unit }
    }
    if (showAppearance) {
        ReaderAppearanceSheet(
            settings = effectiveSettings,
            onSettingsChange = {
                effectiveSettings = it
                onReaderSettingsChange(it)
            },
            fontManager = fontManager,
            onDismiss = { showAppearance = false },
        )
    }
    if (showChapters) {
        ReaderChapterSheet(
            book = book,
            currentPosition = displayedChapterPosition,
            onJump = { target ->
                lookupPopups = emptyList()
                chapterPosition = target
                displayedChapterPosition = target
                onSaveBookmark(target.index, target.progress)
                showChapters = false
            },
            onDismiss = { showChapters = false },
        )
    }
}

@Composable
private fun ReaderTopInfo(
    state: ReaderChromeState,
    colors: ReaderChromeColors,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = state.title,
            color = Color(colors.infoText),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
        )
        Text(
            text = state.progressText(),
            color = Color(colors.infoText),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun BoxScope.ReaderBottomChrome(
    settings: ReaderSettings,
    onClose: () -> Unit,
    onMenu: () -> Unit,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onChapters: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = readerChromeColors(settings)
    if (menuExpanded) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxSize()
                .clickable(onClick = onDismissMenu),
        )
        ReaderMenuCard(
            colors = colors,
            onChapters = onChapters,
            onAppearance = onAppearance,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 24.dp, bottom = 92.dp),
        )
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 26.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderGlassButton(colors = colors, onClick = onClose) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                modifier = Modifier.size(34.dp),
                tint = Color(colors.buttonContent),
            )
        }
        Spacer(Modifier.weight(1f))
        ReaderGlassButton(colors = colors, onClick = onMenu) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "Reader Menu",
                modifier = Modifier.size(30.dp),
                tint = Color(colors.buttonContent),
            )
        }
    }
}

@Composable
private fun ReaderMenuCard(
    colors: ReaderChromeColors,
    onChapters: () -> Unit,
    onAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .width(276.dp)
            .shadow(
                elevation = 24.dp,
                shape = RoundedCornerShape(28.dp),
                ambientColor = Color.Black.copy(alpha = 0.18f),
                spotColor = Color.Black.copy(alpha = 0.18f),
            )
            .border(BorderStroke(1.dp, Color(colors.menuBorder)), RoundedCornerShape(28.dp)),
        shape = RoundedCornerShape(28.dp),
        color = Color(colors.menuContainer),
        tonalElevation = 8.dp,
        shadowElevation = 0.dp,
    ) {
        Column(
            modifier = Modifier.padding(vertical = 10.dp),
        ) {
            ReaderMenuItem(
                text = "Chapters",
                icon = {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.List,
                        contentDescription = null,
                        tint = Color(colors.menuContent),
                    )
                },
                colors = colors,
                onClick = onChapters,
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 22.dp),
                color = Color(colors.menuBorder),
            )
            ReaderMenuItem(
                text = "Appearance",
                icon = {
                    Icon(
                        imageVector = Icons.Rounded.Palette,
                        contentDescription = null,
                        tint = Color(colors.menuContent),
                    )
                },
                colors = colors,
                onClick = onAppearance,
            )
        }
    }
}

@Composable
private fun ReaderMenuItem(
    text: String,
    icon: @Composable () -> Unit,
    colors: ReaderChromeColors,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Box(
            modifier = Modifier.size(30.dp),
            contentAlignment = Alignment.Center,
        ) {
            icon()
        }
        Text(
            text = text,
            color = Color(colors.menuContent),
            style = MaterialTheme.typography.titleLarge,
        )
    }
}

@Composable
private fun ReaderGlassButton(
    colors: ReaderChromeColors,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit,
) {
    Surface(
        modifier = Modifier
            .shadow(18.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.18f), spotColor = Color.Black.copy(alpha = 0.18f))
            .border(BorderStroke(1.dp, Color(colors.buttonBorder)), CircleShape)
            .size(64.dp),
        shape = CircleShape,
        color = Color(colors.buttonContainer),
        tonalElevation = 6.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
            content = content,
        )
    }
}

@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun ChapterWebView(
    book: EpubBook,
    chapterPosition: ReaderChapterPosition,
    onWebViewReady: (WebView) -> Unit,
    onNextChapter: () -> Boolean,
    onPreviousChapter: () -> Boolean,
    onSaveBookmark: (progress: Double) -> Unit,
    readerSettings: ReaderSettings,
    onTextSelected: (ReaderSelectionData) -> Int?,
    onClearLookupPopup: () -> Unit,
    fontManager: ReaderFontManager,
    modifier: Modifier = Modifier,
) {
    val chapter = book.chapters[chapterPosition.index]
    val fontFaceUrl = remember(readerSettings.selectedFont) {
        fontManager.webViewFontUrl(readerSettings.selectedFont)
    }
    val html = remember(chapter, chapterPosition.progress, readerSettings, fontFaceUrl) {
        chapter.html.injectReaderShell(
            initialProgress = chapterPosition.progress,
            settings = readerSettings,
            fontFaceUrl = fontFaceUrl,
        )
    }
    val baseUrl = remember(chapter) { "https://hoshi.local/epub/${chapter.href}" }

    AndroidView(
        modifier = modifier.background(Color(readerSettings.backgroundColor)),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                alpha = 0f
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                addJavascriptInterface(ReaderSelectionBridge(this, onTextSelected), "HoshiTextSelection")
                addJavascriptInterface(ReaderRestoreBridge(this), "HoshiReaderRestore")
                webViewClient = EpubWebViewClient(book, fontManager)
                setOnTouchListener(object : SwipePageTouchListener(context) {
                    override fun onTap(x: Float, y: Float) {
                        val density = resources.displayMetrics.density
                        evaluateJavascript(
                            ReaderSelectionScripts.selectInvocation(
                                x = androidPixelsToCssPixels(x, density),
                                y = androidPixelsToCssPixels(y, density),
                                maxLength = MAX_SELECTION_LENGTH,
                            ),
                        ) { result ->
                            if (ReaderSelectionScripts.didSelectNothing(result)) {
                                onClearLookupPopup()
                            }
                        }
                    }

                    override fun onLeftSwipe() {
                        onClearLookupPopup()
                        navigatePage(ReaderNavigationDirection.Backward, onPreviousChapter, onSaveBookmark)
                    }

                    override fun onRightSwipe() {
                        onClearLookupPopup()
                        navigatePage(ReaderNavigationDirection.Forward, onNextChapter, onSaveBookmark)
                    }
                })
                onWebViewReady(this)
            }
        },
        update = { webView ->
            val loadKey = "$baseUrl#${html.hashCode()}"
            if (webView.tag != loadKey) {
                webView.tag = loadKey
                webView.animate().cancel()
                webView.alpha = 0f
                webView.webViewClient = EpubWebViewClient(book, fontManager)
                webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderAppearanceSheet(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    fontManager: ReaderFontManager,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var importedFonts by remember { mutableStateOf(fontManager.storedFonts()) }
    var fontMenuExpanded by remember { mutableStateOf(false) }
    var fontToDelete by remember { mutableStateOf<String?>(null) }
    var isImportingFont by remember { mutableStateOf(false) }
    val fontImporter = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            isImportingFont = true
            runCatching {
                withContext(Dispatchers.IO) {
                    fontManager.importFont(context.contentResolver, uri)
                }
            }
            importedFonts = fontManager.storedFonts()
            isImportingFont = false
        }
    }
    val fontOptions = remember(importedFonts, settings.selectedFont) {
        (ReaderFontManager.defaultFonts + importedFonts.map { it.name } + settings.selectedFont)
            .filter { it.isNotBlank() }
            .distinct()
    }
    val sheetBackground = when (settings.theme) {
        ReaderTheme.Dark -> Color(0xFF1E1E1E)
        ReaderTheme.Sepia -> Color(0xFFF6EBD8)
        ReaderTheme.Light, ReaderTheme.System -> Color(0xFFF7F6FA)
    }
    val groupColor = when (settings.theme) {
        ReaderTheme.Dark -> Color(0xFF2A2A2A)
        ReaderTheme.Sepia -> Color(0xFFFFF8EC)
        ReaderTheme.Light, ReaderTheme.System -> Color.White
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = sheetBackground,
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
                )
            }
            item {
                ReaderAppearanceGroup(color = groupColor) {
                    SegmentedRow(
                        label = "Theme",
                        options = ReaderTheme.entries.map { it.label },
                        selected = settings.theme.label,
                        onSelected = { label ->
                            ReaderTheme.entries.firstOrNull { it.label == label }?.let {
                                onSettingsChange(settings.copy(theme = it))
                            }
                        },
                    )
                    ReaderAppearanceDivider()
                    SegmentedRow(
                        label = "Text Orientation",
                        options = listOf("縦", "横"),
                        selected = if (settings.verticalWriting) "縦" else "横",
                        onSelected = { label ->
                            onSettingsChange(settings.copy(verticalWriting = label == "縦"))
                        },
                    )
                }
            }
            item {
                ReaderAppearanceGroup(color = groupColor) {
                    ReaderFontRow(
                        settings = settings,
                        fontOptions = fontOptions,
                        fontMenuExpanded = fontMenuExpanded,
                        onFontMenuExpandedChange = { fontMenuExpanded = it },
                        onFontSelected = { fontName ->
                            fontMenuExpanded = false
                            onSettingsChange(settings.copy(selectedFont = fontName))
                        },
                        canDeleteFont = !fontManager.isDefaultFont(settings.selectedFont),
                        onDeleteFont = { fontToDelete = settings.selectedFont },
                    )
                    ReaderAppearanceDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Import Font", style = MaterialTheme.typography.bodyLarge)
                        Button(
                            onClick = { fontImporter.launch(fontMimeTypes) },
                            enabled = !isImportingFont,
                        ) {
                            Text(if (isImportingFont) "Importing..." else "Import")
                        }
                    }
                }
            }
            item {
                ReaderAppearanceGroup(color = groupColor) {
                    StepperRow(
                        label = "Font Size",
                        value = settings.fontSize.toString(),
                        onDecrease = {
                            onSettingsChange(settings.copy(fontSize = (settings.fontSize - 1).coerceAtLeast(16)))
                        },
                        onIncrease = {
                            onSettingsChange(settings.copy(fontSize = (settings.fontSize + 1).coerceAtMost(40)))
                        },
                    )
                    ReaderAppearanceDivider()
                    StepperRow(
                        label = "Horizontal Padding",
                        value = "${settings.horizontalPadding}%",
                        onDecrease = {
                            onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding - 1).coerceAtLeast(0)))
                        },
                        onIncrease = {
                            onSettingsChange(settings.copy(horizontalPadding = (settings.horizontalPadding + 1).coerceAtMost(50)))
                        },
                    )
                    ReaderAppearanceDivider()
                    StepperRow(
                        label = "Vertical Padding",
                        value = "${settings.verticalPadding}%",
                        onDecrease = {
                            onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding - 1).coerceAtLeast(0)))
                        },
                        onIncrease = {
                            onSettingsChange(settings.copy(verticalPadding = (settings.verticalPadding + 1).coerceAtMost(50)))
                        },
                    )
                    ReaderAppearanceDivider()
                    Column(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Line Height", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                String.format(java.util.Locale.US, "%.2f", settings.lineHeight),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        Slider(
                            value = settings.lineHeight.toFloat(),
                            onValueChange = { value ->
                                onSettingsChange(settings.copy(lineHeight = (kotlin.math.round(value * 20) / 20.0)))
                            },
                            valueRange = 1.0f..2.5f,
                            steps = 29,
                        )
                    }
                }
            }
            item {
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Done")
                }
            }
        }
    }
    fontToDelete?.let { fontName ->
        AlertDialog(
            onDismissRequest = { fontToDelete = null },
            title = { Text("Delete \"$fontName\"?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        fontManager.deleteFont(fontName)
                        importedFonts = fontManager.storedFonts()
                        onSettingsChange(settings.copy(selectedFont = ReaderFontManager.defaultFonts.first()))
                        fontToDelete = null
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { fontToDelete = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

private val fontMimeTypes = arrayOf(
    "font/ttf",
    "font/otf",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
    "*/*",
)

@Composable
private fun SegmentedRow(
    label: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            options.forEachIndexed { index, option ->
                SegmentedButton(
                    selected = option == selected,
                    onClick = { onSelected(option) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                ) {
                    Text(option)
                }
            }
        }
    }
}

@Composable
private fun ReaderFontRow(
    settings: ReaderSettings,
    fontOptions: List<String>,
    fontMenuExpanded: Boolean,
    onFontMenuExpandedChange: (Boolean) -> Unit,
    onFontSelected: (String) -> Unit,
    canDeleteFont: Boolean,
    onDeleteFont: () -> Unit,
) {
    Column(
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Font", style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(modifier = Modifier.weight(1f)) {
                TextButton(onClick = { onFontMenuExpandedChange(true) }) {
                    Text(settings.selectedFont)
                }
                DropdownMenu(
                    expanded = fontMenuExpanded,
                    onDismissRequest = { onFontMenuExpandedChange(false) },
                ) {
                    fontOptions.forEach { fontName ->
                        DropdownMenuItem(
                            text = { Text(fontName) },
                            onClick = { onFontSelected(fontName) },
                        )
                    }
                }
            }
            if (canDeleteFont) {
                TextButton(onClick = onDeleteFont) {
                    Text("Delete")
                }
            }
        }
    }
}

@Composable
private fun ReaderAppearanceGroup(
    color: Color,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(28.dp),
        color = color,
        tonalElevation = 1.dp,
    ) {
        Column(content = content)
    }
}

@Composable
private fun ReaderAppearanceDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = 20.dp),
        color = Color(0xFFE4E2E8),
    )
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(value, style = MaterialTheme.typography.bodyLarge)
            Surface(
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFFE2E1E7),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDecrease) {
                        Icon(
                            imageVector = Icons.Rounded.Remove,
                            contentDescription = "Decrease",
                        )
                    }
                    Box(
                        modifier = Modifier
                            .size(width = 1.dp, height = 28.dp)
                            .background(Color(0xFF7D7A85).copy(alpha = 0.35f)),
                    )
                    IconButton(onClick = onIncrease) {
                        Icon(
                            imageVector = Icons.Rounded.Add,
                            contentDescription = "Increase",
                        )
                    }
                }
            }
        }
    }
}

private class EpubWebViewClient(
    private val book: EpubBook,
    private val fontManager: ReaderFontManager,
) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (uri.host != "hoshi.local") return null
        if (uri.path.orEmpty().startsWith("/fonts/")) {
            val fileName = Uri.decode(uri.path.orEmpty().removePrefix("/fonts/"))
            val fontFile = fontManager.fontFileForRequest(fileName) ?: return null
            return WebResourceResponse(fontFile.mediaType(), null, fontFile.inputStream())
        }
        val path = uri.path.orEmpty().removePrefix("/epub/")
        val data = book.readResource(path) ?: return null
        return WebResourceResponse(book.mediaType(path), null, data.inputStream())
    }
}

private fun String.injectReaderShell(
    initialProgress: Double,
    settings: ReaderSettings,
    fontFaceUrl: String?,
): String {
    val css = ReaderContentStyles.styleTag(settings, fontFaceUrl)
    val script = ReaderPaginationScripts.shellScript(initialProgress, settings)
    val selectionScript = ReaderSelectionScripts.script()
    return replace("</head>", "$css\n$script\n$selectionScript\n</head>", ignoreCase = true)
        .takeIf { it != this }
        ?: "$css\n$script\n$selectionScript\n$this"
}

private fun File.mediaType(): String = when (extension.lowercase()) {
    "ttf" -> "font/ttf"
    "otf" -> "font/otf"
    "woff" -> "font/woff"
    "woff2" -> "font/woff2"
    else -> "application/octet-stream"
}

private fun WebView.navigatePage(
    direction: ReaderNavigationDirection,
    onLimit: () -> Boolean,
    onScrolled: (progress: Double) -> Unit,
) {
    evaluateJavascript(ReaderPaginationScripts.paginateInvocation(direction)) { result ->
        if (ReaderPaginationScripts.didScroll(result)) {
            evaluateJavascript(ReaderPaginationScripts.progressInvocation()) { progressResult ->
                ReaderPaginationScripts.doubleResult(progressResult)?.let(onScrolled)
            }
        } else {
            onLimit()
        }
    }
}

private class ReaderSelectionBridge(
    private val webView: WebView,
    private val onTextSelected: (ReaderSelectionData) -> Int?,
) {
    private val json = Json { ignoreUnknownKeys = true }

    @JavascriptInterface
    fun postMessage(message: String) {
        val payload = runCatching { json.decodeFromString<ReaderSelectionPayload>(message) }.getOrNull() ?: return
        val data = ReaderSelectionData(
            text = payload.text,
            sentence = payload.sentence,
            rect = ReaderSelectionRect(
                x = payload.rect.x,
                y = payload.rect.y,
                width = payload.rect.width,
                height = payload.rect.height,
            ),
            normalizedOffset = payload.normalizedOffset,
        )
        webView.post {
            val highlightCount = onTextSelected(data) ?: return@post
            webView.evaluateJavascript(ReaderSelectionScripts.highlightInvocation(highlightCount), null)
        }
    }
}

private class ReaderRestoreBridge(
    private val webView: WebView,
) {
    @JavascriptInterface
    fun postMessage(@Suppress("UNUSED_PARAMETER") message: String) {
        webView.post {
            webView.animate()
                .alpha(1f)
                .setDuration(250L)
                .start()
        }
    }
}

@Serializable
private data class ReaderSelectionPayload(
    val text: String,
    val sentence: String,
    val rect: ReaderSelectionPayloadRect,
    val normalizedOffset: Int? = null,
)

@Serializable
private data class ReaderSelectionPayloadRect(
    val x: Double,
    val y: Double,
    val width: Double,
    val height: Double,
)

private const val MAX_SELECTION_LENGTH = 16
private val ReaderWebViewTopPadding = 72.dp
private val ReaderWebViewBottomPadding = 96.dp

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

internal fun androidPixelsToCssPixels(value: Float, density: Float): Float =
    value / density.coerceAtLeast(1f)

private fun String.codePointCount(): Int =
    codePointCount(0, length)
