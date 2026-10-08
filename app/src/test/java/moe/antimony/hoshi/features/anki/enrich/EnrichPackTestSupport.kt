package moe.antimony.hoshi.features.anki.enrich

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import kotlinx.serialization.json.Json

/** In-memory pack with the server's lookup semantics: exact reading first, else the term's first row. */
internal class FakeEnrichPackSource(
    rows: List<Triple<String, String, Map<String, String>>>,
    version: String = "v1",
    defaults: Map<String, String> = mapOf("ae-definition" to "{glossary-first}"),
    fallbackKeys: Set<String> = setOf("ae-definition", "ae-definition-source", "ae-examples", "ae-tags"),
) : EnrichPackSource {
    private val zdict = "<div class=\"aa-jj\" data-dict=\"sankoku8\">".repeat(4).toByteArray()
    private val encoded = rows.map { (term, reading, values) -> Triple(term, reading, compress(values)) }
    var closed = false
        private set

    override val meta = EnrichPackMeta(
        format = EnrichPackFormat.VERSION,
        version = version,
        builtAt = "2026-10-08T08:35:05+00:00",
        entries = rows.size,
        zdict = zdict,
        defaults = defaults,
        fallbackKeys = fallbackKeys,
    )

    override fun row(term: String, reading: String): EnrichPackRow? {
        val candidates = encoded.filter { it.first == term }
        val pick = candidates.firstOrNull { it.second == reading } ?: candidates.firstOrNull() ?: return null
        return EnrichPackRow(pick.second, pick.third)
    }

    override fun close() {
        closed = true
    }

    private fun compress(values: Map<String, String>): ByteArray {
        val deflater = Deflater(9)
        deflater.setDictionary(zdict)
        deflater.setInput(Json.encodeToString(values).toByteArray())
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}
