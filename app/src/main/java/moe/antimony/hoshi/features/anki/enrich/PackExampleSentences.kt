package moe.antimony.hoshi.features.anki.enrich

import javax.inject.Inject
import moe.antimony.hoshi.features.anki.ExampleSentence
import moe.antimony.hoshi.features.anki.LocalExampleSentenceSource

/** The pack's `{ae-examples}` sentences for a term, read on device so the options sheet shows them at once. */
internal class PackExampleSentenceSource @Inject constructor(
    private val pack: EnrichPackRepository,
) : LocalExampleSentenceSource {
    override fun candidates(term: String): List<ExampleSentence> =
        pack.value(term, reading = "", key = AnkiEnrichHandlebars.EXAMPLES_KEY)?.let(::parsePackExamples).orEmpty()
}

private val ItemRegex = Regex("""<div class="aa-ex-item">(.*?)</div>""", RegexOption.DOT_MATCHES_ALL)
private val JapaneseRegex = Regex("""<span class="aa-ex-jp"[^>]*>(.*)</span>""", RegexOption.DOT_MATCHES_ALL)
private val EnglishRegex = Regex("""<span class="aa-ex-en">.*?</span>$""", RegexOption.DOT_MATCHES_ALL)
private val RubyTextRegex = Regex("""<(rt|rp)\b[^>]*>.*?</\1>""", RegexOption.DOT_MATCHES_ALL)
private val KeywordOpenRegex = Regex("""<b class="aa-ex-kw">""")
private val KeywordCloseRegex = Regex("""</b>""")
private val TagRegex = Regex("""<[^>]+>""")
private val NumericEntityRegex = Regex("""&#(x?)([0-9A-Fa-f]+);""")

private const val KeywordOpen = '\u0000'
private const val KeywordClose = '\u0001'

/**
 * Plain sentences from the pack's rendered 例文 block (anki-enrich `examples/render.py`):
 * furigana readings are dropped and the bolded target word becomes the highlight range.
 */
internal fun parsePackExamples(html: String): List<ExampleSentence> =
    ItemRegex.findAll(html).mapNotNull { item ->
        val japanese = JapaneseRegex.find(item.groupValues[1].replace(EnglishRegex, ""))?.groupValues?.get(1)
            ?: return@mapNotNull null
        val marked = japanese
            .replace(RubyTextRegex, "")
            .replace(KeywordOpenRegex, KeywordOpen.toString())
            .replace(KeywordCloseRegex, KeywordClose.toString())
            .replace(TagRegex, "")
            .decodeEntities()
            .trim()
        highlighted(marked).takeIf { it.text.isNotBlank() }
    }.toList()

private fun highlighted(marked: String): ExampleSentence {
    val text = StringBuilder()
    val ranges = mutableListOf<IntRange>()
    var start = -1
    for (char in marked) {
        when (char) {
            KeywordOpen -> start = text.length
            KeywordClose -> if (start >= 0 && text.length > start) {
                ranges += start until text.length
                start = -1
            }
            else -> text.append(char)
        }
    }
    return ExampleSentence(text.toString(), ranges)
}

private fun String.decodeEntities(): String =
    replace(NumericEntityRegex) { match ->
        val radix = if (match.groupValues[1].isEmpty()) 10 else 16
        match.groupValues[2].toIntOrNull(radix)?.let { String(Character.toChars(it)) } ?: match.value
    }
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&amp;", "&")
