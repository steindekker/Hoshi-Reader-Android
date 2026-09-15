package moe.antimony.hoshi.features.bookshelf

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.core.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.epub.BookSortOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BookshelfSettingsRepositoryTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun emitsIosDefaultsWhenNoSettingsWereSaved() = runBlocking {
        repository().use { repository ->
            val settings = repository.settings.first()

            assertEquals(BookSortOption.Recent, settings.sortOption)
            assertFalse(settings.showReading)
            assertEquals(BookshelfCoverMode.Show, settings.coverMode)
        }
    }

    @Test
    fun persistsSortOptionAndReadingShelfVisibility() = runBlocking {
        repository().use { repository ->
            repository.update { it.copy(sortOption = BookSortOption.Title, showReading = true) }

            val settings = repository.settings.first()

            assertEquals(BookSortOption.Title, settings.sortOption)
            assertTrue(settings.showReading)
        }
    }

    @Test
    fun persistsEveryBookshelfCoverMode() = runBlocking {
        repository().use { repository ->
            BookshelfCoverMode.entries.forEach { mode ->
                repository.update { it.copy(coverMode = mode) }

                assertEquals(mode, repository.settings.first().coverMode)
            }
        }
    }

    @Test
    fun invalidStoredCoverModeFallsBackToShow() = runBlocking {
        repository().use { repository ->
            repository.writeRawCoverMode("Unknown")

            assertEquals(BookshelfCoverMode.Show, repository.settings.first().coverMode)
        }
    }

    private fun repository(): RepositoryHandle {
        val scope = CoroutineScope(Dispatchers.IO + Job())
        val dataStore = PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { tempFolder.newFile("bookshelf-settings.preferences_pb") },
        )
        return RepositoryHandle(BookshelfSettingsRepository(dataStore), dataStore, scope)
    }

    private class RepositoryHandle(
        private val repository: BookshelfSettingsRepository,
        private val dataStore: DataStore<Preferences>,
        private val scope: CoroutineScope,
    ) : AutoCloseable {
        val settings = repository.settings

        suspend fun update(transform: (BookshelfSettings) -> BookshelfSettings) {
            repository.update(transform)
        }

        suspend fun writeRawCoverMode(value: String) {
            dataStore.edit { preferences ->
                preferences[stringPreferencesKey("bookshelfCoverMode")] = value
            }
        }

        override fun close() {
            scope.cancel()
        }
    }
}
