package moe.antimony.hoshi.features.anki.enrich

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.di.ApplicationScope
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.ui.UiText

internal sealed interface EnrichPackStatus {
    data object Idle : EnrichPackStatus
    data object Checking : EnrichPackStatus
    data class Downloading(val percent: Int) : EnrichPackStatus
    data object UpToDate : EnrichPackStatus
    data object Updated : EnrichPackStatus
    data class Failed(val message: UiText) : EnrichPackStatus
}

internal data class EnrichPackInfo(val version: String, val builtAt: String, val entries: Int)

internal interface EnrichPackSettingsRepository {
    val manifestUrl: Flow<String>
    suspend fun setManifestUrl(url: String)
}

private val Context.enrichPackDataStore by preferencesDataStore(name = "enrich_pack")
private val ManifestUrlKey = stringPreferencesKey("manifest_url")

@Singleton
internal class DataStoreEnrichPackSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) : EnrichPackSettingsRepository {
    override val manifestUrl: Flow<String> = context.enrichPackDataStore.data.map { it[ManifestUrlKey].orEmpty() }

    override suspend fun setManifestUrl(url: String) {
        context.enrichPackDataStore.edit { it[ManifestUrlKey] = url }
    }
}

/**
 * Owns the downloaded pack: answers `{ae-*}` lookups while mining and replaces the file when
 * the server's `pack.json` names a new version. Downloads resume after an interruption and
 * are checked against the manifest's size and SHA-256 before they replace the installed pack.
 */
@Singleton
internal class EnrichPackRepository(
    private val directory: File,
    private val settings: EnrichPackSettingsRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val openPack: (File) -> EnrichPackSource,
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : AnkiFieldEnricher {
    @Inject
    constructor(
        @FilesDir filesDir: File,
        settings: EnrichPackSettingsRepository,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(File(filesDir, "enrich"), settings, scope, ioDispatcher, ::SqliteEnrichPackSource)

    private val packFile = File(directory, EnrichPackFormat.FILE_NAME)
    private val partFile = File(directory, EnrichPackFormat.FILE_NAME + ".part")
    private val partVersionFile = File(directory, EnrichPackFormat.FILE_NAME + ".part-version")
    private val sourceLock = Any()
    private var source: EnrichPackSource? = null
    private val updateMutex = Mutex()

    private val _status = MutableStateFlow<EnrichPackStatus>(EnrichPackStatus.Idle)
    val status: StateFlow<EnrichPackStatus> = _status.asStateFlow()

    private val _info = MutableStateFlow<EnrichPackInfo?>(null)
    val info: StateFlow<EnrichPackInfo?> = _info.asStateFlow()

    init {
        scope.launch(ioDispatcher) { refreshInfo() }
    }

    override fun enrich(expression: String, reading: String, templates: Collection<String>): AnkiEnrichment {
        if (expression.isBlank() || templates.none { AnkiEnrichHandlebars.token.containsMatchIn(it) }) {
            return AnkiEnrichment.Empty
        }
        return synchronized(sourceLock) {
            runCatching { currentSource()?.lookup(expression, reading) }
                .onFailure { logWarning("Enrichment pack lookup failed", it) }
                .getOrNull()
        } ?: AnkiEnrichment.Empty
    }

    /** Starts an update in the application scope so leaving the screen does not cancel it. */
    fun startUpdate() {
        if (updateMutex.isLocked) return
        scope.launch(ioDispatcher) { update() }
    }

    internal suspend fun update() = updateMutex.withLock {
        withContext(ioDispatcher) {
            _status.value = EnrichPackStatus.Checking
            _status.value = runCatching { checkAndInstall() }.getOrElse { error ->
                logWarning("Enrichment pack update failed", error)
                EnrichPackStatus.Failed(UiText.Resource(R.string.enrich_pack_error_unreachable))
            }
        }
    }

    private suspend fun checkAndInstall(): EnrichPackStatus {
        val manifestUrl = settings.manifestUrl.first().trim()
        if (manifestUrl.isBlank()) return failed(R.string.enrich_pack_error_url_missing)
        val manifestBase = URL(manifestUrl)
        val manifest = Json { ignoreUnknownKeys = true }
            .decodeFromString<EnrichPackManifest>(readText(manifestBase))
        if (manifest.format != EnrichPackFormat.VERSION) return failed(R.string.enrich_pack_error_format)
        if (_info.value?.version == manifest.version) return EnrichPackStatus.UpToDate

        download(URL(manifestBase, manifest.file), manifest)
        if (partFile.length() != manifest.size || sha256(partFile) != manifest.sha256) {
            partFile.delete()
            return failed(R.string.enrich_pack_error_invalid)
        }
        val valid = runCatching {
            openPack(partFile).use { it.meta.format == EnrichPackFormat.VERSION && it.meta.version == manifest.version }
        }.getOrDefault(false)
        if (!valid) {
            partFile.delete()
            return failed(R.string.enrich_pack_error_invalid)
        }
        synchronized(sourceLock) {
            source?.close()
            source = null
            check(partFile.renameTo(packFile)) { "Unable to install the enrichment pack." }
        }
        partVersionFile.delete()
        refreshInfo()
        return EnrichPackStatus.Updated
    }

    private fun download(url: URL, manifest: EnrichPackManifest) {
        directory.mkdirs()
        if (partVersionFile.takeIf { it.exists() }?.readText() != manifest.version) {
            partFile.delete()
            partVersionFile.writeText(manifest.version)
        }
        val existing = partFile.length()
        if (existing >= manifest.size) return
        val connection = openConnection(url).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val append = when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> false
                else -> error("HTTP ${connection.responseCode} for $url")
            }
            var written = if (append) existing else 0L
            val buffer = ByteArray(64 * 1024)
            connection.inputStream.use { input ->
                FileOutputStream(partFile, append).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        val percent = (written * 100 / manifest.size.coerceAtLeast(1)).toInt()
                        if (_status.value != EnrichPackStatus.Downloading(percent)) {
                            _status.value = EnrichPackStatus.Downloading(percent)
                        }
                    }
                    output.fd.sync()
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readText(url: URL): String {
        val connection = openConnection(url).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            check(connection.responseCode == HttpURLConnection.HTTP_OK) { "HTTP ${connection.responseCode} for $url" }
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    }

    private fun refreshInfo() {
        _info.value = synchronized(sourceLock) {
            runCatching { currentSource()?.meta?.let { EnrichPackInfo(it.version, it.builtAt, it.entries) } }
                .onFailure { logWarning("Unable to open the enrichment pack", it) }
                .getOrNull()
        }
    }

    /** Callers hold [sourceLock]. */
    private fun currentSource(): EnrichPackSource? =
        source ?: packFile.takeIf { it.exists() }?.let(openPack)?.also { source = it }

    private fun failed(message: Int) = EnrichPackStatus.Failed(UiText.Resource(message))

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun logWarning(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }

    private companion object {
        const val TAG = "EnrichPack"
    }
}

/** Read-only SQLite view of a pack file (schema: `ankienrich/pack.py`). */
internal class SqliteEnrichPackSource(file: File) : EnrichPackSource {
    private val db = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    override val meta: EnrichPackMeta = run {
        val values = mutableMapOf<String, Any>()
        db.rawQuery("SELECT key, value FROM meta", null).use { cursor ->
            while (cursor.moveToNext()) {
                val key = cursor.getString(0)
                values[key] = if (key == "zdict") cursor.getBlob(1) else cursor.getString(1)
            }
        }
        val json = EnrichPackFormat.json
        EnrichPackMeta(
            format = (values["format"] as? String)?.toIntOrNull() ?: 0,
            version = values["version"] as? String ?: "",
            builtAt = values["built_at"] as? String ?: "",
            entries = (values["entries"] as? String)?.toIntOrNull() ?: 0,
            zdict = values["zdict"] as? ByteArray ?: ByteArray(0),
            defaults = (values["defaults"] as? String)?.let { json.decodeFromString<Map<String, String>>(it) }.orEmpty(),
            fallbackKeys = (values["fallback_keys"] as? String)?.let { json.decodeFromString<List<String>>(it) }.orEmpty().toSet(),
        )
    }

    override fun row(term: String, reading: String): EnrichPackRow? =
        db.rawQuery(
            "SELECT reading, data FROM entries WHERE term = ? ORDER BY reading = ? DESC, rowid LIMIT 1",
            arrayOf(term, reading),
        ).use { cursor ->
            if (cursor.moveToFirst()) EnrichPackRow(cursor.getString(0), cursor.getBlob(1)) else null
        }

    override fun close() = db.close()
}
