package moe.antimony.hoshi.features.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.anki.AnkiBackend
import moe.antimony.hoshi.features.anki.AnkiDeck
import moe.antimony.hoshi.features.anki.AnkiDuplicateScope
import moe.antimony.hoshi.features.anki.AnkiFieldTemplates
import moe.antimony.hoshi.features.anki.AnkiNoteType
import moe.antimony.hoshi.features.anki.AnkiNoteTypeSpec
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import moe.antimony.hoshi.features.anki.KinomotoNoteType
import moe.antimony.hoshi.features.anki.defaultAnkiCardFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupAnkiConnectorTest {
    private val decks = listOf(AnkiDeck(1L, "Default"), AnkiDeck(2L, "Mining"))
    private val kinomoto = AnkiNoteType(9L, AnkiFieldTemplates.KinomotoNoteType, KinomotoNoteType.Fields)

    @Test
    fun missingNoteTypeIsCreatedAndTheSeamlessFormatGetsItsIds() = runBlocking {
        val backend = FakeBackend(decks, noteTypes = emptyList(), created = kinomoto)
        val settings = FakeSettings(AnkiSettings())

        val result = connector(backend, settings).connect()

        assertEquals(SetupAnkiResult.Connected, result)
        assertEquals(AnkiFieldTemplates.KinomotoNoteType, backend.createdSpec?.name)
        assertEquals("Default", backend.createdDeck?.name)
        val format = settings.state.value.cardFormats.single()
        assertEquals(9L, format.selectedNoteTypeId)
        assertEquals(1L, format.selectedDeckId)
        assertEquals("{ae-sentence}", format.fieldMappings["Sentence"])
        assertEquals(listOf(kinomoto), settings.state.value.availableNoteTypes)
    }

    @Test
    fun existingNoteTypeIsReusedAndCustomMappingsStay() = runBlocking {
        val backend = FakeBackend(decks, noteTypes = listOf(kinomoto))
        val mapped = AnkiFieldTemplates.seamlessCardFormat("seamless").copy(
            selectedDeckName = "Mining",
            fieldMappings = mapOf("Term" to "{expression}", "Meaning" to "{glossary-first}"),
        )
        val lapis = defaultAnkiCardFormat("lapis").copy(
            selectedNoteTypeName = "Lapis",
            fieldMappings = mapOf("Expression" to "{expression}"),
        )
        val settings = FakeSettings(AnkiSettings(cardFormats = listOf(mapped, lapis)))

        connector(backend, settings).connect()

        assertNull(backend.createdSpec)
        val (seamless, other) = settings.state.value.cardFormats
        assertEquals(9L, seamless.selectedNoteTypeId)
        assertEquals(2L, seamless.selectedDeckId)
        assertEquals(mapped.fieldMappings, seamless.fieldMappings)
        assertEquals(lapis, other)
    }

    @Test
    fun missingAnkiDroidReportsUnavailable() = runBlocking {
        val backend = FakeBackend(decks, noteTypes = emptyList(), available = false)
        val settings = FakeSettings(AnkiSettings())

        assertEquals(SetupAnkiResult.Unavailable, connector(backend, settings).connect())
        assertTrue(settings.state.value.cardFormats.isEmpty())
    }

    private fun connector(backend: AnkiBackend, settings: AnkiSettingsRepository) = SetupAnkiConnector(
        backend = backend,
        settings = settings,
        spec = { KinomotoNoteType.spec { "" } },
        ioDispatcher = Dispatchers.Unconfined,
    )

    private class FakeSettings(initial: AnkiSettings) : AnkiSettingsRepository {
        val state = MutableStateFlow(initial)
        override val settings: Flow<AnkiSettings> = state
        override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
            state.value = transform(state.value)
        }
    }

    private class FakeBackend(
        private val decks: List<AnkiDeck>,
        private val noteTypes: List<AnkiNoteType>,
        private val created: AnkiNoteType? = null,
        private val available: Boolean = true,
    ) : AnkiBackend {
        var createdSpec: AnkiNoteTypeSpec? = null
        var createdDeck: AnkiDeck? = null

        override fun isAvailable() = available
        override fun fetchDecks() = decks
        override fun fetchNoteTypes() = noteTypes
        override fun isDuplicate(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ) = false
        override fun addNote(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            fieldsByName: Map<String, String>,
            tags: Set<String>,
            allowDupes: Boolean,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ) = false
        override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null
        override fun createNoteType(spec: AnkiNoteTypeSpec, deck: AnkiDeck?): AnkiNoteType? {
            createdSpec = spec
            createdDeck = deck
            return created
        }
    }
}
