package moe.antimony.hoshi.features.anki.enrich.jrp

/** One token's worth of data passed from the tokenizer to the emitter (port of `jrp_fill/unit.py`). */
internal data class JrpUnit(
    /** Text as it appears, possibly inflected: 見逃し. */
    val surface: String,
    /** Dictionary form: 見逃す. */
    val lemma: String,
    /** Hiragana reading of the surface: みのがし. */
    val reading: String,
    /** Hiragana reading of the lemma (accent key): みのがす. */
    val lemmaReading: String,
    /** False renders bare text (particles, punctuation). */
    val isContent: Boolean,
    /** 動詞/形容詞 emit `;Y`. */
    val isYougen: Boolean,
    /** Empty means furigana only, no pitch block. */
    val accents: List<Int> = emptyList(),
    val ambiguous: Boolean = false,
    /** Accents are the computed conjugated downstep on [reading] itself: emit `;Y<acc>` with no base. */
    val conjComputed: Boolean = false,
)
