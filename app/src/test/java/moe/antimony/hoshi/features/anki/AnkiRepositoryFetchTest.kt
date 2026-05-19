package moe.antimony.hoshi.features.anki

import android.content.ContextWrapper
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Test

class AnkiRepositoryFetchTest {
    @Test
    fun fetchReportsApiUnavailableWithActionableMessage() = runBlocking {
        val repository = repository(backend = FakeAnkiBackend(available = false))

        val result = repository.fetchConfiguration()

        assertEquals(
            AnkiFetchResult.Error(
                UiText.Resource(R.string.anki_fetch_api_unavailable),
                AnkiFetchFailure.ApiUnavailable,
            ),
            result,
        )
    }

    @Test
    fun fetchReportsPermissionDeniedWithActionableMessage() = runBlocking {
        val repository = repository(
            backend = FakeAnkiBackend(fetchDecksError = AnkiFetchException(AnkiFetchFailure.PermissionDenied)),
        )

        val result = repository.fetchConfiguration()

        assertEquals(
            AnkiFetchResult.Error(
                UiText.Resource(R.string.anki_fetch_permission_denied),
                AnkiFetchFailure.PermissionDenied,
            ),
            result,
        )
    }

    @Test
    fun fetchNamesEmptyDeckList() = runBlocking {
        val repository = repository(backend = FakeAnkiBackend(decks = emptyList()))

        val result = repository.fetchConfiguration()

        assertEquals(AnkiFetchResult.Error(UiText.Resource(R.string.anki_fetch_no_ankidroid_decks)), result)
    }

    @Test
    fun fetchNamesEmptyNoteTypeList() = runBlocking {
        val repository = repository(backend = FakeAnkiBackend(noteTypes = emptyList()))

        val result = repository.fetchConfiguration()

        assertEquals(AnkiFetchResult.Error(UiText.Resource(R.string.anki_fetch_no_ankidroid_note_types)), result)
    }

    @Test
    fun fetchKeepsExistingSelectionWhenFetchSucceeds() = runBlocking {
        val settingsRepository = InMemoryAnkiSettingsRepository(
            AnkiSettings(
                selectedDeckId = 2L,
                selectedDeckName = "Mining",
                selectedNoteTypeId = 7L,
                selectedNoteTypeName = "Lapis",
            ),
        )
        val repository = repository(settingsRepository = settingsRepository)

        repository.fetchConfiguration()

        assertEquals(2L, settingsRepository.current.selectedDeckId)
        assertEquals(7L, settingsRepository.current.selectedNoteTypeId)
    }

    private fun repository(
        backend: AnkiBackend = FakeAnkiBackend(),
        settingsRepository: InMemoryAnkiSettingsRepository = InMemoryAnkiSettingsRepository(),
    ): AnkiRepository =
        AnkiRepository(
            context = ContextWrapper(null),
            backend = backend,
            settingsRepository = settingsRepository,
            localAudioRepository = LocalAudioRepository(Files.createTempDirectory("hoshi-anki-test").toFile()),
        )

    private class InMemoryAnkiSettingsRepository(
        initial: AnkiSettings = AnkiSettings(),
    ) : AnkiSettingsRepository {
        private val state = MutableStateFlow(initial)
        val current: AnkiSettings
            get() = state.value

        override val settings: Flow<AnkiSettings> = state

        override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
            state.value = transform(state.value)
        }
    }

    private class FakeAnkiBackend(
        private val available: Boolean = true,
        private val decks: List<AnkiDeck> = listOf(AnkiDeck(1L, "Default"), AnkiDeck(2L, "Mining")),
        private val noteTypes: List<AnkiNoteType> = listOf(
            AnkiNoteType(5L, "Basic", listOf("Front", "Back")),
            AnkiNoteType(7L, "Lapis", listOf("Expression", "MainDefinition")),
        ),
        private val fetchDecksError: Throwable? = null,
        private val fetchNoteTypesError: Throwable? = null,
    ) : AnkiBackend {
        override fun isAvailable(): Boolean = available

        override fun fetchDecks(): List<AnkiDeck> {
            fetchDecksError?.let { throw it }
            return decks
        }

        override fun fetchNoteTypes(): List<AnkiNoteType> {
            fetchNoteTypesError?.let { throw it }
            return noteTypes
        }

        override fun isDuplicate(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ): Boolean = false

        override fun addNote(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            fieldsByName: Map<String, String>,
            tags: Set<String>,
            allowDupes: Boolean,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ): Boolean = true

        override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null
    }
}
