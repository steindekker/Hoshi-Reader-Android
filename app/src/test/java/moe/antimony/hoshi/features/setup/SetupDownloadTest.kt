package moe.antimony.hoshi.features.setup

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import moe.antimony.hoshi.features.anki.AnkiFieldTemplates
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.defaultAnkiCardFormat
import moe.antimony.hoshi.features.audio.AudioSettings
import moe.antimony.hoshi.features.audio.AudioSource
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDownloadTest {
    @Test
    fun extraJsonKeyAndMissingAudioParse() {
        val accent = "accent".toByteArray()
        val dictionary = "dictionary".toByteArray()
        val raw = """
            {"format":1,"ignored":true,"dictionaries":[${asset("sankoku8.zip", "sankoku".toByteArray(), "sankoku8")}],
            "sentence":{"format":1,"accent":${asset("jrp-accent.sqlite", accent, version = "acc")},
            "dictionary":${asset("sudachi-system.dic", dictionary, version = "dic")}}}
        """.trimIndent()

        val manifest = parseSetupManifest(raw)

        assertNull(manifest.audio)
        assertEquals("sankoku8", manifest.dictionaries.single().id)
        assertEquals("acc", manifest.sentence?.accent?.version)
    }

    @Test
    fun rangeResumeRequestsTheRemainderAndChecksTheHash() {
        val full = ByteArray(32) { (it + 1).toByte() }
        val dir = Files.createTempDirectory("setup-range").toFile()
        val dest = File(dir, "blob.bin")
        dest.writeBytes(full.copyOf(10))
        File(dir, "blob.bin.version").writeText("v1")
        val ranges = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/blob.bin") { exchange ->
            val range = exchange.requestHeaders.getFirst("Range")
            ranges += range
            val body = full.copyOfRange(10, full.size)
            exchange.responseHeaders.add("Content-Range", "bytes 10-${full.size - 1}/${full.size}")
            exchange.sendResponseHeaders(206, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            downloadVerified(
                url = URL("http://127.0.0.1:${server.address.port}/blob.bin"),
                dest = dest,
                version = "v1",
                size = full.size.toLong(),
                expectedSha = sha256(full),
                openConnection = { it.openConnection() as HttpURLConnection },
            ) {}
            assertEquals(listOf("bytes=10-"), ranges)
            assertArrayEquals(full, dest.readBytes())
        } finally {
            server.stop(0)
            dir.deleteRecursively()
        }
    }

    @Test
    fun damagedDownloadDoesNotMarkSetupComplete() {
        val harness = harness(corrupt = "sankoku8.zip")
        try {
            val error = runCatching { harness.download.run {} }.exceptionOrNull()
            assertTrue(error is SetupFailure)
            assertEquals(SetupFailure.Reason.Damaged, (error as SetupFailure).reason)
            assertFalse(harness.completed)
            assertFalse(harness.ankiApplied)
        } finally {
            harness.close()
        }
    }

    @Test
    fun setupInstallsPackAndAudioServerButNeverTheOfflineAudio() {
        val harness = harness()
        try {
            harness.download.run {}
            assertFalse(harness.requests.contains("android.db"))
            assertFalse(harness.audioEnabled)
            assertTrue(harness.completed)
            assertTrue(harness.ankiApplied)
            assertEquals(listOf("sankoku8.zip"), harness.imported)
            assertEquals(listOf("${harness.baseUrl}/pack.json"), harness.packManifests)
            assertEquals(SetupAudioServer("Box", "https://audio.example/?term={term}&reading={reading}"), harness.audioServer)
        } finally {
            harness.close()
        }
    }

    @Test
    fun alreadyInstalledDictionaryIsNotDownloadedAgain() {
        val harness = harness(installedTitles = setOf("三省堂国語辞典　第八版"))
        try {
            harness.download.run {}
            assertFalse(harness.requests.contains("sankoku8.zip"))
            assertTrue(harness.imported.isEmpty())
            assertTrue(harness.completed)
        } finally {
            harness.close()
        }
    }

    @Test
    fun failedPackInstallDoesNotMarkSetupComplete() {
        val harness = harness(packFails = true)
        try {
            val error = runCatching { harness.download.run {} }.exceptionOrNull()
            assertTrue(error is SetupFailure)
            assertFalse(harness.completed)
        } finally {
            harness.close()
        }
    }

    @Test
    fun backgroundAudioDownloadInstallsAndEnablesTheDatabase() {
        val harness = harness()
        try {
            harness.download.downloadAudio {}
            assertTrue(harness.requests.contains("android.db"))
            assertArrayEquals("audio".toByteArray(), harness.installedAudio)
            assertTrue(harness.audioEnabled)
        } finally {
            harness.close()
        }
    }

    @Test
    fun datedReleaseCountsAsInstalled() {
        val jmdict = SetupAsset(file = "jmdict.zip", size = 1, sha256 = "x", title = "JMdict")
        assertTrue(jmdict.isInstalled(setOf("JMdict [2026-10-15]")))
        assertTrue(jmdict.isInstalled(setOf("JMdict")))
        assertFalse(jmdict.isInstalled(setOf("JMdict Extra")))
        assertFalse(jmdict.copy(title = "").isInstalled(setOf("")))
    }

    @Test
    fun reorderPutsJitsuyoThenSankokuThenJmdictFirst() {
        val moves = mutableListOf<Pair<Int, Int>>()
        reorderTermDictionaries(listOf("JMdict", "三省堂", "実用", "Other")) { from, to ->
            moves += from to to
        }
        assertEquals(listOf(2 to 0, 2 to 1), moves)
    }

    @Test
    fun fallbackAudioServerGoesRightAfterTheOfflineDatabase() {
        val server = SetupAudioServer("Box", "https://audio.example/?term={term}&reading={reading}")
        val withLocal = AudioSettings().withLocalAudioEnabled(true).withFallbackAudioServer(server)
        assertEquals(AudioSettings.LocalAudioSource, withLocal.audioSources[0])
        assertEquals(AudioSource("Box", server.url), withLocal.audioSources[1])
        assertEquals(AudioSettings.DefaultAudioSources, withLocal.audioSources.drop(2))

        val withoutLocal = AudioSettings().withFallbackAudioServer(server)
        assertEquals(AudioSource("Box", server.url), withoutLocal.audioSources.first())
        assertEquals(withoutLocal, withoutLocal.withFallbackAudioServer(server))
    }

    @Test
    fun setupTokenOnlyGoesToTheCatalogHost() {
        val connections = SetupServerConnections(catalogUrl = "https://setup.example/setup.json", token = "secret")
        val setup = connections.open(URL("https://setup.example/pack.json"))
        val other = connections.open(URL("https://audio.example/?term=x"))
        assertEquals("secret", setup.getRequestProperty(SetupTokenHeader))
        assertNull(other.getRequestProperty(SetupTokenHeader))
    }

    @Test
    fun seamlessFormatIsWrittenOnlyWhenEveryFormatIsUnmapped() {
        val empty = AnkiSettings(
            cardFormats = listOf(defaultAnkiCardFormat("first"), defaultAnkiCardFormat("second")),
            tags = "old",
        )
        val written = empty.withSeamlessFormatIfUnmapped()
        assertEquals(listOf("first"), written.cardFormats.map { it.id })
        assertEquals(AnkiFieldTemplates.KinomotoNoteType, written.selectedNoteTypeName)
        assertEquals(AnkiFieldTemplates.KinomotoDeckName, written.selectedDeckName)
        assertEquals("{ae-definition}", written.fieldMappings["Meaning"])
        assertEquals("{ae-sentence}", written.fieldMappings["Sentence"])
        assertEquals("hoshi {ae-tags}", written.tags)

        val mapped = defaultAnkiCardFormat("kept").copy(fieldMappings = mapOf("Term" to "{expression}"))
        val custom = AnkiSettings(cardFormats = listOf(defaultAnkiCardFormat("empty"), mapped), tags = "keep")
        assertSame(custom, custom.withSeamlessFormatIfUnmapped())
    }

    private fun harness(
        corrupt: String? = null,
        installedTitles: Set<String> = emptySet(),
        packFails: Boolean = false,
    ): Harness {
        val dictionary = "sankoku".toByteArray()
        val accent = "accent".toByteArray()
        val sentenceDictionary = "sudachi".toByteArray()
        val audio = "audio".toByteArray()
        val files = mapOf(
            "sankoku8.zip" to dictionary,
            "jrp-accent.sqlite" to accent,
            "sudachi-system.dic" to sentenceDictionary,
            "android.db" to audio,
        )
        val manifest = """
            {"format":1,"extra":1,"dictionaries":[${asset("sankoku8.zip", dictionary, "sankoku8", title = "三省堂国語辞典　第八版")}],
            "sentence":{"format":1,"accent":${asset("jrp-accent.sqlite", accent, version = "acc-1")},
            "dictionary":${asset("sudachi-system.dic", sentenceDictionary, version = "dic-1")}},
            "audio":${asset("android.db", audio)},
            "pack":"pack.json",
            "audioServer":{"name":"Box","url":"https://audio.example/?term={term}&reading={reading}"}}
        """.trimIndent()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val requests = mutableListOf<String>()
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path.removePrefix("/")
            if (path == "setup.json") {
                val body = manifest.toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
                return@createContext
            }
            requests += path
            val bytes = files.getValue(path)
            val body = if (path == corrupt) ByteArray(bytes.size) { 1 } else bytes
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        val workDir = Files.createTempDirectory("setup-work").toFile()
        val sentenceDir = Files.createTempDirectory("setup-sentence").toFile()
        val installedAudio = mutableListOf<ByteArray>()
        var audioEnabled = false
        var ankiApplied = false
        var completed = false
        val imported = mutableListOf<String>()
        val packManifests = mutableListOf<String>()
        var audioServer: SetupAudioServer? = null
        val download = SetupDownload(
            workDir = workDir,
            openConnection = { it.openConnection() as HttpURLConnection },
            importZip = { file, _ ->
                imported += file.name
                1
            },
            lowRam = { false },
            termTitles = { emptyList() },
            installedTitles = { installedTitles },
            moveTerm = { _, _ -> },
            installSentence = { part, from, _ ->
                check(from.renameTo(File(sentenceDir, part.fileName)))
            },
            sentenceInstalledVersion = { null },
            sentencePartFile = { part -> File(sentenceDir, part.fileName + ".part") },
            installPack = { url, _ ->
                packManifests += url
                check(!packFails) { "pack failed" }
            },
            addAudioServer = { audioServer = it },
            audioInstalled = { false },
            installAudio = { file -> installedAudio += file.readBytes() },
            enableAudio = { audioEnabled = true },
            applyAnki = { ankiApplied = true },
            markComplete = { completed = true },
            catalogUrl = "http://127.0.0.1:${server.address.port}/setup.json",
        )
        return Harness(
            server = server,
            baseUrl = "http://127.0.0.1:${server.address.port}",
            packManifests = packManifests,
            audioServerBox = { audioServer },
            download = download,
            requests = requests,
            imported = imported,
            installedAudioBox = installedAudio,
            audioEnabledBox = { audioEnabled },
            ankiAppliedBox = { ankiApplied },
            completedBox = { completed },
            directories = listOf(workDir, sentenceDir),
        )
    }

    private class Harness(
        private val server: HttpServer,
        val baseUrl: String,
        val packManifests: List<String>,
        private val audioServerBox: () -> SetupAudioServer?,
        val download: SetupDownload,
        val requests: List<String>,
        val imported: List<String>,
        private val installedAudioBox: List<ByteArray>,
        private val audioEnabledBox: () -> Boolean,
        private val ankiAppliedBox: () -> Boolean,
        private val completedBox: () -> Boolean,
        private val directories: List<File>,
    ) : AutoCloseable {
        val installedAudio: ByteArray? get() = installedAudioBox.singleOrNull()
        val audioServer: SetupAudioServer? get() = audioServerBox()
        val audioEnabled: Boolean get() = audioEnabledBox()
        val ankiApplied: Boolean get() = ankiAppliedBox()
        val completed: Boolean get() = completedBox()

        override fun close() {
            server.stop(0)
            directories.forEach { it.deleteRecursively() }
        }
    }
}

private fun asset(file: String, bytes: ByteArray, id: String = "", version: String = "", title: String = ""): String {
    val idPart = if (id.isEmpty()) "" else """"id":"$id","""
    val versionPart = if (version.isEmpty()) "" else """"version":"$version","""
    val titlePart = if (title.isEmpty()) "" else """"title":"$title","""
    return """{$idPart$versionPart$titlePart"file":"$file","size":${bytes.size},"sha256":"${sha256(bytes)}"}"""
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
