package moe.antimony.hoshi.features.live

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

/** The add/edit host form; [editingId] is null while adding. */
internal data class LiveHostForm(
    val editingId: String? = null,
    val host: String = "",
    val port: String = LiveDefaults.Port.toString(),
    val token: String = "",
)

internal data class LiveHostTest(
    val running: Boolean = false,
    val message: UiText? = null,
    val succeeded: Boolean = false,
)

/** Test results are keyed by host id; [FormTestKey] is the unsaved form. */
internal const val FormTestKey = ""

internal data class LiveSettingsUiState(
    val loaded: Boolean = false,
    val saved: LiveSettings = LiveSettings(),
    /** The open form, or null when no host is being added or edited. */
    val form: LiveHostForm? = null,
    val tests: Map<String, LiveHostTest> = emptyMap(),
) {
    val hostError: LiveSettingsError?
        get() {
            val form = form ?: return null
            val host = normalizeLiveHost(form.host)
            validateLiveHost(host)?.let { return it }
            val port = form.port.trim().toIntOrNull()
            val duplicate = saved.hosts.any {
                it.id != form.editingId && it.host.equals(host, ignoreCase = true) && it.port == port
            }
            return if (duplicate) LiveSettingsError.HostDuplicate else null
        }
    val portError: LiveSettingsError? get() = form?.let { validateLivePort(it.port) }
    val tokenError: LiveSettingsError? get() = form?.let { validateLiveToken(it.token) }
    val isFormValid: Boolean get() = form != null && hostError == null && portError == null && tokenError == null

    /** The form as a host, or null while it has validation errors. */
    fun formHost(): LiveHost? {
        val form = form ?: return null
        if (!isFormValid) return null
        return LiveHost(
            id = form.editingId ?: LiveHost.newId(),
            host = normalizeLiveHost(form.host),
            port = form.port.trim().toInt(),
            token = form.token.trim(),
        )
    }
}

@HiltViewModel
internal class LiveSettingsViewModel internal constructor(
    private val settingsRepository: LiveSettingsRepository,
    private val repository: LiveRepository,
    private val injectedScope: CoroutineScope?,
) : ViewModel() {
    @Inject
    constructor(
        settingsRepository: LiveSettingsRepository,
        repository: LiveRepository,
    ) : this(settingsRepository, repository, null)

    private val scope: CoroutineScope
        get() = injectedScope ?: viewModelScope

    private val _uiState = MutableStateFlow(LiveSettingsUiState())
    val uiState: StateFlow<LiveSettingsUiState> = _uiState.asStateFlow()
    private val testJobs = mutableMapOf<String, Job>()

    init {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(loaded = true, saved = settings) }
            }
        }
    }

    fun startAdding() = _uiState.update { it.copy(form = LiveHostForm(), tests = it.tests - FormTestKey) }

    fun startEditing(hostId: String) {
        val host = _uiState.value.saved.host(hostId) ?: return
        _uiState.update {
            it.copy(form = LiveHostForm(host.id, host.host, host.port.toString(), host.token), tests = it.tests - FormTestKey)
        }
    }

    fun cancelForm() = _uiState.update { it.copy(form = null, tests = it.tests - FormTestKey) }

    fun updateHost(value: String) = updateForm { it.copy(host = value) }

    fun updatePort(value: String) = updateForm { it.copy(port = value.filter(Char::isDigit).take(5)) }

    fun updateToken(value: String) = updateForm { it.copy(token = value) }

    /** Adds the form's host, or replaces the edited one. */
    fun saveForm() {
        val host = _uiState.value.formHost() ?: return
        scope.launch {
            settingsRepository.update { settings ->
                val hosts = if (settings.hosts.any { it.id == host.id }) {
                    settings.hosts.map { if (it.id == host.id) host else it }
                } else {
                    settings.hosts + host
                }
                settings.copy(hosts = hosts)
            }
            _uiState.update { it.copy(form = null, tests = it.tests - FormTestKey - host.id) }
        }
    }

    fun removeHost(hostId: String) {
        scope.launch {
            settingsRepository.update { settings -> settings.copy(hosts = settings.hosts.filterNot { it.id == hostId }) }
            _uiState.update { state ->
                state.copy(
                    tests = state.tests - hostId,
                    form = state.form?.takeIf { it.editingId != hostId },
                )
            }
        }
    }

    fun updateScreenshotMaxWidth(width: Int) {
        scope.launch { settingsRepository.update { it.copy(screenshotMaxWidth = width) } }
    }

    fun setAutoPause(enabled: Boolean) {
        scope.launch { settingsRepository.update { it.copy(autoPause = enabled) } }
    }

    /** Tests a saved host against `GET /v2/status`. */
    fun testHost(hostId: String) {
        val host = _uiState.value.saved.host(hostId) ?: return
        runTest(hostId, host)
    }

    /** Tests the form values before saving. */
    fun testForm() {
        val host = _uiState.value.formHost() ?: return
        runTest(FormTestKey, host)
    }

    private fun runTest(key: String, host: LiveHost) {
        testJobs[key]?.cancel()
        _uiState.update { it.copy(tests = it.tests + (key to LiveHostTest(running = true))) }
        testJobs[key] = scope.launch {
            val test = when (val result = repository.testConnection(host)) {
                is LiveTestResult.Success -> LiveHostTest(
                    message = UiText.Resource(
                        R.string.live_settings_test_success,
                        result.status.bridge.hostname.ifBlank { host.displayAddress },
                        result.status.bridge.version.ifBlank { "?" },
                    ),
                    succeeded = true,
                )
                is LiveTestResult.Failure -> LiveHostTest(
                    message = UiText.Resource(R.string.live_settings_test_failed, liveReasonText(result.reason)),
                )
            }
            _uiState.update { it.copy(tests = it.tests + (key to test)) }
        }
    }

    private fun updateForm(transform: (LiveHostForm) -> LiveHostForm) {
        _uiState.update { state ->
            val form = state.form ?: return@update state
            state.copy(form = transform(form), tests = state.tests - FormTestKey)
        }
    }
}
