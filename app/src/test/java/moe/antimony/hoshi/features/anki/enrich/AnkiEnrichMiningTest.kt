package moe.antimony.hoshi.features.anki.enrich

import android.content.ContextWrapper
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

class AnkiEnrichMiningTest {
    private val pack = FakeEnrichPackSource(
        listOf(
            Triple(
                "逃れる",
                "のがれる",
                mapOf(
                    "ae-definition" to """<div class="aa-jj" data-dict="sankoku8">にげ出す。{x}</div>""",
                    "ae-definition-source" to "三省堂",
                    "ae-pitch" to "<svg>のがれる</svg>",
                    "ae-frequency" to "2361",
                    "ae-tags" to "ankienrich::enriched ankienrich::examples",
                ),
            ),
        ),
    )

    @Test
    fun minedKinomotoCardCarriesPackValuesVerbatim() = runBlocking {
        val backend = RecordingBackend()
        val repository = repository(backend) { expression, reading, _ -> pack.lookup(expression, reading) }

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
        assertEquals(setOf("hoshi", "ankienrich::enriched", "ankienrich::examples"), backend.lastTags)
    }

    @Test
    fun termMissingFromPackFallsBackToRenderedDefault() = runBlocking {
        val backend = RecordingBackend()
        val repository = repository(backend) { expression, reading, _ -> pack.lookup(expression, reading) }

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
    fun withoutAPackEnrichHandlebarsAreLeftEmpty() = runBlocking {
        val backend = RecordingBackend()
        val repository = repository(backend, AnkiFieldEnricher.None)

        assertTrue(repository.mineEntry(
            rawPayload = """{"expression":"逃れる","reading":"のがれる"}""",
            context = AnkiMiningContext(sentence = ""),
            decks = emptyList(),
            noteTypes = emptyList(),
        ))

        assertEquals(mapOf("Term" to "逃れる"), backend.lastFields)
        assertEquals(setOf("hoshi"), backend.lastTags)
    }

    private fun repository(backend: AnkiBackend, enricher: AnkiFieldEnricher): AnkiRepository {
        val noteType = AnkiNoteType(20L, "jidoujisho Kinomoto", KinomotoFields)
        val format = AnkiCardFormat(
            id = "kinomoto",
            name = "Kinomoto",
            selectedDeckId = 10L,
            selectedNoteTypeId = noteType.id,
            fieldMappings = mapOf(
                "Term" to "{expression}",
                "Meaning" to "{ae-definition}",
                "Definition Source" to "{ae-definition-source}",
                "Pitch Accent" to "{ae-pitch}",
                "Frequency" to "{ae-frequency}",
            ),
            tags = "hoshi {ae-tags}",
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
        val KinomotoFields = listOf("Term", "Meaning", "Definition Source", "Pitch Accent", "Frequency")
    }
}
