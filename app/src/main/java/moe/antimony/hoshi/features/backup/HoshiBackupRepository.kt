package moe.antimony.hoshi.features.backup

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.di.IoDispatcher

@Singleton
class HoshiBackupRepository @Inject constructor(
    @param:FilesDir private val filesDir: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun exportBooks(contentResolver: ContentResolver, uri: Uri) {
        exportFolder(contentResolver, uri, BackupTarget.Books)
    }

    suspend fun exportBooks(output: OutputStream) {
        exportFolder(BackupTarget.Books, output)
    }

    suspend fun restoreBooks(contentResolver: ContentResolver, uri: Uri) {
        restoreFolder(contentResolver, uri, BackupTarget.Books)
    }

    suspend fun restoreBooks(input: InputStream) {
        restoreFolder(BackupTarget.Books, input)
    }

    suspend fun exportDictionaries(contentResolver: ContentResolver, uri: Uri) {
        exportFolder(contentResolver, uri, BackupTarget.Dictionaries)
    }

    suspend fun exportDictionaries(output: OutputStream) {
        exportFolder(BackupTarget.Dictionaries, output)
    }

    suspend fun restoreDictionaries(contentResolver: ContentResolver, uri: Uri) {
        restoreFolder(contentResolver, uri, BackupTarget.Dictionaries)
    }

    suspend fun restoreDictionaries(input: InputStream) {
        restoreFolder(BackupTarget.Dictionaries, input)
    }

    private suspend fun exportFolder(contentResolver: ContentResolver, uri: Uri, target: BackupTarget) {
        withContext(ioDispatcher) {
            contentResolver.openOutputStream(uri)?.use { output ->
                exportFolder(target, output)
            } ?: error("Unable to open backup destination.")
        }
    }

    private suspend fun exportFolder(target: BackupTarget, output: OutputStream) {
        withContext(ioDispatcher) {
            ZipOutputStream(output.buffered()).use { zip ->
                val root = filesDir.resolve(target.folderName)
                if (!root.isDirectory) return@use
                root.walkTopDown()
                    .filter { it != root }
                    .sortedBy { it.relativeTo(root).invariantSeparatorsPath }
                    .forEach { file ->
                        val relativePath = file.relativeTo(root).invariantSeparatorsPath
                        val entryName = if (file.isDirectory) "$relativePath/" else relativePath
                        zip.putNextEntry(file.toZipEntry(entryName))
                        if (file.isFile) {
                            file.inputStream().use { input -> input.copyTo(zip) }
                        }
                        zip.closeEntry()
                    }
            }
        }
    }

    private suspend fun restoreFolder(contentResolver: ContentResolver, uri: Uri, target: BackupTarget) {
        withContext(ioDispatcher) {
            contentResolver.openInputStream(uri)?.use { input ->
                restoreFolder(target, input)
            } ?: error("Unable to open backup file.")
        }
    }

    private suspend fun restoreFolder(target: BackupTarget, input: InputStream) {
        withContext(ioDispatcher) {
            val destination = filesDir.resolve(target.folderName)
            val archiveFile = filesDir.resolve(".${target.folderName.lowercase()}-restore-${UUID.randomUUID()}.hoshi")
            val tempRoot = filesDir.resolve(".${target.folderName.lowercase()}-restore-${UUID.randomUUID()}")
            archiveFile.delete()
            tempRoot.deleteRecursively()
            check(tempRoot.mkdirs()) { "Unable to create restore directory." }
            try {
                archiveFile.outputStream().use { output -> input.copyTo(output) }
                unzipInto(archiveFile, tempRoot)
                replaceDestinationWithRestoredFolder(target, tempRoot, destination)
            } catch (error: Throwable) {
                tempRoot.deleteRecursively()
                throw error
            } finally {
                archiveFile.delete()
            }
        }
    }

    private fun replaceDestinationWithRestoredFolder(
        target: BackupTarget,
        restoredFolder: File,
        destination: File,
    ) {
        val replacementBackup = destination.takeIf(File::exists)?.let { existing ->
            filesDir.resolve(".${target.folderName.lowercase()}-restore-backup-${UUID.randomUUID()}")
                .also { backup -> moveReplacing(existing, backup) }
        }
        try {
            moveReplacing(restoredFolder, destination)
            replacementBackup?.deleteRecursively()
        } catch (error: Throwable) {
            destination.deleteRecursively()
            if (replacementBackup?.exists() == true) {
                moveReplacing(replacementBackup, destination)
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

    private fun unzipInto(archiveFile: File, destinationRoot: File) {
        val destinationCanonical = destinationRoot.canonicalFile
        ZipFile(archiveFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                val target = destinationCanonical.resolve(entry.name).canonicalFile
                require(target.path == destinationCanonical.path || target.path.startsWith(destinationCanonical.path + File.separator)) {
                    "Unsafe backup entry: ${entry.name}"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                } else {
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
    }

    private fun File.toZipEntry(entryName: String): ZipEntry {
        val entry = ZipEntry(entryName)
        if (isDirectory) {
            entry.size = 0
            entry.crc = 0
            return entry
        }

        entry.size = length()
        entry.crc = crc32()
        return entry
    }

    private fun File.crc32(): Long {
        val crc = CRC32()
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                crc.update(buffer, 0, read)
            }
        }
        return crc.value
    }

    private enum class BackupTarget(val folderName: String) {
        Books("Books"),
        Dictionaries("Dictionaries"),
    }
}

fun booksBackupFileName(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val timestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(zoneId)
        .format(now)
    return "Books_$timestamp.hoshi"
}

fun dictionariesBackupFileName(
    now: Instant = Instant.now(),
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val timestamp = DateTimeFormatter
        .ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(zoneId)
        .format(now)
    return "Dictionaries_$timestamp.hoshi"
}
