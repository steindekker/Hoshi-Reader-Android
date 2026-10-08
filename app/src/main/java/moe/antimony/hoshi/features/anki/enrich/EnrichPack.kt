package moe.antimony.hoshi.features.anki.enrich

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.zip.Inflater
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Reader for the enrichment pack built by anki-enrich (`ankienrich/pack.py`, which owns the
 * format). Each row holds finished card-field values for one (term, reading); the app only
 * looks them up, so every enrichment rule lives on the server.
 */
internal object EnrichPackFormat {
    const val VERSION = 1
    const val FILE_NAME = "hoshi-pack.sqlite"

    val json = Json { ignoreUnknownKeys = true }

    /** Inflates one zlib row compressed against the pack's shared preset dictionary. */
    fun decode(blob: ByteArray, zdict: ByteArray): Map<String, String> {
        val inflater = Inflater()
        val out = ByteArrayOutputStream(blob.size * 4)
        val buffer = ByteArray(8192)
        try {
            inflater.setInput(blob)
            while (!inflater.finished()) {
                val read = inflater.inflate(buffer)
                if (read == 0) {
                    when {
                        inflater.needsDictionary() -> inflater.setDictionary(zdict)
                        inflater.needsInput() -> error("Truncated enrichment pack row.")
                    }
                }
                out.write(buffer, 0, read)
            }
        } finally {
            inflater.end()
        }
        return json.decodeFromString(out.toString(Charsets.UTF_8.name()))
    }
}

internal data class EnrichPackMeta(
    val format: Int,
    val version: String,
    val builtAt: String,
    val entries: Int,
    val zdict: ByteArray,
    val defaults: Map<String, String>,
    val fallbackKeys: Set<String>,
    /** Keys some row carries, in display order; empty for packs built before the list existed. */
    val keys: List<String> = emptyList(),
)

internal data class EnrichPackRow(val reading: String, val data: ByteArray)

/** An opened pack file. Android uses SQLite; tests use an in-memory fake. */
internal interface EnrichPackSource : Closeable {
    val meta: EnrichPackMeta

    /** The term's row for [reading] if present, else its first row in chain order, else null. */
    fun row(term: String, reading: String): EnrichPackRow?
}

/** The pack's lookup contract: exact row, else the term's reading-independent values, else defaults. */
internal fun EnrichPackSource.lookup(term: String, reading: String): AnkiEnrichment {
    val row = row(term, reading) ?: return AnkiEnrichment(emptyMap(), meta.defaults)
    val values = EnrichPackFormat.decode(row.data, meta.zdict)
    val usable = if (row.reading == reading) values else values.filterKeys { it in meta.fallbackKeys }
    return AnkiEnrichment(usable, meta.defaults)
}

@Serializable
internal data class EnrichPackManifest(
    val format: Int,
    val version: String,
    val file: String,
    val size: Long,
    val sha256: String,
    val entries: Int = 0,
)
