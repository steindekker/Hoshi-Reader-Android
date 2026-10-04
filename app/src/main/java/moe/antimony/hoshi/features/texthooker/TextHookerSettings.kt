package moe.antimony.hoshi.features.texthooker

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.HttpUrl

internal object TextHookerDefaults {
    const val Host = "steamdeck"
    const val Port = 7277
    const val ScreenshotMaxWidth = 1280
    const val ScreenshotQuality = 85
    /** Screenshot widths offered in settings; 0 keeps the original resolution. */
    val ScreenshotWidthOptions = listOf(640, 960, 1280, 1920, 0)
}

/** Connection settings for the Steam Deck VN text server (steamdeck-vn-extractor). */
internal data class TextHookerSettings(
    val host: String = TextHookerDefaults.Host,
    val port: Int = TextHookerDefaults.Port,
    val token: String = "",
    val screenshotMaxWidth: Int = TextHookerDefaults.ScreenshotMaxWidth,
) {
    val isConfigured: Boolean get() = host.isNotBlank()

    /** The endpoint, or null when not configured or the stored host/port are invalid. */
    fun endpointOrNull(): TextHookerEndpoint? {
        if (!isConfigured) return null
        if (validateTextHookerHost(host) != null || validateTextHookerPort(port.toString()) != null) return null
        return TextHookerEndpoint(host = host.trim(), port = port, token = token.trim())
    }

    val displayAddress: String get() = textHookerDisplayAddress(host.trim(), port)
}

internal data class TextHookerEndpoint(
    val host: String,
    val port: Int,
    val token: String,
) {
    val displayAddress: String get() = textHookerDisplayAddress(host, port)

    /** Identifies the server the line log belongs to; line ids are only comparable per server. */
    val logKey: String get() = "${host.lowercase()}:$port"

    fun url(path: String, query: Map<String, String> = emptyMap()): HttpUrl =
        HttpUrl.Builder()
            .scheme("http")
            .host(host)
            .port(port)
            .encodedPath(path)
            .apply { query.forEach { (key, value) -> addQueryParameter(key, value) } }
            .build()
}

private fun textHookerDisplayAddress(host: String, port: Int): String =
    if (host.contains(':')) "[$host]:$port" else "$host:$port"

internal enum class TextHookerSettingsError {
    HostInvalid,
    PortInvalid,
    TokenInvalid,
}

/** Returns null when [input] is a usable host name or IP literal. Blank disables the connection. */
internal fun validateTextHookerHost(input: String): TextHookerSettingsError? {
    val host = input.trim()
    if (host.isEmpty()) return null
    if (host.any { it.isWhitespace() || it in "/?#@" }) return TextHookerSettingsError.HostInvalid
    val bare = host.removePrefix("[").removeSuffix("]")
    // A colon is only valid inside an IPv6 literal; host:port must use the separate port field.
    if (bare.contains(':') && !bare.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }) {
        return TextHookerSettingsError.HostInvalid
    }
    return runCatching { HttpUrl.Builder().scheme("http").host(bare).build() }
        .fold(onSuccess = { null }, onFailure = { TextHookerSettingsError.HostInvalid })
}

internal fun validateTextHookerPort(input: String): TextHookerSettingsError? =
    if (input.trim().toIntOrNull() in 1..65535) null else TextHookerSettingsError.PortInvalid

internal fun validateTextHookerToken(input: String): TextHookerSettingsError? =
    if (input.trim().any { it.isISOControl() }) TextHookerSettingsError.TokenInvalid else null

/** Normalizes user input: strips an `http://` prefix and trailing slash, trims, unwraps IPv6 brackets. */
internal fun normalizeTextHookerHost(input: String): String =
    input.trim()
        .removePrefix("http://")
        .removeSuffix("/")
        .trim()
        .let { if (it.startsWith("[") && it.endsWith("]")) it.substring(1, it.length - 1) else it }

internal class TextHookerSettingsRepository(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<TextHookerSettings> = dataStore.data.map { it.toSettings() }

    suspend fun update(transform: (TextHookerSettings) -> TextHookerSettings) {
        dataStore.edit { preferences ->
            val next = transform(preferences.toSettings())
            preferences[KeyHost] = next.host.trim()
            preferences[KeyPort] = next.port.coerceIn(1, 65535)
            preferences[KeyToken] = next.token.trim()
            preferences[KeyScreenshotMaxWidth] = next.screenshotMaxWidth.coerceAtLeast(0)
        }
    }

    private fun Preferences.toSettings(): TextHookerSettings =
        TextHookerSettings(
            host = this[KeyHost] ?: TextHookerDefaults.Host,
            port = this[KeyPort]?.takeIf { it in 1..65535 } ?: TextHookerDefaults.Port,
            token = this[KeyToken].orEmpty(),
            screenshotMaxWidth = this[KeyScreenshotMaxWidth]?.coerceAtLeast(0)
                ?: TextHookerDefaults.ScreenshotMaxWidth,
        )

    private companion object {
        val KeyHost = stringPreferencesKey("textHookerHost")
        val KeyPort = intPreferencesKey("textHookerPort")
        val KeyToken = stringPreferencesKey("textHookerToken")
        val KeyScreenshotMaxWidth = intPreferencesKey("textHookerScreenshotMaxWidth")
    }
}

private val Context.textHookerSettingsDataStore by preferencesDataStore(name = "texthooker-settings")

internal fun Context.textHookerSettingsRepository(): TextHookerSettingsRepository =
    TextHookerSettingsRepository(textHookerSettingsDataStore)
