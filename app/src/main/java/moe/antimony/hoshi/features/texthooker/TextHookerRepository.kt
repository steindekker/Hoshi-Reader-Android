package moe.antimony.hoshi.features.texthooker

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.anki.AnkiScreenshotRequest
import moe.antimony.hoshi.features.anki.AnkiScreenshotResult
import moe.antimony.hoshi.features.anki.AnkiScreenshotSource
import moe.antimony.hoshi.ui.UiText

internal sealed interface TextHookerDisconnectReason {
    data object Unreachable : TextHookerDisconnectReason

    data object Unauthorized : TextHookerDisconnectReason

    data class HttpError(val code: Int) : TextHookerDisconnectReason

    data object ServerClosed : TextHookerDisconnectReason

    data object InvalidResponse : TextHookerDisconnectReason

    data class IncompatibleProtocol(val serverProtocol: Int) : TextHookerDisconnectReason

    data object InvalidSettings : TextHookerDisconnectReason
}

internal sealed interface TextHookerConnectionState {
    /** Nobody is observing the VN tab, so no connection is held. */
    data object Idle : TextHookerConnectionState

    data object NotConfigured : TextHookerConnectionState

    data class Connecting(val attempt: Int) : TextHookerConnectionState

    data object Connected : TextHookerConnectionState

    /** [retryInMillis] is null when no automatic retry is scheduled. */
    data class Disconnected(
        val reason: TextHookerDisconnectReason,
        val retryInMillis: Long?,
    ) : TextHookerConnectionState
}

internal data class TextHookerState(
    val settings: TextHookerSettings = TextHookerSettings(),
    val connection: TextHookerConnectionState = TextHookerConnectionState.Idle,
    val lines: List<TextHookerLine> = emptyList(),
    val serverStatus: TextHookerServerStatus? = null,
)

internal sealed interface TextHookerTestResult {
    data class Success(val status: TextHookerServerStatus) : TextHookerTestResult

    data class Failure(val reason: TextHookerDisconnectReason) : TextHookerTestResult
}

internal sealed interface TextHookerScreenshotPreviewResult {
    data class Ready(val path: String) : TextHookerScreenshotPreviewResult

    data class Failed(val message: UiText) : TextHookerScreenshotPreviewResult
}

/**
 * Owns the live connection to the Deck's steamdeck-vn-extractor server. The WebSocket is held
 * only while [state] has subscribers (the visible VN tab); reconnects back off exponentially and
 * resume with `?after=<latest id>` so the bounded in-memory log has no gaps.
 */
@Singleton
internal class TextHookerRepository(
    private val settings: Flow<TextHookerSettings>,
    private val transport: TextHookerTransport,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val screenshotCache: TextHookerScreenshotCache,
    private val logCapacity: Int = DefaultLogCapacity,
    private val backoffMillis: (attempt: Int) -> Long = ::defaultTextHookerBackoffMillis,
) : AnkiScreenshotSource {
    @Inject
    constructor(
        settingsRepository: TextHookerSettingsRepository,
        transport: TextHookerTransport,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @CacheDir cacheDir: File,
    ) : this(
        settings = settingsRepository.settings,
        transport = transport,
        scope = scope,
        ioDispatcher = ioDispatcher,
        screenshotCache = TextHookerScreenshotCache(File(cacheDir, "texthooker-screenshots")),
    )

    private data class LineLog(val key: String? = null, val lines: List<TextHookerLine> = emptyList())

    private data class ConnectionConfig(val configured: Boolean, val endpoint: TextHookerEndpoint?)

    private data class SocketOutcome(val connected: Boolean, val reason: TextHookerDisconnectReason)

    private val currentSettings = MutableStateFlow(TextHookerSettings())
    private val connection = MutableStateFlow<TextHookerConnectionState>(TextHookerConnectionState.Idle)
    private val log = MutableStateFlow(LineLog())
    private val serverStatus = MutableStateFlow<TextHookerServerStatus?>(null)
    private val retryRequests = MutableStateFlow(0)

    val state: StateFlow<TextHookerState> = channelFlow {
        launch { runSession() }
        combine(currentSettings, connection, log, serverStatus) { settings, connection, log, status ->
            TextHookerState(settings, connection, log.lines, status)
        }.collect { send(it) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(StopTimeoutMillis), TextHookerState())

    /** Reconnects immediately, resetting the backoff. */
    fun retryNow() {
        retryRequests.update { it + 1 }
    }

    suspend fun testConnection(settings: TextHookerSettings): TextHookerTestResult {
        val endpoint = settings.endpointOrNull()
            ?: return TextHookerTestResult.Failure(TextHookerDisconnectReason.InvalidSettings)
        return when (val result = transport.status(endpoint)) {
            is TextHookerHttpResult.Success -> if (result.value.protocol != TextHookerProtocolVersion) {
                TextHookerTestResult.Failure(TextHookerDisconnectReason.IncompatibleProtocol(result.value.protocol))
            } else {
                TextHookerTestResult.Success(result.value)
            }
            is TextHookerHttpResult.HttpError -> TextHookerTestResult.Failure(httpReason(result.code))
            TextHookerHttpResult.NetworkError -> TextHookerTestResult.Failure(TextHookerDisconnectReason.Unreachable)
            TextHookerHttpResult.InvalidResponse -> TextHookerTestResult.Failure(TextHookerDisconnectReason.InvalidResponse)
        }
    }

    override suspend fun fetchScreenshot(request: AnkiScreenshotRequest): AnkiScreenshotResult {
        val settings = settings.first()
        val endpoint = settings.endpointOrNull()
            ?: return AnkiScreenshotResult.Failure(UiText.Resource(R.string.texthooker_screenshot_not_configured))
        val result = transport.screenshot(
            endpoint = endpoint,
            lineId = request.lineId,
            maxWidth = settings.screenshotMaxWidth,
            quality = TextHookerDefaults.ScreenshotQuality,
        )
        return when (result) {
            is TextHookerHttpResult.Success -> AnkiScreenshotResult.Success(result.value.bytes, result.value.mimeType)
            is TextHookerHttpResult.HttpError -> AnkiScreenshotResult.Failure(
                when (result.code) {
                    401 -> UiText.Resource(R.string.texthooker_screenshot_unauthorized)
                    503 -> UiText.Resource(R.string.texthooker_screenshot_unavailable)
                    else -> UiText.Resource(R.string.texthooker_screenshot_http_error, result.code)
                },
            )
            TextHookerHttpResult.NetworkError ->
                AnkiScreenshotResult.Failure(UiText.Resource(R.string.texthooker_screenshot_unreachable))
            TextHookerHttpResult.InvalidResponse ->
                AnkiScreenshotResult.Failure(UiText.Resource(R.string.texthooker_screenshot_failed))
        }
    }

    /** Fetches a screenshot into the app cache for previewing (and later attaching) in the mining sheet. */
    suspend fun fetchScreenshotPreview(lineId: Long?): TextHookerScreenshotPreviewResult =
        when (val result = fetchScreenshot(AnkiScreenshotRequest(lineId))) {
            is AnkiScreenshotResult.Failure -> TextHookerScreenshotPreviewResult.Failed(result.message)
            is AnkiScreenshotResult.Success -> withContext(ioDispatcher) {
                try {
                    val file = screenshotCache.write(result.bytes, if (result.mimeType == "image/png") "png" else "jpg")
                    TextHookerScreenshotPreviewResult.Ready(file.absolutePath)
                } catch (e: IOException) {
                    TextHookerScreenshotPreviewResult.Failed(UiText.Resource(R.string.texthooker_screenshot_failed))
                }
            }
        }

    private suspend fun runSession() {
        try {
            combine(
                settings
                    .onEach { currentSettings.value = it }
                    .map { ConnectionConfig(it.isConfigured, it.endpointOrNull()) }
                    .distinctUntilChanged(),
                retryRequests,
            ) { config, _ -> config }
                .collectLatest(::connectLoop)
        } finally {
            connection.value = TextHookerConnectionState.Idle
        }
    }

    private suspend fun connectLoop(config: ConnectionConfig) {
        if (!config.configured) {
            connection.value = TextHookerConnectionState.NotConfigured
            return
        }
        val endpoint = config.endpoint ?: run {
            connection.value = TextHookerConnectionState.Disconnected(TextHookerDisconnectReason.InvalidSettings, null)
            return
        }
        if (log.value.key != endpoint.logKey) {
            log.value = LineLog(key = endpoint.logKey)
            serverStatus.value = null
        }
        var attempt = 0
        while (true) {
            connection.value = TextHookerConnectionState.Connecting(attempt)
            val outcome = runSocket(endpoint)
            if (outcome.connected) attempt = 0
            if (outcome.reason is TextHookerDisconnectReason.IncompatibleProtocol) {
                connection.value = TextHookerConnectionState.Disconnected(outcome.reason, null)
                return
            }
            val wait = backoffMillis(attempt)
            attempt += 1
            connection.value = TextHookerConnectionState.Disconnected(outcome.reason, wait)
            delay(wait)
        }
    }

    private suspend fun runSocket(endpoint: TextHookerEndpoint): SocketOutcome {
        var connected = false
        var reason: TextHookerDisconnectReason = TextHookerDisconnectReason.ServerClosed
        val after = log.value.lines.lastOrNull()?.id
        transport.socket(endpoint, after)
            .map { event ->
                if (event is TextHookerSocketEvent.Message) {
                    TextHookerProtocol.parseSocketMessage(event.text) ?: TextHookerSocketEvent.Opened
                } else {
                    event
                }
            }
            .flowOn(ioDispatcher)
            .takeWhile { item ->
                when (item) {
                    is TextHookerSocketMessage.Hello -> {
                        if (item.protocol != TextHookerProtocolVersion) {
                            reason = TextHookerDisconnectReason.IncompatibleProtocol(item.protocol)
                            false
                        } else {
                            applyHello(item)
                            connected = true
                            connection.value = TextHookerConnectionState.Connected
                            true
                        }
                    }
                    is TextHookerSocketMessage.Line -> {
                        log.update { it.copy(lines = mergeTextHookerLines(it.lines, listOf(item.line), logCapacity)) }
                        true
                    }
                    is TextHookerSocketMessage.Status -> {
                        serverStatus.value = item.status
                        true
                    }
                    is TextHookerSocketEvent.Closed -> {
                        reason = TextHookerDisconnectReason.ServerClosed
                        false
                    }
                    is TextHookerSocketEvent.Failed -> {
                        reason = item.httpCode?.let(::httpReason) ?: TextHookerDisconnectReason.Unreachable
                        false
                    }
                    else -> true
                }
            }
            .collect {}
        return SocketOutcome(connected, reason)
    }

    private fun applyHello(hello: TextHookerSocketMessage.Hello) {
        serverStatus.value = hello.status
        log.update { current ->
            val localLatest = current.lines.lastOrNull()?.id ?: 0L
            val lines = if (hello.status.latestLineId < localLatest) {
                // The server's log was reset: our ids no longer describe its lines.
                hello.lines.sortedBy { it.id }.takeLast(logCapacity)
            } else {
                mergeTextHookerLines(current.lines, hello.lines, logCapacity)
            }
            current.copy(lines = lines)
        }
    }

    private fun httpReason(code: Int): TextHookerDisconnectReason =
        if (code == 401) TextHookerDisconnectReason.Unauthorized else TextHookerDisconnectReason.HttpError(code)

    internal companion object {
        const val DefaultLogCapacity = 200
        const val StopTimeoutMillis = 5_000L
    }
}

internal fun defaultTextHookerBackoffMillis(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

/** A small cache of screenshot previews; only the newest few files are kept. */
internal class TextHookerScreenshotCache(
    private val directory: File,
    private val keep: Int = 4,
) {
    private val counter = AtomicLong(0)

    @Synchronized
    fun write(bytes: ByteArray, extension: String): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Cannot create $directory")
        val name = "preview_%d_%06d.%s".format(System.currentTimeMillis(), counter.incrementAndGet() % 1_000_000, extension)
        val file = File(directory, name)
        file.writeBytes(bytes)
        directory.listFiles()
            .orEmpty()
            .filter { it.isFile }
            .sortedByDescending { it.name }
            .drop(keep)
            .forEach { it.delete() }
        return file
    }
}
