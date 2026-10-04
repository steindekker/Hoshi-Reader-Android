package moe.antimony.hoshi.features.texthooker

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OkHttpTextHookerTransportTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val transport = OkHttpTextHookerTransport(client, Dispatchers.IO)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
    }

    private fun endpoint(token: String = "") = TextHookerEndpoint(server.hostName, server.port, token)

    @Test
    fun statusSendsBearerTokenAndParsesBody() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"server":"steamdeck-vn-extractor","version":"0.1.0","protocol":1,"hostname":"steamdeck","latestLineId":3,"unknown":[1]}""")
                .build(),
        )
        val result = transport.status(endpoint(token = "secret")) as TextHookerHttpResult.Success
        assertEquals("steamdeck", result.value.hostname)
        val request = server.takeRequest()
        assertEquals("/api/status", request.url.encodedPath)
        assertEquals("Bearer secret", request.headers["Authorization"])
    }

    @Test
    fun statusWithoutTokenOmitsAuthorizationAndMapsErrors() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        assertEquals(TextHookerHttpResult.HttpError(401, "unauthorized"), transport.status(endpoint()))
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun screenshotPostsLineIdAndReturnsJpeg() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/jpeg")
                .addHeader("X-Screenshot-Line-Id", "12")
                .body(okio.Buffer().write(byteArrayOf(-1, -40, -1)))
                .build(),
        )
        val result = transport.screenshot(endpoint(), lineId = 12, maxWidth = 960, quality = 85)
            as TextHookerHttpResult.Success
        assertEquals("image/jpeg", result.value.mimeType)
        assertEquals(3, result.value.bytes.size)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("12", request.url.queryParameter("lineId"))
        assertEquals("960", request.url.queryParameter("maxWidth"))
        assertEquals("85", request.url.queryParameter("quality"))
    }

    @Test
    fun screenshotRejectsNonImagesAndMapsUnavailable() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "text/html").body("<html>").build())
        assertEquals(TextHookerHttpResult.InvalidResponse, transport.screenshot(endpoint(), null, 0, 85))
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":"no backend"}""").build())
        assertEquals(TextHookerHttpResult.HttpError(503, "no backend"), transport.screenshot(endpoint(), null, 0, 85))
    }

    @Test
    fun unreachableServerIsANetworkError() = runBlocking {
        val port = server.port
        server.close()
        assertEquals(TextHookerHttpResult.NetworkError, transport.status(TextHookerEndpoint("127.0.0.1", port, "")))
    }

    @Test
    fun socketStreamsFramesWithAfterParameter() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(helloJson(5))
                            webSocket.send(lineFrameJson(6, "六"))
                            webSocket.close(1000, "bye")
                        }
                    },
                )
                .build(),
        )
        val events = withTimeout(10_000) { transport.socket(endpoint(token = "t"), afterLineId = 5).toList() }
        assertEquals(TextHookerSocketEvent.Opened, events.first())
        val messages = events.filterIsInstance<TextHookerSocketEvent.Message>().map { it.text }
        assertEquals(listOf(helloJson(5), lineFrameJson(6, "六")), messages)
        assertTrue(events.last() is TextHookerSocketEvent.Closed)
        val request = server.takeRequest()
        assertEquals("/api/ws", request.url.encodedPath)
        assertEquals("5", request.url.queryParameter("after"))
        assertEquals("Bearer t", request.headers["Authorization"])
    }

    @Test
    fun rejectedUpgradeReportsHttpCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        val events = withTimeout(10_000) { transport.socket(endpoint(), afterLineId = null).take(1).toList() }
        assertEquals(TextHookerSocketEvent.Failed(401), events.single())
    }
}
