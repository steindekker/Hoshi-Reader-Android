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

    override fun fetchNoteTypes(): List<AnkiNoteType> =
        api.modelList().map { (id, name) ->
            AnkiNoteType(id = id, name = name, fields = api.fieldList(id))
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
