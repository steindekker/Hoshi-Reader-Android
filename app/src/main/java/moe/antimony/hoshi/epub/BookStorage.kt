package moe.antimony.hoshi.epub

import android.content.ContentResolver
import android.net.Uri
import java.io.File
import java.util.zip.ZipInputStream

class BookStorage(private val filesDir: File) {
    private val booksDirectory = File(filesDir, "Books")
    val currentBookFile: File = File(booksDirectory, "current.epub")

    fun loadAllBooks(): List<File> =
        if (currentBookFile.isDirectory) listOf(currentBookFile) else emptyList()

    fun importBook(contentResolver: ContentResolver, uri: Uri): File {
        booksDirectory.mkdirs()
        if (currentBookFile.exists()) {
            currentBookFile.deleteRecursively()
        }
        currentBookFile.mkdirs()
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected EPUB" }
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    val output = currentBookFile.resolve(entry.name).canonicalFile
                    val root = currentBookFile.canonicalFile
                    require(output.path == root.path || output.path.startsWith(root.path + File.separator)) {
                        "Unsafe EPUB entry: ${entry.name}"
                    }
                    if (entry.isDirectory) {
                        output.mkdirs()
                    } else {
                        output.parentFile?.mkdirs()
                        output.outputStream().use { zip.copyTo(it) }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        }
        return currentBookFile
    }
}
