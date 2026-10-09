package moe.antimony.hoshi.features.live

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl

internal object LiveDefaults {
    const val Host = "deck"
    const val Port = 7277
    const val ScreenshotMaxWidth = 1280
    const val ScreenshotQuality = 85
    const val ClipMaxWidth = 480
    const val ClipFps = 12

    /** Screenshot widths offered in settings; 0 keeps the original resolution. */
    val ScreenshotWidthOptions = listOf(640, 960, 1280, 1920, 0)

    val DefaultHosts: List<LiveHost> = listOf(LiveHost(id = "default", host = Host, port = Port))
}

/** One machine running hoshi-bridge (the Steam Deck, a PC with mpv, …). */
@Serializable
internal data class LiveHost(
    val id: String,
    val host: String,
    val port: Int = LiveDefaults.Port,
    val token: String = "",
) {
    /** The endpoint, or null when the stored host/port are invalid. */
    fun endpointOrNull(): LiveEndpoint? {
        if (host.isBlank() || validateLiveHost(host) != null || validateLivePort(port.toString()) != null) return null
        return LiveEndpoint(hostId = id, host = host.trim(), port = port, token = token.trim())
    }

    val displayAddress: String get() = liveDisplayAddress(host.trim(), port)

    /** Short name for the header and session chips. */
    val displayName: String get() = host.trim()

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** Live tab settings: the bridges to connect to and media preferences. */
internal data class LiveSettings(
    val hosts: List<LiveHost> = LiveDefaults.DefaultHosts,
    val screenshotMaxWidth: Int = LiveDefaults.ScreenshotMaxWidth,
    /** Opening a lookup pauses a controllable video session. */
    val autoPause: Boolean = true,
) {
    val isConfigured: Boolean get() = hosts.isNotEmpty()

    fun host(id: String): LiveHost? = hosts.firstOrNull { it.id == id }
}

internal data class LiveEndpoint(
    val hostId: String,
    val host: String,
    val port: Int,
    val token: String,
) {
    val displayAddress: String get() = liveDisplayAddress(host, port)

    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl =
        HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .port(port)
            .encodedPath(path)
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
}

private fun liveDisplayAddress(host: String, port: Int): String =
    if (host.contains(':')) "[$host]:$port" else "$host:$port"

internal enum class LiveSettingsError {
    HostInvalid,
    HostDuplicate,
    PortInvalid,
    TokenInvalid,
}

/** Returns null when [input] is a usable host name or IP literal. */
internal fun validateLiveHost(input: String): LiveSettingsError? {
    val host = input.trim()
    if (host.isEmpty()) return LiveSettingsError.HostInvalid
    if (host.any { it.isWhitespace() || it in "/?#@" }) return LiveSettingsError.HostInvalid
    val bare = host.removePrefix("[").removeSuffix("]")
    // A colon is only valid inside an IPv6 literal; host:port must use the separate port field.
    if (bare.contains(':') && !bare.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
        return LiveSettingsError.HostInvalid
    }
    return runCatching { HttpUrl.Builder().scheme("http").host(bare).build() }
        .fold(onSuccess = { null }, onFailure = { LiveSettingsError.HostInvalid })
}

internal fun validateLivePort(input: String): LiveSettingsError? =
    if (input.trim().toIntOrNull() in 1..65535) null else LiveSettingsError.PortInvalid

internal fun validateLiveToken(input: String): LiveSettingsError? =
    if (input.trim().any { it.isISOControl() }) LiveSettingsError.TokenInvalid else null

/** Normalizes user input: strips an `http://` prefix and trailing slash, trims, unwraps IPv6 brackets. */
internal fun normalizeLiveHost(input: String): String =
    input.trim()
        .removePrefix("http://")
        .removeSuffix("/")
        .trim()
        .let { if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1) else it }

internal class LiveSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<LiveSettings> = dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (LiveSettings) -> LiveSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences[KeyHosts] = LiveProtocol.json.encodeToString(
                next.hosts.map { it.copy(host = it.host.trim(), port = it.port.coerceIn(1, 65535), token = it.token.trim()) },
            )
            preferences[KeyScreenshotMaxWidth] = next.screenshotMaxWidth.coerceAtLeast(0)
            preferences[KeyAutoPause] = next.autoPause
            // The single-host keys of the VN tab are migrated into the host list once.
            preferences.remove(LegacyKeyHost)
            preferences.remove(LegacyKeyPort)
            preferences.remove(LegacyKeyToken)
        }
    }

    private fun Preferences.toSettings(): LiveSettings =
        LiveSettings(
            hosts = this[KeyHosts]
                ?.let { runCatching { LiveProtocol.json.decodeFromString<List<LiveHost>>(it) }.getOrNull() }
                ?: legacyHosts(),
            screenshotMaxWidth = this[KeyScreenshotMaxWidth]?.coerceAtLeast(0) ?: LiveDefaults.ScreenshotMaxWidth,
            autoPause = this[KeyAutoPause] ?: true,
        )

    /** The VN tab stored one host; a blank host meant "off". */
    private fun Preferences.legacyHosts(): List<LiveHost> {
        val host = this[LegacyKeyHost] ?: return LiveDefaults.DefaultHosts
        if (host.isBlank()) return emptyList()
        return listOf(
            LiveHost(
                id = "default",
                host = host,
                port = this[LegacyKeyPort]?.takeIf { it in 1..65535 } ?: LiveDefaults.Port,
                token = this[LegacyKeyToken].orEmpty(),
            ),
        )
    }

    private companion object {
        val KeyHosts = stringPreferencesKey("liveHosts")
        val KeyScreenshotMaxWidth = intPreferencesKey("textHookerScreenshotMaxWidth")
        val KeyAutoPause = booleanPreferencesKey("liveAutoPause")
        val LegacyKeyHost = stringPreferencesKey("textHookerHost")
        val LegacyKeyPort = intPreferencesKey("textHookerPort")
        val LegacyKeyToken = stringPreferencesKey("textHookerToken")
    }
}

// The file keeps the VN tab's name so upgrades keep their settings.
private val Context.liveSettingsDataStore by preferencesDataStore(name = "texthooker-settings")

internal fun Context.liveSettingsRepository(): LiveSettingsRepository =
    LiveSettingsRepository(liveSettingsDataStore)
