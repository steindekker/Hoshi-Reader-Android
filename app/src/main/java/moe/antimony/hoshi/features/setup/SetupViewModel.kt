package moe.antimony.hoshi.features.setup

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.ui.UiText

internal enum class SetupPhase { Welcome, Working, Finished }

internal data class SetupWelcomeUiState(
    val visible: Boolean = false,
    val phase: SetupPhase = SetupPhase.Welcome,
    /** The catalog has been read (or failed to load), so the start button can act. */
    val ready: Boolean = false,
    val downloadBytes: Long = 0L,
    val audioAvailable: Boolean = false,
    val audioBytes: Long = 0L,
    val hasAudioServer: Boolean = false,
    val offlineAudio: Boolean = false,
    val ankiDroidInstalled: Boolean = false,
    val ankiResult: SetupAnkiResult? = null,
    val label: UiText? = null,
    val percent: Int? = null,
    val importing: Boolean = false,
    val failed: Boolean = false,
)

/** Hands the offline-audio download to WorkManager. */
internal class SetupAudioScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun enqueue() = enqueueSetupAudioDownload(context)
}

@HiltViewModel
internal class SetupViewModel @Inject constructor(
    private val repository: SetupRepository,
    private val anki: SetupAnkiConnector,
    private val audioScheduler: SetupAudioScheduler,
) : ViewModel() {
    private val _uiState = MutableStateFlow(SetupWelcomeUiState())
    val uiState: StateFlow<SetupWelcomeUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            // A build without a setup catalog has nothing to offer.
            if (SetupCatalog.DefaultUrl.isBlank() || repository.completed.first()) return@launch
            _uiState.update { it.copy(visible = true, ankiDroidInstalled = anki.isAnkiDroidAvailable()) }
            loadCatalog()
        }
    }

    private suspend fun loadCatalog() {
        val manifest = try {
            repository.peek()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            runCatching { Log.w("Setup", "Unable to read the setup catalog", error) }
            null
        }
        val downloadBytes = manifest?.let { it.downloadSize + repository.packSize(it) } ?: 0L
        _uiState.update {
            it.copy(
                ready = true,
                downloadBytes = downloadBytes,
                audioAvailable = manifest?.audio != null,
                audioBytes = manifest?.audio?.size ?: 0L,
                hasAudioServer = manifest?.audioServer != null,
            )
        }
    }

    fun setOfflineAudio(enabled: Boolean) {
        _uiState.update { state ->
            if (state.phase != SetupPhase.Welcome || !state.audioAvailable) state else state.copy(offlineAudio = enabled)
        }
    }

    /** Runs setup. [ankiPermissionGranted] is checked by the screen after it asked for the permission. */
    fun start(ankiPermissionGranted: Boolean) {
        val state = _uiState.value
        if (!state.ready || state.phase == SetupPhase.Working) return
        viewModelScope.launch {
            _uiState.update {
                it.copy(phase = SetupPhase.Working, failed = false, label = null, percent = null, importing = false)
            }
            try {
                repository.run { progress ->
                    _uiState.update {
                        it.copy(label = progress.label, percent = progress.percent, importing = progress.importing)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _uiState.update { it.copy(phase = SetupPhase.Welcome, failed = true) }
                return@launch
            }
            val ankiResult = when {
                !state.ankiDroidInstalled -> SetupAnkiResult.Unavailable
                ankiPermissionGranted -> anki.connect()
                else -> SetupAnkiResult.Failed
            }
            if (state.offlineAudio) audioScheduler.enqueue()
            _uiState.update { it.copy(phase = SetupPhase.Finished, ankiResult = ankiResult) }
        }
    }

    fun dismiss() {
        _uiState.update { it.copy(visible = false) }
    }
}
