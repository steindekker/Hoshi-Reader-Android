package moe.antimony.hoshi.features.texthooker

import java.nio.file.Files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.AnkiScreenshotRequest
import moe.antimony.hoshi.features.anki.AnkiScreenshotResult
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TextHookerRepositoryTest {
    private val transport = FakeTextHookerTransport()
    private val settings = MutableStateFlow(TextHookerSettings())

    private fun TestScope.repository(capacity: Int = 200): TextHookerRepository =
        TextHookerRepository(
            settings = settings,
            transport = transport,
            scope = backgroundScope,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            screenshotCache = TextHookerScreenshotCache(Files.createTempDirectory("texthooker").toFile()),
            logCapacity = capacity,
        )

    private fun TestScope.subscribe(repository: TextHookerRepository) {
        backgroundScope.launch { repository.state.collect {} }
        runCurrent()
    }

    @Test
    fun connectsOnlyWhileSubscribedAndPublishesHelloLines() = runTest {
        val repository = repository()
        runCurrent()
        assertTrue(transport.connections.isEmpty())

        subscribe(repository)
        assertEquals(1, transport.connections.size)
        assertNull(transport.last.afterLineId)
        assertEquals(TextHookerConnectionState.Connecting(0), repository.state.value.connection)

        transport.last.sendText(helloJson(2, 1L to "一", 2L to "二"))
        runCurrent()
        assertEquals(TextHookerConnectionState.Connected, repository.state.value.connection)
        assertEquals(listOf(1L, 2L), repository.state.value.lines.map { it.id })
        assertEquals("steamdeck", repository.state.value.serverStatus?.hostname)

        transport.last.sendText(lineFrameJson(3, "三"))
        transport.last.sendText(lineFrameJson(3, "三"))
        transport.last.sendText("""{"type":"something-new"}""")
        runCurrent()
        assertEquals(listOf(1L, 2L, 3L), repository.state.value.lines.map { it.id })
    }

    @Test
    fun releasesTheSocketAfterSubscribersLeave() = runTest {
        val repository = repository()
        val job = backgroundScope.launch { repository.state.collect {} }
        runCurrent()
        transport.last.sendText(helloJson(0))
        runCurrent()
        job.cancel()
        advanceTimeBy(TextHookerRepository.StopTimeoutMillis + 1)
        runCurrent()
        assertTrue(transport.last.cancelled)
    }

    @Test
    fun reconnectsWithBackoffAndFillsGapsAfterLatestId() = runTest {
        val repository = repository()
        subscribe(repository)
        transport.last.sendText(helloJson(5, 4L to "四", 5L to "五"))
        runCurrent()

        transport.last.send(TextHookerSocketEvent.Failed(httpCode = null))
        runCurrent()
        val disconnected = repository.state.value.connection as TextHookerConnectionState.Disconnected
        assertEquals(TextHookerDisconnectReason.Unreachable, disconnected.reason)
        assertEquals(1_000L, disconnected.retryInMillis)
        assertEquals(1, transport.connections.size)

        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2, transport.connections.size)
        assertEquals(5L, transport.last.afterLineId)

        // A failure before hello keeps backing off.
        transport.last.send(TextHookerSocketEvent.Failed(httpCode = null))
        runCurrent()
        assertEquals(2_000L, (repository.state.value.connection as TextHookerConnectionState.Disconnected).retryInMillis)
        advanceTimeBy(2_001)
        runCurrent()
        assertEquals(3, transport.connections.size)

        transport.last.sendText(helloJson(7, 6L to "六", 7L to "七"))
        runCurrent()
        assertEquals(listOf(4L, 5L, 6L, 7L), repository.state.value.lines.map { it.id })

        // A successful session resets the backoff.
        transport.last.send(TextHookerSocketEvent.Closed(1000))
        runCurrent()
        val closed = repository.state.value.connection as TextHookerConnectionState.Disconnected
        assertEquals(TextHookerDisconnectReason.ServerClosed, closed.reason)
        assertEquals(1_000L, closed.retryInMillis)
    }

    @Test
    fun retryNowReconnectsImmediately() = runTest {
        val repository = repository()
        subscribe(repository)
        transport.last.send(TextHookerSocketEvent.Failed(httpCode = 401))
        runCurrent()
        assertEquals(
            TextHookerDisconnectReason.Unauthorized,
            (repository.state.value.connection as TextHookerConnectionState.Disconnected).reason,
        )
        repository.retryNow()
        runCurrent()
        assertEquals(2, transport.connections.size)
        assertEquals(TextHookerConnectionState.Connecting(0), repository.state.value.connection)
    }

    @Test
    fun incompatibleProtocolStopsRetrying() = runTest {
        val repository = repository()
        subscribe(repository)
        transport.last.sendText(helloJson(0, protocol = 2))
        runCurrent()
        assertEquals(
            TextHookerConnectionState.Disconnected(TextHookerDisconnectReason.IncompatibleProtocol(2), null),
            repository.state.value.connection,
        )
        assertTrue(transport.last.cancelled)
        advanceTimeBy(60_000)
        assertEquals(1, transport.connections.size)
    }

    @Test
    fun settingsChangesReconnectAndSwitchLogs() = runTest {
        val repository = repository()
        subscribe(repository)
        transport.last.sendText(helloJson(1, 1L to "一"))
        runCurrent()

        settings.value = TextHookerSettings(host = "other-deck", port = 9000, token = "t")
        runCurrent()
        assertTrue(transport.connections[0].cancelled)
        assertEquals(2, transport.connections.size)
        assertEquals("other-deck", transport.last.endpoint.host)
        assertEquals("t", transport.last.endpoint.token)
        assertNull(transport.last.afterLineId)
        assertTrue(repository.state.value.lines.isEmpty())

        // Unrelated settings do not restart the connection.
        settings.value = settings.value.copy(screenshotMaxWidth = 640)
        runCurrent()
        assertEquals(2, transport.connections.size)

        settings.value = settings.value.copy(host = "")
        runCurrent()
        assertEquals(TextHookerConnectionState.NotConfigured, repository.state.value.connection)
    }

    @Test
    fun serverLogResetReplacesTheLocalLog() = runTest {
        val repository = repository()
        subscribe(repository)
        transport.last.sendText(helloJson(9, 8L to "八", 9L to "九"))
        runCurrent()
        transport.last.send(TextHookerSocketEvent.Closed(1001))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        transport.last.sendText(helloJson(1, 1L to "新"))
        runCurrent()
        assertEquals(listOf(1L), repository.state.value.lines.map { it.id })
    }

    @Test
    fun logIsBounded() = runTest {
        val repository = repository(capacity = 3)
        subscribe(repository)
        transport.last.sendText(helloJson(5, 1L to "a", 2L to "b", 3L to "c", 4L to "d", 5L to "e"))
        runCurrent()
        assertEquals(listOf(3L, 4L, 5L), repository.state.value.lines.map { it.id })
    }

    @Test
    fun catchUpLinesStreamedRightAfterHelloAreMergedInOrder() = runTest {
        val repository = repository(capacity = 4)
        subscribe(repository)
        transport.last.sendText(helloJson(2, 1L to "a", 2L to "b"))
        runCurrent()
        transport.last.send(TextHookerSocketEvent.Closed(1001))
        runCurrent()
        advanceTimeBy(1_001)
        runCurrent()
        assertEquals(2L, transport.last.afterLineId)
        // A far-behind client gets the first batch in hello and the rest as ordinary line frames.
        receiveAll(
            helloJson(6, 3L to "c", 4L to "d"),
            lineFrameJson(5, "e"),
            lineFrameJson(4, "d"),
            lineFrameJson(6, "f"),
        )
        runCurrent()
        assertEquals(listOf(3L, 4L, 5L, 6L), repository.state.value.lines.map { it.id })
        assertEquals(TextHookerConnectionState.Connected, repository.state.value.connection)
    }

    private fun receiveAll(vararg frames: String) = frames.forEach(transport.last::sendText)

    @Test
    fun screenshotFailuresMapToLocalizedMessages() = runTest {
        val repository = repository()
        transport.screenshotResult = TextHookerHttpResult.HttpError(503, "no backend")
        assertEquals(
            AnkiScreenshotResult.Failure(UiText.Resource(R.string.texthooker_screenshot_unavailable)),
            repository.fetchScreenshot(AnkiScreenshotRequest(lineId = 4)),
        )
        assertEquals(listOf<Long?>(4L), transport.screenshotRequests)
        transport.screenshotResult = TextHookerHttpResult.NetworkError
        assertEquals(
            AnkiScreenshotResult.Failure(UiText.Resource(R.string.texthooker_screenshot_unreachable)),
            repository.fetchScreenshot(AnkiScreenshotRequest(lineId = 4)),
        )
        settings.value = TextHookerSettings(host = "")
        assertEquals(
            AnkiScreenshotResult.Failure(UiText.Resource(R.string.texthooker_screenshot_not_configured)),
            repository.fetchScreenshot(AnkiScreenshotRequest(lineId = 4)),
        )
    }

    @Test
    fun screenshotPreviewIsCachedToAFile() = runTest {
        val repository = repository()
        transport.screenshotResult = TextHookerHttpResult.Success(TextHookerScreenshotImage(byteArrayOf(1, 2), "image/jpeg"))
        val result = repository.fetchScreenshotPreview(lineId = 2) as TextHookerScreenshotPreviewResult.Ready
        assertEquals(listOf(1, 2), java.io.File(result.path).readBytes().map { it.toInt() })
    }

    @Test
    fun testConnectionReportsStatusAndFailures() = runTest {
        val repository = repository()
        transport.statusResult = TextHookerHttpResult.Success(TextHookerServerStatus(hostname = "steamdeck"))
        assertEquals(
            "steamdeck",
            (repository.testConnection(TextHookerSettings()) as TextHookerTestResult.Success).status.hostname,
        )
        transport.statusResult = TextHookerHttpResult.HttpError(401, null)
        assertEquals(
            TextHookerTestResult.Failure(TextHookerDisconnectReason.Unauthorized),
            repository.testConnection(TextHookerSettings()),
        )
        assertEquals(
            TextHookerTestResult.Failure(TextHookerDisconnectReason.InvalidSettings),
            repository.testConnection(TextHookerSettings(host = "bad host")),
        )
    }

    @Test
    fun cacheKeepsOnlyRecentPreviews() {
        val dir = Files.createTempDirectory("texthooker-cache").toFile()
        val cache = TextHookerScreenshotCache(dir, keep = 2)
        repeat(5) { cache.write(byteArrayOf(it.toByte()), "jpg") }
        assertEquals(2, dir.listFiles()!!.size)
    }
}
