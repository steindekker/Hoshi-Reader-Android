package moe.antimony.hoshi.features.texthooker

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The steamdeck-vn-extractor protocol version this client implements (see its PROTOCOL.md). */
internal const val TextHookerProtocolVersion = 1

private const val HttpSourceKind = "http"

@Serializable
internal data class TextHookerLine(
    val id: Long,
    val text: String,
    val source: String = "",
    val receivedAt: Long = 0,
    val hasScreenshot: Boolean = false,
)

@Serializable
internal data class TextHookerSourceStatus(
    val id: String = "",
    val kind: String = "",
    val target: String? = null,
    val connected: Boolean = false,
    val lastError: String? = null,
)

@Serializable
internal data class TextHookerScreenshotStatus(
    val available: Boolean = false,
    val backend: String? = null,
)

@Serializable
internal data class TextHookerServerStatus(
    val server: String = "",
    val version: String = "",
    val protocol: Int = TextHookerProtocolVersion,
    val hostname: String = "",
    val latestLineId: Long = 0,
    val sources: List<TextHookerSourceStatus> = emptyList(),
    val screenshot: TextHookerScreenshotStatus = TextHookerScreenshotStatus(),
) {
    /**
     * True when the server reports sources but no text hooker (websocket/clipboard) is connected.
     * The always-present `http` ingest source does not count as a hooker.
     */
    val hasNoConnectedSource: Boolean
        get() = sources.isNotEmpty() && sources.none { it.kind != HttpSourceKind && it.connected }
}

@Serializable
internal data class TextHookerLinesResponse(
    val lines: List<TextHookerLine> = emptyList(),
    val latestLineId: Long = 0,
)

internal sealed interface TextHookerSocketMessage {
    data class Hello(
        val protocol: Int,
        val status: TextHookerServerStatus,
        val lines: List<TextHookerLine>,
    ) : TextHookerSocketMessage

    data class Line(val line: TextHookerLine) : TextHookerSocketMessage

    data class Status(val status: TextHookerServerStatus) : TextHookerSocketMessage
}

@Serializable
private data class HelloFrame(
    val protocol: Int = TextHookerProtocolVersion,
    val status: TextHookerServerStatus = TextHookerServerStatus(),
    val lines: List<TextHookerLine> = emptyList(),
)

@Serializable
private data class LineFrame(val line: TextHookerLine)

@Serializable
private data class StatusFrame(val status: TextHookerServerStatus)

@Serializable
private data class ErrorBody(@SerialName("error") val error: String? = null)

internal object TextHookerProtocol {
    val json: Json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
        isLenient = false
    }

    /** Parses one WebSocket text frame; unknown frame types and malformed frames yield null. */
    fun parseSocketMessage(text: String): TextHookerSocketMessage? = runCatching {
        val root: JsonObject = json.parseToJsonElement(text).jsonObject
        when (root["type"]?.jsonPrimitive?.contentOrNull) {
            "hello" -> json.decodeFromJsonElement<HelloFrame>(root).let {
                TextHookerSocketMessage.Hello(
                    protocol = it.protocol,
                    status = it.status,
                    lines = it.lines.filter(::isUsableLine),
                )
            }
            "line" -> json.decodeFromJsonElement<LineFrame>(root).line
                .takeIf(::isUsableLine)
                ?.let(TextHookerSocketMessage::Line)
            "status" -> TextHookerSocketMessage.Status(json.decodeFromJsonElement<StatusFrame>(root).status)
            else -> null
        }
    }.getOrNull()

    fun parseStatus(text: String): TextHookerServerStatus? =
        runCatching { json.decodeFromString<TextHookerServerStatus>(text) }.getOrNull()

    fun parseLines(text: String): TextHookerLinesResponse? =
        runCatching { json.decodeFromString<TextHookerLinesResponse>(text) }.getOrNull()

    fun parseError(text: String?): String? =
        text?.let { runCatching { json.decodeFromString<ErrorBody>(it).error }.getOrNull() }

    private fun isUsableLine(line: TextHookerLine): Boolean = line.id > 0 && line.text.isNotBlank()
}

/** Merges [incoming] into [existing], deduplicating by id, ascending, keeping the newest [capacity]. */
internal fun mergeTextHookerLines(
    existing: List<TextHookerLine>,
    incoming: List<TextHookerLine>,
    capacity: Int,
): List<TextHookerLine> {
    if (incoming.isEmpty()) return existing
    val byId = LinkedHashMap<Long, TextHookerLine>(existing.size + incoming.size)
    existing.forEach { byId[it.id] = it }
    incoming.forEach { byId[it.id] = it }
    return byId.values.sortedBy { it.id }.takeLast(capacity)
}
