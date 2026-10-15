package moe.antimony.hoshi.features.anki.enrich.jrp

/** The slice of a Sudachi morpheme the tokenizer reads. Lets the tokenizer logic run against fakes. */
internal interface JrpMorpheme {
    fun surface(): String
    /** Six-element Sudachi part of speech. */
    fun partOfSpeech(): List<String>
    fun dictionaryForm(): String
    fun readingForm(): String
    /** Re-split in Sudachi mode A. */
    fun splitA(): List<JrpMorpheme>
}

/** Sudachi mode C tokenization of [text]. */
internal fun interface JrpTokenizer {
    fun tokenizeC(text: String): List<JrpMorpheme>
}
