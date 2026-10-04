package moe.antimony.hoshi.features.texthooker

import java.io.IOException
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
import moe.antimony.hoshi.di.IoDispatcher
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

@Qualifier
@Retention(AnnotationRetention.BINARY)
internal annotation class TextHookerHttpClient

internal sealed interface TextHookerSocketEvent {
    data object Opened : TextHookerSocketEvent

    data class Message(val text: String) : TextHookerSocketEvent

    data class Closed(val code: Int) : TextHookerSocketEvent

    /** [httpCode] is set when the upgrade was answered with a non-101 HTTP status. */
    data class Failed(val httpCode: Int?) : TextHookerSocketEvent
}

internal sealed interface TextHookerHttpResult<out T> {
    data class Success<T>(val value: T) : TextHookerHttpResult<T>

    data class HttpError(val code: Int, val message: String?) : TextHookerHttpResult<Nothing>

    data object NetworkError : TextHookerHttpResult<Nothing>

    data object InvalidResponse : TextHookerHttpResult<Nothing>
}

internal class TextHookerScreenshotImage(
    val bytes: ByteArray,
    val mimeType: String,
)

/** Network boundary to the Deck server; the repository owns reconnects and state. */
internal interface TextHookerTransport {
    /** Opens `/api/ws`; the flow completes after [TextHookerSocketEvent.Closed] or [TextHookerSocketEvent.Failed]. */
    fun socket(endpoint: TextHookerEndpoint, afterLineId: Long?): Flow<TextHookerSocketEvent>

    suspend fun status(endpoint: TextHookerEndpoint): TextHookerHttpResult<TextHookerServerStatus>

    suspend fun screenshot(
        endpoint: TextHookerEndpoint,
        lineId: Long?,
        maxWidth: Int,
        quality: Int,
    ): TextHookerHttpResult<TextHookerScreenshotImage>
}

internal class OkHttpTextHookerTransport @Inject constructor(
    @param:TextHookerHttpClient private val client: OkHttpClient,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : TextHookerTransport {
    override fun socket(endpoint: TextHookerEndpoint, afterLineId: Long?): Flow<TextHookerSocketEvent> =
        callbackFlow {
            val query = afterLineId?.let { mapOf("after" to it.toString()) }.orEmpty()
            val request = Request.Builder()
                .url(endpoint.url("/api/ws", query))
                .authorized(endpoint)
                .build()
            val socket = client.newWebSocket(
                request,
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        trySend(TextHookerSocketEvent.Opened)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        trySend(TextHookerSocketEvent.Message(text))
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(NormalClosure, null)
                    }

                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        trySend(TextHookerSocketEvent.Closed(code))
                        channel.close()
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        trySend(TextHookerSocketEvent.Failed(response?.code))
                        channel.close()
                    }
                },
            )
            awaitClose { socket.cancel() }
        }.buffer(Channel.UNLIMITED).flowOn(ioDispatcher)

    override suspend fun status(endpoint: TextHookerEndpoint): TextHookerHttpResult<TextHookerServerStatus> =
        execute(Request.Builder().url(endpoint.url("/api/status")).authorized(endpoint).get().build()) { response ->
            val body = response.body.string()
            TextHookerProtocol.parseStatus(body)
                ?.let { TextHookerHttpResult.Success(it) }
                ?: TextHookerHttpResult.InvalidResponse
        }

    override suspend fun screenshot(
        endpoint: TextHookerEndpoint,
        lineId: Long?,
        maxWidth: Int,
        quality: Int,
    ): TextHookerHttpResult<TextHookerScreenshotImage> {
        val query = buildMap {
            lineId?.let { put("lineId", it.toString()) }
            put("maxWidth", maxWidth.toString())
            put("quality", quality.toString())
        }
        val request = Request.Builder()
            .url(endpoint.url("/api/screenshot", query))
            .authorized(endpoint)
            .post(ByteArray(0).toRequestBody(null))
            .build()
        return execute(request) { response ->
            val mimeType = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
            val bytes = response.body.bytes()
            if (!mimeType.startsWith("image/") || bytes.isEmpty() || bytes.size > MaxScreenshotBytes) {
                TextHookerHttpResult.InvalidResponse
            } else {
                TextHookerHttpResult.Success(TextHookerScreenshotImage(bytes, mimeType))
            }
        }
    }

    private suspend fun <T> execute(
        request: Request,
        onSuccess: (Response) -> TextHookerHttpResult<T>,
    ): TextHookerHttpResult<T> = withContext(ioDispatcher) {
        try {
            client.newCall(request).await().use { response ->
                if (response.isSuccessful) {
                    onSuccess(response)
                } else {
                    val message = runCatching { response.body.string() }.getOrNull()
                    TextHookerHttpResult.HttpError(response.code, TextHookerProtocol.parseError(message))
                }
            }
        } catch (e: IOException) {
            TextHookerHttpResult.NetworkError
        }
    }

    private fun Request.Builder.authorized(endpoint: TextHookerEndpoint): Request.Builder =
        apply {
            if (endpoint.token.isNotEmpty()) header("Authorization", "Bearer ${endpoint.token}")
        }

    private companion object {
        const val NormalClosure = 1000
        const val MaxScreenshotBytes = 16 * 1024 * 1024
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
