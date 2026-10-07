package moe.antimony.hoshi.features.live

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonObject

internal class FakeLiveTransport : LiveTransport {
    class Connection(val endpoint: LiveEndpoint) {
        val events = Channel<LiveSocketEvent>(Channel.UNLIMITED)
        var cancelled = false

        fun send(event: LiveSocketEvent) {
            events.trySend(event)
        }

        fun sendText(text: String) = send(LiveSocketEvent.Message(text))
    }

    data class ControlCall(val hostId: String, val sessionId: String, val action: String, val positionMs: Long?)

    data class MediaCall(val hostId: String, val sessionId: String, val command: LiveMediaCommand, val body: JsonObject)

    val connections = mutableListOf<Connection>()
    val controls = mutableListOf<ControlCall>()
    val mediaCalls = mutableListOf<MediaCall>()
    var statusResult: LiveHttpResult<LiveStatusResponse> = LiveHttpResult.NetworkError
    var controlResult: LiveHttpResult<Unit> = LiveHttpResult.Success(Unit)
    var mediaResult: LiveHttpResult<LiveMedia> = LiveHttpResult.NetworkError

    val last: Connection get() = connections.last()

    fun lastFor(hostId: String): Connection = connections.last { it.endpoint.hostId == hostId }

    override fun socket(endpoint: LiveEndpoint): Flow<LiveSocketEvent> = flow {
        val connection = Connection(endpoint)
        connections += connection
        try {
            for (event in connection.events) {
                emit(event)
                if (event is LiveSocketEvent.Closed || event is LiveSocketEvent.Failed) break
            }
        } finally {
            connection.cancelled = true
        }
    }

    override suspend fun status(endpoint: LiveEndpoint): LiveHttpResult<LiveStatusResponse> = statusResult

    override suspend fun control(
        endpoint: LiveEndpoint,
        sessionId: String,
        action: String,
        positionMs: Long?,
    ): LiveHttpResult<Unit> {
        controls += ControlCall(endpoint.hostId, sessionId, action, positionMs)
        return controlResult
    }

    override suspend fun media(
        endpoint: LiveEndpoint,
        sessionId: String,
        command: LiveMediaCommand,
        body: JsonObject,
    ): LiveHttpResult<LiveMedia> {
        mediaCalls += MediaCall(endpoint.hostId, sessionId, command, body)
        return mediaResult
    }
}

internal fun vnCueJson(id: Long, text: String): String = """{"id":$id,"text":"$text","at":${1_000L * id}}"""

internal fun timedCueJson(id: Long, text: String, startMs: Long, endMs: Long): String =
    """{"id":$id,"text":"$text","startMs":$startMs,"endMs":$endMs}"""

internal fun sessionJson(
    id: String,
    kind: String,
    cues: List<String> = emptyList(),
    title: String = "",
    trackId: String = "t1",
    capabilities: List<String> = emptyList(),
    activeAt: Long = 0,
    playback: String? = null,
): String = buildString {
    append("""{"id":"$id","kind":"$kind","title":"$title","source":"x","trackId":"$trackId","activeAt":$activeAt,""")
    append(""""capabilities":[${capabilities.joinToString(",") { "\"$it\"" }}],"cues":[${cues.joinToString(",")}]""")
    playback?.let { append(""","playback":$it""") }
    append("}")
}

internal fun helloJson(vararg sessions: String, now: Long = 0, hostname: String = "steamdeck", protocol: Int = 2): String =
    """{"type":"hello","protocol":$protocol,"now":$now,"bridge":{"name":"hoshi-bridge","version":"0.2.0","protocol":$protocol,"hostname":"$hostname"},""" +
        """"sessions":[${sessions.joinToString(",")}]}"""

internal fun appendJson(sessionId: String, vararg cues: String, trackId: String = "t1"): String =
    """{"type":"cues","sessionId":"$sessionId","trackId":"$trackId","mode":"append","cues":[${cues.joinToString(",")}]}"""

internal fun replaceJson(sessionId: String, trackId: String, vararg cues: String): String =
    """{"type":"cues","sessionId":"$sessionId","trackId":"$trackId","mode":"replace","cues":[${cues.joinToString(",")}]}"""

internal fun playbackJson(sessionId: String, positionMs: Long, paused: Boolean, speed: Double = 1.0): String =
    """{"type":"playback","sessionId":"$sessionId","positionMs":$positionMs,"paused":$paused,"speed":$speed,"at":1}"""

internal fun playbackObject(positionMs: Long, paused: Boolean): String =
    """{"positionMs":$positionMs,"paused":$paused,"speed":1,"at":1}"""

internal val VideoCapabilities = listOf("control", "screenshot", "audio", "clip")
