package moe.antimony.hoshi.features.reader

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReaderSystemFontRenderingTest {
    @Test
    fun androidJapanesePresetFallbacksRenderDifferentGlyphPixelsInWebView() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val pageLoaded = CountDownLatch(1)
        val scriptFinished = CountDownLatch(1)
        lateinit var webView: WebView
        var pixelDifference = 0

        instrumentation.runOnMainSync {
            webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    pageLoaded.countDown()
                }
            }
            webView.loadDataWithBaseURL(
                null,
                """
                <!doctype html>
                <html lang="ja">
                    <body></body>
                </html>
                """.trimIndent(),
                "text/html",
                "utf-8",
                null,
            )
        }

        assertTrue(pageLoaded.await(5, TimeUnit.SECONDS))

        instrumentation.runOnMainSync {
            webView.evaluateJavascript(renderDiffScript()) { result ->
                pixelDifference = result.toInt()
                scriptFinished.countDown()
            }
        }

        assertTrue(scriptFinished.await(5, TimeUnit.SECONDS))
        assertTrue("Expected Mincho and Gothic presets to render distinct pixels", pixelDifference > 500)
    }
}

private fun renderDiffScript(): String =
    """
    (() => {
        const sample = '読書漢字かな交じり文。';
        function pixels(fontFamily) {
            const canvas = document.createElement('canvas');
            canvas.width = 420;
            canvas.height = 96;
            const ctx = canvas.getContext('2d');
            ctx.fillStyle = '#fff';
            ctx.fillRect(0, 0, canvas.width, canvas.height);
            ctx.fillStyle = '#000';
            ctx.font = '48px ' + fontFamily;
            ctx.textBaseline = 'top';
            ctx.fillText(sample, 8, 8);
            return ctx.getImageData(0, 0, canvas.width, canvas.height).data;
        }
        const mincho = pixels("'Noto Serif CJK JP', 'NotoSerifCJKjp-Regular', serif");
        const gothic = pixels("'Noto Sans CJK JP', 'NotoSansCJKJP-Regular', sans-serif");
        let diff = 0;
        for (let i = 0; i < mincho.length; i += 4) {
            if (Math.abs(mincho[i] - gothic[i]) > 12) diff++;
        }
        return diff;
    })();
    """.trimIndent()
