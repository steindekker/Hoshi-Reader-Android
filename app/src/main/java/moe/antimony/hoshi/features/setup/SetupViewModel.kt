package moe.antimony.hoshi.features.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.ui.UiText

internal data class SetupWelcomeUiState(
    val visible: Boolean = false,
    val ready: Boolean = false,
    val audioAvailable: Boolean = false,
    val includeAudio: Boolean = false,
    val working: Boolean = false,
    val label: UiText? = null,
    val percent: Int? = null,
    val importing: Boolean = false,
    val failed: Boolean = false,
)

@HiltViewModel
internal class SetupViewModel @Inject constructor(
    private val repository: SetupRepository,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SetupWelcomeUiState())
    val uiState: StateFlow<SetupWelcomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            if (repository.completed.first()) return@launch
            _uiState.update { it.copy(visible = true) }
            val manifest = try {
                repository.peek()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                null
            }
            _uiState.update { it.copy(ready = true, audioAvailable = manifest?.audio != null) }
        }
    }

    fun setIncludeAudio(include: Boolean) {
        _uiState.update { state ->
            if (state.working || !state.audioAvailable) state else state.copy(includeAudio = include)
        }
    }

    fun start() {
        val includeAudio = _uiState.value.includeAudio
        if (!_uiState.value.ready || _uiState.value.working) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(working = true, failed = false, label = null, percent = null, importing = false)
            }
            try {
                repository.run(includeAudio) { progress ->
                    _uiState.update {
                        it.copy(
                            label = progress.label,
                            percent = progress.percent,
                            importing = progress.importing,
                        )
                    }
                }
                _uiState.update { it.copy(visible = false, working = false) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(working = false, failed = true) }
            }
        }
    }
}
