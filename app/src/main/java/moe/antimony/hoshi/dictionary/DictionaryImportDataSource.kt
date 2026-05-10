package moe.antimony.hoshi.dictionary

import android.content.ContentResolver
import android.net.Uri
import moe.antimony.hoshi.importing.ImportFileType
import moe.antimony.hoshi.importing.validateImportFile
import java.io.File
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

internal class DictionaryImportDataSource(
    private val nativeBridge: DictionaryNativeBridge = HoshiDictionaryNativeBridge,
) {
    fun importDictionary(
        contentResolver: ContentResolver,
        uri: Uri,
        typeDirectory: File,
    ) {
        contentResolver.validateImportFile(uri, ImportFileType.DictionaryArchive)
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open dictionary file." }
            importDictionary(input, typeDirectory)
        }
    }

    fun importDictionary(
        input: InputStream,
        typeDirectory: File,
    ) {
        typeDirectory.mkdirs()
        val importId = UUID.randomUUID()
        val tempZip = typeDirectory.resolve(".dictionary-import-$importId.zip")
        val stagingRoot = typeDirectory.resolve(".dictionary-import-$importId")
        try {
            input.use { source ->
                tempZip.outputStream().use { output -> source.copyTo(output) }
            }
            stagingRoot.mkdirs()
            val imported = nativeBridge.importDictionary(tempZip.absolutePath, stagingRoot.absolutePath)
            require(imported) { "Failed to import dictionary." }
            commitStagedDictionaries(stagingRoot, typeDirectory)
        } finally {
            tempZip.delete()
            stagingRoot.deleteRecursively()
        }
    }

    private fun commitStagedDictionaries(stagingRoot: File, typeDirectory: File) {
        val importedDictionaries = stagingRoot.listFiles()?.filter(File::isDirectory).orEmpty()
        require(importedDictionaries.isNotEmpty()) { "Failed to import dictionary." }
        importedDictionaries.forEach { stagedDictionary ->
            commitStagedDictionary(stagedDictionary, typeDirectory.resolve(stagedDictionary.name))
        }
    }

    private fun commitStagedDictionary(stagedDictionary: File, target: File) {
        val replacementBackup = target.takeIf(File::exists)?.let { existing ->
            requireNotNull(target.parentFile).resolve(".${target.name}-replace-${UUID.randomUUID()}")
                .also { backup -> moveReplacing(existing, backup) }
        }
        try {
            moveReplacing(stagedDictionary, target)
            replacementBackup?.deleteRecursively()
        } catch (error: Throwable) {
            target.deleteRecursively()
            if (replacementBackup?.exists() == true) {
                moveReplacing(replacementBackup, target)
            }
            throw error
        }
    }

    private fun moveReplacing(source: File, target: File) {
        runCatching {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        }.recoverCatching { error ->
            if (error !is AtomicMoveNotSupportedException) throw error
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }.getOrThrow()
    }
}
