package moe.antimony.hoshi.features.anki.enrich.jrp

import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/**
 * Row access to the sentence model's `jrp-accent.sqlite` (schema owned by anki-enrich
 * `ankienrich/sentence_model.py`). Positions are the server's comma-joined lists.
 */
internal interface JrpAccentRows : Closeable {
    /** `accent.positions` for the (lemma, reading) row, or null without one. */
    fun positions(lemma: String, reading: String): String?

    /** The lemma's (reading, positions) rows in index insertion order. */
    fun readings(lemma: String): List<Pair<String, String>>

    /** `by_reading.positions`: present only where every entry with the reading agrees. */
    fun byReading(reading: String): String?
}

/**
 * [JrpAccentIndex] over the downloaded accent tables. The server precomputes `readings_for`
 * order and `by_reading` agreement with `accent.py` itself, so this only decodes rows.
 */
internal class SqliteJrpAccentIndex(private val rows: JrpAccentRows) : JrpAccentIndex, Closeable {
    constructor(file: File) : this(AndroidJrpAccentRows(file))

    override fun lookup(lemma: String, readingHira: String): List<Int>? =
        rows.positions(lemma, readingHira)?.let(::decode)

    override fun readingsFor(lemma: String): Map<String, List<Int>> =
        rows.readings(lemma).associateTo(LinkedHashMap()) { (reading, positions) -> reading to decode(positions) }

    override fun byReading(readingHira: String): List<Int>? =
        rows.byReading(readingHira)?.let(::decode)

    override fun close() = rows.close()

    private fun decode(positions: String): List<Int> =
        if (positions.isEmpty()) emptyList() else positions.split(',').map(String::toInt)

    companion object {
        const val FORMAT = 1
    }
}

internal class AndroidJrpAccentRows(file: File) : JrpAccentRows {
    private val db = SQLiteDatabase.openDatabase(
        file.absolutePath,
        null,
        SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
    )

    init {
        val format = db.rawQuery("SELECT value FROM meta WHERE key = 'format'", null).use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0).toIntOrNull() else null
        }
        if (format != SqliteJrpAccentIndex.FORMAT) {
            db.close()
            error("Unsupported accent table format $format.")
        }
    }

    override fun positions(lemma: String, reading: String): String? =
        db.rawQuery("SELECT positions FROM accent WHERE lemma = ? AND reading = ?", arrayOf(lemma, reading))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun readings(lemma: String): List<Pair<String, String>> =
        db.rawQuery("SELECT reading, positions FROM accent WHERE lemma = ? ORDER BY rowid", arrayOf(lemma))
            .use { cursor ->
                buildList { while (cursor.moveToNext()) add(cursor.getString(0) to cursor.getString(1)) }
            }

    override fun byReading(reading: String): String? =
        db.rawQuery("SELECT positions FROM by_reading WHERE reading = ?", arrayOf(reading))
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    override fun close() = db.close()
}
