package moe.antimony.hoshi.features.reader

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** In-memory callbacks and statistics only; no player service, books or preferences. */
@RunWith(AndroidJUnit4::class)
class ReaderSasayakiCallbacksTest {
    @get:Rule val compose = createComposeRule()
    private val cue = SasayakiMatch("cue", 0.0, 1.0, "本文", 0, 0, 2)

    @Test
    fun attachedPlayerUsesStatisticsLoadedAfterAttachmentAndCurrentAutostartSetting() {
        val loadedTracker = mutableStateOf<ReaderStatisticsTracker?>(null)
        val autostart = mutableStateOf(false)
        var attachments = 0
        lateinit var attached: ReaderSasayakiCallbacks
        var displayedTracking: Boolean? = null
        compose.setContent {
            val tracker = loadedTracker.value
            val enabled = autostart.value
            fun dispatchCue(received: SasayakiMatch, reveal: Boolean, source: SasayakiCueRevealSource) {
                assertEquals(cue, received)
                assertTrue(reveal)
                assertEquals(SasayakiCueRevealSource.NaturalPlayback, source)
                if (enabled) tracker?.startForPageTurnIfNeeded(100)
                displayedTracking = tracker?.state?.isTracking
            }
            val callbacks = rememberReaderSasayakiCallbacks(
                onCue = ::dispatchCue,
                onClearCue = {},
            )
            // Match the service attachment's lifetime: statistics/settings aren't effect keys.
            LaunchedEffect(Unit) { attached = callbacks; attachments++ }
        }
        compose.waitForIdle()
        val tracker = ReaderStatisticsTracker(emptyMap())
        compose.runOnIdle { loadedTracker.value = tracker }
        compose.runOnIdle {
            attached.onCue(cue, true, SasayakiCueRevealSource.NaturalPlayback)
            assertEquals(false, displayedTracking)
            assertFalse(tracker.state.isTracking)
            autostart.value = true
        }
        compose.runOnIdle {
            attached.onCue(cue, true, SasayakiCueRevealSource.NaturalPlayback)
            assertTrue(tracker.state.isTracking)
            assertEquals(true, displayedTracking)
            assertEquals(1, attachments)
        }
    }

    @Test
    fun attachedPlayerClearsTheCurrentPresentationAfterRecomposition() {
        val document = mutableStateOf(0)
        lateinit var attached: ReaderSasayakiCallbacks
        var clearedDocument: Int? = null
        compose.setContent {
            val currentDocument = document.value
            fun clearCue() { clearedDocument = currentDocument }
            val callbacks = rememberReaderSasayakiCallbacks(
                onCue = { _, _, _ -> },
                onClearCue = ::clearCue,
            )
            LaunchedEffect(Unit) { attached = callbacks }
        }
        compose.runOnIdle { document.value = 1 }
        compose.runOnIdle {
            attached.onClearCue()
            assertEquals(1, clearedDocument)
        }
    }
}
