package moe.antimony.hoshi.features.anki.enrich

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Test

class EnrichPackLookupTest {
    @Test
    fun decodesRowsCompressedByTheServer() {
        // Produced by anki-enrich `pack._compress` with a `pack.train_zdict` dictionary.
        val decoder = Base64.getDecoder()
        val zdict = decoder.decode(
            "ZGljdD0ic2Fua29rdTgiPjxkaXYgY2xhc3M9ImFhLWpqIiBkYXRhLWRpY3Q9InNhbmtva3U4Ij48ZGl2IGNsYXNzPSJhYS1qaiIgZGF0YS1kaWN0PSJzYW5rb2t1OCI+PGRpdiBjbGFzcz0iYWEtamoiIGRhdGEtZGljdD0ic2Fua29rdTgiPjxkaXYgY2xhc3M9ImFhLWpqIiBkYXRhLWRpY3Q9InNhbmtva3U4Ij48ZGl2IGNsYXNzPSJhYS1qaiIgZGF0YS1kaWN0PSJzYW5rb2t1OCI+PGRpdiBjbGFzcz0iYWEtamoiIGRhdGEtZGljdD0ic2Fua29rdTgiPjxkaXYgY2xhc3M9ImFhLWpqIiBkYXRhLWRpY3Q9InNhbmtva3U4Ij48ZGl2IGNsYXNzPSJhYS1qaiIgZGF0YS0=",
        )
        val blob = decoder.decode(
            "ePnX9Wu5q1ZKTNVNSU3LzMssyczPU7JSQlIZA1Eag2xmDNzQGCW7lw3NjxsnPW3f9bhxZnVFrY0+UK+dkg7IzLSi1MLS1LzkSqCRRsZmhkq1AFE8J54=",
        )

        assertEquals(
            mapOf(
                "ae-definition" to """<div class="aa-jj" data-dict="sankoku8">逃げ出す{x}</div>""",
                "ae-frequency" to "2361",
            ),
            EnrichPackFormat.decode(blob, zdict),
        )
    }

    @Test
    fun fieldPickerOffersPackKeysButNotTheTagsKey() {
        assertEquals(
            listOf("{ae-definition}", "{ae-sentence}"),
            AnkiEnrichHandlebars.fieldOptions(listOf("ae-definition", "ae-tags", "ae-sentence")),
        )
    }

    @Test
    fun exactReadingGivesEveryValue() {
        val pack = FakeEnrichPackSource(listOf(Triple("生", "なま", mapOf("ae-definition" to "raw", "ae-pitch" to "<svg/>"))))

        val enrichment = pack.lookup("生", "なま")

        assertEquals("raw|<svg/>", enrichment.fill(enrichment.protect("{ae-definition}|{ae-pitch}")))
    }

    @Test
    fun otherReadingKeepsOnlyReadingIndependentValues() {
        val pack = FakeEnrichPackSource(listOf(Triple("生", "なま", mapOf("ae-definition" to "raw", "ae-pitch" to "<svg/>"))))

        val enrichment = pack.lookup("生", "いき")

        assertEquals("raw|", enrichment.fill(enrichment.protect("{ae-definition}|{ae-pitch}")))
    }

    @Test
    fun unknownTermUsesPackDefaultsBeforeRendering() {
        val pack = FakeEnrichPackSource(emptyList())

        val enrichment = pack.lookup("ぴえん", "ぴえん")

        assertEquals("{glossary-first}|", enrichment.protect("{ae-definition}|{ae-examples}"))
    }
}
