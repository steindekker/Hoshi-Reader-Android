package moe.antimony.hoshi.features.anki.enrich.jrp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SqliteJrpAccentIndexTest {
    // An accent.py table ("lemma\treading" -> positions, insertion order = preference order).
    private val table = linkedMapOf(
        "綾\tあや" to listOf(1),
        "橋\tはし" to listOf(2),
        "箸\tはし" to listOf(1),
        "その人\tそのひと" to listOf(4, 2),
        "綾\tりょう" to listOf(0, 1),
        "其の人\tそのひと" to listOf(2, 4),
        "開放感\tかいほうかん" to listOf(3),
        "解放感\tかいほうかん" to listOf(3),
        "無\tない" to emptyList(),
    )

    private val index = SqliteJrpAccentIndex(ServerBuiltRows(table))

    @Test
    fun lookupKeepsPositionOrderAndTellsMissingFromEmpty() {
        assertEquals(listOf(4, 2), index.lookup("その人", "そのひと"))
        assertEquals(listOf(2, 4), index.lookup("其の人", "そのひと"))
        assertEquals(emptyList<Int>(), index.lookup("無", "ない"))
        assertNull(index.lookup("綾", "あやか"))
        assertNull(index.lookup("未知", "みち"))
    }

    @Test
    fun readingsForFollowsIndexInsertionOrder() {
        val readings = index.readingsFor("綾")

        assertEquals(listOf("あや", "りょう"), readings.keys.toList())
        assertEquals(listOf(0, 1), readings["りょう"])
        assertTrue(index.readingsFor("未知").isEmpty())
    }

    @Test
    fun byReadingNeedsEveryEntryToAgreeOnThePositionSet() {
        assertNull(index.byReading("はし")) // 橋[2] vs 箸[1]
        assertEquals(listOf(4, 2), index.byReading("そのひと")) // same set, first-seen order
        assertEquals(listOf(3), index.byReading("かいほうかん"))
        assertEquals(emptyList<Int>(), index.byReading("ない"))
        assertNull(index.byReading("みち"))
    }

    /**
     * The rows anki-enrich `sentence_model.py` writes for [table]: accent rows in insertion
     * order and `by_reading` from `AccentIndex.by_reading`, positions comma-joined.
     */
    private class ServerBuiltRows(table: Map<String, List<Int>>) : JrpAccentRows {
        private val accent = table.entries.map { (key, positions) ->
            val (lemma, reading) = key.split('\t', limit = 2)
            Triple(lemma, reading, positions.joinToString(","))
        }
        private val byReading = buildMap {
            val sets = mutableMapOf<String, MutableSet<Set<Int>>>()
            val first = mutableMapOf<String, List<Int>>()
            for ((key, positions) in table) {
                val reading = key.substringAfter('\t')
                sets.getOrPut(reading) { mutableSetOf() } += positions.toSet()
                first.putIfAbsent(reading, positions)
            }
            for ((reading, distinct) in sets) if (distinct.size == 1) put(reading, first.getValue(reading).joinToString(","))
        }

        override fun positions(lemma: String, reading: String) =
            accent.firstOrNull { it.first == lemma && it.second == reading }?.third

        override fun readings(lemma: String) =
            accent.filter { it.first == lemma }.map { it.second to it.third }

        override fun byReading(reading: String) = byReading[reading]

        override fun close() = Unit
    }
}
