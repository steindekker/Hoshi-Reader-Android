package moe.antimony.hoshi.features.anki

import android.content.ContentUris
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ichi2.anki.FlashCardsContract
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnkiTagsDeviceTest {
    @Test
    fun miningPersistsResolvedTagsInAnkiDroid() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("ankiTagSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val api = AndroidAnkiContentApi(context)
        assertTrue("AnkiDroid must be installed", api.isAvailable())
        val createdIds = mutableListOf<Long>()
        val trackingApi = object : AnkiContentApi by api {
            override fun addNote(modelId: Long, deckId: Long, fields: Array<String>, tags: Set<String>): Long? =
                api.addNote(modelId, deckId, fields, tags).also { id -> id?.let(createdIds::add) }
        }
        val backend = AnkiDroidBackendAdapter(trackingApi)
        val decks = backend.fetchDecks()
        val noteTypes = backend.fetchNoteTypes()
        val deck = decks.firstOrNull { it.name == "Default" } ?: decks.first()
        val noteType = noteTypes.firstOrNull { it.name == "Lapis" } ?: noteTypes.first()
        val marker = "hoshi_tag_test_${UUID.randomUUID().toString().replace("-", "")}"
        val format = AnkiCardFormat(
            id = "tag-test",
            name = "Tag test",
            selectedDeckId = deck.id,
            selectedNoteTypeId = noteType.id,
            fieldMappings = noteType.fields.associateWith { "{expression}" },
            tags = "hoshi book::{document-title}\n{expression} {unknown}",
        )
        val settings = object : AnkiSettingsRepository {
            override val settings = MutableStateFlow(AnkiSettings(cardFormats = listOf(format)))
            override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
                settings.value = transform(settings.value)
            }
        }
        val repository = AnkiRepository(
            context = context,
            backend = backend,
            settingsRepository = settings,
            localAudioRepository = LocalAudioRepository(context.filesDir),
        )
        try {
            assertTrue(repository.mineEntry(
                rawPayload = """{"expression":"$marker"}""",
                context = AnkiMiningContext(sentence = "", documentTitle = " My\tBook\n第三\u3000巻\u0085 "),
                decks = decks,
                noteTypes = noteTypes,
                formatId = format.id,
            ))
            assertEquals(1, createdIds.size)
            val uri = ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, createdIds.single())
            context.contentResolver.query(
                uri,
                arrayOf(FlashCardsContract.Note.TAGS),
                null,
                null,
                null,
            )!!.use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(
                    setOf("hoshi", "book::My_Book_第三_巻", marker),
                    cursor.getString(0).trim().split(Regex("\\s+")).toSet(),
                )
            }
        } finally {
            createdIds.forEach { id ->
                val uri = ContentUris.withAppendedId(FlashCardsContract.Note.CONTENT_URI, id)
                assertTrue("Remove only the note created by this test", context.contentResolver.delete(uri, null, null) > 0)
            }
        }
    }
}
