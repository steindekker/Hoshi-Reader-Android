package moe.antimony.hoshi.features.live

import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.AnkiLiveCueRef
import moe.antimony.hoshi.features.anki.AnkiLiveMediaResult
import moe.antimony.hoshi.features.anki.AnkiScreenshotRequest
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TestLiveClock(private val scope: TestScope) : LiveClock {
    override fun elapsedMillis(): Long = scope.testScheduler.currentTime

    override fun nowMillis(): Long = WallOffset + scope.testScheduler.currentTime

    companion object {
        const val WallOffset = 1_000_000L
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class LiveRepositoryTest {
    private val transport = FakeLiveTransport()
    private val deck = LiveHost("deck", "deck")
    private val pc = LiveHost("pc", "pc", token = "tok")
    private val settings = MutableStateFlow(LiveSettings(hosts = listOf(deck, pc)))

    private fun TestScope.repository(): LiveRepository =
        LiveRepository(
            settings = settings,
            transport = transport,
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            previewCache = LivePreviewCache(Files.createTempDirectory("live").toFile()),
            clock = TestLiveClock(this),
        )

    private fun TestScope.subscribe(repository: LiveRepository) {
        backgroundScope.launch { repository.state.collect {} }
        runCurrent()
    }

    private fun TestScope.send(hostId: String, vararg frames: String) {
        frames.forEach(transport.lastFor(hostId)::sendText)
        runCurrent()
    }

    @Test
    fun connectsToEveryHostOnlyWhileSubscribed() = runTest {
        val repository = repository()
        runCurrent()
        assertTrue(transport.connections.isEmpty())

        subscribe(repository)
        assertEquals(listOf("deck", "pc"), transport.connections.map { it.endpoint.hostId })
        assertEquals("tok", transport.lastFor("pc").endpoint.token)

        send("deck", helloJson(sessionJson("vn", "vn", listOf(vnCueJson(1, "一"), vnCueJson(2, "二")))))
        send("pc", helloJson(sessionJson("mpv", "video", listOf(timedCueJson(1, "あ", 0, 1000)), capabilities = VideoCapabilities), hostname = "arch"))
        val state = repository.state.value
        assertEquals(LiveConnectionState.Connected, state.host("deck")?.connection)
        assertEquals(listOf("deck/vn", "pc/mpv"), state.sessions.map { it.key })
        assertEquals("arch", state.host("pc")?.displayName)
        assertEquals(listOf(1L, 2L), state.session("deck/vn")?.cues?.map { it.id })
    }

    @Test
    fun releasesTheSocketsAfterSubscribersLeave() = runTest {
        val repository = repository()
        val job = backgroundScope.launch { repository.state.collect {} }
        runCurrent()
        job.cancel()
        advanceTimeBy(LiveRepository.StopTimeoutMillis + 1)
        runCurrent()
        assertTrue(transport.connections.all { it.cancelled })
    }

    @Test
    fun offlineHostBacksOffWithoutAffectingTheOthers() = runTest {
        val repository = repository()
        subscribe(repository)
        send("deck", helloJson(sessionJson("vn", "vn", listOf(vnCueJson(1, "一")))))
        transport.lastFor("pc").send(LiveSocketEvent.Failed(httpCode = null))
        runCurrent()
        val pcState = repository.state.value.host("pc")!!.connection as LiveConnectionState.Disconnected
        assertEquals(LiveDisconnectReason.Unreachable, pcState.reason)
        assertEquals(1_000L, pcState.retryInMillis)
        assertEquals(LiveConnectionState.Connected, repository.state.value.host("deck")?.connection)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, transport.connections.count { it.endpoint.hostId == "pc" })
        transport.lastFor("pc").send(LiveSocketEvent.Failed(httpCode = null))
        runCurrent()
        val second = repository.state.value.host("pc")!!.connection as LiveConnectionState.Disconnected
        assertEquals(2_000L, second.retryInMillis)

        // A dropped host loses its sessions until the next hello.
        transport.lastFor("deck").send(LiveSocketEvent.Closed(1001))
        runCurrent()
        assertTrue(repository.state.value.sessions.isEmpty())
    }

    @Test
    fun appliesCuesSessionsAndPlaybackEvents() = runTest {
        val repository = repository()
        subscribe(repository)
        send(
            "pc",
            helloJson(
                sessionJson("mpv", "video", listOf(timedCueJson(1, "あ", 0, 1000)), capabilities = VideoCapabilities,
                    playback = playbackObject(500, paused = true)),
            ),
        )
        send("pc", replaceJson("mpv", "t2", timedCueJson(1, "い", 0, 1000), timedCueJson(2, "う", 2000, 3000)))
        var mpv = repository.state.value.session("pc/mpv")!!
        assertEquals("t2", mpv.info.trackId)
        assertEquals(listOf("い", "う"), mpv.cues.map { it.text })

        // A session upsert with a new track id drops the old cues.
        send("pc", """{"type":"session","session":{"id":"mpv","kind":"video","title":"Ep 2","capabilities":["control"],"trackId":"t3"}}""")
        mpv = repository.state.value.session("pc/mpv")!!
        assertEquals("Ep 2", mpv.info.title)
        assertTrue(mpv.cues.isEmpty())

        // Playback extrapolates locally while playing.
        send("pc", playbackJson("mpv", 10_000, paused = false, speed = 2.0))
        advanceTimeBy(1_000)
        runCurrent()
        mpv = repository.state.value.session("pc/mpv")!!
        assertEquals(12_000L, mpv.positionAt(testScheduler.currentTime))
        send("pc", playbackJson("mpv", 12_000, paused = true))
        advanceTimeBy(5_000)
        assertEquals(12_000L, repository.state.value.session("pc/mpv")!!.positionAt(testScheduler.currentTime))

        send("deck", helloJson(sessionJson("vn", "vn", listOf(vnCueJson(1, "一")))))
        send("deck", appendJson("vn", vnCueJson(1, "一"), vnCueJson(2, "二")))
        assertEquals(listOf(1L, 2L), repository.state.value.session("deck/vn")!!.cues.map { it.id })

        send("pc", """{"type":"sessionEnded","sessionId":"mpv"}""")
        assertNull(repository.state.value.session("pc/mpv"))
    }

    @Test
    fun activityFromHelloIsCorrectedForClockSkewAndUpdatedByEvents() = runTest {
        val repository = repository()
        subscribe(repository)
        val phoneNow = TestLiveClock.WallOffset + testScheduler.currentTime
        // The bridge clock runs 60 s ahead; its session was active 10 s ago.
        val bridgeNow = phoneNow + 60_000
        send("pc", helloJson(sessionJson("mpv", "video", activeAt = bridgeNow - 10_000), now = bridgeNow))
        assertEquals(phoneNow - 10_000, repository.state.value.session("pc/mpv")!!.activityAt)

        advanceTimeBy(3_000)
        send("pc", playbackJson("mpv", 0, paused = false))
        assertEquals(TestLiveClock.WallOffset + testScheduler.currentTime, repository.state.value.session("pc/mpv")!!.activityAt)
    }

    @Test
    fun incompatibleBridgeStopsRetrying() = runTest {
        val repository = repository()
        subscribe(repository)
        transport.lastFor("deck").send(LiveSocketEvent.Failed(httpCode = 404))
        send("pc", helloJson(protocol = 3))
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(
            LiveConnectionState.Disconnected(LiveDisconnectReason.IncompatibleProtocol(1), null),
            repository.state.value.host("deck")?.connection,
        )
        assertEquals(
            LiveConnectionState.Disconnected(LiveDisconnectReason.IncompatibleProtocol(3), null),
            repository.state.value.host("pc")?.connection,
        )
        assertEquals(2, transport.connections.size)
    }

    @Test
    fun controlAndMediaGoToTheSessionsHost() = runTest {
        val repository = repository()
        assertEquals(LiveCommandResult.Success, repository.control("pc/mpv", "seek", 4_000))
        assertEquals(FakeLiveTransport.ControlCall("pc", "mpv", "seek", 4_000), transport.controls.single())
        transport.controlResult = LiveHttpResult.NetworkError
        val failure = repository.control("pc/mpv", "pause") as LiveCommandResult.Failure
        assertEquals(
            UiText.Resource(R.string.live_control_failed, UiText.Resource(R.string.live_media_reason_unreachable, "pc")),
            failure.message,
        )

        val cue = AnkiLiveCueRef("pc", "mpv", "t1", 12)
        transport.mediaResult = LiveHttpResult.Success(LiveMedia(byteArrayOf(1, 2), "image/webp"))
        val clip = repository.fetchScreenshot(AnkiScreenshotRequest(cue, animated = true)) as AnkiLiveMediaResult.Success
        assertEquals("image/webp", clip.mimeType)
        val clipCall = transport.mediaCalls.last()
        assertEquals(LiveMediaCommand.Clip, clipCall.command)
        assertEquals(JsonPrimitive(12), clipCall.body["cueId"])
        assertEquals(JsonPrimitive("t1"), clipCall.body["trackId"])
        assertEquals(JsonPrimitive(LiveDefaults.ClipMaxWidth), clipCall.body["maxWidth"])

        repository.fetchScreenshot(AnkiScreenshotRequest(cue.copy(hostId = "deck", sessionId = "vn")))
        assertEquals(LiveMediaCommand.Screenshot, transport.mediaCalls.last().command)
        assertEquals(JsonPrimitive(1280), transport.mediaCalls.last().body["maxWidth"])

        transport.mediaResult = LiveHttpResult.HttpError(409, "track changed")
        assertEquals(
            AnkiLiveMediaResult.Failure(
                UiText.Resource(R.string.live_audio_failed, UiText.Resource(R.string.live_media_reason_track_changed)),
            ),
            repository.fetchSentenceAudio(cue),
        )
        assertEquals(LiveMediaCommand.Audio, transport.mediaCalls.last().command)
        assertEquals(
            AnkiLiveMediaResult.Failure(
                UiText.Resource(R.string.live_picture_failed, UiText.Resource(R.string.live_media_reason_no_host)),
            ),
            repository.fetchScreenshot(AnkiScreenshotRequest(cue.copy(hostId = "gone"))),
        )
    }

    @Test
    fun testConnectionMapsResults() = runTest {
        val repository = repository()
        transport.statusResult = LiveHttpResult.Success(LiveStatusResponse(LiveBridgeStatus(protocol = 2, hostname = "pc")))
        assertTrue(repository.testConnection(pc) is LiveTestResult.Success)
        transport.statusResult = LiveHttpResult.HttpError(404, null)
        assertEquals(
            LiveTestResult.Failure(LiveDisconnectReason.IncompatibleProtocol(1)),
            repository.testConnection(pc),
        )
        transport.statusResult = LiveHttpResult.HttpError(401, null)
        assertEquals(LiveTestResult.Failure(LiveDisconnectReason.Unauthorized), repository.testConnection(pc))
        assertEquals(
            LiveTestResult.Failure(LiveDisconnectReason.InvalidSettings),
            repository.testConnection(LiveHost("x", "bad host")),
        )
    }
}
