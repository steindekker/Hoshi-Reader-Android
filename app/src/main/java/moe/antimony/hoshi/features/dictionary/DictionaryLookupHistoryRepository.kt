package moe.antimony.hoshi.features.dictionary

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.withErrorDetails

private val Context.dictionaryLookupHistoryDataStore by preferencesDataStore(name = "dictionary-lookup-history")

internal fun Context.dictionaryLookupHistoryRepository(
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
): DictionaryLookupHistoryRepository =
    DictionaryLookupHistoryRepository(dictionaryLookupHistoryDataStore, applicationScope, ioDispatcher)

internal data class DictionaryLookupHistoryState(
    val entries: List<String> = emptyList(),
    val isLoaded: Boolean = false,
    val loadError: UiText? = null,
    val operationError: UiText? = null,
)

internal interface DictionaryLookupHistory {
    val state: StateFlow<DictionaryLookupHistoryState>
    fun record(text: String)
    fun delete(text: String)
    fun clear()
    fun retryLoad()
    fun dismissOperationError()
}

/** Original submissions only. Reader and popup navigation must never write to this owner. */
internal class DictionaryLookupHistoryRepository(
    private val dataStore: DataStore<Preferences>,
    applicationScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
) : DictionaryLookupHistory {
    private val mutableState = MutableStateFlow(DictionaryLookupHistoryState())
    override val state: StateFlow<DictionaryLookupHistoryState> = mutableState.asStateFlow()
    private val commands = Channel<Command>(Channel.UNLIMITED)

    init {
        commands.trySend(Command.Load)
        // Queue order also orders clear/delete against newly submitted queries. Activity or
        // ViewModel disposal does not cancel an accepted write.
        applicationScope.launch(ioDispatcher) {
            for (command in commands) {
                try {
                    val entries = if (command == Command.Load) {
                        readEntries(dataStore.data.first())
                    } else {
                        val saved = dataStore.edit { preferences ->
                            val current = readEntries(preferences)
                            val next = when (command) {
                                is Command.Record -> (listOf(command.text) + current.filterNot { it == command.text }).take(100)
                                is Command.Delete -> current.filterNot { it == command.text }
                                Command.Clear -> emptyList()
                                Command.Load -> error("Load does not write history")
                            }
                            preferences[EntriesKey] = Json.encodeToString(next)
                        }
                        readEntries(saved)
                    }
                    mutableState.update { it.copy(entries = entries, isLoaded = true, loadError = null) }
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    val message = UiText.Resource(command.errorResource).withErrorDetails(error)
                    mutableState.update {
                        if (command == Command.Load) it.copy(loadError = message)
                        else it.copy(operationError = message)
                    }
                }
            }
        }
    }

    override fun record(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) commands.trySend(Command.Record(trimmed))
    }

    override fun delete(text: String) { commands.trySend(Command.Delete(text)) }
    override fun clear() { commands.trySend(Command.Clear) }
    override fun retryLoad() { commands.trySend(Command.Load) }
    override fun dismissOperationError() { mutableState.update { it.copy(operationError = null) } }

    private fun readEntries(preferences: Preferences): List<String> =
        preferences[EntriesKey]?.let { Json.decodeFromString<List<String>>(it) }.orEmpty()

    private sealed interface Command {
        val errorResource: Int
        data object Load : Command { override val errorResource = R.string.dictionary_history_load_failed }
        data class Record(val text: String) : Command { override val errorResource = R.string.dictionary_history_save_failed }
        data class Delete(val text: String) : Command { override val errorResource = R.string.dictionary_history_delete_failed }
        data object Clear : Command { override val errorResource = R.string.dictionary_history_clear_failed }
    }

    private companion object {
        val EntriesKey = stringPreferencesKey("entries")
    }
}
