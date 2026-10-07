package moe.antimony.hoshi.features.live

import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.anki.AnkiLiveCueRef
import moe.antimony.hoshi.features.anki.AnkiLiveMediaResult
import moe.antimony.hoshi.features.anki.AnkiLiveMediaSource
import moe.antimony.hoshi.features.anki.AnkiScreenshotRequest
import moe.antimony.hoshi.features.anki.liveImageExtension
import moe.antimony.hoshi.ui.UiText

internal sealed interface LiveDisconnectReason {
    data object Unreachable : LiveDisconnectReason

    data object Unauthorized : LiveDisconnectReason

    data class HttpError(val code: Int) : LiveDisconnectReason

    data object ServerClosed : LiveDisconnectReason

    data object InvalidResponse : LiveDisconnectReason

    data class IncompatibleProtocol(val serverProtocol: Int) : LiveDisconnectReason

    data object InvalidSettings : LiveDisconnectReason
}

internal sealed interface LiveConnectionState {
    data class Connecting(val attempt: Int) : LiveConnectionState

    data object Connected : LiveConnectionState

    /** [retryInMillis] is null when no automatic retry is scheduled. */
    data class Disconnected(
        val reason: LiveDisconnectReason,
        val retryInMillis: Long?,
    ) : LiveConnectionState
}

/** Time sources: [elapsedMillis] is monotonic (playback extrapolation), [nowMillis] is wall time (activity). */
internal interface LiveClock {
    fun elapsedMillis(): Long

    fun nowMillis(): Long

    object System : LiveClock {
        override fun elapsedMillis(): Long = java.lang.System.nanoTime() / 1_000_000

        override fun nowMillis(): Long = java.lang.System.currentTimeMillis()
    }
}

/** One session of one host, as the phone sees it. */
internal data class LiveSessionState(
    val hostId: String,
    val info: LiveSessionInfo,
    val cues: List<LiveCue> = emptyList(),
    val playback: LivePlayback? = null,
    /** [LiveClock.elapsedMillis] when [playback] arrived; position is extrapolated from here. */
    val playbackReceivedAt: Long = 0,
    /** Local wall time of the last activity (cues, play/pause/seek, playing). */
    val activityAt: Long = 0,
) {
    val key: String get() = liveSessionKey(hostId, info.id)
    val kind: LiveSessionKind get() = info.sessionKind

    /** The playback position now, extrapolated while playing. */
    fun positionAt(elapsedMillis: Long): Long? {
        val pb = playback ?: return null
        if (pb.paused) return pb.positionMs
        val elapsed = (elapsedMillis - playbackReceivedAt).coerceIn(0, MaxExtrapolationMillis)
        return pb.positionMs + (elapsed * pb.speed).toLong()
    }

    private companion object {
        /** Heartbeats arrive every ~2 s; never run far ahead of a silent bridge. */
        const val MaxExtrapolationMillis = 10_000L
    }
}

internal fun liveSessionKey(hostId: String, sessionId: String): String = "$hostId/$sessionId"

internal data class LiveHostState(
    val host: LiveHost,
    val connection: LiveConnectionState = LiveConnectionState.Connecting(0),
    val bridge: LiveBridgeStatus? = null,
    val sessions: List<LiveSessionState> = emptyList(),
) {
    /** The bridge's host name, else the configured host. */
    val displayName: String get() = bridge?.hostname?.takeIf { it.isNotBlank() } ?: host.displayName
}

internal data class LiveState(
    val settings: LiveSettings = LiveSettings(),
    val hosts: List<LiveHostState> = emptyList(),
    /** False until a subscriber starts the connections. */
    val active: Boolean = false,
) {
    val sessions: List<LiveSessionState> get() = hosts.flatMap { it.sessions }

    fun session(key: String?): LiveSessionState? = key?.let { k -> sessions.firstOrNull { it.key == k } }

    fun host(hostId: String): LiveHostState? = hosts.firstOrNull { it.host.id == hostId }
}

internal sealed interface LiveTestResult {
    data class Success(val status: LiveStatusResponse) : LiveTestResult

    data class Failure(val reason: LiveDisconnectReason) : LiveTestResult
}

internal sealed interface LivePreviewResult {
    data class Ready(val path: String) : LivePreviewResult

    data class Failed(val message: UiText) : LivePreviewResult
}

internal sealed interface LiveCommandResult {
    data object Success : LiveCommandResult

    data class Failure(val message: UiText) : LiveCommandResult
}

/**
 * Owns the live connections to every configured hoshi-bridge. The WebSockets are held only
 * while [state] has subscribers (the visible Live tab); each host reconnects with its own
 * exponential backoff, and every (re)connect starts from the bridge's full `hello` snapshot.
 */
@Singleton
internal class LiveRepository(
    private val settings: Flow<LiveSettings>,
    private val transport: LiveTransport,
    scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val previewCache: LivePreviewCache,
    private val clock: LiveClock = LiveClock.System,
    private val backoffMillis: (attempt: Int) -> Long = ::defaultLiveBackoffMillis,
) : AnkiLiveMediaSource {
    @Inject
    constructor(
        settingsRepository: LiveSettingsRepository,
        transport: LiveTransport,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        @CacheDir cacheDir: File,
    ) : this(
        settings = settingsRepository.settings,
        transport = transport,
        scope = scope,
        ioDispatcher = ioDispatcher,
        previewCache = LivePreviewCache(File(cacheDir, "live-previews")),
    )

    private val currentSettings = MutableStateFlow(LiveSettings())
    private val hostStates = MutableStateFlow<Map<String, LiveHostState>>(emptyMap())
    private val running = MutableStateFlow(false)
    private val retryRequests = MutableStateFlow(0)

    val state: StateFlow<LiveState> = channelFlow {
        launch { runHosts() }
        combine(currentSettings, hostStates, running) { settings, states, running ->
            LiveState(
                settings = settings,
                hosts = settings.hosts.mapNotNull { host -> states[host.id]?.copy(host = host) },
                active = running,
            )
        }.collect { send(it) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(StopTimeoutMillis), LiveState())

    /** Reconnects every host immediately, resetting the backoff. */
    fun retryNow() {
        retryRequests.update { it + 1 }
    }

    suspend fun testConnection(host: LiveHost): LiveTestResult {
        val endpoint = host.endpointOrNull()
            ?: return LiveTestResult.Failure(LiveDisconnectReason.InvalidSettings)
        return when (val result = transport.status(endpoint)) {
            is LiveHttpResult.Success -> if (result.value.bridge.protocol != LiveProtocolVersion) {
                LiveTestResult.Failure(LiveDisconnectReason.IncompatibleProtocol(result.value.bridge.protocol))
            } else {
                LiveTestResult.Success(result.value)
            }
            is LiveHttpResult.HttpError -> LiveTestResult.Failure(
                // A v1 server answers 404 for /v2/status.
                if (result.code == 404) LiveDisconnectReason.IncompatibleProtocol(1) else httpReason(result.code),
            )
            LiveHttpResult.NetworkError -> LiveTestResult.Failure(LiveDisconnectReason.Unreachable)
            LiveHttpResult.InvalidResponse -> LiveTestResult.Failure(LiveDisconnectReason.InvalidResponse)
        }
    }

    /** Sends pause/play/seek to a session that advertises `control`. */
    suspend fun control(sessionKey: String, action: String, positionMs: Long? = null): LiveCommandResult {
        val (hostId, sessionId) = splitKey(sessionKey) ?: return LiveCommandResult.Failure(noHost())
        val endpoint = endpoint(hostId) ?: return LiveCommandResult.Failure(noHost())
        return when (val result = transport.control(endpoint, sessionId, action, positionMs)) {
            is LiveHttpResult.Success -> LiveCommandResult.Success
            else -> LiveCommandResult.Failure(
                UiText.Resource(R.string.live_control_failed, mediaReason(result, endpoint)),
            )
        }
    }

    override suspend fun fetchScreenshot(request: AnkiScreenshotRequest): AnkiLiveMediaResult {
        val settings = settings.first()
        val cue = request.cue
        val endpoint = settings.host(cue.hostId)?.endpointOrNull()
            ?: return AnkiLiveMediaResult.Failure(UiText.Resource(R.string.live_picture_failed, noHost()))
        val body = cueBody(cue).toMutableMap()
        val command = if (request.animated) {
            body["maxWidth"] = JsonPrimitive(LiveDefaults.ClipMaxWidth)
            body["fps"] = JsonPrimitive(LiveDefaults.ClipFps)
            LiveMediaCommand.Clip
        } else {
            body["maxWidth"] = JsonPrimitive(settings.screenshotMaxWidth)
            body["quality"] = JsonPrimitive(LiveDefaults.ScreenshotQuality)
            LiveMediaCommand.Screenshot
        }
        val result = transport.media(endpoint, cue.sessionId, command, JsonObject(body))
        return when (result) {
            is LiveHttpResult.Success -> AnkiLiveMediaResult.Success(result.value.bytes, result.value.mimeType)
            else -> AnkiLiveMediaResult.Failure(UiText.Resource(R.string.live_picture_failed, mediaReason(result, endpoint)))
        }
    }

    override suspend fun fetchSentenceAudio(cue: AnkiLiveCueRef): AnkiLiveMediaResult {
        val endpoint = endpoint(cue.hostId)
            ?: return AnkiLiveMediaResult.Failure(UiText.Resource(R.string.live_audio_failed, noHost()))
        return when (val result = transport.media(endpoint, cue.sessionId, LiveMediaCommand.Audio, JsonObject(cueBody(cue)))) {
            is LiveHttpResult.Success -> AnkiLiveMediaResult.Success(result.value.bytes, result.value.mimeType)
            else -> AnkiLiveMediaResult.Failure(UiText.Resource(R.string.live_audio_failed, mediaReason(result, endpoint)))
        }
    }

    /** Fetches the cue's picture into the app cache for previewing (and later attaching) in the mining sheet. */
    suspend fun fetchPreview(request: AnkiScreenshotRequest): LivePreviewResult =
        when (val result = fetchScreenshot(request)) {
            is AnkiLiveMediaResult.Failure -> LivePreviewResult.Failed(result.message)
            is AnkiLiveMediaResult.Success -> withContext(ioDispatcher) {
                try {
                    LivePreviewResult.Ready(previewCache.write(result.bytes, liveImageExtension(result.mimeType)).absolutePath)
                } catch (e: IOException) {
                    LivePreviewResult.Failed(
                        UiText.Resource(R.string.live_picture_failed, UiText.Resource(R.string.live_media_reason_invalid)),
                    )
                }
            }
        }

    private fun cueBody(cue: AnkiLiveCueRef): Map<String, JsonPrimitive> = mapOf(
        "trackId" to JsonPrimitive(cue.trackId),
        "cueId" to JsonPrimitive(cue.cueId),
    )

    private suspend fun endpoint(hostId: String): LiveEndpoint? = settings.first().host(hostId)?.endpointOrNull()

    private fun splitKey(key: String): Pair<String, String>? {
        val slash = key.indexOf('/')
        if (slash <= 0 || slash == key.lastIndex) return null
        return key.substring(0, slash) to key.substring(slash + 1)
    }

    private fun noHost(): UiText = UiText.Resource(R.string.live_media_reason_no_host)

    private fun mediaReason(result: LiveHttpResult<*>, endpoint: LiveEndpoint): UiText = when (result) {
        is LiveHttpResult.HttpError -> when (result.code) {
            401 -> UiText.Resource(R.string.live_media_reason_unauthorized)
            409 -> UiText.Resource(R.string.live_media_reason_track_changed)
            503, 501 -> UiText.Resource(
                R.string.live_media_reason_unavailable,
                result.message?.takeIf { it.isNotBlank() }?.let(UiText::Literal) ?: UiText.Literal("HTTP ${result.code}"),
            )
            else -> UiText.Resource(R.string.live_media_reason_http, result.code)
        }
        LiveHttpResult.NetworkError -> UiText.Resource(R.string.live_media_reason_unreachable, endpoint.host)
        else -> UiText.Resource(R.string.live_media_reason_invalid)
    }

    // --- connections ----------------------------------------------------------------

    private suspend fun runHosts() {
        running.value = true
        try {
            combine(
                settings
                    .onEach { currentSettings.value = it }
                    .map { it.hosts }
                    .distinctUntilChanged(),
                retryRequests,
            ) { hosts, _ -> hosts }
                .collectLatest { hosts ->
                    hostStates.update { states ->
                        hosts.associate { host -> host.id to (states[host.id]?.takeIf { it.host == host } ?: LiveHostState(host)) }
                    }
                    coroutineScope {
                        hosts.forEach { host -> launch { connectLoop(host) } }
                    }
                }
        } finally {
            running.value = false
        }
    }

    private fun updateHost(hostId: String, transform: (LiveHostState) -> LiveHostState) {
        hostStates.update { states ->
            val current = states[hostId] ?: return@update states
            states + (hostId to transform(current))
        }
    }

    private suspend fun connectLoop(host: LiveHost) {
        val endpoint = host.endpointOrNull() ?: run {
            updateHost(host.id) {
                it.copy(connection = LiveConnectionState.Disconnected(LiveDisconnectReason.InvalidSettings, null))
            }
            return
        }
        var attempt = 0
        while (true) {
            updateHost(host.id) { it.copy(connection = LiveConnectionState.Connecting(attempt)) }
            val outcome = runSocket(endpoint)
            // A dropped host's sessions go away; they come back with the next hello.
            updateHost(host.id) { it.copy(sessions = emptyList()) }
            if (outcome.connected) attempt = 0
            if (outcome.reason is LiveDisconnectReason.IncompatibleProtocol) {
                updateHost(host.id) { it.copy(connection = LiveConnectionState.Disconnected(outcome.reason, null)) }
                return
            }
            val wait = backoffMillis(attempt)
            attempt += 1
            updateHost(host.id) { it.copy(connection = LiveConnectionState.Disconnected(outcome.reason, wait)) }
            delay(wait)
        }
    }

    private data class SocketOutcome(val connected: Boolean, val reason: LiveDisconnectReason)

    private suspend fun runSocket(endpoint: LiveEndpoint): SocketOutcome {
        var connected = false
        var reason: LiveDisconnectReason = LiveDisconnectReason.ServerClosed
        val hostId = endpoint.hostId
        transport.socket(endpoint)
            .map { event ->
                if (event is LiveSocketEvent.Message) {
                    LiveProtocol.parseSocketMessage(event.text) ?: LiveSocketEvent.Opened
                } else {
                    event
                }
            }
            .flowOn(ioDispatcher)
            .takeWhile { item ->
                when (item) {
                    is LiveSocketMessage.Hello -> {
                        if (item.protocol != LiveProtocolVersion) {
                            reason = LiveDisconnectReason.IncompatibleProtocol(item.protocol)
                            false
                        } else {
                            applyHello(hostId, item)
                            connected = true
                            true
                        }
                    }
                    is LiveSocketMessage -> {
                        if (connected) apply(hostId, item)
                        true
                    }
                    is LiveSocketEvent.Closed -> {
                        reason = LiveDisconnectReason.ServerClosed
                        false
                    }
                    is LiveSocketEvent.Failed -> {
                        // A v1 bridge has no /v2/ws.
                        reason = when (item.httpCode) {
                            null -> LiveDisconnectReason.Unreachable
                            404, 410 -> LiveDisconnectReason.IncompatibleProtocol(1)
                            else -> httpReason(item.httpCode)
                        }
                        false
                    }
                    else -> true
                }
            }
            .collect {}
        return SocketOutcome(connected, reason)
    }

    private fun applyHello(hostId: String, hello: LiveSocketMessage.Hello) {
        val now = clock.nowMillis()
        val elapsed = clock.elapsedMillis()
        // activeAt is bridge wall time; shift it by the bridge/phone clock offset.
        val skew = if (hello.now > 0) now - hello.now else 0
        val sessions = hello.sessions.map { snapshot ->
            LiveSessionState(
                hostId = hostId,
                info = snapshot.info,
                cues = snapshot.cues.takeLast(capacityFor(snapshot.info)),
                playback = snapshot.playback,
                playbackReceivedAt = elapsed,
                activityAt = when {
                    snapshot.playback?.paused == false -> now // playing right now
                    snapshot.info.activeAt > 0 -> (snapshot.info.activeAt + skew).coerceAtMost(now)
                    else -> 0
                },
            )
        }
        updateHost(hostId) {
            it.copy(connection = LiveConnectionState.Connected, bridge = hello.bridge, sessions = sessions)
        }
    }

    private fun apply(hostId: String, message: LiveSocketMessage) {
        val now = clock.nowMillis()
        when (message) {
            is LiveSocketMessage.Hello -> Unit
            is LiveSocketMessage.Bridge -> updateHost(hostId) { it.copy(bridge = message.bridge) }
            is LiveSocketMessage.Session -> updateSessions(hostId) { sessions ->
                val existing = sessions.firstOrNull { it.info.id == message.info.id }
                if (existing == null) {
                    sessions + LiveSessionState(hostId, message.info, activityAt = now)
                } else {
                    val trackChanged = existing.info.trackId != message.info.trackId
                    sessions.map {
                        if (it.info.id != message.info.id) it else it.copy(
                            info = message.info,
                            cues = if (trackChanged) emptyList() else it.cues,
                        )
                    }
                }
            }
            is LiveSocketMessage.SessionEnded -> updateSessions(hostId) { sessions ->
                sessions.filterNot { it.info.id == message.sessionId }
            }
            is LiveSocketMessage.Cues -> updateSession(hostId, message.sessionId) { session ->
                val sameTrack = message.trackId.isEmpty() || message.trackId == session.info.trackId
                val cues = when {
                    message.mode == LiveCueMode.Replace -> message.cues
                    sameTrack -> appendLiveCues(session.cues, message.cues, capacityFor(session.info))
                    else -> message.cues
                }
                session.copy(
                    info = session.info.copy(trackId = message.trackId.ifEmpty { session.info.trackId }),
                    cues = cues.takeLast(capacityFor(session.info)),
                    activityAt = if (message.mode == LiveCueMode.Append && message.cues.isEmpty()) session.activityAt else now,
                )
            }
            is LiveSocketMessage.Playback -> updateSession(hostId, message.sessionId) { session ->
                val previous = session.playback
                val changed = previous == null || previous.paused != message.playback.paused ||
                    previous.speed != message.playback.speed
                session.copy(
                    playback = message.playback,
                    playbackReceivedAt = clock.elapsedMillis(),
                    activityAt = if (changed || !message.playback.paused) now else session.activityAt,
                )
            }
        }
    }

    private fun updateSessions(hostId: String, transform: (List<LiveSessionState>) -> List<LiveSessionState>) {
        updateHost(hostId) { it.copy(sessions = transform(it.sessions)) }
    }

    private fun updateSession(hostId: String, sessionId: String, transform: (LiveSessionState) -> LiveSessionState) {
        updateSessions(hostId) { sessions -> sessions.map { if (it.info.id == sessionId) transform(it) else it } }
    }

    private fun capacityFor(info: LiveSessionInfo): Int =
        if (info.sessionKind == LiveSessionKind.Vn) VnCapacity else TrackCapacity

    private fun httpReason(code: Int): LiveDisconnectReason =
        if (code == 401) LiveDisconnectReason.Unauthorized else LiveDisconnectReason.HttpError(code)

    internal companion object {
        const val VnCapacity = 200
        const val TrackCapacity = 20_000
        const val StopTimeoutMillis = 5_000L
    }
}

internal fun defaultLiveBackoffMillis(attempt: Int): Long =
    (1_000L shl attempt.coerceIn(0, 5)).coerceAtMost(30_000L)

/** A small cache of picture previews; only the newest few files are kept. */
internal class LivePreviewCache(
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
