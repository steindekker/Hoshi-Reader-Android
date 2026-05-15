package moe.antimony.hoshi.dictionary

import android.content.ContentResolver
import android.net.Uri
import java.io.File

internal class DictionaryRepository(
    filesDir: File,
    private val storage: DictionaryStorageDataSource = DictionaryStorageDataSource(filesDir),
    private val importDataSource: DictionaryImportDataSource = DictionaryImportDataSource(),
    private val lookupQueryService: DictionaryLookupQueryService = DictionaryLookupQueryService(),
    private val remoteDataSource: DictionaryRemoteDataSource = UrlDictionaryRemoteDataSource(),
) {
    fun loadDictionaries(type: DictionaryType): List<DictionaryInfo> =
        storage.loadDictionaries(type)

    fun updatableDictionaries(): List<DictionaryUpdateCandidate> =
        storage.updatableDictionaries()

    fun importDictionary(contentResolver: ContentResolver, uri: Uri, type: DictionaryType) {
        val imported = importDataSource.importDictionary(
            contentResolver = contentResolver,
            uri = uri,
            typeDirectory = storage.typeDirectory(type),
            shouldSkip = { index -> storage.hasDictionaryWithIndex(type, index) },
        )
        if (imported) {
            storage.saveConfigFromStorage()
            rebuildLookupQuery()
        }
    }

    fun setDictionaryEnabled(type: DictionaryType, fileName: String, enabled: Boolean) {
        val config = storage.configWithDictionaryEnabled(type, fileName, enabled)
        storage.saveConfig(config)
        rebuildLookupQuery()
    }

    fun deleteDictionary(type: DictionaryType, fileName: String) {
        storage.deleteDictionary(type, fileName)
        storage.saveConfig(storage.currentConfig())
        rebuildLookupQuery()
    }

    fun moveDictionary(type: DictionaryType, fromIndex: Int, toIndex: Int) {
        val config = storage.configWithDictionaryMoved(type, fromIndex, toIndex)
        storage.saveConfig(config)
        rebuildLookupQuery()
    }

    fun updateDictionaries(
        onProgress: (DictionaryUpdateProgress) -> Unit = {},
    ): DictionaryUpdateSummary {
        val candidates = updatableDictionaries()
        var updatedCount = 0
        val renames = mutableListOf<DictionaryRename>()
        candidates.forEach { candidate ->
            val installed = candidate.dictionary
            val installedIndex = installed.index
            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Checking, installedIndex.title))
            val remoteIndex = remoteDataSource.fetchIndex(installedIndex.indexUrl)
            if (remoteIndex.revision == installedIndex.revision) return@forEach

            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, remoteIndex.title))
            val imported = remoteDataSource.downloadArchive(remoteIndex.downloadUrl).use { input ->
                onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Importing, remoteIndex.title))
                importDataSource.importDictionaryWithResult(
                    input = input,
                    typeDirectory = storage.typeDirectory(candidate.type),
                )
            }
            val replacement = imported.firstOrNull() ?: return@forEach
            if (replacement.fileName != installed.path.name) {
                storage.deleteDictionary(candidate.type, installed.path.name)
            }
            storage.saveConfig(
                storage.configWithImportedDictionaryReplacing(
                    type = candidate.type,
                    replacementFileName = replacement.fileName,
                    enabled = installed.isEnabled,
                    order = installed.order,
                ),
            )
            if (replacement.index.title != installedIndex.title) {
                renames += DictionaryRename(
                    oldTitle = installedIndex.title,
                    newTitle = replacement.index.title,
                )
            }
            updatedCount += 1
        }
        if (updatedCount > 0) {
            rebuildLookupQuery()
        }
        return DictionaryUpdateSummary(
            checkedCount = candidates.size,
            updatedCount = updatedCount,
            renamedDictionaries = renames,
        )
    }

    fun importRecommendedDictionaries(
        dictionaries: List<RecommendedDictionary>,
        onProgress: (DictionaryUpdateProgress) -> Unit = {},
    ) {
        var importedCount = 0
        dictionaries.forEach { dictionary ->
            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Fetching, dictionary.name))
            val remoteIndex = remoteDataSource.fetchIndex(dictionary.indexUrl)
            onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Downloading, remoteIndex.title))
            val imported = remoteDataSource.downloadArchive(remoteIndex.downloadUrl).use { input ->
                onProgress(DictionaryUpdateProgress(DictionaryUpdateStage.Importing, remoteIndex.title))
                importDataSource.importDictionaryWithResult(
                    input = input,
                    typeDirectory = storage.typeDirectory(dictionary.type),
                    shouldSkip = { index -> storage.hasDictionaryWithIndex(dictionary.type, index) },
                )
            }
            importedCount += imported.size
            if (imported.isNotEmpty()) {
                storage.saveConfigFromStorage()
            }
        }
        if (importedCount > 0) {
            rebuildLookupQuery()
        }
    }

    fun rebuildLookupQuery() {
        lookupQueryService.rebuild(
            termDictionaries = storage.enabledDictionaryPaths(DictionaryType.Term),
            frequencyDictionaries = storage.enabledDictionaryPaths(DictionaryType.Frequency),
            pitchDictionaries = storage.enabledDictionaryPaths(DictionaryType.Pitch),
        )
    }
}
