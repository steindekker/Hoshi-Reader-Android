package moe.antimony.hoshi.features.tts

import java.io.File
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.CacheDir
import moe.antimony.hoshi.di.IoDispatcher

internal interface TtsAudioSource {
    suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): List<File>
    suspend fun clearPlaybackCache()
}

@Singleton
class TtsAudioRepository internal constructor(
    private val engine: TtsEngineRouter,
    @param:CacheDir private val cache: File,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val fallback: TtsExportFallback,
) : TtsAudioSource {
    @Inject constructor(engine: TtsEngineRouter, @CacheDir cache: File, @IoDispatcher ioDispatcher: CoroutineDispatcher,
        fallback: AndroidTtsExportFallback) : this(engine, cache, ioDispatcher, fallback as TtsExportFallback)
    private val synthesis = Semaphore(ttsSynthesisConcurrency)
    private val clearing = Mutex()
    private class KeyLock(val mutex: Mutex = Mutex(), var users: Int = 0)
    private val keyLocks = mutableMapOf<Pair<Long, String>, KeyLock>()
    private var cacheEpoch = 0L
    private val cacheLock = Mutex()
    private val files = LinkedHashMap<String, List<File>>(32, .75f, true)
    override suspend fun audio(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): List<File> =
        withContext(ioDispatcher) {
            val epoch = clearing.withLock { cacheLock.withLock { cacheEpoch } }
            val plan = retryTtsSynthesis { plan(sentence, bookId, preferences) }
            // Prefetch depth and player speed do not change the synthesized audio.
            val identity = plan.joinToString("\u0000") { "${it.settings.synthesisSettings()}\u0000${it.text}" }
            val key = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray()).joinToString("") { "%02x".format(it) }
            cached(key)?.let { return@withContext it }
            withKeyLock(epoch to key) {
                cached(key)?.let { return@withKeyLock it }
                retryTtsSynthesis {
                    synthesis.withPermit {
                        cacheLock.withLock {
                            if (epoch != cacheEpoch) throw CancellationException("Playback cache replaced")
                        }
                        synthesize(plan, File(cache, "tts/playback")).also { generated ->
                            // Own completed files even if cancellation wins their return delivery.
                            withContext(NonCancellable) {
                                cacheLock.withLock {
                                    files[key] = generated
                                    // 32 entries cover the 20-unit horizon plus current/in-flight work.
                                    if (files.size > 32) files.remove(files.keys.first())?.forEach { it.delete() }
                                }
                            }
                        }
                    }
                }
            }
        }

    private suspend fun <T> withKeyLock(key: Pair<Long, String>, block: suspend () -> T): T {
        val entry = cacheLock.withLock { keyLocks.getOrPut(key) { KeyLock() }.also { it.users++ } }
        try { return entry.mutex.withLock { block() } }
        finally {
            withContext(NonCancellable) {
                cacheLock.withLock { if (--entry.users == 0) keyLocks.remove(key) }
            }
        }
    }

    private suspend fun plan(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): List<TtsSynthesisPart> {
        val settings = preferences.settings
        // Deleted sentences need neither a voice catalog nor an engine request.
        val spoken = TtsText.spokenParts(sentence, bookId, preferences.words, settings.useRubyReadings)
        if (spoken.all { it.text.isBlank() }) return emptyList()
        val catalog = if (settings.routing().enabled && settings.customSource()?.protocol != TtsCustomProtocol.OpenAi)
            engine.cachedCatalog(settings) ?: engine.catalog(settings) else null
        return ttsSynthesisPlan(sentence, bookId, preferences, catalog, spoken)
    }

    private suspend fun synthesize(plan: List<TtsSynthesisPart>, directory: File): List<File> {
        val files = mutableListOf<File>()
        try {
            plan.forEach { files += engine.synthesize(it.text, it.settings, directory) }
            return files
        } catch (error: Throwable) {
            withContext(NonCancellable + ioDispatcher) { files.forEach { it.delete() } }
            throw error
        }
    }

    private suspend fun cached(key: String): List<File>? = cacheLock.withLock {
        files[key]?.takeIf { list -> list.all { it.isFile } }
    }

    override suspend fun clearPlaybackCache() = clearing.withLock {
        var acquired = 0
        try {
            repeat(ttsSynthesisConcurrency) { synthesis.acquire(); acquired++ }
            withContext(ioDispatcher) {
                cacheLock.withLock {
                    cacheEpoch++
                    files.clear()
                    File(cache, "tts/playback").deleteRecursively()
                    Unit
                }
            }
        } finally { repeat(acquired) { synthesis.release() } }
    }

    /** Export has its own lifetime so changing books cannot delete Anki's pending upload. */
    suspend fun export(sentence: TtsSentence, bookId: String?, preferences: TtsPreferences): File {
        val target = File(cache, "tts/export/${java.util.UUID.randomUUID()}.${engine.extension(preferences.settings)}")
        var parts: List<File> = emptyList()
        var output: File? = null
        try {
            return withContext(ioDispatcher) {
                parts = synthesize(plan(sentence, bookId, preferences), File(cache, "tts/export"))
                if (parts.isEmpty()) throw TtsException(TtsFailure.EmptyText)
                joinTtsAudio(parts, target, fallback, ioDispatcher).also { output = it }
            }
        } catch (error: Throwable) {
            withContext(NonCancellable + ioDispatcher) { output?.delete() }
            throw error
        } finally {
            withContext(NonCancellable + ioDispatcher) { parts.forEach { it.delete() } }
        }
    }
}

/** Own the final file outside the dispatcher boundary, including cancelled return delivery. */
internal suspend fun withTtsExportFile(
    target: File,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    write: suspend (File) -> Unit,
): File {
    try {
        return withContext(dispatcher) { write(target); target }
    } catch (error: Throwable) {
        withContext(NonCancellable + dispatcher) { target.delete() }
        throw error
    }
}

/** Android synthesizeToFile produces PCM WAV. Preserve format and concatenate data chunks. */
internal object TtsWav {
    fun concatenate(parts: List<File>, target: File): File {
        if (parts.size == 1) { parts.single().copyTo(target, overwrite = true); return target }
        var format: ByteArray? = null
        val data = java.io.ByteArrayOutputStream()
        parts.forEach { file ->
            val bytes = file.readBytes()
            require(bytes.size >= 12 && String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE")
            var cursor = 12
            while (cursor + 8 <= bytes.size) {
                val id = String(bytes, cursor, 4)
                val size = java.nio.ByteBuffer.wrap(bytes, cursor + 4, 4).order(java.nio.ByteOrder.LITTLE_ENDIAN).int
                require(size >= 0 && cursor.toLong() + 8 + size <= bytes.size)
                val body = bytes.copyOfRange(cursor + 8, cursor + 8 + size)
                if (id == "fmt ") {
                    if (format != null && !format.contentEquals(body)) throw TtsAudioFormatMismatch()
                    format = body
                } else if (id == "data") data.write(body)
                cursor += 8 + size + size % 2
            }
        }
        val fmt = requireNotNull(format)
        fun java.io.OutputStream.le(value: Int) = write(java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(value).array())
        target.outputStream().use { out ->
            out.write("RIFF".toByteArray()); out.le(20 + fmt.size + fmt.size % 2 + data.size() + data.size() % 2)
            out.write("WAVEfmt ".toByteArray()); out.le(fmt.size); out.write(fmt)
            if (fmt.size % 2 != 0) out.write(0)
            out.write("data".toByteArray()); out.le(data.size()); data.writeTo(out)
            if (data.size() % 2 != 0) out.write(0)
        }
        return target
    }
}
