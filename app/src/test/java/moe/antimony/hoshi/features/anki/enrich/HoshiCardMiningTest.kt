package moe.antimony.hoshi.features.anki.enrich

import android.content.ContextWrapper
import hoshi.card.CardEnricher
import hoshi.card.CardEnrichment
import hoshi.card.HoshiCardEnricher
import java.nio.file.Files
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.anki.AnkiBackend
import moe.antimony.hoshi.features.anki.AnkiCardAddedEvents
import moe.antimony.hoshi.features.anki.AnkiCardFormat
import moe.antimony.hoshi.features.anki.AnkiDeck
import moe.antimony.hoshi.features.anki.AnkiDuplicateScope
import moe.antimony.hoshi.features.anki.AnkiMiningContext
import moe.antimony.hoshi.features.anki.AnkiNoteType
import moe.antimony.hoshi.features.anki.AnkiRepository
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import moe.antimony.hoshi.features.anki.AnkiLiveCueRef
import moe.antimony.hoshi.features.anki.AnkiLiveMediaResult
import moe.antimony.hoshi.features.anki.AnkiLiveMediaSource
import moe.antimony.hoshi.features.anki.AnkiScreenshotRequest
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mining through [AnkiRepository] with hoshi-card values: they survive the app's renderer verbatim. */
class HoshiCardMiningTest {
    /** A pack with one row for 逃れる, and the format-2 default for every other term. */
    private val pack = CardEnricher { expression, reading, _, _ ->
        val defaults = mapOf("definition" to "{glossary-first}")
        if (expression == "逃れる" && reading == "のがれる") {
            CardEnrichment(
                mapOf(
                    "definition" to """<div class="aa-jj" data-dict="sankoku8">にげ出す。{x}</div>""",
                    "definition-source" to "三省堂",
                    "pitch-graph" to "<svg>のがれる</svg>",
                    "frequency-rank" to "2361",
                ),
                defaults,
            )
        } else {
            CardEnrichment(emptyMap(), defaults)
        }
    }

    @Test
    fun minedHoshiCardCarriesPackValuesVerbatim() = runBlocking {
        val backend = RecordingBackend()
        val repository = repository(backend, pack)

        assertTrue(repository.mineEntry(
            rawPayload = """{"expression":"逃れる","reading":"のがれる","glossaryFirst":"mined"}""",
            context = AnkiMiningContext(sentence = "難を逃れる。"),
            decks = emptyList(),
            noteTypes = emptyList(),
        ))

        assertEquals("""<div class="aa-jj" data-dict="sankoku8">にげ出す。{x}</div>""", backend.lastFields["Meaning"])
        assertEquals("三省堂", backend.lastFields["Definition Source"])
        assertEquals("<svg>のがれる</svg>", backend.lastFields["Pitch Accent"])
        assertEquals("2361", backend.lastFields["Frequency"])
        assertEquals(setOf("hoshi"), backend.lastTags)
    }

    @Test
    fun termMissingFromPackFallsBackToRenderedDefault() = runBlocking {
        val backend = RecordingBackend()
        val repository = repository(backend, pack)

        assertTrue(repository.mineEntry(
            rawPayload = """{"expression":"ぴえん","reading":"ぴえん","glossaryFirst":"crying face"}""",
            context = AnkiMiningContext(sentence = "ぴえん"),
            decks = emptyList(),
            noteTypes = emptyList(),
        ))

        assertEquals("crying face", backend.lastFields["Meaning"])
        assertEquals(null, backend.lastFields["Pitch Accent"])
        assertEquals(setOf("hoshi"), backend.lastTags)
    }

    @Test
    fun sentenceHandlebarGetsTheEnrichersMarkupOfTheMiningSentence() = runBlocking {
        val backend = RecordingBackend()
        val repository = repository(backend) { expression, reading, sentence, templates ->
            pack.enrich(expression, reading, sentence, templates)
                .with("sentence-pitch", "{[難|なん];1}を{[逃|のが]れる;3}。|$sentence")
        }

        assertTrue(repository.mineEntry(
            rawPayload = """{"expression":"逃れる","reading":"のがれる","matched":"逃れる"}""",
            context = AnkiMiningContext(sentence = "難を逃れる。"),
            decks = emptyList(),
            noteTypes = emptyList(),
        ))

        // The value is filled after rendering, so its JRP braces are never parsed as handlebars.
        assertEquals("{[難|なん];1}を{[逃|のが]れる;3}。|難を逃れる。", backend.lastFields["Sentence"])
    }

    @Test
    fun withoutAPackTheDictionariesFillTheFields() = runBlocking {
        val backend = RecordingBackend()
        val repository = repository(backend, HoshiCardEnricher(pack = null, sentenceModel = null))

        assertTrue(repository.mineEntry(
            rawPayload = """{"expression":"逃れる","reading":"のがれる","glossaryFirst":"to escape"}""",
            context = AnkiMiningContext(sentence = "難を逃れる。"),
            decks = emptyList(),
            noteTypes = emptyList(),
        ))

        assertEquals("to escape", backend.lastFields["Meaning"])
        assertEquals("難を逃れる。", backend.lastFields["Sentence"])
        assertEquals(setOf("hoshi"), backend.lastTags)
    }

    @Test
    fun withoutAnEnricherHoshiCardFieldsAreLeftEmpty() = runBlocking {
        val backend = RecordingBackend()
        val repository = repository(backend, CardEnricher.None)

        assertTrue(repository.mineEntry(
            rawPayload = """{"expression":"逃れる","reading":"のがれる"}""",
            context = AnkiMiningContext(sentence = ""),
            decks = emptyList(),
            noteTypes = emptyList(),
        ))

        assertEquals(mapOf("Term" to "逃れる"), backend.lastFields)
        assertEquals(setOf("hoshi"), backend.lastTags)
    }

    private fun repository(backend: AnkiBackend, enricher: CardEnricher): AnkiRepository {
        val noteType = AnkiNoteType(20L, "Hoshi", KinomotoFields)
        val format = AnkiCardFormat(
            id = "kinomoto",
            name = "Kinomoto",
            selectedDeckId = 10L,
            selectedNoteTypeId = noteType.id,
            fieldMappings = mapOf(
                "Term" to "{expression}",
                "Meaning" to "{definition}",
                "Definition Source" to "{definition-source}",
                "Pitch Accent" to "{pitch-graph}",
                "Frequency" to "{frequency-rank}",
                "Sentence" to "{sentence-pitch}",
            ),
            tags = "hoshi",
        )
        (backend as RecordingBackend).noteTypes = listOf(noteType)
        return AnkiRepository(
            context = ContextWrapper(null),
            backend = backend,
            settingsRepository = InMemorySettings(AnkiSettings(cardFormats = listOf(format))),
            localAudioRepository = LocalAudioRepository(Files.createTempDirectory("hoshi-enrich-test").toFile()),
            ankiConnectBackendFactory = { _, _ -> backend },
            loadDictionaryMedia = { null },
            loadTermDictionaries = { emptyList() },
            liveMediaSource = NoLiveMedia,
            cardAddedEvents = AnkiCardAddedEvents(),
            ankiFieldEnricher = enricher,
        )
    }

    private object NoLiveMedia : AnkiLiveMediaSource {
        override suspend fun fetchScreenshot(request: AnkiScreenshotRequest): AnkiLiveMediaResult = error("unused")
        override suspend fun fetchSentenceAudio(cue: AnkiLiveCueRef): AnkiLiveMediaResult = error("unused")
    }

    private class InMemorySettings(initial: AnkiSettings) : AnkiSettingsRepository {
        override val settings: Flow<AnkiSettings> = MutableStateFlow(initial)
        override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) = Unit
    }

    private class RecordingBackend : AnkiBackend {
        var noteTypes: List<AnkiNoteType> = emptyList()
        var lastFields: Map<String, String> = emptyMap()
        var lastTags: Set<String> = emptySet()

        override fun isAvailable() = true
        override fun fetchDecks() = listOf(AnkiDeck(10L, "Mining"))
        override fun fetchNoteTypes() = noteTypes
        override fun isDuplicate(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ) = false
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
            lastTags = tags
            return true
        }
        override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String? = null
        override fun addMediaFromBytes(bytes: ByteArray, preferredName: String, mimeType: String): String? = null
        override fun sync() = true
        override fun openNotes(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            key: String,
            duplicateScope: AnkiDuplicateScope,
            checkDuplicatesAcrossAllModels: Boolean,
        ) = true
    }

    private companion object {
        val KinomotoFields = listOf("Term", "Meaning", "Definition Source", "Pitch Accent", "Frequency", "Sentence")
    }
}
