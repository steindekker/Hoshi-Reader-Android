package moe.antimony.hoshi.features.dictionary

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class DictionaryLookupHistoryRepositoryTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test fun preservesFullTextAndMovesExactDuplicatesToFront() = runTest {
        val repository = DictionaryLookupHistoryRepository(HistoryMemoryStore(), backgroundScope, StandardTestDispatcher(testScheduler))
        repository.record(" \n前𠮟る、 猫。\n次の行。 \n")
        repository.record("猫")
        repository.record("前𠮟る、 猫。\n次の行。")
        repository.record(" \n ")
        runCurrent()
        assertEquals(listOf("前𠮟る、 猫。\n次の行。", "猫"), repository.state.value.entries)
        assertTrue(repository.state.value.isLoaded)
    }

    @Test fun boundsHistoryAndSerializesDeleteClearAndNewSubmissions() = runTest {
        val repository = DictionaryLookupHistoryRepository(HistoryMemoryStore(), backgroundScope, StandardTestDispatcher(testScheduler))
        repeat(101) { repository.record("sentence $it") }
        runCurrent()
        assertEquals(100, repository.state.value.entries.size)
        assertEquals("sentence 100", repository.state.value.entries.first())
        assertEquals("sentence 1", repository.state.value.entries.last())
        repository.delete("sentence 50")
        repository.record("sentence 1")
        runCurrent()
        assertEquals(99, repository.state.value.entries.size)
        assertEquals("sentence 1", repository.state.value.entries.first())
        assertFalse(repository.state.value.entries.contains("sentence 50"))
        repository.clear()
        repository.record("after clear")
        runCurrent()
        assertEquals(listOf("after clear"), repository.state.value.entries)
    }

    @Test fun failedReadDoesNotOverwriteDataAndRetryRestoresHistory() = runTest {
        val store = HistoryMemoryStore(preferencesOf(stringPreferencesKey("entries") to "[\"saved\"]"))
        store.readFailure = IOException("cannot read history")
        val repository = DictionaryLookupHistoryRepository(store, backgroundScope, StandardTestDispatcher(testScheduler))
        repository.record("new")
        runCurrent()
        assertFalse(repository.state.value.isLoaded)
        assertNotNull(repository.state.value.loadError)
        assertEquals("[\"saved\"]", store.snapshot.value[stringPreferencesKey("entries")])
        store.readFailure = null
        repository.retryLoad()
        runCurrent()
        assertEquals(listOf("saved"), repository.state.value.entries)
        assertNull(repository.state.value.loadError)
    }

    @Test fun failedDeletionKeepsConfirmedEntriesAndCanBeRetried() = runTest {
        val store = HistoryMemoryStore()
        val repository = DictionaryLookupHistoryRepository(store, backgroundScope, StandardTestDispatcher(testScheduler))
        repository.record("saved")
        runCurrent()
        store.writeFailure = IOException("disk full", IOException("original cause"))
        repository.delete("saved")
        runCurrent()
        assertEquals(listOf("saved"), repository.state.value.entries)
        assertTrue(repository.state.value.operationError.toString().contains("original cause"))
        repository.clear()
        runCurrent()
        assertEquals(listOf("saved"), repository.state.value.entries)
        store.writeFailure = null
        repository.delete("saved")
        runCurrent()
        assertTrue(repository.state.value.entries.isEmpty())
    }

    @Test fun malformedHistoryIsNotSilentlyReplacedByNewLookup() = runTest {
        val store = HistoryMemoryStore(preferencesOf(stringPreferencesKey("entries") to "broken json"))
        val repository = DictionaryLookupHistoryRepository(store, backgroundScope, StandardTestDispatcher(testScheduler))
        repository.record("new")
        runCurrent()
        assertNotNull(repository.state.value.loadError)
        assertEquals("broken json", store.snapshot.value[stringPreferencesKey("entries")])
    }

    @Test fun queuedWritesPublishOnlyAfterDiskCommitAndSurviveCallerDisposal() = runTest {
        val store = HistoryMemoryStore()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val repository = DictionaryLookupHistoryRepository(store, backgroundScope, dispatcher)
        repository.record("saved")
        runCurrent()
        val gate = CompletableDeferred<Unit>()
        store.beforeWrite = { gate.await() }
        val callerJob = Job()
        val callerScope = CoroutineScope(callerJob + dispatcher)
        val caller = callerScope.launch { repository.record("accepted before close") }
        runCurrent()
        caller.join()
        callerJob.cancelAndJoin()
        assertEquals(listOf("saved"), repository.state.value.entries)
        repository.delete("saved")
        gate.complete(Unit)
        runCurrent()
        assertEquals(listOf("accepted before close"), repository.state.value.entries)
    }

    @Test fun restoresHistoryFromDiskAfterRepositoryAndScopeRestart() = runBlocking {
        val file = temporaryFolder.newFile("history.preferences_pb")
        val firstJob = Job()
        val firstScope = CoroutineScope(Dispatchers.IO + firstJob)
        val firstStore = PreferenceDataStoreFactory.create(scope = firstScope) { file }
        try {
            val repository = DictionaryLookupHistoryRepository(firstStore, firstScope, Dispatchers.IO)
            repository.record("完整句子。\n次行")
            repository.state.first { it.entries.isNotEmpty() }
        } finally { firstJob.cancelAndJoin() }
        val secondJob = Job()
        val secondScope = CoroutineScope(Dispatchers.IO + secondJob)
        val secondStore = PreferenceDataStoreFactory.create(scope = secondScope) { file }
        try {
            val restored = DictionaryLookupHistoryRepository(secondStore, secondScope, Dispatchers.IO)
            assertEquals(listOf("完整句子。\n次行"), restored.state.first { it.isLoaded }.entries)
        } finally { secondJob.cancelAndJoin() }
    }
}

internal class HistoryMemoryStore(initial: Preferences = emptyPreferences()) : DataStore<Preferences> {
    val snapshot = MutableStateFlow(initial)
    var readFailure: Throwable? = null
    var writeFailure: Throwable? = null
    var beforeWrite: suspend () -> Unit = {}
    override val data: Flow<Preferences> = flow {
        readFailure?.let { throw it }
        emit(snapshot.value)
    }
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        readFailure?.let { throw it }
        writeFailure?.let { throw it }
        beforeWrite()
        return transform(snapshot.value).also { snapshot.value = it }
    }
}
