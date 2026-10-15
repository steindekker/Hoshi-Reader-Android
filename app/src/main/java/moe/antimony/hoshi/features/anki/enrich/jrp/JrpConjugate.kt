package moe.antimony.hoshi.features.anki.enrich.jrp

/**
 * Pitch-accent rules for conjugated 用言 (port of `jrp_fill/conjugate.py`).
 *
 * Given the accent class (heiban vs accented) and the outermost conjugation form, compute the
 * downstep mora on the conjugated reading. Rules derived from OJAD. N = downstep after the Nth mora
 * (1-based), 0 = heiban. The caller emits it on the conjugated reading with `;Y<N>` and no base.
 * `null` means not covered; the caller falls back to the dict-form `;Y<dictacc>|base`.
 */
internal object JrpConjugate {
    /** Small kana that fuse onto the previous mora (拗音 + small vowels); っ ん ー count. */
    private const val SMALL = "ゃゅょゎぁぃぅぇぉャュョヮァィゥェォ"

    private val VERB_FORMS = setOf("dict", "ta", "te", "nai", "nakatta", "masu", "ba", "volitional", "imperative")
    private val ADJ_FORMS = setOf("dict", "ta", "te", "nai", "nakatta", "masu", "ba")

    fun countMoras(readingHira: String): Int =
        readingHira.codePoints().filter { it > 0xFFFF || SMALL.indexOf(it.toChar()) < 0 }.count().toInt()

    /**
     * Downstep for a verb in [form] with [nMoras] moras. [heiban]: dict accent 0. [atamadaka]: dict
     * accent 1 and not class-changed; these keep the accent on mora 1 in te/ta/ba/imperative instead of
     * shifting back like 中高 verbs. Passive/causative/potential are class-preserving: the caller passes
     * the extended reading with atamadaka = false (the derived verb is 中高), and heiban stays heiban.
     */
    fun verbDownstep(heiban: Boolean, atamadaka: Boolean, form: String, nMoras: Int): Int? {
        if (form !in VERB_FORMS) return null
        if (heiban) {
            if (form in setOf("masu", "ba", "volitional")) return nMoras - 1
            if (form == "nakatta") return nMoras - 3 // nucleus on the な of なかった
            return 0 // dict, te, ta, nai, imperative stay heiban
        }
        if (form == "masu" || form == "volitional") return nMoras - 1 // nucleus on the auxiliary (ま / よ)
        if (form == "nai") return maxOf(1, nMoras - 2) // last mora of 未然形
        if (form == "nakatta") return maxOf(1, nMoras - 4) // verb stem's last 自立拍
        if (form in setOf("te", "ta", "ba", "imperative", "dict")) {
            if (atamadaka) return 1 // 頭高 stays 頭高
            // 中高: dict/imperative penult, te/ta/ba antepenult
            return if (form == "imperative" || form == "dict") maxOf(1, nMoras - 1) else maxOf(1, nMoras - 2)
        }
        return null
    }

    /** Downstep for an i-adjective in [form]; [stemMoras] = moras of the dictionary reading minus the final い. */
    fun adjDownstep(heiban: Boolean, form: String, stemMoras: Int): Int? {
        if (form !in ADJ_FORMS) return null
        if (heiban) {
            if (form == "dict") return 0
            if (form in setOf("te", "ta", "ba", "masu")) return stemMoras // on the stem-final mora
            return stemMoras + 2 // nai/nakatta: on the な
        }
        if (form == "dict" || form == "masu") return stemMoras // on the mora before い
        return maxOf(1, stemMoras - 1) // te/ta/ba/nai/nakatta shift back one
    }
}
