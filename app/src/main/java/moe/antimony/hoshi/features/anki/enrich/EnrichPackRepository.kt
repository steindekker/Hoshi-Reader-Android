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
    data class Downloading(val percent: Int, val item: EnrichDownload = EnrichDownload.Pack) : EnrichPackStatus
    data object UpToDate : EnrichPackStatus
    data object Updated : EnrichPackStatus
    data class Failed(val message: UiText) : EnrichPackStatus
}

/** What an update is downloading: the pack, or a part of the sentence model. */
internal enum class EnrichDownload { Pack, Accent, Dictionary }

internal data class EnrichPackInfo(
    val version: String,
    val builtAt: String,
    val entries: Int,
    val keys: List<String> = AnkiEnrichHandlebars.defaultKeys,
)

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
 * the server's `pack.json` names a new version. The manifest's sentence model (for
 * `{ae-sentence}`) is downloaded the same way, each file on its own version. Downloads resume
 * after an interruption and are checked against the manifest's size and SHA-256 before they
 * replace the installed file.
 */
@Singleton
internal class EnrichPackRepository(
    private val directory: File,
    private val settings: EnrichPackSettingsRepository,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val openPack: (File) -> EnrichPackSource,
    private val sentenceModel: JrpSentenceModel = JrpSentenceModel.inDirectory(directory),
    private val openConnection: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : AnkiFieldEnricher {
    @Inject
    constructor(
        @FilesDir filesDir: File,
        settings: EnrichPackSettingsRepository,
        @ApplicationScope scope: CoroutineScope,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
        sentenceModel: JrpSentenceModel,
    ) : this(File(filesDir, EnrichPackFormat.DIRECTORY), settings, scope, ioDispatcher, ::SqliteEnrichPackSource, sentenceModel)

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

    /** Whether the sentence model is installed, so `{ae-sentence}` gets JRP markup. */
    val sentenceModelInstalled: StateFlow<Boolean> = sentenceModel.installed

    init {
        scope.launch(ioDispatcher) { refreshInfo() }
    }

    override fun enrich(
        expression: String,
        reading: String,
        sentence: String,
        templates: Collection<String>,
    ): AnkiEnrichment {
        if (templates.none { AnkiEnrichHandlebars.token.containsMatchIn(it) }) return AnkiEnrichment.Empty
        val enrichment = if (expression.isBlank()) {
            AnkiEnrichment.Empty
        } else {
            synchronized(sourceLock) {
                runCatching { currentSource()?.lookup(expression, reading) }
                    .onFailure { logWarning("Enrichment pack lookup failed", it) }
                    .getOrNull()
            } ?: AnkiEnrichment.Empty
        }
        val sentenceToken = "{${AnkiEnrichHandlebars.SENTENCE_KEY}}"
        val withSentence = if (templates.none { sentenceToken in it }) {
            enrichment
        } else {
            enrichment.with(AnkiEnrichHandlebars.SENTENCE_KEY, sentenceModel.markup(sentence))
        }
        return withSentence.withDefaults(AnkiEnrichment.DictionaryDefaults)
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
        var updated = false
        if (_info.value?.version != manifest.version) {
            installPack(manifestBase, manifest)?.let { return it }
            updated = true
        }
        for ((part, entry) in manifest.sentence?.parts().orEmpty()) {
            if (sentenceModel.installedVersion(part) == entry.version) continue
            installSentenceModelPart(manifestBase, part, entry)?.let { return it }
            updated = true
        }
        return if (updated) EnrichPackStatus.Updated else EnrichPackStatus.UpToDate
    }

    /** Downloads and installs the pack; a failure status, or null once installed. */
    private fun installPack(manifestBase: URL, manifest: EnrichPackManifest): EnrichPackStatus? {
        download(URL(manifestBase, manifest.file), manifest.version, manifest.size, partFile, partVersionFile, EnrichDownload.Pack)
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
        return null
    }

    /** Downloads and installs one sentence-model file; a failure status, or null once installed. */
    private fun installSentenceModelPart(
        manifestBase: URL,
        part: SentenceModelPart,
        entry: EnrichFileManifest,
    ): EnrichPackStatus? {
        val partFile = File(directory, part.fileName + ".part")
        val partVersionFile = File(directory, part.fileName + ".part-version")
        val item = when (part) {
            SentenceModelPart.Accent -> EnrichDownload.Accent
            SentenceModelPart.Dictionary -> EnrichDownload.Dictionary
        }
        download(URL(manifestBase, entry.file), entry.version, entry.size, partFile, partVersionFile, item)
        if (partFile.length() != entry.size || sha256(partFile) != entry.sha256) {
            partFile.delete()
            return failed(R.string.enrich_pack_error_invalid)
        }
        sentenceModel.install(part, partFile, entry.version)
        partVersionFile.delete()
        return null
    }

    private fun download(
        url: URL,
        version: String,
        size: Long,
        partFile: File,
        partVersionFile: File,
        item: EnrichDownload,
    ) {
        directory.mkdirs()
        if (partVersionFile.takeIf { it.exists() }?.readText() != version) {
            partFile.delete()
            partVersionFile.writeText(version)
        }
        val existing = partFile.length()
        if (existing >= size) return
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
                        val progress = EnrichPackStatus.Downloading((written * 100 / size.coerceAtLeast(1)).toInt(), item)
                        if (_status.value != progress) _status.value = progress
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
            runCatching {
                currentSource()?.meta?.let { meta ->
                    EnrichPackInfo(
                        version = meta.version,
                        builtAt = meta.builtAt,
                        entries = meta.entries,
                        keys = meta.keys.ifEmpty { AnkiEnrichHandlebars.defaultKeys },
                    )
                }
            }
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
            keys = (values["keys"] as? String)?.let { json.decodeFromString<List<String>>(it) }.orEmpty(),
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
