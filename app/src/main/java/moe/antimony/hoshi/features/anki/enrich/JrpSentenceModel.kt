package moe.antimony.hoshi.features.anki.enrich

import android.util.Log
import java.io.Closeable
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import moe.antimony.hoshi.di.FilesDir
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpAccentIndex
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpEmit
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpSentence
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpTokenize
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpTokenizer
import moe.antimony.hoshi.features.anki.enrich.jrp.SqliteJrpAccentIndex
import moe.antimony.hoshi.features.anki.enrich.jrp.SudachiJrpTokenizer

/** The two downloaded files of the sentence model (anki-enrich `sentence_model.py`). */
internal enum class SentenceModelPart(val fileName: String) {
    Accent("jrp-accent.sqlite"),
    Dictionary("sudachi-system.dic"),
}

/**
 * Owns the installed sentence model and writes `{ae-sentence}`: the mining sentence with JRP
 * markup, the app's port of the server's nightly sentence pass. Sudachi and the accent tables
 * open lazily on first use (mining runs on an IO dispatcher) and stay open; Sudachi mmaps the
 * dictionary. Without a model, or when generation fails, the plain sentence is used.
 */
@Singleton
internal class JrpSentenceModel(
    private val directory: File,
    private val openAccent: (File) -> JrpAccentIndex,
    private val openTokenizer: (File) -> JrpTokenizer,
    private val generator: (JrpTokenizer, JrpAccentIndex) -> (String) -> String,
) {
    @Inject
    constructor(@FilesDir filesDir: File) : this(
        File(filesDir, EnrichPackFormat.DIRECTORY),
        ::openAccentTables,
        ::openSudachi,
        ::jrpGenerator,
    )

    private val lock = Any()
    private var generate: ((String) -> String)? = null
    private var handles: List<Any> = emptyList()

    private val _installed = MutableStateFlow(isInstalled())
    /** Whether both files are installed, so `{ae-sentence}` is worth offering. */
    val installed: StateFlow<Boolean> = _installed.asStateFlow()

    fun file(part: SentenceModelPart) = File(directory, part.fileName)

    fun installedVersion(part: SentenceModelPart): String? =
        versionFile(part).takeIf { it.exists() && file(part).exists() }?.readText()?.trim()

    /** Replaces [part] with the verified download [from]; open handles are closed first. */
    fun install(part: SentenceModelPart, from: File, version: String) {
        synchronized(lock) {
            closeHandles()
            versionFile(part).delete()
            check(from.renameTo(file(part))) { "Unable to install ${part.fileName}." }
            versionFile(part).writeText(version)
        }
        _installed.value = isInstalled()
    }

    /** JRP markup for [sentence]; the sentence itself without a model or on any failure. */
    fun markup(sentence: String): String {
        if (sentence.isBlank()) return sentence
        return synchronized(lock) {
            runCatching {
                val generate = currentGenerator() ?: return@synchronized sentence
                JrpSentence.fill(sentence, generate)
            }
                .onFailure { logWarning("JRP sentence markup failed", it) }
                .getOrDefault(sentence)
        }
    }

    /** Callers hold [lock]. */
    private fun currentGenerator(): ((String) -> String)? {
        generate?.let { return it }
        if (!isInstalled()) return null
        val accent = openAccent(file(SentenceModelPart.Accent))
        val tokenizer = try {
            openTokenizer(file(SentenceModelPart.Dictionary))
        } catch (error: Throwable) {
            (accent as? Closeable)?.close()
            throw error
        }
        handles = listOf(accent, tokenizer)
        return generator(tokenizer, accent).also { generate = it }
    }

    private fun closeHandles() {
        handles.forEach { handle -> runCatching { (handle as? Closeable)?.close() } }
        handles = emptyList()
        generate = null
    }

    private fun isInstalled() = SentenceModelPart.entries.all { installedVersion(it) != null }

    private fun versionFile(part: SentenceModelPart) = File(directory, part.fileName + ".version")

    private fun logWarning(message: String, error: Throwable) {
        runCatching { Log.w(TAG, message, error) }
    }

    companion object {
        private const val TAG = "JrpSentence"

        /** The production model (Sudachi, accent tables, real generator) over [directory]. */
        fun inDirectory(directory: File) = JrpSentenceModel(directory, ::openAccentTables, ::openSudachi, ::jrpGenerator)
    }
}

private fun openAccentTables(file: File): JrpAccentIndex = SqliteJrpAccentIndex(file)

private fun openSudachi(dictionary: File): JrpTokenizer = SudachiJrpTokenizer.open(dictionary)

/** Production generator (`jrp_fill.make_generator()`, conjugation accents off). */
private fun jrpGenerator(tokenizer: JrpTokenizer, accent: JrpAccentIndex): (String) -> String =
    { text -> JrpEmit.emitSentence(JrpTokenize.units(text, tokenizer, accent)) }
