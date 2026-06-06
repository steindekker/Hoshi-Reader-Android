package moe.antimony.hoshi.features.update

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
internal class UpdatePromptEvents @Inject constructor() {
    private val _availablePromptKey = MutableStateFlow<String?>(null)
    val availablePromptKey: StateFlow<String?> = _availablePromptKey.asStateFlow()

    fun notifyAvailable(update: AvailableUpdate) {
        _availablePromptKey.value = update.promptKey()
    }

    fun consumeAvailablePrompt(key: String) {
        if (_availablePromptKey.value == key) {
            _availablePromptKey.value = null
        }
    }
}
