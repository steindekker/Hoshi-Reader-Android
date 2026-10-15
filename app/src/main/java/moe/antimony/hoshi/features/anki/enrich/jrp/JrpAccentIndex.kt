package moe.antimony.hoshi.features.anki.enrich.jrp

/**
 * Port of `jrp_fill/accent.py`. Positions keep the index's preference order; `null` means
 * the index has no entry, which differs from an empty result in `readingsFor`.
 */
internal interface JrpAccentIndex {
    /** `table[lemma + "\t" + readingHira]`. */
    fun lookup(lemma: String, readingHira: String): List<Int>?

    /** All readings the dict knows for [lemma], in the index's insertion order. */
    fun readingsFor(lemma: String): Map<String, List<Int>>

    /**
     * Accent for an exact reading when every entry with that reading agrees on the set of
     * positions; the first-seen positions list in index order. Otherwise `null`.
     */
    fun byReading(readingHira: String): List<Int>?
}
