package moe.antimony.hoshi.epub

import android.content.ContentResolver
import android.net.Uri
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipInputStream

@Serializable
data class Bookmark(
    val chapterIndex: Int,
    val progress: Double,
    val characterCount: Int,
    val lastModified: Double? = null,
)

@Serializable
data class BookInfo(
    val characterCount: Int,
    val chapterInfo: Map<String, ChapterInfo>,
) {
    @Serializable
    data class ChapterInfo(
        val spineIndex: Int?,
        val currentTotal: Int,
        val chapterCount: Int,
    )
}

@Serializable
data class BookMetadata(
    val id: String,
    val title: String?,
    val cover: String?,
    val folder: String?,
    val lastAccess: Double,
)

data class BookEntry(
    val root: File,
    val metadata: BookMetadata,
)

enum class BookSortOption {
    Recent,
    Title,
}

class BookStorage(private val filesDir: File) {
    private val booksDirectory = File(filesDir, "Books")
    val currentBookFile: File = File(booksDirectory, "current.epub")
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "    "
        encodeDefaults = true
    }

    fun loadAllBooks(): List<File> =
        booksDirectory
            .listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()

    fun loadBookEntries(sortOption: BookSortOption = BookSortOption.Recent): List<BookEntry> {
        val entries = loadAllBooks()
            .map { root ->
                BookEntry(
                    root = root,
                    metadata = loadMetadata(root) ?: root.fallbackMetadata(),
                )
            }
        return when (sortOption) {
            BookSortOption.Recent -> entries.sortedByDescending { it.metadata.lastAccess }
            BookSortOption.Title -> entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.metadata.title.orEmpty() })
        }
    }

    fun createBookDirectory(folder: String = UUID.randomUUID().toString()): File {
        booksDirectory.mkdirs()
        val root = booksDirectory.resolve(folder).canonicalFile
        val booksRoot = booksDirectory.canonicalFile
        require(root.path == booksRoot.path || root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book folder: $folder"
        }
        root.mkdirs()
        return root
    }

    fun createBookDirectoryForImportedTitle(title: String): File {
        val safeTitle = title.sanitizeImportedBookTitle()
        require(safeTitle.isNotBlank()) { "EPUB title is empty" }
        return createBookDirectory(safeTitle)
    }

    fun loadMetadata(bookRoot: File): BookMetadata? {
        val file = bookRoot.resolve(METADATA_FILE_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<BookMetadata>(file.readText()) }.getOrNull()
    }

    fun saveMetadata(bookRoot: File, metadata: BookMetadata) {
        bookRoot.mkdirs()
        bookRoot.resolve(METADATA_FILE_NAME).writeText(json.encodeToString(metadata))
    }

    fun coverFile(entry: BookEntry): File? {
        val cover = entry.metadata.cover?.takeIf { it.isNotBlank() } ?: return null
        val root = entry.root.canonicalFile
        val file = root.resolve(cover).canonicalFile
        if (file.path != root.path && !file.path.startsWith(root.path + File.separator)) return null
        return file.takeIf { it.isFile }
    }

    fun deleteBook(bookRoot: File) {
        val root = bookRoot.canonicalFile
        val booksRoot = booksDirectory.canonicalFile
        require(root.path != booksRoot.path && root.path.startsWith(booksRoot.path + File.separator)) {
            "Unsafe book directory: ${bookRoot.path}"
        }
        if (root.exists()) {
            root.deleteRecursively()
        }
    }

    fun loadBookmark(bookRoot: File): Bookmark? {
        val file = bookRoot.resolve(BOOKMARK_FILE_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<Bookmark>(file.readText()) }.getOrNull()
    }

    fun saveBookmark(bookRoot: File, bookmark: Bookmark) {
        bookRoot.mkdirs()
        bookRoot.resolve(BOOKMARK_FILE_NAME).writeText(json.encodeToString(bookmark))
    }

    fun loadBookInfo(bookRoot: File): BookInfo? {
        val file = bookRoot.resolve(BOOKINFO_FILE_NAME)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<BookInfo>(file.readText()) }.getOrNull()
    }

    fun saveBookInfo(bookRoot: File, bookInfo: BookInfo) {
        bookRoot.mkdirs()
        bookRoot.resolve(BOOKINFO_FILE_NAME).writeText(json.encodeToString(bookInfo))
    }

    fun loadReadingProgress(bookRoot: File): Double {
        val total = loadBookInfo(bookRoot)?.characterCount ?: return 0.0
        if (total <= 0) return 0.0
        val current = loadBookmark(bookRoot)?.characterCount ?: return 0.0
        return current.toDouble().div(total.toDouble()).coerceIn(0.0, 1.0)
    }

    fun currentAppleReferenceDateSeconds(): Double {
        val now = Instant.now()
        return now.epochSecond.toDouble() + (now.nano.toDouble() / 1_000_000_000.0) - APPLE_REFERENCE_EPOCH_SECONDS
    }

    fun importBook(contentResolver: ContentResolver, uri: Uri): File {
        val tempRoot = File(filesDir, "ImportTemp/${UUID.randomUUID()}").canonicalFile
        contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to open selected EPUB" }
            runCatching {
                tempRoot.mkdirs()
                ZipInputStream(input).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val output = tempRoot.resolve(entry.name).canonicalFile
                        val root = tempRoot.canonicalFile
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
            }.onFailure {
                tempRoot.deleteRecursively()
                throw it
            }
        }
        val parsedBook = runCatching { EpubBookParser().parse(tempRoot) }
            .onFailure { tempRoot.deleteRecursively() }
            .getOrThrow()
        val targetRoot = createBookDirectoryForImportedTitle(parsedBook.title)
        if (targetRoot.listFiles()?.isNotEmpty() == true) {
            tempRoot.deleteRecursively()
            return targetRoot
        }
        targetRoot.deleteRecursively()
        check(tempRoot.renameTo(targetRoot)) { "Unable to move imported EPUB into Books/${targetRoot.name}" }
        return targetRoot
    }

    private companion object {
        const val METADATA_FILE_NAME = "metadata.json"
        const val BOOKMARK_FILE_NAME = "bookmark.json"
        const val BOOKINFO_FILE_NAME = "bookinfo.json"
        const val APPLE_REFERENCE_EPOCH_SECONDS = 978_307_200.0
    }

    private fun File.fallbackMetadata(): BookMetadata =
        BookMetadata(
            id = name,
            title = null,
            cover = null,
            folder = name,
            lastAccess = (lastModified().toDouble() / 1000.0) - APPLE_REFERENCE_EPOCH_SECONDS,
        )
}

private fun String.sanitizeImportedBookTitle(): String =
    split(Regex("[\\\\/:*?\"<>|\\n\\r\\u0000-\\u001F]"))
        .joinToString("_")
        .trim()
