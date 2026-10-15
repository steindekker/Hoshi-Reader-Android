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

/** Puts 三省堂, then 実用, then JMdict ahead of any other term dictionaries. */
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
    "三省堂" in title -> 0
    "実用" in title -> 1
    title.contains("JMdict", ignoreCase = true) -> 2
    else -> 3
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
 * Yomitan importer. The sentence model is the on-device `{ae-sentence}` pair.
 */
internal class SetupDownload(
    private val workDir: File,
    private val openConnection: (URL) -> HttpURLConnection,
    private val importZip: (File, Boolean) -> Int,
    private val lowRam: () -> Boolean,
    private val termTitles: () -> List<String>,
    private val moveTerm: (Int, Int) -> Unit,
    private val installSentence: (SentenceModelPart, File, String) -> Unit,
    private val sentenceInstalledVersion: (SentenceModelPart) -> String?,
    private val sentencePartFile: (SentenceModelPart) -> File,
    private val audioInstalled: () -> Boolean,
    private val installAudio: (File) -> Unit,
    private val enableAudio: () -> Unit,
    private val applyAnki: () -> Unit,
    private val markComplete: () -> Unit,
    private val catalogUrl: String = SetupCatalog.DefaultUrl,
) {
    fun peek(): SetupManifest = parseSetupManifest(readText(URL(catalogUrl)))

    fun run(includeAudio: Boolean, onProgress: (SetupProgress) -> Unit) {
        val manifest = peek()
        val base = URL(catalogUrl)
        val lowRamImport = lowRam()
        manifest.dictionaries.forEach { asset ->
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
        val audio = manifest.audio
        if (includeAudio && audio != null) {
            if (!audioInstalled()) {
                val file = download(base, audio, workDir, onProgress, UiText.Resource(R.string.setup_item_audio))
                installAudio(file)
                file.delete()
            }
            enableAudio()
        }
        applyAnki()
        markComplete()
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
                throw SetupFailure(SetupFailure.Reason.Unreachable)
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
    throw SetupFailure(SetupFailure.Reason.Unreachable)
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
