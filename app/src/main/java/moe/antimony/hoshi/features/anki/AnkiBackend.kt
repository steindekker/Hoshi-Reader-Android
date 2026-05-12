package moe.antimony.hoshi.features.anki

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
    fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String?
}

enum class AnkiFetchFailure(
    val userMessage: String,
) {
    ApiUnavailable(
        "AnkiDroid is unavailable. Install AnkiDroid, then try again.",
    ),
    PermissionDenied(
        "AnkiDroid database access was denied. Grant the permission to fetch decks and note types.",
    ),
    DeckListUnavailable(
        "Unable to read AnkiDroid decks. Open AnkiDroid and try again.",
    ),
    ModelListUnavailable(
        "Unable to read AnkiDroid note types. Open AnkiDroid and try again.",
    ),
    ModelFieldsUnavailable(
        "Unable to read fields for one or more AnkiDroid note types.",
    ),
    ProviderFailure(
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
    fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long?
    fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null
    fun isAvailable(): Boolean = true
}

class AnkiDroidBackendAdapter(
    private val api: AnkiContentApi,
) : AnkiBackend {
    override fun isAvailable(): Boolean = api.isAvailable()

    override fun fetchDecks(): List<AnkiDeck> =
        api.deckList().map { (id, name) -> AnkiDeck(id, name) }

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
    ): Boolean {
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
        ) return false
        val fields = noteType.fields.map { fieldsByName[it].orEmpty() }.toTypedArray()
        return api.addNote(
            modelId = noteType.id,
            deckId = deck.id,
            fields = fields,
            tags = tags,
        ) != null
    }

    override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? =
        api.addMediaFromUri(uriString, preferredName, mimeType)
}
