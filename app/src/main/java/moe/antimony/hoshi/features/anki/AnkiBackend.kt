package moe.antimony.hoshi.features.anki

import androidx.annotation.StringRes
import javax.inject.Inject
import moe.antimony.hoshi.R

interface AnkiBackend {
    fun isAvailable(): Boolean
    fun fetchDecks(): List<AnkiDeck>
    fun fetchNoteTypes(): List<AnkiNoteType>
    fun isDuplicate(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean
    fun addNote(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        fieldsByName: Map<String, String>,
        tags: Set<String>,
        allowDupes: Boolean,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean

    /**
     * Adds the note and returns its id. `0` means the note was added but the backend did not
     * report an id. `null` means it was not added. The default keeps fakes that only implement
     * [addNote] working.
     */
    fun addNoteReturning(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        fieldsByName: Map<String, String>,
        tags: Set<String>,
        allowDupes: Boolean,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Long? = if (
        addNote(
            deck = deck,
            noteType = noteType,
            fieldsByName = fieldsByName,
            tags = tags,
            allowDupes = allowDupes,
            duplicateScope = duplicateScope,
            checkDuplicatesAcrossAllModels = checkDuplicatesAcrossAllModels,
        )
    ) {
        0L
    } else {
        null
    }

    fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String?
    fun addMediaFromBytes(bytes: ByteArray, preferredName: String, mimeType: String): String? = null
    fun openNotes(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean = false

    /** Opens the card browser on one note. `false` when this backend cannot address a note by id. */
    fun openNote(noteId: Long): Boolean = false

    /** First note id matching the duplicate key, or null when this backend cannot resolve one. */
    fun findNoteId(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Long? = null

    fun sync(): Boolean = false
}

enum class AnkiFetchFailure(
    @param:StringRes val userMessageRes: Int,
    val userMessage: String,
) {
    ApiUnavailable(
        R.string.anki_fetch_api_unavailable,
        "AnkiDroid is unavailable. Install AnkiDroid, then try again.",
    ),
    PermissionDenied(
        R.string.anki_fetch_permission_denied,
        "AnkiDroid database access was denied. Grant the permission to fetch decks and note types.",
    ),
    DeckListUnavailable(
        R.string.anki_fetch_deck_list_unavailable,
        "Unable to read AnkiDroid decks. Open AnkiDroid and try again.",
    ),
    ModelListUnavailable(
        R.string.anki_fetch_model_list_unavailable,
        "Unable to read AnkiDroid note types. Open AnkiDroid and try again.",
    ),
    ModelFieldsUnavailable(
        R.string.anki_fetch_model_fields_unavailable,
        "Unable to read fields for one or more AnkiDroid note types.",
    ),
    ProviderFailure(
        R.string.anki_fetch_provider_failure,
        "AnkiDroid did not respond while fetching decks and note types. Open AnkiDroid and try again.",
    ),
}

class AnkiFetchException(
    val failure: AnkiFetchFailure,
    override val message: String = failure.userMessage,
    override val cause: Throwable? = null,
) : RuntimeException(message, cause)

interface AnkiContentApi {
    fun deckList(): Map<Long, String>
    fun modelList(): Map<Long, String>
    fun fieldList(modelId: Long): List<String>
    fun findDuplicateNotes(
        deck: AnkiDeck,
        modelId: Long,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkAllModels: Boolean,
    ): Boolean

    /** First matching note id, or null. Default keeps test fakes that only report a boolean. */
    fun findNoteId(
        deck: AnkiDeck,
        modelId: Long,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkAllModels: Boolean,
    ): Long? = null

    fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long?
    fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null
    fun openNotes(query: String): Boolean = false
    fun sync(): Boolean = false
    fun isAvailable(): Boolean = true
}

class AnkiDroidBackendAdapter @Inject constructor(
    private val api: AnkiContentApi,
) : AnkiBackend {
    override fun isAvailable(): Boolean = api.isAvailable()

    override fun fetchDecks(): List<AnkiDeck> =
        api.deckList()
            .map { (id, name) -> AnkiDeck(id, name) }
            .sortedAnkiDecks()

    override fun fetchNoteTypes(): List<AnkiNoteType> {
        val unreadableModels = mutableListOf<String>()
        val noteTypes = api.modelList().mapNotNull { (id, name) ->
            val fields = runCatching { api.fieldList(id) }
                .getOrElse { error ->
                    if (error is AnkiFetchException && error.failure == AnkiFetchFailure.ModelFieldsUnavailable) {
                        unreadableModels += name
                        return@mapNotNull null
                    }
                    throw error
                }
            if (fields.isEmpty()) {
                unreadableModels += name
                null
            } else {
                AnkiNoteType(id = id, name = name, fields = fields)
            }
        }
        if (unreadableModels.isNotEmpty()) {
            val message = if (unreadableModels.size == 1) {
                "Unable to read fields for AnkiDroid note type: ${unreadableModels.single()}."
            } else {
                "Unable to read fields for AnkiDroid note types: ${unreadableModels.joinToString()}."
            }
            throw AnkiFetchException(AnkiFetchFailure.ModelFieldsUnavailable, message)
        }
        return noteTypes
    }

    override fun isDuplicate(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean =
        key.isNotBlank() && api.findDuplicateNotes(
            deck = deck,
            modelId = noteType.id,
            key = key,
            duplicateScope = duplicateScope,
            checkAllModels = checkDuplicatesAcrossAllModels,
        )

    override fun addNote(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        fieldsByName: Map<String, String>,
        tags: Set<String>,
        allowDupes: Boolean,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean = addNoteReturning(
        deck = deck,
        noteType = noteType,
        fieldsByName = fieldsByName,
        tags = tags,
        allowDupes = allowDupes,
        duplicateScope = duplicateScope,
        checkDuplicatesAcrossAllModels = checkDuplicatesAcrossAllModels,
    ) != null

    override fun addNoteReturning(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        fieldsByName: Map<String, String>,
        tags: Set<String>,
        allowDupes: Boolean,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Long? {
        val duplicateKey = noteType.fields.firstOrNull()?.let(fieldsByName::get).orEmpty()
        if (
            !allowDupes &&
            isDuplicate(
                deck = deck,
                noteType = noteType,
                key = duplicateKey,
                duplicateScope = duplicateScope,
                checkDuplicatesAcrossAllModels = checkDuplicatesAcrossAllModels,
            )
        ) return null
        val fields = noteType.fields.map { fieldsByName[it].orEmpty() }.toTypedArray()
        return api.addNote(
            modelId = noteType.id,
            deckId = deck.id,
            fields = fields,
            tags = tags,
        )
    }

    override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? =
        api.addMediaFromUri(uriString, preferredName, mimeType)

    override fun openNotes(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Boolean = api.openNotes(
        ankiNoteBrowserQuery(
            deck = deck,
            noteType = noteType,
            key = key,
            duplicateScope = duplicateScope,
            checkAllModels = checkDuplicatesAcrossAllModels,
        ),
    )

    override fun openNote(noteId: Long): Boolean {
        if (noteId <= 0L) return false
        return api.openNotes("nid:$noteId")
    }

    override fun findNoteId(
        deck: AnkiDeck,
        noteType: AnkiNoteType,
        key: String,
        duplicateScope: AnkiDuplicateScope,
        checkDuplicatesAcrossAllModels: Boolean,
    ): Long? {
        if (key.isBlank()) return null
        return api.findNoteId(
            deck = deck,
            modelId = noteType.id,
            key = key,
            duplicateScope = duplicateScope,
            checkAllModels = checkDuplicatesAcrossAllModels,
        )
    }

    override fun sync(): Boolean = api.sync()
}

internal fun List<AnkiDeck>.sortedAnkiDecks(): List<AnkiDeck> =
    sortedWith(
        compareBy<AnkiDeck, String>(String.CASE_INSENSITIVE_ORDER) { it.name }
            .thenBy { it.name }
            .thenBy { it.id },
    )

internal fun ankiNoteBrowserQuery(
    deck: AnkiDeck,
    noteType: AnkiNoteType,
    key: String,
    duplicateScope: AnkiDuplicateScope,
    checkAllModels: Boolean,
): String {
    val firstField = noteType.fields.firstOrNull() ?: return ""
    val escapedKey = key.replace("\"", "")
    if (escapedKey.isBlank()) return ""
    return buildList {
        add("\"$firstField:$escapedKey\"")
        if (!checkAllModels) add("\"note:${noteType.name}\"")
        when (duplicateScope) {
            AnkiDuplicateScope.Collection -> Unit
            AnkiDuplicateScope.Deck -> add("\"deck:${deck.name}\"")
            AnkiDuplicateScope.DeckRoot -> add("\"deck:${deck.name.substringBefore("::")}\"")
        }
    }.joinToString(" ")
}
