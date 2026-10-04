package moe.antimony.hoshi.features.anki

import android.content.ContextWrapper
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.ui.UiText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiRepositoryScreenshotTest {
    private val deck = AnkiDeck(10L, "Mining")
    private val noteType = AnkiNoteType(20L, "Lapis", listOf("Expression", "Picture"))
    private val backend = ImageRecordingBackend(deck, noteType)
    private val ankiCacheDir = Files.createTempDirectory("hoshi-anki-screenshot").toFile()

    @Test
    fun attachesFetchedScreenshotForImageHandlebarAndCleansUpTempFile() = runBlocking {
        val source = RecordingScreenshotSource(AnkiScreenshotResult.Success(byteArrayOf(9, 8, 7), "image/jpeg"))
        val result = repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(lineId = 42)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )

        assertTrue(result.added)
        assertTrue(result.warnings.isEmpty())
        assertEquals(listOf<Long?>(42L), source.requests.map { it.lineId })
        assertEquals(listOf(9, 8, 7), backend.lastMediaBytes.map { it.toInt() })
        assertTrue(backend.lastMediaName.startsWith("hoshi_screenshot_"))
        assertEquals("<img src=\"${backend.lastMediaName}\">", backend.lastFields["Picture"])
        assertTrue(ankiCacheDir.resolve("anki-media").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun screenshotWinsOverCover() = runBlocking {
        val cover = Files.createTempFile("cover", ".png").also { Files.write(it, byteArrayOf(1)) }
        val source = RecordingScreenshotSource(AnkiScreenshotResult.Success(byteArrayOf(5), "image/jpeg"))
        repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(
                sentence = "本を読む",
                coverPath = cover.toString(),
                screenshot = AnkiScreenshotRequest(lineId = 1),
            ),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(backend.lastFields.getValue("Picture").contains("hoshi_screenshot_"))
    }

    @Test
    fun failedScreenshotStillAddsCardWithWarning() = runBlocking {
        val warning = UiText.Resource(R.string.texthooker_screenshot_unreachable)
        val source = RecordingScreenshotSource(AnkiScreenshotResult.Failure(warning))
        val result = repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(lineId = 3)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(result.added)
        assertEquals(listOf(warning), result.warnings)
        assertEquals(mapOf("Expression" to "読む"), backend.lastFields)
    }

    @Test
    fun throwingSourceIsReportedAsWarning() = runBlocking {
        val source = AnkiScreenshotSource { error("boom") }
        val result = repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(lineId = 3)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(result.added)
        assertEquals(listOf<UiText>(UiText.Resource(R.string.texthooker_screenshot_failed)), result.warnings)
    }

    @Test
    fun usesFetchedPreviewWithoutContactingTheDeck() = runBlocking {
        val preview = Files.createTempFile("preview", ".jpg").also { Files.write(it, byteArrayOf(4, 4)) }
        val source = RecordingScreenshotSource(AnkiScreenshotResult.Failure(UiText.Literal("unused")))
        val result = repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(
                sentence = "本を読む",
                screenshot = AnkiScreenshotRequest(lineId = 3, localPath = preview.toString()),
            ),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(result.added)
        assertTrue(source.requests.isEmpty())
        assertEquals(listOf(4, 4), backend.lastMediaBytes.map { it.toInt() })
        assertTrue(preview.toFile().exists())
    }

    @Test
    fun doesNotContactTheDeckWhenImageIsUnreferencedOrWebImagePicked() = runBlocking {
        val source = RecordingScreenshotSource(AnkiScreenshotResult.Success(byteArrayOf(1), "image/jpeg"))
        repository(source, mappings = mapOf("Expression" to "{expression}")).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(lineId = 3)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        // Unreachable on purpose: the picked web image wins even when its download fails.
        // (The failed download's Log call is unavailable on the JVM, so the outcome is ignored.)
        runCatching {
            repository(source).mineEntryWithResult(
                rawPayload = """{"expression":"読む"}""",
                context = AnkiMiningContext(
                    sentence = "本を読む",
                    webImageUrl = "http://127.0.0.1:1/picked.jpg",
                    screenshot = AnkiScreenshotRequest(lineId = 3),
                ),
                decks = emptyList(),
                noteTypes = emptyList(),
            )
        }
        assertTrue(source.requests.isEmpty())
        assertFalse(backend.lastFields.containsKey("Picture"))
    }

    @Test
    fun rendererPrefersWebImageThenScreenshotThenCover() {
        fun render(context: AnkiMiningContext) = AnkiHandlebarRenderer.render(
            template = "{image}",
            payload = AnkiMiningPayload.fromJson("""{"expression":"本"}"""),
            context = context,
        )
        val all = AnkiMiningContext(sentence = "s", coverPath = "c", webImagePath = "w", screenshotPath = "p")
        assertEquals("w", render(all))
        assertEquals("p", render(all.copy(webImagePath = null)))
        assertEquals("c", render(all.copy(webImagePath = null, screenshotPath = null)))
    }

    private fun repository(
        source: AnkiScreenshotSource,
        mappings: Map<String, String> = mapOf("Expression" to "{expression}", "Picture" to "{image}"),
    ): AnkiRepository = AnkiRepository(
        context = object : ContextWrapper(null) {
            override fun getCacheDir(): java.io.File = ankiCacheDir
        },
        backend = backend,
        settingsRepository = object : AnkiSettingsRepository {
            override val settings: Flow<AnkiSettings> = MutableStateFlow(
                AnkiSettings(
                    backendKind = AnkiBackendKind.AnkiConnect,
                    ankiConnectUrl = "https://anki.example.com",
                    selectedDeckId = deck.id,
                    selectedDeckName = deck.name,
                    selectedNoteTypeId = noteType.id,
                    selectedNoteTypeName = noteType.name,
                    availableDecks = listOf(deck),
                    availableNoteTypes = listOf(noteType),
                    fieldMappings = mappings,
                ),
            )

            override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) = Unit
        },
        localAudioRepository = LocalAudioRepository(Files.createTempDirectory("hoshi-anki-audio").toFile()),
        ankiConnectBackendFactory = { _, _ -> backend },
        screenshotSource = source,
    )

    private class RecordingScreenshotSource(private val result: AnkiScreenshotResult) : AnkiScreenshotSource {
        val requests = mutableListOf<AnkiScreenshotRequest>()

        override suspend fun fetchScreenshot(request: AnkiScreenshotRequest): AnkiScreenshotResult {
            requests += request
            return result
        }
    }

    private class ImageRecordingBackend(
        private val deck: AnkiDeck,
        private val noteType: AnkiNoteType,
    ) : AnkiBackend {
        var lastFields: Map<String, String> = emptyMap()
        var lastMediaBytes: ByteArray = byteArrayOf()
        var lastMediaName: String = ""

        override fun isAvailable(): Boolean = true
        override fun fetchDecks(): List<AnkiDeck> = listOf(deck)
        override fun fetchNoteTypes(): List<AnkiNoteType> = listOf(noteType)
        override fun isDuplicate(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ): Boolean = false

        override fun addNote(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            fieldsByName: Map<String, String>,
            tags: Set<String>,
            allowDupes: Boolean,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ): Boolean {
            lastFields = fieldsByName
            return true
        }

        override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null

        override fun addMediaFromBytes(bytes: ByteArray, preferredName: String, mimeType: String): String {
            lastMediaBytes = bytes
            lastMediaName = preferredName
            return """<img src="$preferredName">"""
        }

        override fun openNotes(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ): Boolean = false
    }
}
