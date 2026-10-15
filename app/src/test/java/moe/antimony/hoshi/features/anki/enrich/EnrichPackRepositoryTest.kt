package moe.antimony.hoshi.features.anki.enrich

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpAccentIndex
import moe.antimony.hoshi.features.anki.enrich.jrp.JrpTokenizer
import moe.antimony.hoshi.ui.UiText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EnrichPackRepositoryTest {
    private lateinit var server: HttpServer
    private lateinit var directory: File
    private var packBytes = ByteArray(0)
    private var manifestSha: String? = null
    private val rangeHeaders = mutableListOf<String?>()
    private val requests = mutableListOf<String>()
    /** Sentence-model files the server lists (file name -> content); null for an older server. */
    private var sentenceFiles: Map<String, ByteArray>? = null

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("enrich-pack").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hoshi/pack.json") { exchange ->
            val sentence = sentenceFiles?.let { files ->
                val parts = files.entries.joinToString(",") { (name, bytes) ->
                    val part = if (name.endsWith(".dic")) "dictionary" else "accent"
                    """"$part":{"file":"$name","version":"${String(bytes)}","size":${bytes.size},"sha256":"${sha256(bytes)}"}"""
                }
                ""","sentence":{"format":1,$parts}"""
            }.orEmpty()
            val manifest = """{"format":1,"version":"${String(packBytes)}","file":"hoshi-pack.sqlite",""" +
                """"size":${packBytes.size},"sha256":"${manifestSha ?: sha256(packBytes)}"$sentence}"""
            val body = manifest.toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/hoshi/hoshi-pack.sqlite") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            rangeHeaders += range
            val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
            val body = packBytes.copyOfRange(start, packBytes.size)
            exchange.sendResponseHeaders(if (range != null) 206 else 200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        for (part in SentenceModelPart.entries) {
            server.createContext("/hoshi/${part.fileName}") { exchange ->
                requests += part.fileName
                val body = sentenceFiles.orEmpty().getValue(part.fileName)
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        directory.deleteRecursively()
    }

    @Test
    fun missingPackFillsAeFieldsFromTheInstalledDictionaries() {
        val enrichment = repository().enrich(
            expression = "食べる",
            reading = "たべる",
            sentence = "パンを食べる。",
            templates = listOf("{ae-definition}", "{ae-definition-source}", "{ae-pitch}", "{ae-frequency}"),
        )

        assertEquals(
            "{glossary-first}|{glossary-first-dictionary}|{pitch-accent-graphs}|{frequency-harmonic-rank}",
            enrichment.protect("{ae-definition}|{ae-definition-source}|{ae-pitch}|{ae-frequency}"),
        )
    }

    @Test
    fun updateInstallsANewPackAndServesLookupsFromIt() = runBlocking {
        packBytes = "v2".toByteArray()
        val repository = repository()

        repository.update()

        assertEquals(EnrichPackStatus.Updated, repository.status.value)
        assertEquals("v2", repository.info.value?.version)
        assertTrue(File(directory, EnrichPackFormat.FILE_NAME).exists())
        assertFalse(File(directory, EnrichPackFormat.FILE_NAME + ".part").exists())
    }

    @Test
    fun installedPackAdvertisesItsKeysOrTheDefaultsForOlderPacks() = runBlocking {
        packBytes = "v2".toByteArray()
        repository().apply { update() }.let { assertEquals(AnkiEnrichHandlebars.defaultKeys, it.info.value?.keys) }

        val withKeys = repository(openPack = { file ->
            FakeEnrichPackSource(emptyList(), version = file.readText(), keys = listOf("ae-definition", "ae-sentence"))
        })

        assertEquals(listOf("ae-definition", "ae-sentence"), withKeys.info.value?.keys)
    }

    @Test
    fun sameVersionIsUpToDateWithoutDownloading() = runBlocking {
        packBytes = "v2".toByteArray()
        val repository = repository()
        repository.update()
        rangeHeaders.clear()

        repository.update()

        assertEquals(EnrichPackStatus.UpToDate, repository.status.value)
        assertTrue(rangeHeaders.isEmpty())
    }

    @Test
    fun interruptedDownloadResumesFromThePartialFile() = runBlocking {
        packBytes = ("v3-" + "x".repeat(4096)).toByteArray()
        File(directory, EnrichPackFormat.FILE_NAME + ".part").writeBytes(packBytes.copyOfRange(0, 1000))
        File(directory, EnrichPackFormat.FILE_NAME + ".part-version").writeText(String(packBytes))
        val repository = repository()

        repository.update()

        assertEquals(listOf<String?>("bytes=1000-"), rangeHeaders)
        assertEquals(EnrichPackStatus.Updated, repository.status.value)
        assertEquals(String(packBytes), repository.info.value?.version)
    }

    @Test
    fun checksumMismatchKeepsTheInstalledPack() = runBlocking {
        packBytes = "v2".toByteArray()
        val repository = repository()
        repository.update()
        packBytes = "v3".toByteArray()
        manifestSha = "0".repeat(64)

        repository.update()

        assertEquals(EnrichPackStatus.Failed(UiText.Resource(R.string.enrich_pack_error_invalid)), repository.status.value)
        assertEquals("v2", repository.info.value?.version)
        assertEquals("v2", File(directory, EnrichPackFormat.FILE_NAME).readText())
    }

    @Test
    fun missingUrlAsksForOne() = runBlocking {
        val repository = repository(url = "")

        repository.update()

        assertEquals(EnrichPackStatus.Failed(UiText.Resource(R.string.enrich_pack_error_url_missing)), repository.status.value)
    }

    @Test
    fun updateInstallsTheSentenceModelBesideThePack() = runBlocking {
        packBytes = "v2".toByteArray()
        sentenceFiles = sentenceModel(accent = "acc-1", dictionary = "dic-1")
        val repository = repository()
        assertFalse(repository.sentenceModelInstalled.value)

        repository.update()

        assertEquals(EnrichPackStatus.Updated, repository.status.value)
        assertTrue(repository.sentenceModelInstalled.value)
        assertEquals("dic-1", File(directory, SentenceModelPart.Dictionary.fileName).readText())
        assertEquals("acc-1", File(directory, SentenceModelPart.Accent.fileName).readText())
        assertFalse(File(directory, SentenceModelPart.Dictionary.fileName + ".part").exists())
    }

    @Test
    fun sentenceModelPartsUpdateOnTheirOwnVersions() = runBlocking {
        packBytes = "v2".toByteArray()
        sentenceFiles = sentenceModel(accent = "acc-1", dictionary = "dic-1")
        val repository = repository()
        repository.update()
        requests.clear()
        rangeHeaders.clear()

        sentenceFiles = sentenceModel(accent = "acc-2", dictionary = "dic-1")
        repository.update()

        assertEquals(EnrichPackStatus.Updated, repository.status.value)
        assertEquals(listOf(SentenceModelPart.Accent.fileName), requests)
        assertTrue(rangeHeaders.isEmpty()) // the pack itself was not downloaded again
        assertEquals("acc-2", File(directory, SentenceModelPart.Accent.fileName).readText())

        requests.clear()
        repository.update()
        assertEquals(EnrichPackStatus.UpToDate, repository.status.value)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun olderServerWithoutASentenceModelStillUpdatesThePack() = runBlocking {
        packBytes = "v2".toByteArray()
        val repository = repository()

        repository.update()

        assertEquals(EnrichPackStatus.Updated, repository.status.value)
        assertFalse(repository.sentenceModelInstalled.value)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun sentenceHandlebarGetsJrpMarkupFromTheInstalledModel() = runBlocking {
        packBytes = "v2".toByteArray()
        sentenceFiles = sentenceModel(accent = "acc-1", dictionary = "dic-1")
        val opened = mutableListOf<String>()
        val repository = repository(
            sentenceModel = sentenceModel(opened) { text ->
                if (text == "難を逃れる。") "{[難|なん];1}を{[逃|のが]れる;3}。" else text
            },
        )
        repository.update()

        val enrichment = repository.enrich("逃れる", "のがれる", "難を逃れる。", listOf("{ae-sentence}"))

        assertEquals("{[難|なん];1}を{[逃|のが]れる;3}。", enrichment.fill(enrichment.protect("{ae-sentence}")))
        assertEquals(listOf("acc-1", "dic-1"), opened)
        repository.enrich("逃れる", "のがれる", "難を逃れる。", listOf("{ae-sentence}"))
        assertEquals(2, opened.size) // opened once, kept open
    }

    @Test
    fun sentenceHandlebarFallsBackToThePlainSentence() = runBlocking {
        packBytes = "v2".toByteArray()
        val notInstalled = repository(sentenceModel = sentenceModel { error("unused") })
        notInstalled.enrich("", "", "難を逃れる。", listOf("{ae-sentence}")).let {
            assertEquals("難を逃れる。", it.fill(it.protect("{ae-sentence}")))
        }

        sentenceFiles = sentenceModel(accent = "acc-1", dictionary = "dic-1")
        val failing = repository(sentenceModel = sentenceModel { error("tokenizer exploded") })
        failing.update()
        failing.enrich("逃れる", "のがれる", "難を逃れる。", listOf("{ae-sentence}")).let {
            assertEquals("難を逃れる。", it.fill(it.protect("{ae-sentence}")))
        }
    }

    private fun sentenceModel(accent: String, dictionary: String) = mapOf(
        SentenceModelPart.Accent.fileName to accent.toByteArray(),
        SentenceModelPart.Dictionary.fileName to dictionary.toByteArray(),
    )

    private fun sentenceModel(opened: MutableList<String> = mutableListOf(), generate: (String) -> String) =
        JrpSentenceModel(
            directory = directory,
            openAccent = { file ->
                opened += file.readText()
                object : JrpAccentIndex {
                    override fun lookup(lemma: String, readingHira: String): List<Int>? = null
                    override fun readingsFor(lemma: String): Map<String, List<Int>> = emptyMap()
                    override fun byReading(readingHira: String): List<Int>? = null
                }
            },
            openTokenizer = { file ->
                opened += file.readText()
                JrpTokenizer { emptyList() }
            },
            generator = { _, _ -> generate },
        )

    private fun repository(
        url: String = "http://127.0.0.1:${server.address.port}/hoshi/pack.json",
        // A test "pack" is just its version string, so the opened meta can be checked.
        openPack: (File) -> EnrichPackSource = { file -> FakeEnrichPackSource(emptyList(), version = file.readText()) },
        sentenceModel: JrpSentenceModel = JrpSentenceModel.inDirectory(directory),
    ) =
        EnrichPackRepository(
            directory = directory,
            settings = object : EnrichPackSettingsRepository {
                override val manifestUrl: Flow<String> = MutableStateFlow(url)
                override suspend fun setManifestUrl(url: String) = Unit
            },
            scope = CoroutineScope(Dispatchers.Unconfined),
            ioDispatcher = Dispatchers.Unconfined,
            openPack = openPack,
            sentenceModel = sentenceModel,
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
