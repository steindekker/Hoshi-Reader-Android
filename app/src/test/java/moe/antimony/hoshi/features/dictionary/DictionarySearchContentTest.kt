package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import moe.antimony.hoshi.features.reader.ReaderSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DictionarySearchContentTest {
    @Test
    fun blankQueryClearsSearchContentLikeIos() {
        val state = DictionarySearchContent.runLookup(
            query = "   ",
            lookup = { error("lookup should not run for blank query") },
            assets = LookupPopupAssets(popupJs = "", popupCss = ""),
        )

        assertEquals("", state.lastQuery)
        assertEquals("", state.html)
        assertFalse(state.hasResults)
    }

    @Test
    fun nonBlankQueryRendersResultsThroughPopupPipeline() {
        val state = DictionarySearchContent.runLookup(
            query = " 猫 ",
            lookup = {
                listOf(
                    LookupResult(
                        matched = "猫",
                        deinflected = "猫",
                        process = emptyArray(),
                        term = TermResult(
                            expression = "猫",
                            reading = "ねこ",
                            rules = "",
                            glossaries = arrayOf(
                                GlossaryEntry(
                                    dictName = "JMdict",
                                    glossary = "cat",
                                    definitionTags = "",
                                    termTags = "",
                                ),
                            ),
                            frequencies = emptyArray<FrequencyEntry>(),
                            pitches = emptyArray<PitchEntry>(),
                        ),
                        preprocessorSteps = 0,
                    ),
                )
            },
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
            ),
        )

        assertEquals("猫", state.lastQuery)
        assertTrue(state.hasResults)
        assertTrue(state.html.contains("window.lookupEntries = ["))
        assertTrue(state.html.contains(""""expression":"猫""""))
        assertTrue(state.html.contains("""<div style="height: 118px;"></div>"""))
        assertTrue(state.html.contains("window.renderPopup();"))
    }

    @Test
    fun dictionarySearchCanRenderEInkPopupCssFromReaderSettings() {
        val state = DictionarySearchContent.runLookup(
            query = " 猫 ",
            lookup = { listOf(lookupResult()) },
            assets = LookupPopupAssets(
                popupJs = "window.renderPopup = function() {};",
                popupCss = ".entry-header {}",
            ),
            eInkMode = ReaderSettings(eInkMode = true).eInkMode,
        )

        assertTrue(state.hasResults)
        assertTrue(state.html.contains("""data-hoshi-eink-mode="true""""))
        assertTrue(state.html.contains("""html[data-hoshi-eink-mode="true"] .frequency-group"""))
    }

    @Test
    fun dictionaryPopupOptionsUseAppearancePopupSettingsLikeIos() {
        val options = dictionarySearchPopupOptions(
            readerSettings = ReaderSettings(
                eInkMode = true,
                popupWidth = 480,
                popupHeight = 360,
                popupFullWidth = true,
                popupSwipeToDismiss = true,
                popupSwipeThreshold = 65,
            ),
            dictionarySettings = DictionarySettings(maxResults = 7),
            darkMode = true,
            audioSettings = moe.antimony.hoshi.features.audio.AudioSettings(enableAutoplay = true),
        )

        assertFalse(options.isVertical)
        assertFalse(options.isFullWidth)
        assertEquals(480, options.width)
        assertEquals(360, options.height)
        assertTrue(options.swipeToDismiss)
        assertEquals(65, options.swipeThreshold)
        assertEquals(7, options.dictionarySettings.maxResults)
        assertTrue(options.darkMode)
        assertTrue(options.eInkMode)
        assertTrue(options.audioSettings.enableAutoplay)
    }

    @Test
    fun dictionarySearchHistoryGestureDoesNotConsumeWebViewVerticalScroll() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySearchView.kt").readText()
        val webViewModifier = source.substringAfter("html.isNotBlank() -> DictionaryResultWebView(")
            .substringBefore("errorMessage != null ->")

        assertFalse(webViewModifier.contains("detectDragGestures("))
        assertTrue(webViewModifier.contains("observeDictionaryHistorySwipe("))
    }

    @Test
    fun dictionaryResultWebViewKeepsRedirectResultsAcrossHistoryRecomposition() {
        val source = File("src/main/java/moe/antimony/hoshi/features/dictionary/DictionarySearchView.kt").readText()
        val webViewSource = source.substringAfter("private fun DictionaryResultWebView(")
            .substringBefore("@Composable\nprivate fun DictionarySearchMessage")
        val setupBeforeAndroidView = webViewSource.substringAfter("val lookupResultsHolder = remember")
            .substringBefore("AndroidView(")
        val reloadBranch = webViewSource.substringAfter("if (loadedHtml != html) {")
            .substringBefore("if (appliedClearSelectionSignal")

        assertFalse(setupBeforeAndroidView.contains("lookupResultsHolder.results = results"))
        assertTrue(reloadBranch.contains("lookupResultsHolder.results = results"))
        assertTrue(reloadBranch.indexOf("lookupResultsHolder.results = results") < reloadBranch.indexOf("loadDataWithBaseURL"))
    }

    private fun lookupResult(): LookupResult = LookupResult(
        matched = "猫",
        deinflected = "猫",
        process = emptyArray(),
        term = TermResult(
            expression = "猫",
            reading = "ねこ",
            rules = "",
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "JMdict",
                    glossary = "cat",
                    definitionTags = "",
                    termTags = "",
                ),
            ),
            frequencies = emptyArray<FrequencyEntry>(),
            pitches = emptyArray<PitchEntry>(),
        ),
        preprocessorSteps = 0,
    )
}
