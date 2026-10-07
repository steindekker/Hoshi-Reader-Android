package moe.antimony.hoshi.features.live

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LiveSelectionTest {
    private fun video(vararg cues: LiveCue, playback: LivePlayback? = null, receivedAt: Long = 0, activity: Long = 0) =
        LiveSessionState(
            hostId = "pc",
            info = LiveSessionInfo(id = "mpv", kind = "video"),
            cues = cues.toList(),
            playback = playback,
            playbackReceivedAt = receivedAt,
            activityAt = activity,
        )

    private val cues = arrayOf(LiveCue(1, "a", 1_000, 2_000), LiveCue(2, "b", 3_000, 4_000), LiveCue(3, "c", 5_000, 6_000))

    @Test
    fun playingCueIsTheLastStartedCue() {
        assertNull(livePlayingCueId(video(*cues, playback = LivePlayback(500, paused = true)), 0))
        assertEquals(1L, livePlayingCueId(video(*cues, playback = LivePlayback(1_000, paused = true)), 0))
        assertEquals(1L, livePlayingCueId(video(*cues, playback = LivePlayback(2_500, paused = true)), 0))
        assertEquals(3L, livePlayingCueId(video(*cues, playback = LivePlayback(99_000, paused = true)), 0))
        // Extrapolated while playing.
        assertEquals(2L, livePlayingCueId(video(*cues, playback = LivePlayback(2_500, paused = false), receivedAt = 100), 700))
        // Without playback the newest cue counts (VN-like).
        assertEquals(3L, livePlayingCueId(video(*cues), 0))
    }

    @Test
    fun activeSessionPrefersThePinnedOneThenActivity() {
        val a = video(activity = 10)
        val b = video(activity = 20).copy(hostId = "deck")
        assertEquals("deck/mpv", liveActiveSession(listOf(a, b), null)?.key)
        assertEquals("pc/mpv", liveActiveSession(listOf(a, b), "pc/mpv")?.key)
        assertEquals("deck/mpv", liveActiveSession(listOf(a, b), "gone/x")?.key)
    }

    @Test
    fun focusFollowsPinsAndResets() {
        val session = video(*cues)
        var focus = LiveFocus().reconcile(session, playingCueId = 1, lookupActive = false)
        assertEquals(LiveFocus("pc/mpv", 1, true), focus)
        focus = focus.reconcile(session, 2, lookupActive = true)
        assertEquals(1L, focus.cueId)
        focus = focus.reconcile(session, 2, lookupActive = false)
        assertEquals(2L, focus.cueId)
        focus = focus.select(1, playingCueId = 2)
        assertEquals(LiveFocus("pc/mpv", 1, false), focus.reconcile(session, 3, lookupActive = false))
        assertEquals(LiveFocus("pc/mpv", 3, true), focus.backToPlaying(3))
        // A replaced track resets the focus.
        assertEquals(LiveFocus("pc/mpv", 9, true), focus.reconcile(video(LiveCue(9, "x", 0, 1)), 9, lookupActive = true))
    }

    @Test
    fun formatsTitlesAndClocks() {
        assertEquals("Frieren (01m05s)", liveTimestampedTitle("Frieren", 65_900))
        assertEquals("Movie (75m03s)", liveTimestampedTitle("Movie", 4_503_000))
        assertEquals("1:05", liveClockText(65_900))
        assertEquals("1:15:03", liveClockText(4_503_000))
    }
}
