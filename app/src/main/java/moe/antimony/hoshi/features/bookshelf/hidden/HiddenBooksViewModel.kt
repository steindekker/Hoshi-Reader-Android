package moe.antimony.hoshi.features.bookshelf.hidden

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal data class HiddenBooksUiState(
    val hiddenBookIds: Set<String> = emptySet(),
    val showHiddenBooks: Boolean = false,
    val isLoaded: Boolean = false,
)

@HiltViewModel
internal class HiddenBooksViewModel internal constructor(
    private val repository: HiddenBooksRepository,
    injectedScope: CoroutineScope? = null,
) : ViewModel() {
    @Inject
    constructor(repository: HiddenBooksRepository) : this(repository, injectedScope = null)

    private val workScope: CoroutineScope = injectedScope ?: viewModelScope
    private val showHiddenBooks = MutableStateFlow(false)

    val uiState: StateFlow<HiddenBooksUiState> = combine(
        repository.hiddenBookIds,
        showHiddenBooks,
    ) { hiddenIds, showHidden ->
        HiddenBooksUiState(
            hiddenBookIds = hiddenIds,
            showHiddenBooks = showHidden,
            isLoaded = true,
        )
    }.stateIn(
        scope = workScope,
        started = SharingStarted.Eagerly,
        initialValue = HiddenBooksUiState(),
    )

    fun toggleShowHidden() {
        showHiddenBooks.update { !it }
    }

    fun setHidden(bookId: String, hidden: Boolean) {
        workScope.launch {
            repository.setHidden(setOf(bookId), hidden)
        }
    }

    fun setHidden(bookIds: Set<String>, hidden: Boolean) {
        if (bookIds.isEmpty()) return
        workScope.launch {
            repository.setHidden(bookIds, hidden)
        }
    }
}
