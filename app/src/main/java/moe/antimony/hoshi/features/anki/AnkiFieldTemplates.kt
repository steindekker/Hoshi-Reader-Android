package moe.antimony.hoshi.features.anki

object AnkiFieldTemplates {
    /** The note type this install mines into (shipped under `assets/anki/hoshi/`). */
    const val HoshiNoteType = "Hoshi"

    /** Deck name used until Anki has been fetched. Matches the mining deck. */
    const val HoshiDeckName = "Default"


    private val templates = mapOf(
        "Lapis" to mapOf(
            "Expression" to "{expression}",
            "ExpressionFurigana" to "{furigana-plain}",
            "ExpressionReading" to "{reading}",
            "ExpressionAudio" to "{audio}",
            "SelectionText" to "{popup-selection-text}",
            "MainDefinition" to "{glossary-first}",
            "Sentence" to "{sentence}",
            "SentenceAudio" to "{sentence-audio}",
            "Picture" to "{image}",
            "Glossary" to "{glossary}",
            "PitchPosition" to "{pitch-accent-positions}",
            "PitchCategories" to "{pitch-accent-categories}",
            "Frequency" to "{frequencies}",
            "FreqSort" to "{frequency-harmonic-rank}",
            "MiscInfo" to "{document-title}",
        ),
        "Kiku" to mapOf(
            "Expression" to "{expression}",
            "ExpressionFurigana" to "{furigana-plain}",
            "ExpressionReading" to "{reading}",
            "ExpressionAudio" to "{audio}",
            "SelectionText" to "{popup-selection-text}",
            "MainDefinition" to "{glossary-first}",
            "Sentence" to "{sentence}",
            "SentenceAudio" to "{sentence-audio}",
            "Picture" to "{image}",
            "Glossary" to "{glossary}",
            "PitchPosition" to "{pitch-accent-positions}",
            "PitchCategories" to "{pitch-accent-categories}",
            "Frequency" to "{frequencies}",
            "FreqSort" to "{frequency-harmonic-rank}",
            "MiscInfo" to "{document-title}",
        ),
        "Senren" to mapOf(
            "word" to "{expression}",
            "reading" to "{reading}",
            "sentence" to "<span class=\"group\">{cloze-prefix}<span class=\"highlight\">{cloze-body}</span>{cloze-suffix}</span>",
            "selectionText" to "{popup-selection-text}",
            "definition" to "{glossary-first}",
            "wordAudio" to "{audio}",
            "sentenceAudio" to "{sentence-audio}",
            "picture" to "{image}",
            "glossary" to "{glossary}",
            "pitchPositions" to "{pitch-accent-positions}",
            "pitchCategories" to "{pitch-accent-categories}",
            "frequencies" to "{frequencies}",
            "freqSort" to "{frequency-harmonic-rank}",
            "miscInfo" to "{document-title}",
        ),
        // Filled after the first-run download. `{ae-*}` resolves from the installed
        // dictionaries when no enrichment pack is present, and `{ae-sentence}` from
        // the on-device sentence model.
        HoshiNoteType to HoshiMappings,
    )

    /** Card format written by first-run setup, before Anki has been fetched. */
    fun seamlessCardFormat(id: String): AnkiCardFormat = AnkiCardFormat(
        id = id,
        name = "Mining",
        selectedDeckName = HoshiDeckName,
        selectedNoteTypeName = HoshiNoteType,
        fieldMappings = HoshiMappings,
        tags = "$DefaultAnkiTag {ae-tags}",
    )

    fun matches(noteType: AnkiNoteType): Boolean =
        templates.containsKey(noteType.name)

    fun defaultMappings(noteType: AnkiNoteType): Map<String, String> {
        val defaults = templates[noteType.name].orEmpty()
        return noteType.fields.mapNotNull { field ->
            defaults[field]?.let { field to it }
        }.toMap()
    }

    fun applyDefaultsIfUnmapped(
        noteType: AnkiNoteType,
        currentMappings: Map<String, String>,
    ): Map<String, String> {
        if (!matches(noteType)) return currentMappings
        if (noteType.fields.any { field -> currentMappings[field] != null }) return currentMappings
        return defaultMappings(noteType)
    }
}

private val HoshiMappings = mapOf(
    "Term" to "{expression}",
    "Reading" to "{reading}",
    "Furigana" to "{furigana-plain}",
    "Sentence" to "{ae-sentence}",
    "SentenceFurigana" to "{sentence}",
    "Cloze Before" to "{cloze-prefix}",
    "Cloze Inside" to "{cloze-body}",
    "Cloze After" to "{cloze-suffix}",
    "Meaning" to "{ae-definition}",
    "Context" to "{document-title}",
    "Frequency" to "{ae-frequency}",
    "Pitch Accent" to "{ae-pitch}",
    "Image" to "{image}",
    "Term Audio" to "{audio}",
    "Sentence Audio" to "{sentence-audio}",
    "Definition Source" to "{ae-definition-source}",
    "Examples" to "{ae-examples}",
)
