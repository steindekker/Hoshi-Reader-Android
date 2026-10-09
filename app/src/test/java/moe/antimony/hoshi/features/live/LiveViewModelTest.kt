package moe.antimony.hoshi.features.live

import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.features.anki.AnkiLiveCueRef
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
class LiveViewModelTest {
    private val transport = FakeLiveTransport()
    private val lookup = FakeLookupRepository()
    private val settings = MutableStateFlow(LiveSettings(hosts = listOf(LiveHost("deck", "deck"), LiveHost("pc", "pc"))))

    private fun TestScope.viewModel(): LiveViewModel {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val clock = TestLiveClock(this)
        val repository = LiveRepository(
            settings = settings,
            transport = transport,
            scope = backgroundScope,
            ioDispatcher = dispatcher,
            previewCache = LivePreviewCache(Files.createTempDirectory("live-vm").toFile()),
            clock = clock,
        )
        val viewModel = LiveViewModel(repository, lookup, dispatcher, backgroundScope, clock)
        backgroundScope.launch { viewModel.uiState.collect {} }
        runCurrent()
        return viewModel
    }

    private fun TestScope.receive(hostId: String, vararg frames: String) {
        frames.forEach(transport.lastFor(hostId)::sendText)
        runCurrent()
    }

    private fun TestScope.tick(millis: Long = LiveViewModel.TickMillis) {
        advanceTimeBy(millis + 1)
        runCurrent()
    }

    private val videoCues = listOf(
        timedCueJson(1, "一行目", 1_000, 3_000),
        timedCueJson(2, "猫が好き", 4_000, 6_000),
        timedCueJson(3, "三行目", 7_000, 9_000),
    )

    private fun TestScope.helloVideo(positionMs: Long, paused: Boolean, title: String = "Frieren 01") =
        receive(
            "pc",
            helloJson(
                sessionJson("mpv", "video", videoCues, title = title, capabilities = VideoCapabilities,
                    playback = playbackObject(positionMs, paused)),
                hostname = "arch",
            ),
        )

    @Test
    fun vnFocusFollowsTheNewestCue() = runTest {
        val viewModel = viewModel()
        receive("deck", helloJson(sessionJson("vn", "vn", listOf(vnCueJson(1, "一行目")))))
        assertEquals(1L, viewModel.uiState.value.focusedCue?.id)
        receive("deck", appendJson("vn", vnCueJson(2, "二行目")))
        tick()
        assertEquals(2L, viewModel.uiState.value.focusedCue?.id)
        assertEquals(2L, viewModel.uiState.value.playingCueId)
        assertFalse(viewModel.uiState.value.showBackToPlaying)
    }

    @Test
    fun followsTheMostRecentlyActiveSessionUnlessOneIsPicked() = runTest {
        val viewModel = viewModel()
        receive("deck", helloJson(sessionJson("vn", "vn", listOf(vnCueJson(1, "一")))))
        tick()
        assertEquals("deck/vn", viewModel.uiState.value.activeSession?.key)

        advanceTimeBy(1_000)
        helloVideo(positionMs = 0, paused = false)
        tick()
        assertEquals("pc/mpv", viewModel.uiState.value.activeSession?.key)

        viewModel.selectSession("deck/vn")
        runCurrent()
        receive("pc", playbackJson("mpv", 500, paused = false))
        tick()
        assertEquals("deck/vn", viewModel.uiState.value.activeSession?.key)
        assertTrue(viewModel.uiState.value.sessionPinned)

        viewModel.selectSession(null)
        tick()
        assertEquals("pc/mpv", viewModel.uiState.value.activeSession?.key)
    }

    @Test
    fun videoFocusTracksTheExtrapolatedPlayingCue() = runTest {
        val viewModel = viewModel()
        helloVideo(positionMs = 1_500, paused = false)
        tick()
        assertEquals(1L, viewModel.uiState.value.playingCueId)
        assertEquals(1L, viewModel.uiState.value.focusedCue?.id)
        tick(3_000)
        assertEquals(2L, viewModel.uiState.value.playingCueId)
        assertEquals(2L, viewModel.uiState.value.focusedCue?.id)
        assertEquals("live-root-${"pc/mpv".hashCode().toUInt()}-2", viewModel.uiState.value.rootPopupId)
    }

    @Test
    fun lookupPinsTheFocusedCueAndPausesPlayback() = runTest {
        val viewModel = viewModel()
        helloVideo(positionMs = 4_500, paused = false)
        tick()
        lookup.results = listOf(lookupResult("好き"))
        assertEquals(1, viewModel.lookupRootRedirect("好き").size)
        runCurrent()
        assertEquals(2, viewModel.uiState.value.lookup.sentenceOffset)
        assertEquals(FakeLiveTransport.ControlCall("pc", "mpv", "pause", null), transport.controls.single())

        // Playback moves on (the pause has not arrived yet): the transcript follows, the focus stays.
        tick(3_000)
        assertEquals(3L, viewModel.uiState.value.playingCueId)
        assertEquals(2L, viewModel.uiState.value.focusedCue?.id)
        assertTrue(viewModel.uiState.value.showBackToPlaying)

        viewModel.backToPlaying()
        runCurrent()
        assertEquals(3L, viewModel.uiState.value.focusedCue?.id)
        assertTrue(viewModel.uiState.value.lookup.results.isEmpty())
    }

    @Test
    fun autoPauseRespectsTheSettingAndPausedSessions() = runTest {
        settings.value = settings.value.copy(autoPause = false)
        val viewModel = viewModel()
        helloVideo(positionMs = 4_500, paused = false)
        tick()
        lookup.results = listOf(lookupResult("猫"))
        viewModel.lookupRootRedirect("猫が好き")
        runCurrent()
        assertTrue(transport.controls.isEmpty())

        settings.value = settings.value.copy(autoPause = true)
        receive("pc", playbackJson("mpv", 4_500, paused = true))
        viewModel.lookupRootRedirect("猫が好き")
        runCurrent()
        assertTrue(transport.controls.isEmpty())
    }

    @Test
    fun pickingACuePinsItAndDismissingALookupResumesFollowing() = runTest {
        val viewModel = viewModel()
        helloVideo(positionMs = 7_500, paused = true)
        tick()
        viewModel.focusCue(1)
        tick()
        assertEquals(1L, viewModel.uiState.value.focusedCue?.id)
        assertTrue(viewModel.uiState.value.showBackToPlaying)

        viewModel.backToPlaying()
        runCurrent()
        lookup.results = listOf(lookupResult("三"))
        viewModel.lookupRootRedirect("三行目")
        viewModel.dismissRootPopup()
        tick()
        assertEquals(3L, viewModel.uiState.value.focusedCue?.id)
        assertFalse(viewModel.uiState.value.lookup.isActive)
    }

    @Test
    fun playbackControlsSendCommands() = runTest {
        val viewModel = viewModel()
        helloVideo(positionMs = 0, paused = true)
        tick()
        viewModel.togglePlayback()
        viewModel.seekToCue(2)
        runCurrent()
        assertEquals(
            listOf(
                FakeLiveTransport.ControlCall("pc", "mpv", "play", null),
                FakeLiveTransport.ControlCall("pc", "mpv", "seek", 4_000),
            ),
            transport.controls,
        )
        transport.controlResult = LiveHttpResult.NetworkError
        viewModel.togglePlayback()
        runCurrent()
        assertTrue(viewModel.uiState.value.message != null)
    }

    @Test
    fun videoMiningContextCarriesTimestampedTitleClipAndAudio() = runTest {
        val viewModel = viewModel()
        helloVideo(positionMs = 4_500, paused = true)
        tick()
        lookup.results = listOf(lookupResult("好き"))
        viewModel.lookupRootRedirect("好き")
        runCurrent()
        val context = viewModel.rootMiningContext()!!
        val cue = AnkiLiveCueRef("pc", "mpv", "t1", 2)
        assertEquals("猫が好き", context.sentence)
        assertEquals(2, context.sentenceOffset)
        assertEquals("Frieren 01 (00m04s)", context.documentTitle)
        assertEquals(AnkiScreenshotRequest(cue, animated = true), context.screenshot)
        assertEquals(cue, context.sentenceAudio)
    }

    @Test
    fun vnMiningContextUsesAScreenshotAndTheHostName() = runTest {
        val viewModel = viewModel()
        receive("deck", helloJson(sessionJson("vn", "vn", listOf(vnCueJson(4, "「本を読む」")), capabilities = listOf("screenshot"))))
        tick()
        val context = viewModel.rootMiningContext()!!
        assertEquals("deck", context.documentTitle)
        assertEquals(AnkiScreenshotRequest(AnkiLiveCueRef("deck", "vn", "t1", 4)), context.screenshot)
        assertNull(context.sentenceAudio)
        assertNull(context.coverPath)
    }

    @Test
    fun previewFailureReportsMessage() = runTest {
        val viewModel = viewModel()
        transport.mediaResult = LiveHttpResult.HttpError(503, "no backend")
        viewModel.requestPreview(AnkiScreenshotRequest(AnkiLiveCueRef("deck", "vn", "log", 5)))
        runCurrent()
        assertEquals(LivePreviewState.Failed, viewModel.uiState.value.preview)
        val message = viewModel.uiState.value.message!!
        viewModel.consumeMessage(message)
        runCurrent()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun messagesQueueInOrderAndCollapseConsecutiveDuplicates() = runTest {
        val viewModel = viewModel()
        val added = UiText.Resource(R.string.live_card_failed)
        val warning = UiText.Resource(R.string.live_picture_attach_failed)
        viewModel.showMessage(added)
        viewModel.showMessage(added)
        viewModel.showMessage(warning)
        runCurrent()
        assertEquals(added, viewModel.uiState.value.message)
        viewModel.consumeMessage(added)
        runCurrent()
        assertEquals(warning, viewModel.uiState.value.message)
        viewModel.consumeMessage(warning)
        runCurrent()
        assertNull(viewModel.uiState.value.message)
    }

    @Test
    fun failedMinesReportASnackbarAndSuccessesDoNot() = runTest {
        val viewModel = viewModel()
        viewModel.onCardMined(mined = true)
        runCurrent()
        assertNull(viewModel.uiState.value.message)
        viewModel.onCardMined(mined = false)
        runCurrent()
        assertEquals(UiText.Resource(R.string.live_card_failed), viewModel.uiState.value.message)
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
