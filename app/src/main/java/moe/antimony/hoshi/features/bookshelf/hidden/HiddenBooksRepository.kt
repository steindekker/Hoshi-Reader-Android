package moe.antimony.hoshi.features.bookshelf.hidden

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import moe.antimony.hoshi.epub.BookEntry
import moe.antimony.hoshi.features.bookshelf.BookshelfSectionModel

private val Context.hiddenBooksDataStore by preferencesDataStore(name = "hidden-books")

/**
 * Persists the set of book IDs hidden from the bookshelf. Stored in its own DataStore rather than
 * [moe.antimony.hoshi.epub.BookMetadata] so hiding stays local to this device and never interferes
 * with upstream sync or backup schemas.
 */
@Singleton
class HiddenBooksRepository internal constructor(
    private val dataStore: DataStore<Preferences>,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context.hiddenBooksDataStore)

    val hiddenBookIds: Flow<Set<String>> = dataStore.data.map { preferences ->
        preferences[KEY_HIDDEN_BOOK_IDS].orEmpty()
    }

    suspend fun setHidden(bookIds: Set<String>, hidden: Boolean) {
        if (bookIds.isEmpty()) return
        dataStore.edit { preferences ->
            val current = preferences[KEY_HIDDEN_BOOK_IDS].orEmpty()
            val next = if (hidden) current + bookIds else current - bookIds
            if (next.isEmpty()) {
                preferences.remove(KEY_HIDDEN_BOOK_IDS)
            } else {
                preferences[KEY_HIDDEN_BOOK_IDS] = next
            }
        }
    }

    suspend fun hide(bookIds: Set<String>) = setHidden(bookIds, hidden = true)

    suspend fun unhide(bookIds: Set<String>) = setHidden(bookIds, hidden = false)

    private companion object {
        val KEY_HIDDEN_BOOK_IDS = stringSetPreferencesKey("hiddenBookIds")
    }
}

internal fun filterVisibleBooks(
    entries: List<BookEntry>,
    hiddenBookIds: Set<String>,
    showHidden: Boolean,
): List<BookEntry> =
    if (showHidden || hiddenBookIds.isEmpty()) {
        entries
    } else {
        entries.filterNot { it.metadata.id in hiddenBookIds }
    }

internal fun filterVisibleSections(
    sections: List<BookshelfSectionModel>,
    hiddenBookIds: Set<String>,
    showHidden: Boolean,
): List<BookshelfSectionModel> =
    if (showHidden || hiddenBookIds.isEmpty()) {
        sections
    } else {
        sections.mapNotNull { section ->
            val visible = section.books.filterNot { it.metadata.id in hiddenBookIds }
            if (visible.isEmpty()) null else section.copy(books = visible)
        }
    }

/** True when every currently selected book is hidden, so the bulk button should offer Unhide. */
internal fun shouldUnhideSelectedBooks(
    selectedBookIds: Set<String>,
    hiddenBookIds: Set<String>,
): Boolean = selectedBookIds.isNotEmpty() && selectedBookIds.all { it in hiddenBookIds }

/** Most recent non-hidden book for startup launch routing so hidden books never auto-open. */
internal fun mostRecentVisibleBookId(
    entries: List<BookEntry>,
    hiddenBookIds: Set<String>,
): String? = entries.firstOrNull { it.metadata.id !in hiddenBookIds }?.metadata?.id
