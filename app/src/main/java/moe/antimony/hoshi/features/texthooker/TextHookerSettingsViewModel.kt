package moe.antimony.hoshi.features.texthooker

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText

internal data class TextHookerSettingsForm(
    val host: String = "",
    val port: String = "",
    val token: String = "",
)

internal data class TextHookerSettingsUiState(
    val loaded: Boolean = false,
    val saved: TextHookerSettings = TextHookerSettings(),
    val form: TextHookerSettingsForm = TextHookerSettingsForm(),
    val isTesting: Boolean = false,
    val testMessage: UiText? = null,
    val testSucceeded: Boolean = false,
) {
    val hostError: TextHookerSettingsError? get() = validateTextHookerHost(normalizeTextHookerHost(form.host))
    val portError: TextHookerSettingsError? get() = validateTextHookerPort(form.port)
    val tokenError: TextHookerSettingsError? get() = validateTextHookerToken(form.token)
    val isValid: Boolean get() = hostError == null && portError == null && tokenError == null

    /** The form as settings, or null while it has validation errors. */
    fun formSettings(): TextHookerSettings? =
        if (!isValid) {
            null
        } else {
            saved.copy(
                host = normalizeTextHookerHost(form.host),
                port = form.port.trim().toInt(),
                token = form.token.trim(),
            )
        }

    val hasChanges: Boolean get() = formSettings()?.let { it != saved } ?: true
}

internal fun TextHookerSettings.toForm(): TextHookerSettingsForm =
    TextHookerSettingsForm(host = host, port = port.toString(), token = token)

@HiltViewModel
internal class TextHookerSettingsViewModel internal constructor(
    private val settingsRepository: TextHookerSettingsRepository,
    private val repository: TextHookerRepository,
    private val injectedScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        settingsRepository: TextHookerSettingsRepository,
        repository: TextHookerRepository,
    ) : this(settingsRepository, repository, null)

    private val scope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    private val _uiState = MutableStateFlow(TextHookerSettingsUiState())
    val uiState: StateFlow<TextHookerSettingsUiState> = _uiState.asStateFlow()
    private var testJob: Job? = null

    init {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { state ->
                    if (state.loaded) {
                        state.copy(saved = settings)
                    } else {
                        state.copy(loaded = true, saved = settings, form = settings.toForm())
                    }
                }
            }
        }
    }

    fun updateHost(value: String) = updateForm { it.copy(host = value) }

    fun updatePort(value: String) = updateForm { it.copy(port = value.filter(Char::isDigit).take(5)) }

    fun updateToken(value: String) = updateForm { it.copy(token = value) }

    fun resetToDefaults() = updateForm {
        TextHookerSettings().toForm().copy(token = it.token)
    }

    fun save() {
        val next = _uiState.value.formSettings() ?: return
        scope.launch {
            settingsRepository.update { next }
            _uiState.update { it.copy(form = next.toForm()) }
        }
    }

    fun updateScreenshotMaxWidth(width: Int) {
        scope.launch { settingsRepository.update { it.copy(screenshotMaxWidth = width) } }
    }

    /** Tests the form values (saved or not) against `GET /api/status`. */
    fun testConnection() {
        val settings = _uiState.value.formSettings() ?: return
        testJob?.cancel()
        _uiState.update { it.copy(isTesting = true, testMessage = null, testSucceeded = false) }
        testJob = scope.launch {
            val (message, succeeded) = when (val result = repository.testConnection(settings)) {
                is TextHookerTestResult.Success -> UiText.Resource(
                    R.string.texthooker_settings_test_success,
                    result.status.hostname.ifBlank { settings.displayAddress },
                    result.status.version.ifBlank { "?" },
                ) to true
                is TextHookerTestResult.Failure -> UiText.Resource(
                    R.string.texthooker_settings_test_failed,
                    textHookerReasonText(result.reason),
                ) to false
            }
            _uiState.update { it.copy(isTesting = false, testMessage = message, testSucceeded = succeeded) }
        }
    }

    private fun updateForm(transform: (TextHookerSettingsForm) -> TextHookerSettingsForm) {
        _uiState.update { it.copy(form = transform(it.form), testMessage = null, testSucceeded = false) }
    }
}
