package moe.antimony.hoshi.features.anki

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import moe.antimony.hoshi.features.audio.LocalAudioRepository
import moe.antimony.hoshi.features.audio.LocalAudioResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AnkiLocalAudioDeviceTest {
    @Test
    fun miningUsesImportedLocalAudioDatabaseForAudioField() = runBlocking {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val localAudioRepository = LocalAudioRepository(appContext.filesDir)
        assumeTrue("Local audio database is not imported on this device.", localAudioRepository.dbFile.isFile)

        val localAudio = firstLocalAudioFile(localAudioRepository.dbFile)
        assumeTrue("Local audio database has no mp3 rows to validate.", localAudio != null)

        val backend = FakeAnkiBackend()
        val repository = AnkiRepository(
            context = appContext,
            backend = backend,
            settingsRepository = FakeAnkiSettingsRepository(
                AnkiSettings(
                    selectedDeckId = 1L,
                    selectedDeckName = "Mining",
                    selectedNoteTypeId = 7L,
                    selectedNoteTypeName = "Lapis",
                    fieldMappings = mapOf(
                        "Expression" to "{expression}",
                        "Audio" to "{audio}",
                    ),
                ),
            ),
            localAudioRepository = localAudioRepository,
        )
        val audioUrl = LocalAudioResolver.audioUrl(localAudio!!.source, localAudio.file)
        val payload = """{"expression":"食べる","audio":"$audioUrl"}"""

        val added = repository.mineEntry(
            rawPayload = payload,
            context = AnkiMiningContext(sentence = "パンを食べる。"),
            decks = listOf(AnkiDeck(1L, "Mining")),
            noteTypes = listOf(AnkiNoteType(7L, "Lapis", listOf("Expression", "Audio"))),
        )

        assertTrue(added)
        assertEquals("食べる", backend.addedFields?.get("Expression"))
        val audioField = backend.addedFields?.get("Audio")
        assertNotNull(audioField)
        assertTrue(audioField!!, audioField.startsWith("[sound:hoshi_audio_"))
        assertTrue(audioField, audioField.endsWith(".mp3]"))
        assertEquals("audio/mpeg", backend.addedMediaMimeType)
    }

    private fun firstLocalAudioFile(dbFile: java.io.File): DeviceLocalAudioFile? = runCatching {
        SQLiteDatabase.openDatabase(
            dbFile.absolutePath,
            null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        ).use { db ->
            db.rawQuery(
                """
                SELECT e.source, e.file
                FROM entries e
                JOIN android a ON a.source = e.source AND a.file = e.file
                WHERE e.file LIKE '%.mp3'
                LIMIT 1
                """.trimIndent(),
                null,
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                DeviceLocalAudioFile(
                    source = cursor.getString(0),
                    file = cursor.getString(1),
                )
            }
        }
    }.getOrNull()

    private data class DeviceLocalAudioFile(
        val source: String,
        val file: String,
    )

    private class FakeAnkiSettingsRepository(settings: AnkiSettings) : AnkiSettingsRepository {
        private val state = MutableStateFlow(settings)

        override val settings = state

        override suspend fun update(transform: (AnkiSettings) -> AnkiSettings) {
            state.value = transform(state.value)
        }
    }

    private class FakeAnkiBackend : AnkiBackend {
        var addedFields: Map<String, String>? = null
            private set
        var addedMediaMimeType: String? = null
            private set

        override fun isAvailable(): Boolean = true

        override fun fetchDecks(): List<AnkiDeck> = listOf(AnkiDeck(1L, "Mining"))

        override fun fetchNoteTypes(): List<AnkiNoteType> =
            listOf(AnkiNoteType(7L, "Lapis", listOf("Expression", "Audio")))

        override fun isDuplicate(modelId: Long, key: String): Boolean = false

        override fun addNote(
            deck: AnkiDeck,
            noteType: AnkiNoteType,
            fieldsByName: Map<String, String>,
            tags: Set<String>,
            allowDupes: Boolean,
        ): Boolean {
            addedFields = fieldsByName
            return true
        }

        override fun addMediaFromUri(uriString: String, preferredName: String, mimeType: String): String {
            addedMediaMimeType = mimeType
            return "[sound:$preferredName]"
        }
    }
}
