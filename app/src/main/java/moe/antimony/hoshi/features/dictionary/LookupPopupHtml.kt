package moe.antimony.hoshi.features.dictionary

import de.manhhao.hoshi.GlossaryEntry
import de.manhhao.hoshi.LookupResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal object LookupPopupHtml {
    private val json = Json { ignoreUnknownKeys = true }

    fun render(results: List<LookupResult>): String = """
        <!doctype html>
        <html>
        <head>
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
          <style>
            :root {
              color-scheme: light dark;
              font-family: -apple-system, BlinkMacSystemFont, "Hiragino Sans", "Noto Sans CJK JP", sans-serif;
              font-size: 15px;
              line-height: 1.4;
            }
            * { box-sizing: border-box; }
            body {
              margin: 0;
              padding: 10px 12px 14px;
              color: #202124;
              background: transparent;
              overflow-x: hidden;
              -webkit-text-size-adjust: none;
            }
            .entry { padding: 4px 0 12px; border-bottom: 1px solid rgba(0,0,0,0.08); }
            .entry:last-child { border-bottom: 0; }
            .expression { font-size: 18px; font-weight: 650; line-height: 1.25; }
            .reading { color: #5f6368; margin-top: 2px; }
            .glossary { margin-top: 8px; }
            ul, ol { margin: 0.35em 0 0.35em 1.25em; padding: 0; }
            li { margin: 0.2em 0; }
            a { color: inherit; text-decoration: underline; text-decoration-thickness: 0.08em; }
            .dictionary { color: #6f7378; font-size: 12px; margin-top: 6px; }
            @media (prefers-color-scheme: dark) {
              body { color: #f1f3f4; }
              .reading, .dictionary { color: #bdc1c6; }
              .entry { border-bottom-color: rgba(255,255,255,0.14); }
            }
          </style>
        </head>
        <body>
          ${results.take(MAX_POPUP_RESULTS).joinToString(separator = "\n") { renderResult(it) }}
        </body>
        </html>
    """.trimIndent()

    private fun renderResult(result: LookupResult): String {
        val term = result.term
        val reading = term.reading.takeIf { it.isNotBlank() && it != term.expression }
            ?.let { """<div class="reading">${escapeHtml(it)}</div>""" }
            .orEmpty()
        val glossaries = term.glossaries.take(MAX_GLOSSARIES_PER_RESULT)
            .joinToString(separator = "\n") { renderGlossary(it) }

        return """
            <section class="entry">
              <div class="expression">${escapeHtml(term.expression)}</div>
              $reading
              <div class="glossary">$glossaries</div>
            </section>
        """.trimIndent()
    }

    private fun renderGlossary(entry: GlossaryEntry): String {
        val content = parseStructuredContent(entry.glossary)
            ?.let(::renderElement)
            ?: "<p>${escapeHtml(entry.glossary)}</p>"
        val dictionary = entry.dictName.takeIf { it.isNotBlank() }
            ?.let { """<div class="dictionary">${escapeHtml(it)}</div>""" }
            .orEmpty()
        return "$content$dictionary"
    }

    private fun parseStructuredContent(value: String): JsonElement? =
        runCatching { json.parseToJsonElement(value) }.getOrNull()

    private fun renderElement(element: JsonElement): String = when (element) {
        is JsonArray -> element.joinToString(separator = "") { renderElement(it) }
        is JsonObject -> renderObject(element)
        is JsonPrimitive -> escapeHtml(element.contentOrNull.orEmpty())
    }

    private fun renderObject(value: JsonObject): String {
        val content = value["content"]
        val tag = value["tag"]?.jsonPrimitive?.contentOrNull
        val renderedContent = content?.let(::renderContent) ?: ""
        val safeTag = tag?.takeIf { it in SAFE_TAGS }

        return if (safeTag == null) {
            renderedContent
        } else {
            val attributes = buildAttributes(value)
            "<$safeTag$attributes>$renderedContent</$safeTag>"
        }
    }

    private fun renderContent(content: JsonElement): String = when (content) {
        is JsonArray -> content.jsonArray.joinToString(separator = "") { renderElement(it) }
        is JsonObject -> renderObject(content.jsonObject)
        is JsonPrimitive -> escapeHtml(content.contentOrNull.orEmpty())
    }

    private fun buildAttributes(value: JsonObject): String {
        val style = value["style"]?.jsonObject
            ?.mapNotNull { (key, rawValue) ->
                val cssKey = STYLE_KEYS[key] ?: return@mapNotNull null
                val cssValue = rawValue.jsonPrimitive.contentOrNull
                    ?.takeIf(::isSafeCssValue)
                    ?: return@mapNotNull null
                "$cssKey: $cssValue"
            }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(separator = "; ")
            ?.let { " style=\"${escapeHtml(it)}\"" }
            .orEmpty()
        val href = value["href"]?.jsonPrimitive?.contentOrNull
            ?.takeIf { it.startsWith("?") }
            ?.let { " href=\"${escapeHtml(it)}\"" }
            .orEmpty()
        return style + href
    }

    private fun isSafeCssValue(value: String): Boolean =
        value.none { it == '<' || it == '>' || it == '"' || it == '\'' || it == ';' || it == '\\' }

    private fun escapeHtml(value: String): String = buildString(value.length) {
        value.forEach { char ->
            when (char) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(char)
            }
        }
    }

    private val SAFE_TAGS = setOf(
        "a",
        "b",
        "br",
        "div",
        "em",
        "i",
        "li",
        "ol",
        "p",
        "ruby",
        "rp",
        "rt",
        "span",
        "strong",
        "table",
        "tbody",
        "td",
        "th",
        "thead",
        "tr",
        "ul",
    )

    private val STYLE_KEYS = mapOf(
        "fontSize" to "font-size",
        "fontWeight" to "font-weight",
        "listStyleType" to "list-style-type",
        "verticalAlign" to "vertical-align",
    )

    private const val MAX_POPUP_RESULTS = 3
    private const val MAX_GLOSSARIES_PER_RESULT = 4
}
