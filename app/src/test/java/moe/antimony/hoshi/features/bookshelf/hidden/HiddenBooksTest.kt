package moe.antimony.hoshi.features.bookshelf.hidden

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.features.bookshelf.BookshelfSectionModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenBooksTest {
    private fun book(id: String, lastAccess: Double = 0.0): BookEntry = BookEntry(
        root = File("/tmp/$id"),
        metadata = BookMetadata(
            id = id,
            title = "Title $id",
            lastAccess = lastAccess,
        ),
    )

    private fun testRepository(scope: kotlinx.coroutines.CoroutineScope): HiddenBooksRepository {
        val dir = Files.createTempDirectory("hoshi-hidden-books-test").toFile()
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { dir.resolve("hidden-books.preferences_pb") },
        )
        return HiddenBooksRepository(dataStore)
    }

    @Test
    fun repositoryPersistsHideAndUnhide() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val repo = testRepository(scope)
            assertEquals(emptySet<String>(), repo.hiddenBookIds.first())

            repo.hide(setOf("book-1", "book-2"))
            assertEquals(setOf("book-1", "book-2"), repo.hiddenBookIds.first())

            repo.unhide(setOf("book-1"))
            assertEquals(setOf("book-2"), repo.hiddenBookIds.first())

            repo.unhide(setOf("book-2"))
            assertEquals(emptySet<String>(), repo.hiddenBookIds.first())
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun viewModelStartsHiddenAndTogglesVisibility() = runBlocking {
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
        try {
            val repo = testRepository(scope)
            val viewModel = HiddenBooksViewModel(repo, scope)

            val loaded = withTimeout(5_000) { viewModel.uiState.first { it.isLoaded } }
            assertFalse(loaded.showHiddenBooks)
            assertEquals(emptySet<String>(), loaded.hiddenBookIds)

            viewModel.setHidden("nsfw-1", true)
            val afterHide = withTimeout(5_000) { viewModel.uiState.first { "nsfw-1" in it.hiddenBookIds } }
            assertEquals(setOf("nsfw-1"), afterHide.hiddenBookIds)

            viewModel.toggleShowHidden()
            assertTrue(withTimeout(5_000) { viewModel.uiState.first { it.showHiddenBooks } }.showHiddenBooks)

            viewModel.toggleShowHidden()
            assertFalse(withTimeout(5_000) { viewModel.uiState.first { !it.showHiddenBooks } }.showHiddenBooks)

            viewModel.setHidden(setOf("nsfw-1"), false)
            val afterUnhide = withTimeout(5_000) { viewModel.uiState.first { it.hiddenBookIds.isEmpty() } }
            assertEquals(emptySet<String>(), afterUnhide.hiddenBookIds)
        } finally {
            scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        }
    }

    @Test
    fun filterVisibleBooksAndSectionsDropHiddenEntriesAndEmptyShelves() {
        val visible = book("normal")
        val hidden = book("secret")
        val sections = listOf(
            BookshelfSectionModel(title = "Reading", books = listOf(hidden, visible), isReading = true),
            BookshelfSectionModel(title = "Secret Shelf", books = listOf(hidden), shelfName = "Secret Shelf"),
            BookshelfSectionModel(title = "Unshelved", books = listOf(visible)),
        )

        assertEquals(listOf(visible), filterVisibleBooks(listOf(visible, hidden), setOf("secret"), showHidden = false))
        assertEquals(listOf(visible, hidden), filterVisibleBooks(listOf(visible, hidden), setOf("secret"), showHidden = true))

        val filteredSections = filterVisibleSections(sections, setOf("secret"), showHidden = false)
        assertEquals(listOf("Reading", "Unshelved"), filteredSections.map { it.title })
        assertEquals(listOf(visible), filteredSections.first().books)

        val revealedSections = filterVisibleSections(sections, setOf("secret"), showHidden = true)
        assertEquals(sections, revealedSections)
    }

    @Test
    fun bulkSelectionOffersUnhideOnlyWhenAllSelectedAreHidden() {
        val hidden = setOf("a", "b")
        assertFalse(shouldUnhideSelectedBooks(emptySet(), hidden))
        assertTrue(shouldUnhideSelectedBooks(setOf("a"), hidden))
        assertTrue(shouldUnhideSelectedBooks(setOf("a", "b"), hidden))
        assertFalse(shouldUnhideSelectedBooks(setOf("a", "c"), hidden))
    }

    @Test
    fun mostRecentVisibleBookIdSkipsHiddenBooks() {
        val recentHidden = book("secret", lastAccess = 200.0)
        val olderVisible = book("normal", lastAccess = 100.0)

        assertEquals("normal", mostRecentVisibleBookId(listOf(recentHidden, olderVisible), setOf("secret")))
        assertNull(mostRecentVisibleBookId(listOf(recentHidden), setOf("secret")))
        assertEquals("secret", mostRecentVisibleBookId(listOf(recentHidden, olderVisible), emptySet()))
    }
}
