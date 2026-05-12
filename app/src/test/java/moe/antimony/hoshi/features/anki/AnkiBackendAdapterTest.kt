package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiBackendAdapterTest {
    @Test
    fun adapterBuildsFieldsInNoteTypeOrderAndBlocksDuplicatesByDefault() {
        val api = FakeAnkiContentApi(
            duplicateKeys = setOf("食べる"),
        )
        val backend = AnkiDroidBackendAdapter(api)
        val noteType = AnkiNoteType(
            id = 7L,
            name = "Lapis",
            fields = listOf("Expression", "Sentence", "Frequency"),
        )

        val added = backend.addNote(
            deck = AnkiDeck(id = 3L, name = "Mining"),
            noteType = noteType,
            fieldsByName = mapOf(
                "Frequency" to "1139",
                "Expression" to "食べる",
                "Sentence" to "パンを<b>食べる</b>。",
            ),
            tags = setOf("hoshi", "reader"),
            allowDupes = false,
            duplicateScope = AnkiDuplicateScope.Collection,
            checkDuplicatesAcrossAllModels = false,
        )

        assertFalse(added)
        assertNull(api.addedFields)
        assertEquals(7L, api.lastDuplicateModelId)
        assertEquals(3L, api.lastDuplicateDeckId)
        assertEquals(AnkiDuplicateScope.Collection, api.lastDuplicateScope)
        assertFalse(api.lastDuplicateCheckAllModels)
    }

    @Test
    fun adapterAllowsDuplicatesWhenConfigured() {
        val api = FakeAnkiContentApi(
            duplicateKeys = setOf("食べる"),
        )
        val backend = AnkiDroidBackendAdapter(api)
        val noteType = AnkiNoteType(
            id = 7L,
            name = "Lapis",
            fields = listOf("Expression", "Sentence", "Frequency"),
        )

        val added = backend.addNote(
            deck = AnkiDeck(id = 3L, name = "Mining"),
            noteType = noteType,
            fieldsByName = mapOf(
                "Frequency" to "1139",
                "Expression" to "食べる",
                "Sentence" to "パンを<b>食べる</b>。",
            ),
            tags = setOf("hoshi", "reader"),
            allowDupes = true,
            duplicateScope = AnkiDuplicateScope.Deck,
            checkDuplicatesAcrossAllModels = true,
        )

        assertTrue(added)
        assertArrayEquals(arrayOf("食べる", "パンを<b>食べる</b>。", "1139"), api.addedFields)
        assertEquals(setOf("hoshi", "reader"), api.addedTags)
        assertNull(api.lastDuplicateModelId)
    }

    @Test
    fun adapterExposesDecksModelsAndDuplicateChecks() {
        val api = FakeAnkiContentApi(
            decks = mapOf(1L to "Default", 2L to "Mining"),
            models = mapOf(5L to "Basic", 7L to "Lapis"),
            fields = mapOf(5L to listOf("Front", "Back"), 7L to listOf("Expression", "Sentence")),
            duplicateKeys = setOf("読む"),
        )
        val backend = AnkiDroidBackendAdapter(api)

        assertEquals(listOf(AnkiDeck(1L, "Default"), AnkiDeck(2L, "Mining")), backend.fetchDecks())
        assertEquals(
            listOf(
                AnkiNoteType(5L, "Basic", listOf("Front", "Back")),
                AnkiNoteType(7L, "Lapis", listOf("Expression", "Sentence")),
            ),
            backend.fetchNoteTypes(),
        )
        assertTrue(
            backend.isDuplicate(
                deck = AnkiDeck(2L, "Mining"),
                noteType = AnkiNoteType(7L, "Lapis", listOf("Expression", "Sentence")),
                key = "読む",
                duplicateScope = AnkiDuplicateScope.Collection,
                checkDuplicatesAcrossAllModels = false,
            ),
        )
        assertFalse(
            backend.isDuplicate(
                deck = AnkiDeck(2L, "Mining"),
                noteType = AnkiNoteType(7L, "Lapis", listOf("Expression", "Sentence")),
                key = "書く",
                duplicateScope = AnkiDuplicateScope.Collection,
                checkDuplicatesAcrossAllModels = false,
            ),
        )
    }

    @Test
    fun adapterReportsUnavailableAnkiDroidApi() {
        val backend = AnkiDroidBackendAdapter(
            FakeAnkiContentApi(
                deckFailure = AnkiFetchFailure.ApiUnavailable,
            ),
        )

        val error = assertAnkiFetchFailure(AnkiFetchFailure.ApiUnavailable) {
            backend.fetchDecks()
        }

        assertEquals(
            "AnkiDroid is unavailable. Install AnkiDroid, then try again.",
            error.message,
        )
    }

    @Test
    fun adapterReportsPermissionDeniedWhenProviderRejectsFetch() {
        val backend = AnkiDroidBackendAdapter(
            FakeAnkiContentApi(
                modelFailure = AnkiFetchFailure.PermissionDenied,
            ),
        )

        val error = assertAnkiFetchFailure(AnkiFetchFailure.PermissionDenied) {
            backend.fetchNoteTypes()
        }

        assertEquals(
            "AnkiDroid database access was denied. Grant the permission to fetch decks and note types.",
            error.message,
        )
    }

    @Test
    fun adapterReportsNullDeckAndModelListsSeparately() {
        assertAnkiFetchFailure(AnkiFetchFailure.DeckListUnavailable) {
            AnkiDroidBackendAdapter(FakeAnkiContentApi(decks = null)).fetchDecks()
        }
        assertAnkiFetchFailure(AnkiFetchFailure.ModelListUnavailable) {
            AnkiDroidBackendAdapter(FakeAnkiContentApi(models = null)).fetchNoteTypes()
        }
    }

    @Test
    fun adapterReportsUnreadableModelFieldsAndSkipsUnusableModels() {
        val backend = AnkiDroidBackendAdapter(
            FakeAnkiContentApi(
                models = mapOf(5L to "Basic", 7L to "Lapis"),
                fields = mapOf(7L to listOf("Expression")),
                nullFieldModelIds = setOf(5L),
            ),
        )

        val error = assertAnkiFetchFailure(AnkiFetchFailure.ModelFieldsUnavailable) {
            backend.fetchNoteTypes()
        }

        assertEquals("Unable to read fields for AnkiDroid note type: Basic.", error.message)
    }

    @Test
    fun adapterReportsAllModelFieldsUnavailableWhenNoModelIsUsable() {
        val backend = AnkiDroidBackendAdapter(
            FakeAnkiContentApi(
                models = mapOf(5L to "Basic"),
                fields = emptyMap(),
                nullFieldModelIds = setOf(5L),
            ),
        )

        val error = assertAnkiFetchFailure(AnkiFetchFailure.ModelFieldsUnavailable) {
            backend.fetchNoteTypes()
        }

        assertEquals("Unable to read fields for AnkiDroid note type: Basic.", error.message)
    }

    @Test
    fun adapterPassesDeckScopeAndAllModelsToDuplicateCheck() {
        val api = FakeAnkiContentApi(duplicateKeys = setOf("読む"))
        val backend = AnkiDroidBackendAdapter(api)

        assertTrue(
            backend.isDuplicate(
                deck = AnkiDeck(2L, "Mining"),
                noteType = AnkiNoteType(7L, "Lapis", listOf("Expression")),
                key = "読む",
                duplicateScope = AnkiDuplicateScope.Deck,
                checkDuplicatesAcrossAllModels = true,
            ),
        )

        assertEquals(7L, api.lastDuplicateModelId)
        assertEquals(2L, api.lastDuplicateDeckId)
        assertEquals(AnkiDuplicateScope.Deck, api.lastDuplicateScope)
        assertTrue(api.lastDuplicateCheckAllModels)
    }

    @Test
    fun adapterPassesDeckRootScopeToDuplicateCheck() {
        val api = FakeAnkiContentApi(duplicateKeys = setOf("読む"))
        val backend = AnkiDroidBackendAdapter(api)

        assertTrue(
            backend.isDuplicate(
                deck = AnkiDeck(2L, "Mining"),
                noteType = AnkiNoteType(7L, "Lapis", listOf("Expression")),
                key = "読む",
                duplicateScope = AnkiDuplicateScope.DeckRoot,
                checkDuplicatesAcrossAllModels = false,
            ),
        )

        assertEquals(AnkiDuplicateScope.DeckRoot, api.lastDuplicateScope)
    }

    private class FakeAnkiContentApi(
        private val decks: Map<Long, String>? = mapOf(1L to "Default"),
        private val models: Map<Long, String>? = mapOf(7L to "Lapis"),
        private val fields: Map<Long, List<String>> = mapOf(7L to listOf("Expression")),
        private val duplicateKeys: Set<String> = emptySet(),
        private val deckFailure: AnkiFetchFailure? = null,
        private val modelFailure: AnkiFetchFailure? = null,
        private val nullFieldModelIds: Set<Long> = emptySet(),
    ) : AnkiContentApi {
        var addedFields: Array<String>? = null
            private set
        var addedTags: Set<String>? = null
            private set
        var lastDuplicateModelId: Long? = null
            private set
        var lastDuplicateDeckId: Long? = null
            private set
        var lastDuplicateScope: AnkiDuplicateScope? = null
            private set
        var lastDuplicateCheckAllModels: Boolean = false
            private set

        override fun deckList(): Map<Long, String> =
            deckFailure?.let { throw AnkiFetchException(it) } ?: decks
                ?: throw AnkiFetchException(AnkiFetchFailure.DeckListUnavailable)

        override fun modelList(): Map<Long, String> =
            modelFailure?.let { throw AnkiFetchException(it) } ?: models
                ?: throw AnkiFetchException(AnkiFetchFailure.ModelListUnavailable)

        override fun fieldList(modelId: Long): List<String> =
            if (modelId in nullFieldModelIds) {
                throw AnkiFetchException(AnkiFetchFailure.ModelFieldsUnavailable)
            } else {
                fields[modelId].orEmpty()
            }

        override fun findDuplicateNotes(
            deck: AnkiDeck,
            modelId: Long,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkAllModels: Boolean,
        ): Boolean {
            lastDuplicateDeckId = deck.id
            lastDuplicateModelId = modelId
            lastDuplicateScope = duplicateScope
            lastDuplicateCheckAllModels = checkAllModels
            return key in duplicateKeys
        }

        override fun addNote(
            modelId: Long,
            deckId: Long,
            fields: Array<String>,
            tags: Set<String>,
        ): Long? {
            addedFields = fields
            addedTags = tags
            return 123L
        }
    }

    private fun assertAnkiFetchFailure(
        expected: AnkiFetchFailure,
        block: () -> Unit,
    ): AnkiFetchException {
        return try {
            block()
            throw AssertionError("Expected AnkiFetchException.")
        } catch (error: AnkiFetchException) {
            assertEquals(expected, error.failure)
            error
        }
    }
}
