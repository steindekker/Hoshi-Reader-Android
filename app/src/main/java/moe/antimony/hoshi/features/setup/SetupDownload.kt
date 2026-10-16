package moe.antimony.hoshi.features.setup

import hoshi.card.setup.HoshiCardInstallStep
import hoshi.card.setup.SetupAsset
import hoshi.card.setup.SetupAudioServer
import hoshi.card.setup.SetupCatalogClient
import hoshi.card.setup.SetupManifest
import hoshi.card.setup.downloadVerified
import hoshi.card.setup.rethrowAsSetupFailure
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.AnkiFieldTemplates
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.defaultAnkiCardFormat
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSource
import moe.antimony.hoshi.features.audio.isBuiltInLocalAudioSource
import moe.antimony.hoshi.ui.UiText

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
 * Downloads the setup catalog and installs it. Dictionaries go through the existing Yomitan
 * importer. The sentence model (for `{sentence-pitch}`) and the enrichment pack install through
 * hoshi-card ([installCardData], a `HoshiCardInstaller`). Offline audio is separate:
 * [downloadAudio] runs in the background after setup.
 */
internal class SetupDownload(
    private val workDir: File,
    private val openConnection: (URL) -> HttpURLConnection,
    private val importZip: (File, Boolean) -> Int,
    private val lowRam: () -> Boolean,
    private val termTitles: () -> List<String>,
    private val installedTitles: () -> Set<String>,
    private val moveTerm: (Int, Int) -> Unit,
    private val installCardData: (
        catalogUrl: String,
        manifest: SetupManifest,
        onProgress: (HoshiCardInstallStep, Int) -> Unit,
    ) -> Unit,
    private val addAudioServer: (SetupAudioServer) -> Unit,
    private val audioInstalled: () -> Boolean,
    private val installAudio: (File) -> Unit,
    private val enableAudio: () -> Unit,
    private val applyAnki: () -> Unit,
    private val markComplete: () -> Unit,
    private val catalogUrl: String = SetupCatalogConfig.DefaultUrl,
) {
    private val catalog = SetupCatalogClient(catalogUrl, openConnection)

    fun peek(): SetupManifest = catalog.manifest()

    /** The enrichment pack's size from its own manifest; 0 when the catalog has none or it can't be read. */
    fun packSize(manifest: SetupManifest): Long = catalog.packSize(manifest)

    fun run(onProgress: (SetupProgress) -> Unit) {
        val manifest = peek()
        val base = URL(catalogUrl)
        val lowRamImport = lowRam()
        val installed = installedTitles()
        manifest.dictionaries.forEach { asset ->
            if (asset.isInstalled(installed)) return@forEach
            val file = download(base, asset, onProgress, dictionaryLabel(asset.id))
            onProgress(SetupProgress(dictionaryLabel(asset.id), importing = true))
            importZip(file, lowRamImport)
            file.delete()
        }
        reorderTermDictionaries(termTitles(), moveTerm)
        try {
            installCardData(catalogUrl, manifest) { step, percent -> onProgress(SetupProgress(cardDataLabel(step), percent)) }
        } catch (error: Exception) {
            error.rethrowAsSetupFailure()
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
            val label = UiText.Resource(R.string.setup_offline_audio_title)
            val file = download(URL(catalogUrl), audio, onProgress, label)
            onProgress(SetupProgress(label, importing = true))
            installAudio(file)
            file.delete()
        }
        enableAudio()
    }

    private fun download(base: URL, asset: SetupAsset, onProgress: (SetupProgress) -> Unit, label: UiText): File {
        val dest = File(workDir, asset.file)
        try {
            downloadVerified(URL(base, asset.file), dest, asset.installVersion, asset.size, asset.sha256, openConnection) { percent ->
                onProgress(SetupProgress(label, percent))
            }
        } catch (error: Exception) {
            error.rethrowAsSetupFailure()
        }
        return dest
    }
}

private fun cardDataLabel(step: HoshiCardInstallStep): UiText = UiText.Resource(
    when (step) {
        HoshiCardInstallStep.SentenceAccent -> R.string.setup_item_accent
        HoshiCardInstallStep.SentenceDictionary -> R.string.setup_item_sentence_dictionary
        HoshiCardInstallStep.Pack -> R.string.setup_item_pack
    },
)

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
