package moe.antimony.hoshi.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveProtocolTest {
    @Test
    fun parsesHelloWithSessionsCuesAndPlayback() {
        val hello = LiveProtocol.parseSocketMessage(
            helloJson(
                sessionJson("vn", "vn", listOf(vnCueJson(1, "一"), """{"id":2,"text":"  "}"""), title = "sgre_steam"),
                sessionJson("mpv", "video", listOf(timedCueJson(1, "あ", 1000, 2000)), capabilities = VideoCapabilities,
                    playback = playbackObject(1500, paused = false), activeAt = 99),
                now = 1234,
            ),
        ) as LiveSocketMessage.Hello
        assertEquals(2, hello.protocol)
        assertEquals(1234L, hello.now)
        assertEquals("steamdeck", hello.bridge.hostname)
        val (vn, video) = hello.sessions
        assertEquals(LiveSessionKind.Vn, vn.info.sessionKind)
        assertEquals(listOf(1L), vn.cues.map { it.id })
        assertNull(vn.playback)
        assertEquals(LiveSessionKind.Video, video.info.sessionKind)
        assertTrue(video.info.has(LiveCapability.Clip))
        assertEquals(1000L, video.cues.single().startMs)
        assertEquals(LivePlayback(1500, paused = false, speed = 1.0, at = 1), video.playback)
        assertEquals(99L, video.info.activeAt)
    }

    @Test
    fun parsesEventsAndIgnoresUnknownFrames() {
        assertEquals(
            LiveSocketMessage.Cues("vn", "t1", LiveCueMode.Append, listOf(LiveCue(3, "三", at = 3000))),
            LiveProtocol.parseSocketMessage(appendJson("vn", vnCueJson(3, "三"))),
        )
        assertEquals(LiveCueMode.Replace, (LiveProtocol.parseSocketMessage(replaceJson("mpv", "t2")) as LiveSocketMessage.Cues).mode)
        assertEquals(
            LiveSocketMessage.Playback("mpv", LivePlayback(5, paused = true, speed = 1.0, at = 1)),
            LiveProtocol.parseSocketMessage(playbackJson("mpv", 5, paused = true)),
        )
        assertEquals(
            LiveSocketMessage.SessionEnded("mpv"),
            LiveProtocol.parseSocketMessage("""{"type":"sessionEnded","sessionId":"mpv"}"""),
        )
        val session = LiveProtocol.parseSocketMessage(
            """{"type":"session","session":{"id":"mpv","kind":"video","title":"T","capabilities":["control"],"trackId":"x","extra":1}}""",
        ) as LiveSocketMessage.Session
        assertEquals("T", session.info.title)
        assertNull(LiveProtocol.parseSocketMessage("""{"type":"threads","threads":[]}"""))
        assertNull(LiveProtocol.parseSocketMessage("not json"))
        assertEquals(LiveSessionKind.Web, LiveSessionKind.of("something-new"))
    }

    @Test
    fun appendDeduplicatesAndBounds() {
        val existing = listOf(LiveCue(1, "a"), LiveCue(2, "b"))
        val merged = appendLiveCues(existing, listOf(LiveCue(2, "b"), LiveCue(3, "c"), LiveCue(4, "d")), capacity = 3)
        assertEquals(listOf(2L, 3L, 4L), merged.map { it.id })
        assertTrue(appendLiveCues(existing, listOf(LiveCue(1, "a")), 10) === existing)
    }

    @Test
    fun parsesStatusAndErrors() {
        val status = LiveProtocol.parseStatus(
            """{"bridge":{"name":"hoshi-bridge","version":"0.2.0","protocol":2,"hostname":"pc"},"sessions":[{"id":"mpv","kind":"video"}]}""",
        )!!
        assertEquals("pc", status.bridge.hostname)
        assertEquals("mpv", status.sessions.single().id)
        assertEquals("nope", LiveProtocol.parseError("""{"error":"nope"}"""))
    }
}
