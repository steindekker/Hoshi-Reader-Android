package moe.antimony.hoshi.features.tts

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Opt-in public samples, temporary files and isolated engines; no saved settings or library access. */
class TtsConcurrencyBenchmarkDeviceTest {
    @Test fun compareMicrosoftTranslatorConcurrency() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ttsConcurrencyBenchmark") == "true")
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.cacheDir, "tts-concurrency-${java.util.UUID.randomUUID()}")
        val transport = OnlineTtsTransport()
        val session = MicrosoftTranslatorSession(transport)
        val catalogs = TtsVoiceCatalogCache()
        fun engine() = OnlineTtsEngine(transport, { _, _ -> error("No account credentials") }, Dispatchers.IO, session, catalogs)
        val settings = TtsSettings(engine = TtsOnlineSource.MicrosoftTranslator.id).withVoice("ja-JP-NanamiNeural")
        val shortSamples = listOf("彼女は", "「こんにちは」", "と笑顔で言った。", "「はい」", "「わかりました」", "私はうなずいて、静かに窓の外を見た。", "「明日も会える？」", "彼はゆっくりと答えた。")
        val profile = InstrumentationRegistry.getArguments().getString("ttsBenchmarkProfile") ?: "short"
        val medium = "駅を出ると、冷たい風が吹いていた。私は鞄を持ち直し、約束の場所へゆっくり歩き始めた。"
        val long = "いつもの喫茶店に着くと、彼女は窓際の席で本を読んでいた。私に気づくと小さく手を振り、向かいの椅子を指した。温かい紅茶を注文してから、私たちは昨日の出来事を順番に話し始めた。外では雨が降り始めていたが、店の中には静かな音楽が流れていた。"
        val samples = if (profile == "mixed") (0 until 24).map { index ->
            when (index % 3) { 0 -> shortSamples[index / 3]; 1 -> medium; else -> long }
        } else shortSamples
        data class Completed(val requestMs: Long, val readyMs: Long, val audioMs: Long)
        try {
            engine().catalog(settings) // Exclude cold catalog and authorization from the comparison.
            for ((round, concurrency) in listOf(1, 2, 3, 4, 4, 3, 2, 1).withIndex()) {
                val pool = Channel<OnlineTtsEngine>(concurrency)
                repeat(concurrency) { pool.send(engine()) }
                val start = SystemClock.elapsedRealtime()
                val times = coroutineScope {
                    samples.map { sample -> async(start = CoroutineStart.UNDISPATCHED) {
                        val worker = pool.receive()
                        val requestStart = SystemClock.elapsedRealtime()
                        try {
                            val files = worker.synthesize(sample, settings, directory)
                            assertTrue(files.isNotEmpty() && files.all { it.length() > 0 })
                            val ready = SystemClock.elapsedRealtime()
                            val duration = files.sumOf { file ->
                                val extractor = MediaExtractor()
                                try {
                                    extractor.setDataSource(file.absolutePath)
                                    extractor.getTrackFormat(0).getLong(MediaFormat.KEY_DURATION) / 1000
                                } finally { extractor.release() }
                            }
                            files.forEach { it.delete() }
                            Completed(ready - requestStart, ready - start, duration)
                        } finally { pool.trySend(worker) }
                    } }.awaitAll()
                }
                var end = times.first().readyMs
                var wait = 0L
                for (item in times) {
                    val gap = (item.readyMs - end).coerceAtLeast(0)
                    wait += gap
                    end += gap + item.audioMs / 2
                }
                Log.i("HoshiTtsConcurrency", "profile=$profile round=$round concurrency=$concurrency sentences=${samples.size} totalMs=${times.maxOf { it.readyMs }} firstMs=${times.first().readyMs} audioMs=${times.sumOf { it.audioMs }} estimatedWait2xMs=$wait requestMs=${times.map { it.requestMs }.sorted()} failures=0")
                pool.close()
            }
        } catch (error: Exception) {
            Log.e("HoshiTtsConcurrency", "Benchmark stopped on failure; no automatic retry", error)
            throw error
        } finally { directory.deleteRecursively() }
    }
}
