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

    @Before
    fun setUp() {
        directory = Files.createTempDirectory("enrich-pack").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/hoshi/pack.json") { exchange ->
            val manifest = """{"format":1,"version":"${String(packBytes)}","file":"hoshi-pack.sqlite",""" +
                """"size":${packBytes.size},"sha256":"${manifestSha ?: sha256(packBytes)}"}"""
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
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
        directory.deleteRecursively()
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

    private fun repository(
        url: String = "http://127.0.0.1:${server.address.port}/hoshi/pack.json",
        // A test "pack" is just its version string, so the opened meta can be checked.
        openPack: (File) -> EnrichPackSource = { file -> FakeEnrichPackSource(emptyList(), version = file.readText()) },
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
        )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
