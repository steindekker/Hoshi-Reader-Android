package moe.antimony.hoshi.features.live

import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Qualifier
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import moe.antimony.hoshi.di.IoDispatcher
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class LiveHttpClient

internal sealed interface LiveSocketEvent {
    data object Opened : LiveSocketEvent

    data class Message(val text: String) : LiveSocketEvent

    data class Closed(val code: Int) : LiveSocketEvent

    /** [httpCode] is set when the upgrade was answered with a non-101 HTTP status. */
    data class Failed(val httpCode: Int?) : LiveSocketEvent
}

internal sealed interface LiveHttpResult<out T> {
    data class Success<T>(val value: T) : LiveHttpResult<T>

    data class HttpError(val code: Int, val message: String?) : LiveHttpResult<Nothing>

    data object NetworkError : LiveHttpResult<Nothing>

    data object InvalidResponse : LiveHttpResult<Nothing>
}

internal class LiveMedia(
    val bytes: ByteArray,
    val mimeType: String,
)

/** Session commands that return a media file. */
internal enum class LiveMediaCommand(val path: String) {
    Screenshot("screenshot"),
    Clip("clip"),
    Audio("audio"),
}

/** Network boundary to hoshi-bridge; the repository owns reconnects and state. */
internal interface LiveTransport {
    /** Opens `/v2/ws`; the flow completes after [LiveSocketEvent.Closed] or [LiveSocketEvent.Failed]. */
    fun socket(endpoint: LiveEndpoint): Flow<LiveSocketEvent>

    suspend fun status(endpoint: LiveEndpoint): LiveHttpResult<LiveStatusResponse>

    suspend fun control(endpoint: LiveEndpoint, sessionId: String, action: String, positionMs: Long?): LiveHttpResult<Unit>

    suspend fun media(
        endpoint: LiveEndpoint,
        sessionId: String,
        command: LiveMediaCommand,
        body: JsonObject,
    ): LiveHttpResult<LiveMedia>
}

internal class OkHttpLiveTransport @Inject constructor(
    @param:LiveHttpClient private val client: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : LiveTransport {
    /** Clips and audio are encoded on demand on the bridge, so media calls get more time. */
    private val mediaClient: OkHttpClient by lazy {
        client.newBuilder()
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }

    override fun socket(endpoint: LiveEndpoint): Flow<LiveSocketEvent> =
        callbackFlow {
            val request = Request.Builder()
                .url(endpoint.url("/v2/ws"))
                .authorized(endpoint)
                .build()
            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        trySend(LiveSocketEvent.Opened)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        trySend(LiveSocketEvent.Message(text))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(NormalClosure, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        trySend(LiveSocketEvent.Closed(code))
                        channel.close()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        trySend(LiveSocketEvent.Failed(response?.code))
                        channel.close()
                    }
                },
            )
            awaitClose { socket.cancel() }
        }.buffer(Channel.UNLIMITED).flowOn(ioDispatcher)

    override suspend fun status(endpoint: LiveEndpoint): LiveHttpResult<LiveStatusResponse> =
        execute(client, Request.Builder().url(endpoint.url("/v2/status")).authorized(endpoint).get().build()) { response ->
            LiveProtocol.parseStatus(response.body.string())
                ?.takeIf { it.bridge.protocol > 0 }
                ?.let { LiveHttpResult.Success(it) }
                ?: LiveHttpResult.InvalidResponse
        }

    override suspend fun control(
        endpoint: LiveEndpoint,
        sessionId: String,
        action: String,
        positionMs: Long?,
    ): LiveHttpResult<Unit> {
        val body = buildString {
            append("{\"action\":").append(LiveProtocol.json.encodeToString(action))
            positionMs?.let { append(",\"ms\":").append(it) }
            append('}')
        }
        val request = Request.Builder()
            .url(endpoint.url("/v2/sessions/${encodeSegment(sessionId)}/control"))
            .authorized(endpoint)
            .post(body.toRequestBody(JsonMediaType))
            .build()
        return execute(client, request) { LiveHttpResult.Success(Unit) }
    }

    override suspend fun media(
        endpoint: LiveEndpoint,
        sessionId: String,
        command: LiveMediaCommand,
        body: JsonObject,
    ): LiveHttpResult<LiveMedia> {
        val request = Request.Builder()
            .url(endpoint.url("/v2/sessions/${encodeSegment(sessionId)}/${command.path}"))
            .authorized(endpoint)
            .post(body.toString().toRequestBody(JsonMediaType))
            .build()
        return execute(mediaClient, request) { response ->
            val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            val bytes = response.body.bytes()
            val expected = if (command == LiveMediaCommand.Audio) "audio/" else "image/"
            if (!mimeType.startsWith(expected) || bytes.isEmpty() || bytes.size > MaxMediaBytes) {
                LiveHttpResult.InvalidResponse
            } else {
                LiveHttpResult.Success(LiveMedia(bytes, mimeType))
            }
        }
    }

    private suspend fun <T> execute(
        httpClient: OkHttpClient,
        request: Request,
        onSuccess: (Response) -> LiveHttpResult<T>,
    ): LiveHttpResult<T> = withContext(ioDispatcher) {
        try {
            httpClient.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    onSuccess(response)
                } else {
                    val message = runCatching { response.body.string() }.getOrNull()
                    LiveHttpResult.HttpError(response.code, LiveProtocol.parseError(message))
                }
            }
        } catch (e: IOException) {
            LiveHttpResult.NetworkError
        }
    }

    private fun Request.Builder.authorized(endpoint: LiveEndpoint): Request.Builder =
        apply {
            if (endpoint.token.isNotEmpty()) header("Authorization", "Bearer ${endpoint.token}")
        }

    private fun encodeSegment(value: String): String =
        okhttp3.HttpUrl.Builder().scheme("http").host("x").addPathSegment(value).build().encodedPathSegments.first()

    private companion object {
        const val NormalClosure = 1000
        const val MaxMediaBytes = 16 * 1024 * 1024
        val JsonMediaType = "application/json; charset=utf-8".toMediaType()
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(
        object : Callback {
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }
        },
    )
}
