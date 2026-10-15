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
            val error = runCatching { harness.download.run(includeAudio = false) {} }.exceptionOrNull()
            assertTrue(error is SetupFailure)
            assertEquals(SetupFailure.Reason.Damaged, (error as SetupFailure).reason)
            assertFalse(harness.completed)
            assertFalse(harness.ankiApplied)
        } finally {
            harness.close()
        }
    }

    @Test
    fun audioToggleOffNeverRequestsTheAudioFile() {
        val harness = harness()
        try {
            harness.download.run(includeAudio = false) {}
            assertFalse(harness.requests.contains("android.db"))
            assertFalse(harness.audioEnabled)
            assertTrue(harness.completed)
            assertTrue(harness.ankiApplied)
            assertEquals(listOf("sankoku8.zip"), harness.imported)
        } finally {
            harness.close()
        }
    }

    @Test
    fun audioToggleOnInstallsAndEnablesTheDatabase() {
        val harness = harness()
        try {
            harness.download.run(includeAudio = true) {}
            assertTrue(harness.requests.contains("android.db"))
            assertArrayEquals("audio".toByteArray(), harness.installedAudio)
            assertTrue(harness.audioEnabled)
            assertTrue(harness.completed)
        } finally {
            harness.close()
        }
    }

    @Test
    fun reorderPutsSankokuThenJitsuyoThenJmdictFirst() {
        val moves = mutableListOf<Pair<Int, Int>>()
        reorderTermDictionaries(listOf("JMdict", "実用", "三省堂", "Other")) { from, to ->
            moves += from to to
        }
        assertEquals(listOf(2 to 0, 2 to 1), moves)
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

    private fun harness(corrupt: String? = null): Harness {
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
            {"format":1,"extra":1,"dictionaries":[${asset("sankoku8.zip", dictionary, "sankoku8")}],
            "sentence":{"format":1,"accent":${asset("jrp-accent.sqlite", accent, version = "acc-1")},
            "dictionary":${asset("sudachi-system.dic", sentenceDictionary, version = "dic-1")}},
            "audio":${asset("android.db", audio)}}
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
        val download = SetupDownload(
            workDir = workDir,
            openConnection = { it.openConnection() as HttpURLConnection },
            importZip = { file, _ ->
                imported += file.name
                1
            },
            lowRam = { false },
            termTitles = { emptyList() },
            moveTerm = { _, _ -> },
            installSentence = { part, from, _ ->
                check(from.renameTo(File(sentenceDir, part.fileName)))
            },
            sentenceInstalledVersion = { null },
            sentencePartFile = { part -> File(sentenceDir, part.fileName + ".part") },
            audioInstalled = { false },
            installAudio = { file -> installedAudio += file.readBytes() },
            enableAudio = { audioEnabled = true },
            applyAnki = { ankiApplied = true },
            markComplete = { completed = true },
            catalogUrl = "http://127.0.0.1:${server.address.port}/setup.json",
        )
        return Harness(
            server = server,
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
        val audioEnabled: Boolean get() = audioEnabledBox()
        val ankiApplied: Boolean get() = ankiAppliedBox()
        val completed: Boolean get() = completedBox()

        override fun close() {
            server.stop(0)
            directories.forEach { it.deleteRecursively() }
        }
    }
}

private fun asset(file: String, bytes: ByteArray, id: String = "", version: String = ""): String {
    val idPart = if (id.isEmpty()) "" else """"id":"$id","""
    val versionPart = if (version.isEmpty()) "" else """"version":"$version","""
    return """{$idPart$versionPart"file":"$file","size":${bytes.size},"sha256":"${sha256(bytes)}"}"""
}

private fun sha256(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}
