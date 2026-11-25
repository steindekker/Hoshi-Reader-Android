package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Test

/** Real decoder callbacks with generated audio and in-memory settings; no saved books or preferences. */
@OptIn(UnstableApi::class)
class TtsImageHoldDeviceTest {
    @Test fun manualPageTurnDuringImageHoldResumesDecodedSpeech() = runBlocking {
        fixture { controller, player ->
            val holding = CompletableDeferred<Unit>()
            var revealedAfterHold = false
            controller.presentSentence = { _, hold ->
                if (hold) {
                    holding.complete(Unit)
                    delay(60_000)
                    revealedAfterHold = true
                }
            }
            controller.play()
            withTimeout(10_000) { holding.await() }
            assertTrue(controller.state.value.preparing)
            assertEquals(1, controller.state.value.index)
            assertFalse(player.playWhenReady)

            awaitNextSpeech(controller, player) { controller.cancelReaderPresentation() }

            assertFalse(revealedAfterHold)
        }
    }

    @Test fun cancelledChapterRestoreDuringImageHoldResumesDecodedSpeech() = runBlocking {
        fixture { controller, player ->
            val holding = CompletableDeferred<Unit>()
            val restore = CompletableDeferred<Unit>()
            controller.presentSentence = { _, hold ->
                if (hold) {
                    holding.complete(Unit)
                    restore.await()
                }
            }
            controller.play()
            withTimeout(10_000) { holding.await() }

            awaitNextSpeech(controller, player) { restore.cancel() }
        }
    }

    private suspend fun awaitNextSpeech(controller: TtsPlaybackController, player: ExoPlayer, resume: () -> Unit) {
        val playing = CompletableDeferred<Unit>()
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying && controller.state.value.index == 1) playing.complete(Unit)
            }
        }
        player.addListener(listener)
        try {
            resume()
            withTimeout(5000) { playing.await() }
            assertTrue(controller.state.value.playing)
            assertFalse(controller.state.value.preparing)
            assertNull(controller.state.value.error)
            assertEquals(1, controller.state.value.index)
        } finally { player.removeListener(listener) }
    }

    private suspend fun fixture(body: suspend (TtsPlaybackController, ExoPlayer) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = File.createTempFile("tts-image-hold-", ".wav", context.cacheDir)
        val samples = 8000
        file.writeBytes(ByteBuffer.allocate(44 + samples * 2).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + samples * 2); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(8000); putInt(16000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(samples * 2)
            repeat(samples) { putShort(0) }
        }.array())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withContext(Dispatchers.Main) {
                val player = ExoPlayer.Builder(context).build()
                val output = AndroidTtsPlaybackOutput(context)
                output.attach(player)
                val connected = object : TtsPlaybackOutput by output { override suspend fun connect() = Unit }
                val audio = object : TtsAudioSource {
                    override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences) = listOf(file)
                    override suspend fun clearPlaybackCache() = Unit
                }
                val store = object : DataStore<Preferences> {
                    override val data = MutableStateFlow(emptyPreferences())
                    override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
                        transform(data.value).also { data.value = it }
                }
                val repository = TtsRepository(store)
                repository.enabled(true)
                val controller = TtsPlaybackController(TtsBookRepository(Dispatchers.IO), repository, audio, connected, scope)
                try {
                    controller.load("image-hold-fixture", EpubBook("Fixture", chapters = listOf(
                        EpubChapter("one", "one.xhtml", "application/xhtml+xml", "<p>一。</p><img src='fixture.png'/><p>二。三。</p>"),
                    )), false)
                    controller.prepareAt(0, 0)
                    withTimeout(5000) { controller.state.first { it.sentences.size == 3 } }
                    body(controller, player)
                } finally { controller.stop(); output.detach(); player.release() }
            }
        } finally { scope.coroutineContext[Job]?.cancelAndJoin(); file.delete() }
    }
}
