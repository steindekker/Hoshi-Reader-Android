package moe.antimony.hoshi.features.texthooker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHookerProtocolTest {
    @Test
    fun parsesHelloWithStatusAndLinesIgnoringUnknownFields() {
        val message = TextHookerProtocol.parseSocketMessage(
            """
            {"type":"hello","protocol":1,"future":{"x":1},
             "status":{"server":"steamdeck-vn-extractor","version":"0.1.0","protocol":1,"hostname":"steamdeck",
               "latestLineId":42,"extra":true,
               "sources":[{"id":"textractor","kind":"websocket","target":"ws://127.0.0.1:6677","connected":true,"lastError":null,"new":1}],
               "screenshot":{"available":true,"backend":"gamescope"}},
             "lines":[{"id":41,"text":"「おはよう」","source":"textractor","receivedAt":1759600000000,"hasScreenshot":false,"speaker":"?"},
                      {"id":42,"text":"今日もいい天気だね","source":"textractor","receivedAt":1759600001000,"hasScreenshot":true}]}
            """.trimIndent(),
        ) as TextHookerSocketMessage.Hello

        assertEquals(1, message.protocol)
        assertEquals("steamdeck", message.status.hostname)
        assertEquals(42L, message.status.latestLineId)
        assertTrue(message.status.screenshot.available)
        assertEquals(listOf(41L, 42L), message.lines.map { it.id })
        assertEquals("「おはよう」", message.lines.first().text)
        assertTrue(message.lines.last().hasScreenshot)
    }

    @Test
    fun parsesLineAndStatusFrames() {
        val line = TextHookerProtocol.parseSocketMessage(
            """{"type":"line","line":{"id":7,"text":"行くぞ","source":"http","receivedAt":1,"hasScreenshot":false}}""",
        ) as TextHookerSocketMessage.Line
        assertEquals(TextHookerLine(7, "行くぞ", "http", 1, false), line.line)

        val status = TextHookerProtocol.parseSocketMessage(
            """{"type":"status","status":{"hostname":"deck","latestLineId":7,"sources":[{"id":"a","kind":"clipboard","connected":false}]}}""",
        ) as TextHookerSocketMessage.Status
        assertEquals("deck", status.status.hostname)
        assertTrue(status.status.hasNoConnectedSource)
    }

    @Test
    fun ignoresUnknownTypesMalformedFramesAndUnusableLines() {
        assertNull(TextHookerProtocol.parseSocketMessage("""{"type":"pong"}"""))
        assertNull(TextHookerProtocol.parseSocketMessage("not json"))
        assertNull(TextHookerProtocol.parseSocketMessage("""{"type":"line","line":{"id":0,"text":"x"}}"""))
        assertNull(TextHookerProtocol.parseSocketMessage("""{"type":"line","line":{"id":3,"text":"  "}}"""))
    }

    @Test
    fun parsesRestBodiesAndErrors() {
        val lines = TextHookerProtocol.parseLines("""{"lines":[{"id":1,"text":"a"}],"latestLineId":1,"x":0}""")
        assertEquals(1L, lines?.latestLineId)
        assertEquals("a", lines?.lines?.single()?.text)
        assertEquals("unauthorized", TextHookerProtocol.parseError("""{"error":"unauthorized"}"""))
        assertNull(TextHookerProtocol.parseError("<html>"))
        assertNull(TextHookerProtocol.parseStatus("[]"))
    }

    @Test
    fun mergeDeduplicatesByIdSortsAndKeepsNewest() {
        val existing = listOf(line(1), line(2), line(3))
        val merged = mergeTextHookerLines(existing, listOf(line(5), line(3, "edited"), line(4)), capacity = 4)
        assertEquals(listOf(2L, 3L, 4L, 5L), merged.map { it.id })
        assertEquals("edited", merged[1].text)
    }

    @Test
    fun httpIngestSourceDoesNotCountAsAConnectedHooker() {
        fun status(sources: String) = TextHookerProtocol.parseStatus("""{"hostname":"deck","sources":[$sources]}""")!!
        val http = """{"id":"http","kind":"http","target":null,"connected":true,"lastError":null}"""
        assertTrue(status(http).hasNoConnectedSource)
        assertTrue(status("""$http,{"id":"tx","kind":"websocket","connected":false}""").hasNoConnectedSource)
        assertFalse(status("""$http,{"id":"clip","kind":"clipboard","connected":true}""").hasNoConnectedSource)
        assertFalse(status("").hasNoConnectedSource)
    }

    private fun line(id: Long, text: String = "line $id") = TextHookerLine(id = id, text = text)
}
