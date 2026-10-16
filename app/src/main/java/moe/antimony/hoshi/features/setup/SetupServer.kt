package moe.antimony.hoshi.features.setup

import java.net.HttpURLConnection
import java.net.URL
import moe.antimony.hoshi.BuildConfig

/** Header the setup server checks before it serves a file. */
internal const val SetupTokenHeader = "X-Hoshi-Token"

/**
 * Opens connections for the setup server. Requests to the catalog's host carry the shared
 * token; any other host (an audio server, a mirror) never sees it.
 */
internal class SetupServerConnections(
    catalogUrl: String = SetupCatalog.DefaultUrl,
    private val token: String = BuildConfig.HOSHI_SETUP_TOKEN,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) {
    private val host = runCatching { URL(catalogUrl).host }.getOrNull()

    fun open(url: URL): HttpURLConnection = open.invoke(url).apply {
        if (token.isNotEmpty() && host != null && url.host.equals(host, ignoreCase = true)) {
            setRequestProperty(SetupTokenHeader, token)
        }
    }
}
