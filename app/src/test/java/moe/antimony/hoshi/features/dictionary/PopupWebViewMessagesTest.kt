package moe.antimony.hoshi.features.dictionary

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class PopupWebViewMessagesTest {
    @Test
    fun popupWebViewClientServesDictionaryImagesLikeIosImageHandler() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/PopupWebViewMessages.kt")
            .readText()

        assertTrue(source.contains("isIosImageScheme"))
        assertTrue(source.contains("isAndroidImageEndpoint"))
        assertTrue(source.contains("getMediaFile"))
        assertTrue(source.contains("image/svg+xml"))
    }

    @Test
    fun dictionaryImageMimeTypeMatchesIosImageHandler() {
        assertTrue(dictionaryImageMimeType("icons/arrow.svg") == "image/svg+xml")
        assertTrue(dictionaryImageMimeType("photo.PNG") == "image/png")
        assertTrue(dictionaryImageMimeType("image.jpeg") == "image/jpeg")
        assertTrue(dictionaryImageMimeType("unknown.bin") == "application/octet-stream")
    }

    @Test
    fun popupJavascriptGivesDictionaryImageContainersExplicitHeightForAndroidWebView() {
        val source = File("src/main/assets/hoshi-popup/popup.js").readText()

        assertTrue(source.contains("function applyDictionaryImageContainerFixes(imageContainer)"))
        assertTrue(source.contains("window.disablePopupImageViewportMaxHeight"))
        assertTrue(source.contains("imageContainer.style.maxHeight = 'none';"))
        assertTrue(source.contains("applyDictionaryImageContainerFixes(imageContainer);"))
    }
}
