package moe.antimony.hoshi.features.setup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.dictionary.DictionaryRepository
import moe.antimony.hoshi.dictionary.DictionaryType
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import moe.antimony.hoshi.features.anki.enrich.EnrichPackRepository
import moe.antimony.hoshi.features.anki.enrich.JrpSentenceModel
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettingsRepository

private val Context.setupDataStore by preferencesDataStore(name = "setup")
private val SetupCompletedKey = booleanPreferencesKey("completed")
private val SetupVersionKey = intPreferencesKey("version")

/** Bumped when setup gains a step that installs from earlier versions should run once. */
internal const val SetupVersion = 2

/**
 * First-run download of the Yomitan dictionaries, the on-device sentence model and the
 * enrichment pack, plus the optional offline word audio that [SetupAudioWorker] fetches later.
 */
@Singleton
internal class SetupRepository @Inject constructor(
    @ApplicationContext context: Context,
    @FilesDir filesDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    dictionaries: DictionaryRepository,
    dictionarySettings: DictionarySettingsRepository,
    sentenceModel: JrpSentenceModel,
    enrichPack: EnrichPackRepository,
    localAudio: LocalAudioRepository,
    audioSettings: AudioSettingsRepository,
    ankiSettings: AnkiSettingsRepository,
) {
    val completed: Flow<Boolean> = context.setupDataStore.data.map { (it[SetupVersionKey] ?: 0) >= SetupVersion }

    private val download = SetupDownload(
        workDir = File(filesDir, "setup-downloads"),
        openConnection = SetupServerConnections()::open,
        importZip = { file, lowRam ->
            file.inputStream().use { input -> dictionaries.importDictionary(input, lowRam) }
        },
        lowRam = { runBlocking { dictionarySettings.settings.first().lowRamDictionaryImport } },
        termTitles = { dictionaries.loadDictionaries(DictionaryType.Term).map { it.index.title } },
        installedTitles = {
            DictionaryType.entries.flatMap { type -> dictionaries.loadDictionaries(type).map { it.index.title } }.toSet()
        },
        moveTerm = { from, to -> dictionaries.moveDictionary(DictionaryType.Term, from, to) },
        installSentence = sentenceModel::install,
        sentenceInstalledVersion = sentenceModel::installedVersion,
        sentencePartFile = { part -> File(sentenceModel.file(part).parentFile, part.fileName + ".part") },
        installPack = { manifestUrl, onPercent -> runBlocking { enrichPack.installFrom(manifestUrl, onPercent) } },
        addAudioServer = { server -> runBlocking { audioSettings.update { it.withFallbackAudioServer(server) } } },
        audioInstalled = localAudio::canOpenDatabase,
        installAudio = { file -> localAudio.installDownloadedDatabase(file) },
        enableAudio = { runBlocking { audioSettings.update { it.withLocalAudioEnabled(true) } } },
        applyAnki = { runBlocking { ankiSettings.update { it.withSeamlessFormatIfUnmapped() } } },
        markComplete = {
            runBlocking {
                context.setupDataStore.edit {
                    it[SetupCompletedKey] = true
                    it[SetupVersionKey] = SetupVersion
                }
            }
        },
    )

    suspend fun peek(): SetupManifest = withContext(ioDispatcher) { download.peek() }

    suspend fun packSize(manifest: SetupManifest): Long = withContext(ioDispatcher) { download.packSize(manifest) }

    suspend fun run(onProgress: (SetupProgress) -> Unit) =
        withContext(ioDispatcher) { download.run(onProgress) }

    suspend fun downloadAudio(onProgress: (SetupProgress) -> Unit) =
        withContext(ioDispatcher) { download.downloadAudio(onProgress) }
}
