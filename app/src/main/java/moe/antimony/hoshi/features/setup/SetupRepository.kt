package moe.antimony.hoshi.features.setup

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.net.HttpURLConnection
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
import moe.antimony.hoshi.features.anki.enrich.JrpSentenceModel
import moe.antimony.hoshi.features.audio.AudioSettingsRepository
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.dictionary.DictionarySettingsRepository

private val Context.setupDataStore by preferencesDataStore(name = "setup")
private val SetupCompletedKey = booleanPreferencesKey("completed")

/**
 * First-run download of the Yomitan dictionaries and the on-device sentence model.
 * The enrichment pack is not required: `{ae-*}` falls back to the installed dictionaries.
 */
@Singleton
internal class SetupRepository @Inject constructor(
    @ApplicationContext context: Context,
    @FilesDir filesDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    dictionaries: DictionaryRepository,
    dictionarySettings: DictionarySettingsRepository,
    sentenceModel: JrpSentenceModel,
    localAudio: LocalAudioRepository,
    audioSettings: AudioSettingsRepository,
    ankiSettings: AnkiSettingsRepository,
) {
    val completed: Flow<Boolean> = context.setupDataStore.data.map { it[SetupCompletedKey] == true }

    private val download = SetupDownload(
        workDir = File(filesDir, "setup-downloads"),
        openConnection = { it.openConnection() as HttpURLConnection },
        importZip = { file, lowRam ->
            file.inputStream().use { input -> dictionaries.importDictionary(input, lowRam) }
        },
        lowRam = { runBlocking { dictionarySettings.settings.first().lowRamDictionaryImport } },
        termTitles = { dictionaries.loadDictionaries(DictionaryType.Term).map { it.index.title } },
        moveTerm = { from, to -> dictionaries.moveDictionary(DictionaryType.Term, from, to) },
        installSentence = sentenceModel::install,
        sentenceInstalledVersion = sentenceModel::installedVersion,
        sentencePartFile = { part -> File(sentenceModel.file(part).parentFile, part.fileName + ".part") },
        audioInstalled = localAudio::canOpenDatabase,
        installAudio = { file -> localAudio.installDownloadedDatabase(file) },
        enableAudio = { runBlocking { audioSettings.update { it.withLocalAudioEnabled(true) } } },
        applyAnki = { runBlocking { ankiSettings.update { it.withSeamlessFormatIfUnmapped() } } },
        markComplete = {
            runBlocking {
                context.setupDataStore.edit { it[SetupCompletedKey] = true }
            }
        },
    )

    suspend fun peek(): SetupManifest = withContext(ioDispatcher) { download.peek() }

    suspend fun run(includeAudio: Boolean, onProgress: (SetupProgress) -> Unit) =
        withContext(ioDispatcher) { download.run(includeAudio, onProgress) }
}
