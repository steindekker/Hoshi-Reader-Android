package moe.antimony.hoshi.features.setup

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.di.IoDispatcher
import moe.antimony.hoshi.features.anki.AnkiBackend
import moe.antimony.hoshi.features.anki.AnkiBackendKind
import moe.antimony.hoshi.features.anki.AnkiDeck
import moe.antimony.hoshi.features.anki.AnkiFieldTemplates
import moe.antimony.hoshi.features.anki.AnkiNoteType
import moe.antimony.hoshi.features.anki.AnkiNoteTypeSpec
import moe.antimony.hoshi.features.anki.AnkiSettings
import moe.antimony.hoshi.features.anki.AnkiSettingsRepository
import moe.antimony.hoshi.features.anki.HoshiNoteType
import moe.antimony.hoshi.features.anki.selectDeckAfterFetch

internal enum class SetupAnkiResult { Connected, Unavailable, Failed }

/**
 * Connects the first-run card format to AnkiDroid once the database permission is granted:
 * finds the shipped Hoshi note type (creating it when the collection has none) and stores
 * the real deck and note-type ids. Existing field mappings are kept, so the user never has to
 * remap after setup. AnkiConnect users are left alone.
 */
@Singleton
internal class SetupAnkiConnector(
    private val backend: AnkiBackend,
    private val settings: AnkiSettingsRepository,
    private val spec: () -> AnkiNoteTypeSpec,
    private val ioDispatcher: CoroutineDispatcher,
) {
    @Inject
    constructor(
        @ApplicationContext context: Context,
        backend: AnkiBackend,
        settings: AnkiSettingsRepository,
        @IoDispatcher ioDispatcher: CoroutineDispatcher,
    ) : this(
        backend = backend,
        settings = settings,
        spec = {
            HoshiNoteType.spec { path -> context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) } }
        },
        ioDispatcher = ioDispatcher,
    )

    fun isAnkiDroidAvailable(): Boolean = backend.isAvailable()

    suspend fun connect(): SetupAnkiResult = withContext(ioDispatcher) {
        if (settings.settings.first().backendKind != AnkiBackendKind.AnkiDroid) return@withContext SetupAnkiResult.Connected
        if (!backend.isAvailable()) return@withContext SetupAnkiResult.Unavailable
        try {
            val decks = backend.fetchDecks()
            if (decks.isEmpty()) return@withContext SetupAnkiResult.Failed
            var noteTypes = backend.fetchNoteTypes()
            val deck = selectDeckAfterFetch(decks, settings.settings.first().withSeamlessFormatIfUnmapped())
            // A collection that has not synced the rename yet still has the old name; reuse it.
            val noteType = noteTypes.firstOrNull { it.name == AnkiFieldTemplates.HoshiNoteType }
                ?: noteTypes.firstOrNull { it.name == AnkiFieldTemplates.LegacyHoshiNoteType }
                ?: backend.createNoteType(spec(), deck)?.also { noteTypes = noteTypes + it }
                ?: return@withContext SetupAnkiResult.Failed
            settings.update { it.withConnectedNoteType(decks, noteTypes, deck, noteType) }
            SetupAnkiResult.Connected
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runCatching { Log.w("SetupAnki", "Unable to connect AnkiDroid", error) }
            SetupAnkiResult.Failed
        }
    }
}

/**
 * Points every format that mines into [noteType] (or has no note type yet) at the fetched ids.
 * Mappings that already target the note type stay; an unmapped format gets the shipped defaults.
 */
internal fun AnkiSettings.withConnectedNoteType(
    decks: List<AnkiDeck>,
    noteTypes: List<AnkiNoteType>,
    deck: AnkiDeck,
    noteType: AnkiNoteType,
): AnkiSettings {
    val formats = withSeamlessFormatIfUnmapped().cardFormats.map { format ->
        val targets = format.selectedNoteTypeName == null ||
            format.selectedNoteTypeName == noteType.name ||
            (AnkiFieldTemplates.isHoshiNoteType(format.selectedNoteTypeName) && AnkiFieldTemplates.isHoshiNoteType(noteType.name))
        if (!targets) return@map format
        val formatDeck = decks.firstOrNull { it.id == format.selectedDeckId }
            ?: format.selectedDeckName?.let { name -> decks.firstOrNull { it.name == name } }
            ?: deck
        format.copy(
            selectedDeckId = formatDeck.id,
            selectedDeckName = formatDeck.name,
            selectedNoteTypeId = noteType.id,
            selectedNoteTypeName = noteType.name,
            fieldMappings = AnkiFieldTemplates.applyDefaultsIfUnmapped(noteType, format.fieldMappings),
        )
    }
    val first = formats.first()
    return copy(
        cardFormats = formats,
        selectedDeckId = first.selectedDeckId,
        selectedDeckName = first.selectedDeckName,
        selectedNoteTypeId = first.selectedNoteTypeId,
        selectedNoteTypeName = first.selectedNoteTypeName,
        fieldMappings = first.fieldMappings,
        availableDecks = decks,
        availableNoteTypes = noteTypes,
    )
}
