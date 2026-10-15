package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of anki-enrich `tests/test_jrp_conjugate.py`, validated against the frozen OJAD cases. */
class JrpConjugateTest {
    private data class Case(val pos: String, val reading: String, val accent: Int)

    private fun load(): Map<String, Map<String, Case>> {
        val text = javaClass.getResourceAsStream("conj_accent_cases.tsv")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        val by = LinkedHashMap<String, LinkedHashMap<String, Case>>()
        for (line in text.split("\n")) {
            if (line.startsWith("#") || line.isBlank()) continue
            val (lemma, pos, form, reading, acc) = line.split("\t")
            by.getOrPut(lemma) { LinkedHashMap() }[form] = Case(pos, reading, acc.toInt())
        }
        return by
    }

    @Test
    fun engineMatchesOjadReferenceCases() {
        val derivedForms = setOf("causative", "passive", "potential")
        // adjectives don't take these (OJAD repeats the dict form in those columns)
        val adjSkip = derivedForms + setOf("imperative", "volitional")
        val by = load()
        assertTrue("fixture missing", by.isNotEmpty())
        var checked = 0
        for ((lemma, forms) in by) {
            val dict = forms.getValue("dict")
            val heiban = dict.accent == 0
            val atamadaka = dict.accent == 1
            for ((form, case) in forms) {
                val m = JrpConjugate.countMoras(case.reading)
                val predicted = if (dict.pos == "v") {
                    if (form in derivedForms) {
                        JrpConjugate.verbDownstep(heiban, false, "dict", m) // derived verb: 中高/heiban present
                    } else {
                        JrpConjugate.verbDownstep(heiban, atamadaka, form, m)
                    }
                } else {
                    if (form in adjSkip) continue
                    JrpConjugate.adjDownstep(heiban, form, JrpConjugate.countMoras(dict.reading) - 1)
                }
                assertEquals("$lemma $form ${case.reading}", case.accent, predicted)
                checked += 1
            }
        }
        assertTrue(checked >= 150)
    }

    @Test
    fun passivePastIsTheMotivatingCase() {
        // 断られた: 断る[3] -> passive (中高) -> past = antepenult of ことわられた(6) = 4
        assertEquals(4, JrpConjugate.verbDownstep(false, false, "ta", JrpConjugate.countMoras("ことわられた")))
    }

    @Test
    fun countMorasHandlesYouonAndSpecial() {
        assertEquals(1, JrpConjugate.countMoras("しゃ")) // 拗音 fuses
        assertEquals(4, JrpConjugate.countMoras("がっこう")) // っ and long vowel each count
        assertEquals(6, JrpConjugate.countMoras("ことわられた"))
        assertEquals(2, JrpConjugate.countMoras("ファン"))
        assertEquals(2, JrpConjugate.countMoras("𠮟る")) // a surrogate pair is one mora
    }

    @Test
    fun uncoveredFormsFallBack() {
        assertNull(JrpConjugate.verbDownstep(false, false, "causative", 5))
        assertNull(JrpConjugate.adjDownstep(true, "volitional", 2))
        assertEquals(0, JrpConjugate.verbDownstep(true, false, "imperative", 3))
        assertEquals(4, JrpConjugate.adjDownstep(true, "nai", 2))
        assertEquals(1, JrpConjugate.adjDownstep(false, "te", 1))
        assertEquals(1, JrpConjugate.verbDownstep(false, false, "nakatta", 4))
    }
}
