package moe.antimony.hoshi.features.live

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The hoshi-bridge protocol version this client implements (see bridge/PROTOCOL.md). */
internal const val LiveProtocolVersion = 2

internal object LiveCapability {
    const val Control = "control"
    const val Screenshot = "screenshot"
    const val Audio = "audio"
    const val Clip = "clip"
}

internal enum class LiveSessionKind {
    /** Cues append over time, no timestamps, no playback. */
    Vn,

    /** The whole subtitle track up front, with playback. */
    Video,

    /** Reserved for browser producers; behaves like [Video]. */
    Web,
    ;

    val isTimed: Boolean get() = this != Vn

    companion object {
        fun of(value: String): LiveSessionKind = when (value) {
            "vn" -> Vn
            "video" -> Video
            else -> Web
        }
    }
}

@Serializable
internal data class LiveCue(
    val id: Long,
    val text: String,
    val startMs: Long? = null,
    val endMs: Long? = null,
    val at: Long = 0,
) {
    val isTimed: Boolean get() = startMs != null && endMs != null
}

@Serializable
internal data class LivePlayback(
    val positionMs: Long = 0,
    val paused: Boolean = true,
    val speed: Double = 1.0,
    val at: Long = 0,
)

@Serializable
internal data class LiveSessionInfo(
    val id: String,
    val kind: String = "vn",
    val title: String = "",
    val source: String = "",
    val capabilities: List<String> = emptyList(),
    val trackId: String = "",
    val activeAt: Long = 0,
) {
    val sessionKind: LiveSessionKind get() = LiveSessionKind.of(kind)

    fun has(capability: String): Boolean = capability in capabilities
}

@Serializable
internal data class LiveSourceStatus(
    val id: String = "",
    val kind: String = "",
    val target: String? = null,
    val connected: Boolean = false,
    val lastError: String? = null,
)

@Serializable
internal data class LiveBridgeStatus(
    val name: String = "",
    val version: String = "",
    val protocol: Int = LiveProtocolVersion,
    val hostname: String = "",
    val sources: List<LiveSourceStatus> = emptyList(),
)

@Serializable
internal data class LiveStatusResponse(
    val bridge: LiveBridgeStatus = LiveBridgeStatus(),
    val sessions: List<LiveSessionInfo> = emptyList(),
)

internal data class LiveSessionSnapshot(
    val info: LiveSessionInfo,
    val cues: List<LiveCue>,
    val playback: LivePlayback?,
)

internal enum class LiveCueMode { Replace, Append }

internal sealed interface LiveSocketMessage {
    data class Hello(
        val protocol: Int,
        val now: Long,
        val bridge: LiveBridgeStatus,
        val sessions: List<LiveSessionSnapshot>,
    ) : LiveSocketMessage

    data class Bridge(val bridge: LiveBridgeStatus) : LiveSocketMessage

    data class Session(val info: LiveSessionInfo) : LiveSocketMessage

    data class SessionEnded(val sessionId: String) : LiveSocketMessage

    data class Cues(
        val sessionId: String,
        val trackId: String,
        val mode: LiveCueMode,
        val cues: List<LiveCue>,
    ) : LiveSocketMessage

    data class Playback(val sessionId: String, val playback: LivePlayback) : LiveSocketMessage
}

@Serializable
private data class SnapshotFrame(
    val cues: List<LiveCue> = emptyList(),
    val playback: LivePlayback? = null,
)

@Serializable
private data class HelloFrame(
    val protocol: Int = 0,
    val now: Long = 0,
    val bridge: LiveBridgeStatus = LiveBridgeStatus(),
)

@Serializable
private data class BridgeFrame(val bridge: LiveBridgeStatus)

@Serializable
private data class SessionFrame(val session: LiveSessionInfo)

@Serializable
private data class SessionEndedFrame(val sessionId: String)

@Serializable
private data class CuesFrame(
    val sessionId: String,
    val trackId: String = "",
    val mode: String = "append",
    val cues: List<LiveCue> = emptyList(),
)

@Serializable
private data class PlaybackFrame(
    val sessionId: String,
    val positionMs: Long = 0,
    val paused: Boolean = true,
    val speed: Double = 1.0,
    val at: Long = 0,
)

@Serializable
private data class ErrorBody(@SerialName("error") val error: String? = null)

internal object LiveProtocol {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = false
        explicitNulls = false
    }

    /** Parses one WebSocket text frame; unknown frame types and malformed frames yield null. */
    fun parseSocketMessage(text: String): LiveSocketMessage? = runCatching {
        val root: JsonObject = json.parseToJsonElement(text).jsonObject
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "hello" -> {
                val frame = json.decodeFromJsonElement<HelloFrame>(root)
                val sessions = (root["sessions"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { element ->
                    runCatching {
                        val info = json.decodeFromJsonElement<LiveSessionInfo>(element)
                        val snapshot = json.decodeFromJsonElement<SnapshotFrame>(element)
                        LiveSessionSnapshot(info, snapshot.cues.filter(::isUsableCue), snapshot.playback)
                    }.getOrNull()
                }
                LiveSocketMessage.Hello(frame.protocol, frame.now, frame.bridge, sessions)
            }
            "bridge" -> LiveSocketMessage.Bridge(json.decodeFromJsonElement<BridgeFrame>(root).bridge)
            "session" -> LiveSocketMessage.Session(json.decodeFromJsonElement<SessionFrame>(root).session)
            "sessionEnded" -> LiveSocketMessage.SessionEnded(json.decodeFromJsonElement<SessionEndedFrame>(root).sessionId)
            "cues" -> json.decodeFromJsonElement<CuesFrame>(root).let { frame ->
                LiveSocketMessage.Cues(
                    sessionId = frame.sessionId,
                    trackId = frame.trackId,
                    mode = if (frame.mode == "replace") LiveCueMode.Replace else LiveCueMode.Append,
                    cues = frame.cues.filter(::isUsableCue),
                )
            }
            "playback" -> json.decodeFromJsonElement<PlaybackFrame>(root).let {
                LiveSocketMessage.Playback(it.sessionId, LivePlayback(it.positionMs, it.paused, it.speed, it.at))
            }
            else -> null
        }
    }.getOrNull()

    fun parseStatus(text: String): LiveStatusResponse? =
        runCatching { json.decodeFromString<LiveStatusResponse>(text) }.getOrNull()

    fun parseError(text: String?): String? =
        text?.let { runCatching { json.decodeFromString<ErrorBody>(it).error }.getOrNull() }

    private fun isUsableCue(cue: LiveCue): Boolean = cue.text.isNotBlank()
}

/** Appends [incoming] to [existing], deduplicating by id and keeping the newest [capacity] cues. */
internal fun appendLiveCues(existing: List<LiveCue>, incoming: List<LiveCue>, capacity: Int): List<LiveCue> {
    if (incoming.isEmpty()) return existing
    val known = existing.mapTo(HashSet(existing.size)) { it.id }
    val added = incoming.filter { known.add(it.id) }
    if (added.isEmpty()) return existing
    return (existing + added).takeLast(capacity)
}
