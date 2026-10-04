package moe.antimony.hoshi.features.texthooker

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupOptions
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.features.anki.AnkiScreenshotRequest
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.dictionary.DictionarySearchRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextHookerViewModelTest {
    private val transport = FakeTextHookerTransport()
    private val lookup = FakeLookupRepository()

    private fun TestScope.viewModel(): TextHookerViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = TextHookerRepository(
            settings = MutableStateFlow(TextHookerSettings()),
            transport = transport,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            screenshotCache = TextHookerScreenshotCache(Files.createTempDirectory("texthooker-vm").toFile()),
        )
        val viewModel = TextHookerViewModel(repository, lookup, dispatcher, backgroundScope)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun TestScope.receive(vararg frames: String) {
        frames.forEach(transport.last::sendText)
        runCurrent()
    }

    @Test
    fun followsNewLinesWhileNoLookupIsActive() = runTest {
        val viewModel = viewModel()
        receive(helloJson(1, 1L to "一行目"))
        assertEquals(1L, viewModel.uiState.value.displayedLine?.id)

        receive(lineFrameJson(2, "二行目"))
        assertEquals(2L, viewModel.uiState.value.displayedLine?.id)
        assertFalse(viewModel.uiState.value.hasNewerLine)
        assertEquals("texthooker-root-2", viewModel.uiState.value.rootPopupId)
    }

    @Test
    fun activeLookupPinsTheLineAndShowsNewLineIndicator() = runTest {
        val viewModel = viewModel()
        receive(helloJson(1, 1L to "猫が好き"))
        lookup.results = listOf(lookupResult("好き"))

        assertEquals(1, viewModel.lookupRootRedirect("好き").size)

        runCurrent()
        assertEquals(2, viewModel.uiState.value.lookup.sentenceOffset)
        assertEquals(1, viewModel.uiState.value.lookup.backCount)

        receive(lineFrameJson(2, "次の行"))
        assertEquals(1L, viewModel.uiState.value.displayedLine?.id)
        assertTrue(viewModel.uiState.value.hasNewerLine)

        viewModel.jumpToLatest()
        runCurrent()
        assertEquals(2L, viewModel.uiState.value.displayedLine?.id)
        assertFalse(viewModel.uiState.value.hasNewerLine)
        assertTrue(viewModel.uiState.value.lookup.results.isEmpty())
        assertEquals(0, viewModel.uiState.value.lookup.backCount)
    }

    @Test
    fun selectingAnOlderLinePinsItAndClearsLookup() = runTest {
        val viewModel = viewModel()
        receive(helloJson(2, 1L to "一", 2L to "二"))
        lookup.results = listOf(lookupResult("二"))
        viewModel.lookupRootRedirect("二")
        runCurrent()

        viewModel.selectLine(1)
        runCurrent()
        assertEquals(1L, viewModel.uiState.value.displayedLine?.id)
        assertTrue(viewModel.uiState.value.lookup.results.isEmpty())

        receive(lineFrameJson(3, "三"))
        assertEquals(1L, viewModel.uiState.value.displayedLine?.id)
        assertTrue(viewModel.uiState.value.hasNewerLine)
    }

    @Test
    fun rootMiningContextCarriesSentenceOffsetTitleAndScreenshot() = runTest {
        val viewModel = viewModel()
        receive(helloJson(4, 4L to "「本を読む」"))
        lookup.results = listOf(lookupResult("読む"))
        viewModel.lookupRootRedirect("読む」")
        runCurrent()

        val context = viewModel.rootMiningContext()!!
        assertEquals("「本を読む」", context.sentence)
        assertEquals(3, context.sentenceOffset)
        assertEquals("steamdeck", context.documentTitle)
        assertEquals(AnkiScreenshotRequest(lineId = 4), context.screenshot)
        assertNull(context.coverPath)
    }

    @Test
    fun failedRootRedirectKeepsState() = runTest {
        val viewModel = viewModel()
        receive(helloJson(1, 1L to "ああ"))
        lookup.results = emptyList()
        assertTrue(viewModel.lookupRootRedirect("ああ").isEmpty())
        assertEquals(0, viewModel.uiState.value.lookup.backCount)
        receive(lineFrameJson(2, "いい"))
        assertEquals(2L, viewModel.uiState.value.displayedLine?.id)
    }

    @Test
    fun rootHistoryNavigationAndSourceRestore() = runTest {
        val viewModel = viewModel()
        receive(helloJson(1, 1L to "猫が好き"))
        lookup.results = listOf(lookupResult("猫"))
        viewModel.lookupRootRedirect("猫が好き")
        runCurrent()
        viewModel.lookupRootRedirect("好き")
        viewModel.navigateRootBack()
        viewModel.restoreRootSourceHistory(0)
        runCurrent()
        val state = viewModel.uiState.value.lookup
        assertEquals(1, state.backCount)
        assertEquals(1, state.forwardCount)
        assertEquals(0, state.sentenceOffset)
        viewModel.restoreRootSourceHistory(99)
        runCurrent()
        assertEquals(0, viewModel.uiState.value.lookup.sentenceOffset)
    }

    @Test
    fun screenshotPreviewFailureReportsMessage() = runTest {
        val viewModel = viewModel()
        transport.screenshotResult = TextHookerHttpResult.HttpError(503, null)
        viewModel.requestScreenshotPreview(5)
        runCurrent()
        assertEquals(TextHookerScreenshotPreviewState.Failed(5), viewModel.uiState.value.screenshotPreview)
        val message = viewModel.uiState.value.message!!
        viewModel.consumeMessage(message)
        runCurrent()
        assertNull(viewModel.uiState.value.message)
    }

    private fun lookupResult(matched: String): LookupResult = LookupResult(
        matched = matched,
        term = TermResult(
            expression = matched,
            reading = matched,
            rules = "",
            glossaries = arrayOf(GlossaryEntry(dictName = "JMdict", glossary = "g", definitionTags = "", termTags = "")),
            frequencies = emptyArray<FrequencyEntry>(),
            pitches = emptyArray<PitchEntry>(),
        ),
        traceCandidates = emptyArray(),
    )
}

private class FakeLookupRepository : DictionarySearchRepository {
    var results: List<LookupResult> = emptyList()
    override val dictionarySettings: StateFlow<DictionarySettings> = MutableStateFlow(DictionarySettings())
    override val audioSettings: StateFlow<AudioSettings> = MutableStateFlow(AudioSettings())

    override suspend fun rebuildLookupQuery() = Unit

    override fun lookup(query: String, maxResults: Int, scanLength: Int, options: LookupOptions): List<LookupResult> = results

    override fun dictionaryStyles(): Map<String, String> = emptyMap()
}
