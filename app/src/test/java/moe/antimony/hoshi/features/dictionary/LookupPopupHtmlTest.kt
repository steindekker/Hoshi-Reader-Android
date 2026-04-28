package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.FrequencyEntry
import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import de.manhhao.hoshi.PitchEntry
import de.manhhao.hoshi.TermResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LookupPopupHtmlTest {
    @Test
    fun rendersStructuredGlossaryContentInsteadOfRawJson() {
        val html = LookupPopupHtml.render(
            listOf(
                lookupResult(
                    expression = "冷や",
                    reading = "ひや",
                    glossary = """
                        [{"content":[{"content":{"content":"cold water","tag":"li"},"tag":"ul"}],"type":"structured-content"}]
                    """.trimIndent(),
                ),
            ),
        )

        assertTrue(html.contains("冷や"))
        assertTrue(html.contains("ひや"))
        assertTrue(html.contains("<li>cold water</li>"))
        assertFalse(html.contains("structured-content"))
    }

    @Test
    fun escapesPlainGlossaryHtml() {
        val html = LookupPopupHtml.render(
            listOf(
                lookupResult(
                    expression = "<猫>",
                    reading = "",
                    glossary = "<b>cat</b>",
                ),
            ),
        )

        assertTrue(html.contains("&lt;猫&gt;"))
        assertTrue(html.contains("&lt;b&gt;cat&lt;/b&gt;"))
        assertFalse(html.contains("<b>cat</b>"))
    }

    private fun lookupResult(
        expression: String,
        reading: String,
        glossary: String,
    ): LookupResult = LookupResult(
        expression,
        expression,
        emptyArray(),
        TermResult(
            expression = expression,
            reading = reading,
            rules = "",
            glossaries = arrayOf(
                GlossaryEntry(
                    dictName = "JMdict",
                    glossary = glossary,
                    definitionTags = "",
                    termTags = "",
                ),
            ),
            frequencies = emptyArray<FrequencyEntry>(),
            pitches = emptyArray<PitchEntry>(),
        ),
        0,
    )
}
