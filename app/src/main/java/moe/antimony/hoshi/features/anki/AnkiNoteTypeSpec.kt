package moe.antimony.hoshi.features.anki

/** A note type the app can create in the collection: fields in order, one card template and its CSS. */
data class AnkiNoteTypeSpec(
    val name: String,
    val fields: List<String>,
    val cardName: String,
    val front: String,
    val back: String,
    val css: String,
)

/**
 * The jidoujisho Kinomoto note type first-run setup maps `{ae-*}` into. The card design ships
 * under `assets/anki/kinomoto/`, taken from the collection this install mines into.
 */
internal object KinomotoNoteType {
    const val AssetDirectory = "anki/kinomoto"

    val Fields = listOf(
        "Term",
        "Reading",
        "Furigana",
        "Sentence",
        "SentenceFurigana",
        "Cloze Before",
        "Cloze Inside",
        "Cloze After",
        "Meaning",
        "Expanded Meaning",
        "Collapsed Meaning",
        "Notes",
        "Context",
        "Frequency",
        "Pitch Accent",
        "Image",
        "Term Audio",
        "Sentence Audio",
        "Original Meaning",
        "Definition Source",
        "Examples",
    )

    fun spec(readAsset: (String) -> String) = AnkiNoteTypeSpec(
        name = AnkiFieldTemplates.KinomotoNoteType,
        fields = Fields,
        cardName = AnkiFieldTemplates.KinomotoNoteType,
        front = readAsset("$AssetDirectory/front.html"),
        back = readAsset("$AssetDirectory/back.html"),
        css = readAsset("$AssetDirectory/style.css"),
    )
}
