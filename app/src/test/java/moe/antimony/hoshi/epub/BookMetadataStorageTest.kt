package moe.antimony.hoshi.epub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Files

class BookMetadataStorageTest {
    @Test
    fun saveMetadataWritesIosCompatibleMetadataJson() {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata").toFile())
        val bookRoot = storage.createBookDirectory("book-a")
        val metadata = BookMetadata(
            id = "book-a",
            title = "屍人荘の殺人",
            cover = "item/image/cover.jpg",
            folder = "book-a",
            lastAccess = 798720000.0,
        )

        storage.saveMetadata(bookRoot, metadata)

        val saved = Json.parseToJsonElement(bookRoot.resolve("metadata.json").readText()).jsonObject
        assertEquals("book-a", saved.getValue("id").jsonPrimitive.content)
        assertEquals("屍人荘の殺人", saved.getValue("title").jsonPrimitive.content)
        assertEquals("item/image/cover.jpg", saved.getValue("cover").jsonPrimitive.content)
        assertEquals("book-a", saved.getValue("folder").jsonPrimitive.content)
        assertEquals(798720000.0, saved.getValue("lastAccess").jsonPrimitive.double, 0.0)
    }

    @Test
    fun loadBookEntriesReturnsMetadataBackedBooksSortedByLastAccessDescending() {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-list").toFile())
        val olderRoot = storage.createBookDirectory("older")
        val newerRoot = storage.createBookDirectory("newer")
        storage.saveMetadata(
            olderRoot,
            BookMetadata(id = "older", title = "Older", cover = null, folder = "older", lastAccess = 10.0),
        )
        storage.saveMetadata(
            newerRoot,
            BookMetadata(id = "newer", title = "Newer", cover = null, folder = "newer", lastAccess = 20.0),
        )

        val entries = storage.loadBookEntries()

        assertEquals(listOf("newer", "older"), entries.map { it.metadata.id })
        assertEquals(listOf(newerRoot, olderRoot).map { it.canonicalFile }, entries.map { it.root.canonicalFile })
    }

    @Test
    fun loadBookEntriesCanSortByTitleLikeIos() {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-title").toFile())
        val zRoot = storage.createBookDirectory("z")
        val aRoot = storage.createBookDirectory("a")
        storage.saveMetadata(
            zRoot,
            BookMetadata(id = "z", title = "Zeta", cover = null, folder = "z", lastAccess = 20.0),
        )
        storage.saveMetadata(
            aRoot,
            BookMetadata(id = "a", title = "Alpha", cover = null, folder = "a", lastAccess = 10.0),
        )

        val entries = storage.loadBookEntries(BookSortOption.Title)

        assertEquals(listOf("a", "z"), entries.map { it.metadata.id })
    }

    @Test
    fun deleteBookRemovesBookDirectory() {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-delete").toFile())
        val root = storage.createBookDirectory("delete-me")
        root.resolve("metadata.json").writeText("{}")

        storage.deleteBook(root)

        assertFalse(root.exists())
        assertEquals(emptyList<BookEntry>(), storage.loadBookEntries())
    }

    @Test
    fun importedBookDirectoryNameMatchesIosSanitizedTitleAndDeduplicates() {
        val storage = BookStorage(Files.createTempDirectory("hoshi-metadata-dedupe").toFile())

        val first = storage.createBookDirectoryForImportedTitle("屍人荘/の:殺人")
        first.resolve("metadata.json").writeText("{}")
        val second = storage.createBookDirectoryForImportedTitle("屍人荘/の:殺人")

        assertEquals("屍人荘_の_殺人", first.name)
        assertEquals(first.canonicalFile, second.canonicalFile)
    }
}
