package moe.antimony.hoshi.features.reader

import android.annotation.SuppressLint
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import moe.antimony.hoshi.epub.EpubBook

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderWebView(
    book: EpubBook,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var chapterPosition by remember(book) { mutableStateOf(ReaderChapterPosition(index = 0)) }
    var webView by remember { mutableStateOf<WebView?>(null) }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = book.title,
                        maxLines = 1,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Text("‹")
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color(0xFFF7F3EA)),
        ) {
            ChapterWebView(
                book = book,
                chapterPosition = chapterPosition,
                onWebViewReady = { webView = it },
                onNextChapter = {
                    val next = chapterPosition.nextOrNull(book.chapters.lastIndex)
                    if (next != null) {
                        chapterPosition = next
                        true
                    } else {
                        false
                    }
                },
                onPreviousChapter = {
                    val previous = chapterPosition.previousOrNull()
                    if (previous != null) {
                        chapterPosition = previous
                        true
                    } else {
                        false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            webView?.let { _ -> Unit }
        }
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
    modifier: Modifier = Modifier,
) {
    val chapter = book.chapters[chapterPosition.index]
    val html = remember(chapter, chapterPosition.progress) {
        chapter.html.injectReaderShell(initialProgress = chapterPosition.progress)
    }
    val baseUrl = remember(chapter) { "https://hoshi.local/epub/${chapter.href}" }

    AndroidView(
        modifier = modifier.background(Color(0xFFF7F3EA)),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                isVerticalScrollBarEnabled = false
                isHorizontalScrollBarEnabled = false
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                webViewClient = EpubWebViewClient(book)
                setOnTouchListener(object : SwipePageTouchListener(context) {
                    override fun onLeftSwipe() {
                        navigatePage(ReaderNavigationDirection.Backward, onPreviousChapter)
                    }

                    override fun onRightSwipe() {
                        navigatePage(ReaderNavigationDirection.Forward, onNextChapter)
                    }
                })
                onWebViewReady(this)
            }
        },
        update = { webView ->
            webView.webViewClient = EpubWebViewClient(book)
            webView.loadDataWithBaseURL(baseUrl, html, "text/html", "UTF-8", null)
        },
    )
}

private class EpubWebViewClient(private val book: EpubBook) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
        val uri = request.url ?: return null
        if (uri.host != "hoshi.local") return null
        val path = uri.path.orEmpty().removePrefix("/epub/")
        val data = book.readResource(path) ?: return null
        return WebResourceResponse(book.mediaType(path), null, data.inputStream())
    }
}

private fun String.injectReaderShell(initialProgress: Double): String {
    val css = ReaderContentStyles.styleTag()
    val script = ReaderPaginationScripts.shellScript(initialProgress)
    return replace("</head>", "$css\n$script\n</head>", ignoreCase = true)
        .takeIf { it != this }
        ?: "$css\n$script\n$this"
}

private fun WebView.navigatePage(
    direction: ReaderNavigationDirection,
    onLimit: () -> Boolean,
) {
    evaluateJavascript(ReaderPaginationScripts.paginateInvocation(direction)) { result ->
        if (!ReaderPaginationScripts.didScroll(result)) {
            onLimit()
        }
    }
}
