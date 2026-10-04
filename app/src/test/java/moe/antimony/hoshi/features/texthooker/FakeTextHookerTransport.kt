package moe.antimony.hoshi.features.texthooker

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

internal class FakeTextHookerTransport : TextHookerTransport {
    class Connection(val endpoint: TextHookerEndpoint, val afterLineId: Long?) {
        val events = Channel<TextHookerSocketEvent>(Channel.UNLIMITED)
        var cancelled = false

        fun send(event: TextHookerSocketEvent) {
            events.trySend(event)
        }

        fun sendText(text: String) = send(TextHookerSocketEvent.Message(text))
    }

    val connections = mutableListOf<Connection>()
    val screenshotRequests = mutableListOf<Long?>()
    var statusResult: TextHookerHttpResult<TextHookerServerStatus> = TextHookerHttpResult.NetworkError
    var screenshotResult: TextHookerHttpResult<TextHookerScreenshotImage> = TextHookerHttpResult.NetworkError

    val last: Connection get() = connections.last()

    override fun socket(endpoint: TextHookerEndpoint, afterLineId: Long?): Flow<TextHookerSocketEvent> = flow {
        val connection = Connection(endpoint, afterLineId)
        connections += connection
        try {
            for (event in connection.events) {
                emit(event)
                if (event is TextHookerSocketEvent.Closed || event is TextHookerSocketEvent.Failed) break
            }
        } finally {
            connection.cancelled = true
        }
    }

    override suspend fun status(endpoint: TextHookerEndpoint): TextHookerHttpResult<TextHookerServerStatus> =
        statusResult

    override suspend fun screenshot(
        endpoint: TextHookerEndpoint,
        lineId: Long?,
        maxWidth: Int,
        quality: Int,
    ): TextHookerHttpResult<TextHookerScreenshotImage> {
        screenshotRequests += lineId
        return screenshotResult
    }
}

internal fun helloJson(latestLineId: Long, vararg lines: Pair<Long, String>, protocol: Int = 1): String =
    """{"type":"hello","protocol":$protocol,"status":{"hostname":"steamdeck","protocol":$protocol,"latestLineId":$latestLineId},""" +
        """"lines":[${lines.joinToString(",") { (id, text) -> lineJson(id, text) }}]}"""

internal fun lineFrameJson(id: Long, text: String): String = """{"type":"line","line":${lineJson(id, text)}}"""

private fun lineJson(id: Long, text: String): String =
    """{"id":$id,"text":"$text","source":"textractor","receivedAt":${1_000L * id},"hasScreenshot":false}"""
