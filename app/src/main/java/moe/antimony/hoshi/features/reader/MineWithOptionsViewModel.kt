package moe.antimony.hoshi.features.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.antimony.hoshi.features.anki.ExampleSentence
import moe.antimony.hoshi.features.anki.ExampleSentenceSource
import moe.antimony.hoshi.features.anki.ImageCandidate
import moe.antimony.hoshi.features.anki.ImageSearchSource
import moe.antimony.hoshi.features.anki.LocalExampleSentenceSource

internal data class MineWithOptionsUiState(
    val term: String = "",
    val loading: Boolean = false,
    val candidates: List<ExampleSentence> = emptyList(),
    val imageLoading: Boolean = false,
    val imageCandidates: List<ImageCandidate> = emptyList(),
)

@HiltViewModel
internal class MineWithOptionsViewModel : ViewModel {
    private val exampleSentenceSource: ExampleSentenceSource
    private val localSentenceSource: LocalExampleSentenceSource
    private val localDispatcher: CoroutineDispatcher
    private val imageSearchSource: ImageSearchSource
    private val tips: MiningTipRepository
    private val injectedScope: CoroutineScope?
    private val workScope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    @Inject
    constructor(
        exampleSentenceSource: ExampleSentenceSource,
        localSentenceSource: LocalExampleSentenceSource,
        imageSearchSource: ImageSearchSource,
        tips: MiningTipRepository,
    ) : super() {
        this.exampleSentenceSource = exampleSentenceSource
        this.localSentenceSource = localSentenceSource
        this.localDispatcher = Dispatchers.IO
        this.imageSearchSource = imageSearchSource
        this.tips = tips
        this.injectedScope = null
    }

    internal constructor(
        exampleSentenceSource: ExampleSentenceSource,
        imageSearchSource: ImageSearchSource,
        coroutineScope: CoroutineScope,
        localSentenceSource: LocalExampleSentenceSource = LocalExampleSentenceSource.None,
        localDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        tips: MiningTipRepository = MiningTipRepository.None,
    ) : super() {
        this.exampleSentenceSource = exampleSentenceSource
        this.localSentenceSource = localSentenceSource
        this.localDispatcher = localDispatcher
        this.imageSearchSource = imageSearchSource
        this.tips = tips
        this.injectedScope = coroutineScope
    }

    private val _uiState = MutableStateFlow(MineWithOptionsUiState())
    val uiState: StateFlow<MineWithOptionsUiState> = _uiState.asStateFlow()

    private val _showLongPressTip = MutableStateFlow(false)
    /** Set once ever, the first time the sheet opens: long press adds a card without it. */
    val showLongPressTip: StateFlow<Boolean> = _showLongPressTip.asStateFlow()
    private var tipChecked = false

    fun longPressTipShown() {
        _showLongPressTip.value = false
    }

    private var loadedTerm: String? = null
    private var loadJob: Job? = null
    // Bumped each start(); a late coroutine from a superseded term won't write stale state.
    private var generation = 0

    fun start(term: String) {
        if (!tipChecked) {
            tipChecked = true
            workScope.launch { if (runCatching { tips.consumeLongPressTip() }.getOrDefault(false)) _showLongPressTip.value = true }
        }
        if (loadedTerm == term) return
        loadedTerm = term
        loadJob?.cancel()
        val gen = ++generation
        _uiState.value = MineWithOptionsUiState(term = term, loading = true, imageLoading = true)
        loadJob = workScope.launch {
            launch {
                // The pack's sentences are on the device: show them at once, then append Massif's
                // (still marked loading) once the search answers.
                val local = loadOrEmpty { withContext(localDispatcher) { localSentenceSource.candidates(term) } }
                if (gen == generation && local.isNotEmpty()) _uiState.update { it.copy(candidates = local) }
                val remote = loadOrEmpty { exampleSentenceSource.candidates(term) }
                val merged = local + remote.filter { sentence -> local.none { it.text == sentence.text } }
                if (gen == generation) _uiState.update { it.copy(loading = false, candidates = merged) }
            }
            launch {
                val images = loadOrEmpty { imageSearchSource.candidates(term) }
                if (gen == generation) _uiState.update { it.copy(imageLoading = false, imageCandidates = images) }
            }
        }
    }

    // Cancellation must propagate (structured concurrency); other failures degrade to empty
    // so the loading flag still clears.
    private suspend fun <T> loadOrEmpty(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
}
