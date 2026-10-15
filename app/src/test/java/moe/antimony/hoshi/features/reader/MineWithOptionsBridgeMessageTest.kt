package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.anki.AnkiMineResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MineWithOptionsBridgeMessageTest {
    @Test
    fun parsesMineWithOptionsWithPayloadAndIds() {
        val json = """{"name":"mineWithOptions","popupId":"p1","id":"m7","body":{"expression":"勉強"}}"""
        val message = ReaderLookupPopupBridgeMessage.fromJson(json)
        assertTrue(message is ReaderLookupPopupBridgeMessage.MineWithOptions)
        message as ReaderLookupPopupBridgeMessage.MineWithOptions
        assertEquals("p1", message.popupId)
        assertEquals("m7", message.messageId)
        assertEquals("""{"expression":"勉強"}""", message.payloadJson)
        assertNull(message.formatId)
    }

    @Test
    fun parsesMineWithOptionsFormatId() {
        val json = """{"name":"mineWithOptions","popupId":"p1","id":"m7","body":{"formatId":"word","expression":"勉強"}}"""
        val message = ReaderLookupPopupBridgeMessage.fromJson(json) as ReaderLookupPopupBridgeMessage.MineWithOptions

        assertEquals("word", message.formatId)
        assertTrue(message.payloadJson.contains("\"expression\":\"勉強\""))
    }

    @Test
    fun mineReplyCarriesTheCreatedNoteId() {
        assertEquals("false", readerPopupMineReplyJson(AnkiMineResult(added = false, noteId = 9L)))
        assertEquals("""{"added":true}""", readerPopupMineReplyJson(AnkiMineResult(added = true)))
        assertEquals(
            """{"added":true,"noteId":42}""",
            readerPopupMineReplyJson(AnkiMineResult(added = true, noteId = 42L)),
        )
    }

    @Test
    fun rejectsMineWithOptionsWithoutMessageId() {
        val json = """{"name":"mineWithOptions","popupId":"p1","body":{"expression":"x"}}"""
        assertNull(ReaderLookupPopupBridgeMessage.fromJson(json))
    }

    @Test
    fun rejectsMineWithOptionsWithoutBody() {
        val json = """{"name":"mineWithOptions","popupId":"p1","id":"m7"}"""
        assertNull(ReaderLookupPopupBridgeMessage.fromJson(json))
    }
}
