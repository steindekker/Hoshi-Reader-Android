package moe.antimony.hoshi.features.anki

import android.content.ContextWrapper
import java.nio.file.Files
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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

    private fun cue(id: Long) = AnkiLiveCueRef("pc", "mpv", "t1", id)

    @Test
    fun animatedClipIsAttachedAsWebp() = runBlocking {
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Success(byteArrayOf(82, 73), "image/webp"))
        repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(cue(5), animated = true)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(source.requests.single().animated)
        assertTrue(backend.lastMediaName.endsWith(".webp"))
        assertEquals("<img src=\"${backend.lastMediaName}\">", backend.lastFields["Picture"])
    }

    @Test
    fun attachesFetchedScreenshotForImageHandlebarAndCleansUpTempFile() = runBlocking {
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Success(byteArrayOf(9, 8, 7), "image/jpeg"))
        val result = repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(cue(42))),
            decks = emptyList(),
            noteTypes = emptyList(),
        )

        assertTrue(result.added)
        assertTrue(result.warnings.isEmpty())
        assertEquals(listOf(42L), source.requests.map { it.cue.cueId })
        assertEquals(listOf(9, 8, 7), backend.lastMediaBytes.map { it.toInt() })
        assertTrue(backend.lastMediaName.startsWith("hoshi_screenshot_"))
        assertEquals("<img src=\"${backend.lastMediaName}\">", backend.lastFields["Picture"])
        assertTrue(ankiCacheDir.resolve("anki-media").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun screenshotWinsOverCover() = runBlocking {
        val cover = Files.createTempFile("cover", ".png").also { Files.write(it, byteArrayOf(1)) }
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Success(byteArrayOf(5), "image/jpeg"))
        repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(
                sentence = "本を読む",
                coverPath = cover.toString(),
                screenshot = AnkiScreenshotRequest(cue(1)),
            ),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(backend.lastFields.getValue("Picture").contains("hoshi_screenshot_"))
    }

    @Test
    fun failedScreenshotStillAddsCardWithWarning() = runBlocking {
        val warning = UiText.Resource(R.string.live_picture_failed, UiText.Resource(R.string.live_media_reason_unreachable, "pc"))
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Failure(warning))
        val result = repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(cue(3))),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(result.added)
        assertEquals(listOf(warning), result.warnings)
        assertEquals(mapOf("Expression" to "読む"), backend.lastFields)
    }

    @Test
    fun throwingSourceIsReportedAsWarning() = runBlocking {
        val source = object : AnkiLiveMediaSource {
            override suspend fun fetchScreenshot(request: AnkiScreenshotRequest): AnkiLiveMediaResult = error("boom")
            override suspend fun fetchSentenceAudio(cue: AnkiLiveCueRef): AnkiLiveMediaResult = error("boom")
        }
        val result = repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(cue(3))),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(result.added)
        assertEquals(
            listOf<UiText>(UiText.Resource(R.string.live_picture_failed, UiText.Resource(R.string.live_media_reason_invalid))),
            result.warnings,
        )
    }

    @Test
    fun usesFetchedPreviewWithoutContactingTheDeck() = runBlocking {
        val preview = Files.createTempFile("preview", ".jpg").also { Files.write(it, byteArrayOf(4, 4)) }
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Failure(UiText.Literal("unused")))
        val result = repository(source).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(
                sentence = "本を読む",
                screenshot = AnkiScreenshotRequest(cue(3), localPath = preview.toString()),
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
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Success(byteArrayOf(1), "image/jpeg"))
        repository(source, mappings = mapOf("Expression" to "{expression}")).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", screenshot = AnkiScreenshotRequest(cue(3))),
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
                    screenshot = AnkiScreenshotRequest(cue(3)),
                ),
                decks = emptyList(),
                noteTypes = emptyList(),
            )
        }
        assertTrue(source.requests.isEmpty())
        assertFalse(backend.lastFields.containsKey("Picture"))
    }

    @Test
    fun attachesLiveSentenceAudioAndReportsItsFailure() = runBlocking {
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Success(byteArrayOf(73, 68, 51), "audio/mpeg"))
        val mappings = mapOf("Expression" to "{expression}", "Picture" to "{sentence-audio}")
        val result = repository(source, mappings).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", sentenceAudio = cue(8)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(result.added)
        assertEquals(listOf(8L), source.audioRequests.map { it.cueId })
        assertTrue(backend.lastMediaName.startsWith("hoshi_sentence_") && backend.lastMediaName.endsWith(".mp3"))
        assertEquals(listOf(73, 68, 51), backend.lastMediaBytes.map { it.toInt() })

        val warning = UiText.Resource(R.string.live_audio_failed, UiText.Resource(R.string.live_media_reason_track_changed))
        val failing = repository(RecordingScreenshotSource(AnkiLiveMediaResult.Failure(warning)), mappings).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", sentenceAudio = cue(8)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(failing.added)
        assertEquals(listOf<UiText>(warning), failing.warnings)
    }

    @Test
    fun addedCardsArePublishedWithTheirTerm() = runBlocking {
        val events = AnkiCardAddedEvents()
        val published = mutableListOf<AnkiCardAdded>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { events.added.collect { published += it } }
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Failure(UiText.Resource(R.string.live_card_failed)))
        val result = repository(source, mapOf("Expression" to "{expression}"), events).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む"),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        yield()
        collector.cancel()
        assertTrue(result.added)
        assertEquals(listOf(AnkiCardAdded("読む")), published)
    }

    @Test
    fun sentenceAudioIsNotFetchedWhenUnreferenced() = runBlocking {
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Success(byteArrayOf(1), "audio/mpeg"))
        repository(source, mapOf("Expression" to "{expression}", "Sentence" to "{sentence}")).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", sentenceAudio = cue(8)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertTrue(source.audioRequests.isEmpty())
    }

    @Test
    fun legacySasayakiAudioMappingFetchesLiveSentenceAudio() = runBlocking {
        val source = RecordingScreenshotSource(AnkiLiveMediaResult.Success(byteArrayOf(1), "audio/mpeg"))
        repository(source, mapOf("Expression" to "{expression}", "Picture" to "{sasayaki-audio}")).mineEntryWithResult(
            rawPayload = """{"expression":"読む"}""",
            context = AnkiMiningContext(sentence = "本を読む", sentenceAudio = cue(8)),
            decks = emptyList(),
            noteTypes = emptyList(),
        )
        assertEquals(listOf(cue(8)), source.audioRequests)
    }

    @Test
    fun sentenceAudioPrefersTheLiveCueThenSasayaki() {
        fun render(context: AnkiMiningContext, template: String = "{sentence-audio}") = AnkiHandlebarRenderer.render(
            template = template,
            payload = AnkiMiningPayload.fromJson("""{"expression":"本"}"""),
            context = context,
        )
        val both = AnkiMiningContext(sentence = "s", sasayakiAudioPath = "[sound:cue.m4a]", sentenceAudioPath = "[sound:live.mp3]")
        assertEquals("[sound:live.mp3]", render(both))
        assertEquals("[sound:cue.m4a]", render(both.copy(sentenceAudioPath = null)))
        // {sasayaki-audio} prefers the Sasayaki cue and falls back to the Live clip.
        assertEquals("[sound:cue.m4a]", render(both, "{sasayaki-audio}"))
        assertEquals("[sound:live.mp3]", render(both.copy(sasayakiAudioPath = null), "{sasayaki-audio}"))
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
        source: AnkiLiveMediaSource,
        mappings: Map<String, String> = mapOf("Expression" to "{expression}", "Picture" to "{image}"),
        cardAddedEvents: AnkiCardAddedEvents = AnkiCardAddedEvents(),
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
        liveMediaSource = source,
        cardAddedEvents = cardAddedEvents,
    )

    private class RecordingScreenshotSource(private val result: AnkiLiveMediaResult) : AnkiLiveMediaSource {
        val requests = mutableListOf<AnkiScreenshotRequest>()
        val audioRequests = mutableListOf<AnkiLiveCueRef>()

        override suspend fun fetchScreenshot(request: AnkiScreenshotRequest): AnkiLiveMediaResult {
            requests += request
            return result
        }

        override suspend fun fetchSentenceAudio(cue: AnkiLiveCueRef): AnkiLiveMediaResult {
            audioRequests += cue
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
