package moe.antimony.hoshi.features.anki.enrich

/**
 * Fork-only `{ae-*}` handlebars filled from the anki-enrich pack. They are resolved around
 * the upstream renderer instead of inside it: [AnkiEnrichment.protect] swaps each token for a
 * brace-free placeholder before rendering (upstream blanks unknown handlebars) and
 * [AnkiEnrichment.fill] puts the value back afterwards, so dictionary text is never
 * re-parsed as a handlebar. A pack default may itself be an upstream handlebar such as
 * `{glossary-first}`; it is substituted before rendering so it renders as usual.
 */
internal object AnkiEnrichHandlebars {
    /** Keys the current pack provides, listed in the handlebar picker. Any `{ae-*}` key works. */
    val all: List<String> = listOf(
        "{ae-definition}",
        "{ae-definition-source}",
        "{ae-pitch}",
        "{ae-examples}",
        "{ae-frequency}",
        "{ae-tags}",
    )

    val token = Regex("""\{(ae-[^{}]*)\}""")
}

internal fun interface AnkiFieldEnricher {
    fun enrich(expression: String, reading: String, templates: Collection<String>): AnkiEnrichment

    companion object {
        val None = AnkiFieldEnricher { _, _, _ -> AnkiEnrichment.Empty }
    }
}

internal class AnkiEnrichment(
    private val values: Map<String, String>,
    private val defaults: Map<String, String> = emptyMap(),
) {
    fun protect(template: String): String =
        AnkiEnrichHandlebars.token.replace(template) { match ->
            val key = match.groupValues[1]
            if (key in values) placeholder(key) else defaults[key].orEmpty()
        }

    fun fill(rendered: String): String =
        if ('' !in rendered) {
            rendered
        } else {
            values.entries.fold(rendered) { acc, (key, value) -> acc.replace(placeholder(key), value) }
        }

    private fun placeholder(key: String): String = "$key"

    companion object {
        val Empty = AnkiEnrichment(emptyMap())
    }
}
