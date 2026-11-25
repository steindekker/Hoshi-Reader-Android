package moe.antimony.hoshi.features.tts

import android.content.Context
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import moe.antimony.hoshi.epub.EpubBook
import moe.antimony.hoshi.epub.EpubChapter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Public samples, isolated player/cache and in-memory preferences; preserves the user's reading session. */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class TtsContinuousPlaybackDeviceTest {
    @Test fun fastEdgePlaybackCompletesEverySentenceWithPrefetch() = checkPlayback(2)
    @Test fun fastEdgePlaybackCompletesShortSentencesWithSixPrefetchedSentences() = checkPlayback(6)
    @Test fun devicePlaybackCompletesQuoteUnitsWithPrefetchAndSentencePauses() = checkPlayback(6, quoteUnits = true)

    @Test fun microsoftTranslatorPlaybackCompletesWithTwentyPrefetchedUnits() =
        checkPlayback(20, source = TtsOnlineSource.MicrosoftTranslator)

    private fun checkPlayback(prefetchSentences: Int, quoteUnits: Boolean = false, source: TtsOnlineSource = TtsOnlineSource.Edge) = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "tts-continuous-device-test")
        val router = TtsEngineRouter(AndroidTtsEngine(context),
            OnlineTtsEngine(OnlineTtsTransport(), TtsCredentialCipher(Dispatchers.IO), Dispatchers.IO), CustomTtsEngine(OnlineTtsTransport(), { _, _ -> throw TtsException(TtsFailure.Configuration) }, kotlinx.coroutines.Dispatchers.IO))
        val audio = TtsAudioRepository(router, directory, Dispatchers.IO, AndroidTtsExportFallback(context))
        val repository = TtsRepository(MemoryStore())
        repository.settings {
            if (quoteUnits) TtsSettings(enabled = true, prefetchSentences = prefetchSentences, sentencePauseMs = 150)
            else TtsSettings(enabled = true, engine = source.id,
                prefetchSentences = prefetchSentences).withVoice("ja-JP-NanamiNeural").withRate(2f)
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            withContext(Dispatchers.Main) {
                val player = ExoPlayer.Builder(context).build()
                val output = DirectOutput(AndroidTtsPlaybackOutput(context), player)
                val controller = TtsPlaybackController(TtsBookRepository(Dispatchers.IO), repository, audio, output, scope)
                val text = if (quoteUnits) "彼女は「こんにちは」と言った。「はい」「わかった」"
                    else if (prefetchSentences >= 6) "一。二。三。四。五。六。七。八。"
                    else "今日は晴れです。次の文章です。日本語を読んでいます。これが最後の文章です。"
                val book = EpubBook("Public sample", chapters = listOf(EpubChapter("one", "one.xhtml",
                    "application/xhtml+xml", "<p>$text</p>")))
                try {
                    controller.load("continuous-fixture", book, false)
                    controller.prepareAt(0, 0)
                    val loaded = withTimeout(5000) { controller.state.first { it.sentences.isNotEmpty() } }
                    assertEquals(if (quoteUnits) 5 else if (prefetchSentences >= 6) 8 else 4, loaded.sentences.size)
                    if (quoteUnits) assertEquals(listOf("彼女は", "「こんにちは」", "と言った。", "「はい」", "「わかった」"), loaded.sentences.map { it.text })
                    controller.play()
                    val finished = withTimeout(60_000) {
                        controller.state.first { it.error != null || it.started && it.index == loaded.sentences.lastIndex && !it.playing }
                    }
                    assertNull(finished.error)
                    assertEquals(loaded.sentences.lastIndex, finished.index)
                    assertEquals(loaded.sentences.map { it.text }, output.sentences)
                } finally { controller.stop(); player.release() }
            }
        } finally {
            scope.cancel()
            audio.clearPlaybackCache()
            directory.deleteRecursively()
        }
    }

    private class MemoryStore : DataStore<Preferences> {
        override val data = MutableStateFlow(emptyPreferences())
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences) =
            transform(data.value).also { data.value = it }
    }

    private class DirectOutput(private val output: AndroidTtsPlaybackOutput, player: ExoPlayer) : TtsPlaybackOutput by output {
        val sentences = mutableListOf<String>()
        init {
            output.attach(player)
            player.addListener(object : Player.Listener {
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    mediaItem?.mediaMetadata?.artist?.toString()?.let { sentences += it }
                }
            })
        }
        override suspend fun connect() = Unit
    }
}
