package moe.antimony.hoshi.features.live

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

class OkHttpLiveTransportTest {
    private val server = MockWebServer()
    private val client = OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build()
    private val transport = OkHttpLiveTransport(client, Dispatchers.IO)

    @Before
    fun setUp() {
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
        client.dispatcher.executorService.shutdown()
    }

    private fun endpoint(token: String = "") = LiveEndpoint("h1", server.hostName, server.port, token)

    @Test
    fun statusSendsBearerTokenAndParsesBody() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .body("""{"bridge":{"name":"hoshi-bridge","version":"0.2.0","protocol":2,"hostname":"pc","unknown":[1]},"sessions":[]}""")
                .build(),
        )
        val result = transport.status(endpoint(token = "secret")) as LiveHttpResult.Success
        assertEquals("pc", result.value.bridge.hostname)
        val request = server.takeRequest()
        assertEquals("/v2/status", request.url.encodedPath)
        assertEquals("Bearer secret", request.headers["Authorization"])
    }

    @Test
    fun statusWithoutTokenOmitsAuthorizationAndMapsErrors() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        assertEquals(LiveHttpResult.HttpError(401, "unauthorized"), transport.status(endpoint()))
        assertNull(server.takeRequest().headers["Authorization"])
    }

    @Test
    fun controlPostsActionAndPosition() = runBlocking {
        server.enqueue(MockResponse.Builder().code(200).body("""{"ok":true}""").build())
        assertEquals(LiveHttpResult.Success(Unit), transport.control(endpoint(), "mpv", "seek", 61_500))
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/v2/sessions/mpv/control", request.url.encodedPath)
        assertEquals("""{"action":"seek","ms":61500}""", request.body?.utf8())
    }

    @Test
    fun mediaPostsCueBodyAndValidatesContentType() = runBlocking {
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "image/webp")
                .body(okio.Buffer().write(byteArrayOf(82, 73, 70, 70)))
                .build(),
        )
        val body = JsonObject(mapOf("trackId" to JsonPrimitive("t1"), "cueId" to JsonPrimitive(12)))
        val result = transport.media(endpoint(), "mpv", LiveMediaCommand.Clip, body) as LiveHttpResult.Success
        assertEquals("image/webp", result.value.mimeType)
        assertEquals(4, result.value.bytes.size)
        val request = server.takeRequest()
        assertEquals("/v2/sessions/mpv/clip", request.url.encodedPath)
        assertEquals("""{"trackId":"t1","cueId":12}""", request.body?.utf8())

        server.enqueue(MockResponse.Builder().code(200).addHeader("Content-Type", "image/jpeg").body("x").build())
        assertEquals(LiveHttpResult.InvalidResponse, transport.media(endpoint(), "mpv", LiveMediaCommand.Audio, body))
        server.enqueue(MockResponse.Builder().code(503).body("""{"error":"ffmpeg failed"}""").build())
        assertEquals(LiveHttpResult.HttpError(503, "ffmpeg failed"), transport.media(endpoint(), "mpv", LiveMediaCommand.Audio, body))
    }

    @Test
    fun unreachableServerIsANetworkError() = runBlocking {
        val port = server.port
        server.close()
        assertEquals(LiveHttpResult.NetworkError, transport.status(LiveEndpoint("h", "127.0.0.1", port, "")))
    }

    @Test
    fun socketStreamsFrames() = runBlocking {
        val hello = helloJson(sessionJson("vn", "vn"))
        server.enqueue(
            MockResponse.Builder()
                .webSocketUpgrade(
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            webSocket.send(hello)
                            webSocket.send(appendJson("vn", vnCueJson(1, "一")))
                            webSocket.close(1000, "bye")
                        }
                    },
                )
                .build(),
        )
        val events = withTimeout(10_000) { transport.socket(endpoint(token = "t")).toList() }
        assertEquals(LiveSocketEvent.Opened, events.first())
        val messages = events.filterIsInstance<LiveSocketEvent.Message>().map { it.text }
        assertEquals(listOf(hello, appendJson("vn", vnCueJson(1, "一"))), messages)
        assertTrue(events.last() is LiveSocketEvent.Closed)
        val request = server.takeRequest()
        assertEquals("/v2/ws", request.url.encodedPath)
        assertEquals("Bearer t", request.headers["Authorization"])
    }

    @Test
    fun rejectedUpgradeReportsHttpCode() = runBlocking {
        server.enqueue(MockResponse.Builder().code(401).body("""{"error":"unauthorized"}""").build())
        val events = withTimeout(10_000) { transport.socket(endpoint()).take(1).toList() }
        assertEquals(LiveSocketEvent.Failed(401), events.single())
    }
}
