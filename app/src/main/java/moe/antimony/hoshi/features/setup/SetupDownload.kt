package moe.antimony.hoshi.features.setup

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.AnkiFieldTemplates
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.defaultAnkiCardFormat
import moe.antimony.hoshi.features.anki.enrich.SentenceModelPart
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSource
import moe.antimony.hoshi.features.audio.isBuiltInLocalAudioSource
import moe.antimony.hoshi.ui.UiText
import java.util.UUID

private val setupJson = Json { ignoreUnknownKeys = true }

internal fun parseSetupManifest(raw: String): SetupManifest {
    val manifest = runCatching { setupJson.decodeFromString<SetupManifest>(raw) }
        .getOrElse { throw SetupFailure(SetupFailure.Reason.Format) }
    if (manifest.format != SetupCatalog.Format) throw SetupFailure(SetupFailure.Reason.Format)
    val sentence = manifest.sentence ?: throw SetupFailure(SetupFailure.Reason.Format)
    if (sentence.format != SetupCatalog.Format) throw SetupFailure(SetupFailure.Reason.Format)
    (manifest.dictionaries + sentence.accent + sentence.dictionary + listOfNotNull(manifest.audio)).forEach { asset ->
        if (asset.file.isBlank() || asset.size <= 0L || asset.sha256.isBlank()) {
            throw SetupFailure(SetupFailure.Reason.Format)
        }
    }
    return manifest
}

/** An exact title match, or a dated release of it such as `JMdict [2026-10-16]` for `JMdict`. */
internal fun SetupAsset.isInstalled(installedTitles: Set<String>): Boolean =
    title.isNotBlank() && installedTitles.any { it == title || it.startsWith("$title [") }

/** Puts 実用, then 三省堂, then JMdict ahead of any other term dictionaries. */
internal fun reorderTermDictionaries(titles: List<String>, move: (from: Int, to: Int) -> Unit) {
    val current = titles.toMutableList()
    val desired = current.withIndex()
        .sortedWith(compareBy<IndexedValue<String>>({ termRank(it.value) }, { it.index }))
        .map { it.value }
    desired.forEachIndexed { target, title ->
        val from = current.indexOf(title)
        if (from == target || from < 0) return@forEachIndexed
        move(from, target)
        current.add(target, current.removeAt(from))
    }
}

private fun termRank(title: String): Int = when {
    "実用" in title -> 0
    "三省堂" in title -> 1
    title.contains("JMdict", ignoreCase = true) -> 2
    else -> 3
}

/**
 * Adds the catalog's audio server right after the offline database, so a word the database
 * lacks (or every word, while offline audio is off) still gets audio.
 */
internal fun AudioSettings.withFallbackAudioServer(server: SetupAudioServer): AudioSettings {
    if (server.name.isBlank() || server.url.isBlank()) return this
    val source = AudioSource(name = server.name, url = server.url)
    val (local, rest) = audioSources.filterNot { it.url == source.url }.partition { it.isBuiltInLocalAudioSource }
    return copy(audioSources = local + source + rest)
}

internal fun AnkiSettings.withSeamlessFormatIfUnmapped(): AnkiSettings {
    val formats = cardFormats.ifEmpty { listOf(defaultAnkiCardFormat(UUID.randomUUID().toString())) }
    if (formats.any { it.fieldMappings.isNotEmpty() }) return this
    val format = AnkiFieldTemplates.seamlessCardFormat(formats.first().id)
    return copy(
        cardFormats = listOf(format),
        selectedDeckName = format.selectedDeckName,
        selectedNoteTypeName = format.selectedNoteTypeName,
        fieldMappings = format.fieldMappings,
        tags = format.tags,
    )
}

/**
 * Downloads the setup catalog and installs it. Dictionaries go through the existing
 * Yomitan importer. The sentence model is the on-device `{ae-sentence}` pair, and the
 * enrichment pack (examples, definitions, pitch) installs through its own updater.
 * Offline audio is separate: [downloadAudio] runs in the background after setup.
 */
internal class SetupDownload(
    private val workDir: File,
    private val openConnection: (URL) -> HttpURLConnection,
    private val importZip: (File, Boolean) -> Int,
    private val lowRam: () -> Boolean,
    private val termTitles: () -> List<String>,
    private val installedTitles: () -> Set<String>,
    private val moveTerm: (Int, Int) -> Unit,
    private val installSentence: (SentenceModelPart, File, String) -> Unit,
    private val sentenceInstalledVersion: (SentenceModelPart) -> String?,
    private val sentencePartFile: (SentenceModelPart) -> File,
    private val installPack: (manifestUrl: String, onPercent: (Int) -> Unit) -> Unit,
    private val addAudioServer: (SetupAudioServer) -> Unit,
    private val audioInstalled: () -> Boolean,
    private val installAudio: (File) -> Unit,
    private val enableAudio: () -> Unit,
    private val applyAnki: () -> Unit,
    private val markComplete: () -> Unit,
    private val catalogUrl: String = SetupCatalog.DefaultUrl,
) {
    fun peek(): SetupManifest = parseSetupManifest(readText(URL(catalogUrl)))

    /** The enrichment pack's size from its own manifest; 0 when the catalog has none or it can't be read. */
    fun packSize(manifest: SetupManifest): Long {
        val pack = manifest.pack ?: return 0L
        return runCatching {
            setupJson.decodeFromString<SetupPackSize>(readText(URL(URL(catalogUrl), pack))).size
        }.getOrDefault(0L)
    }

    fun run(onProgress: (SetupProgress) -> Unit) {
        val manifest = peek()
        val base = URL(catalogUrl)
        val lowRamImport = lowRam()
        val installed = installedTitles()
        manifest.dictionaries.forEach { asset ->
            if (asset.isInstalled(installed)) return@forEach
            val file = download(base, asset, workDir, onProgress, dictionaryLabel(asset.id))
            onProgress(SetupProgress(dictionaryLabel(asset.id), importing = true))
            importZip(file, lowRamImport)
            file.delete()
        }
        reorderTermDictionaries(termTitles(), moveTerm)
        installSentenceAsset(base, SentenceModelPart.Accent, manifest.sentence!!.accent, onProgress, UiText.Resource(R.string.setup_item_accent))
        installSentenceAsset(
            base,
            SentenceModelPart.Dictionary,
            manifest.sentence.dictionary,
            onProgress,
            UiText.Resource(R.string.setup_item_sentence_dictionary),
        )
        manifest.pack?.let { pack ->
            val label = UiText.Resource(R.string.setup_item_pack)
            try {
                installPack(URL(base, pack).toString()) { percent -> onProgress(SetupProgress(label, percent)) }
            } catch (error: Exception) {
                error.asSetupFailure()
            }
        }
        manifest.audioServer?.let(addAudioServer)
        applyAnki()
        markComplete()
    }

    /** Downloads and enables the offline word-audio database; a no-op when the catalog has none. */
    fun downloadAudio(onProgress: (SetupProgress) -> Unit) {
        val manifest = peek()
        val audio = manifest.audio ?: return
        if (!audioInstalled()) {
            val file = download(URL(catalogUrl), audio, workDir, onProgress, UiText.Resource(R.string.setup_offline_audio_title))
            onProgress(SetupProgress(UiText.Resource(R.string.setup_offline_audio_title), importing = true))
            installAudio(file)
            file.delete()
        }
        enableAudio()
    }

    private fun installSentenceAsset(
        base: URL,
        part: SentenceModelPart,
        asset: SetupAsset,
        onProgress: (SetupProgress) -> Unit,
        label: UiText,
    ) {
        val version = asset.version.ifBlank { asset.sha256 }
        if (sentenceInstalledVersion(part) == version) return
        val partFile = sentencePartFile(part)
        download(base, asset, partFile.parentFile ?: workDir, onProgress, label, partFile.name)
        installSentence(part, partFile, version)
        File(partFile.parentFile, partFile.name + ".version").delete()
    }

    private fun download(
        base: URL,
        asset: SetupAsset,
        directory: File,
        onProgress: (SetupProgress) -> Unit,
        label: UiText,
        fileName: String = asset.file,
    ): File {
        val dest = File(directory, fileName)
        val version = asset.version.ifBlank { asset.sha256 }
        try {
            downloadVerified(URL(base, asset.file), dest, version, asset.size, asset.sha256, openConnection) { percent ->
                onProgress(SetupProgress(label, percent))
            }
        } catch (error: Exception) {
            error.asSetupFailure()
        }
        return dest
    }

    private fun readText(url: URL): String = try {
        val connection = openConnection(url).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw SetupFailure(SetupFailure.Reason.Unreachable, "HTTP ${connection.responseCode} for $url")
            }
            connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            connection.disconnect()
        }
    } catch (error: Exception) {
        error.asSetupFailure()
    }
}

private fun dictionaryLabel(id: String): UiText = UiText.Literal(
    when (id) {
        "sankoku8" -> "三省堂"
        "jitsuyo" -> "実用"
        "jmdict" -> "JMdict"
        "nhk-pitch" -> "NHK"
        "jpdb-freq" -> "JPDB"
        "kanjidic" -> "KANJIDIC"
        else -> id
    },
)

internal fun downloadVerified(
    url: URL,
    dest: File,
    version: String,
    size: Long,
    expectedSha: String,
    openConnection: (URL) -> HttpURLConnection,
    onProgress: (Int) -> Unit,
) {
    dest.parentFile?.mkdirs()
    val versionFile = File(dest.parentFile, dest.name + ".version")
    if (versionFile.takeIf { it.exists() }?.readText() != version) {
        dest.delete()
        versionFile.writeText(version)
    }
    var existing = dest.length()
    if (existing < size) {
        val connection = openConnection(url).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            if (existing > 0) setRequestProperty("Range", "bytes=$existing-")
        }
        try {
            val append = when (connection.responseCode) {
                HttpURLConnection.HTTP_PARTIAL -> true
                HttpURLConnection.HTTP_OK -> {
                    existing = 0
                    dest.delete()
                    false
                }
                else -> throw SetupFailure(SetupFailure.Reason.Unreachable)
            }
            var written = if (append) existing else 0L
            val buffer = ByteArray(64 * 1024)
            connection.inputStream.use { input ->
                FileOutputStream(dest, append).use { output ->
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        onProgress((written * 100 / size.coerceAtLeast(1)).toInt())
                    }
                    output.fd.sync()
                }
            }
        } catch (error: Exception) {
            error.asSetupFailure()
        } finally {
            connection.disconnect()
        }
    }
    if (dest.length() != size || sha256(dest) != expectedSha.lowercase()) {
        dest.delete()
        versionFile.delete()
        throw SetupFailure(SetupFailure.Reason.Damaged)
    }
}

private fun Exception.asSetupFailure(): Nothing {
    if (this is CancellationException || this is SetupFailure) throw this
    throw SetupFailure(SetupFailure.Reason.Unreachable, cause = this)
}

private fun sha256(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}
