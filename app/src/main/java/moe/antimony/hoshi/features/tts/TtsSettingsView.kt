package moe.antimony.hoshi.features.tts

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.features.settings.SettingsLoadState
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

enum class TtsSettingsSection(@param:StringRes val titleRes: Int) {
    Voice(R.string.tts_tab_voice), Replacements(R.string.tts_tab_dictionary), Reading(R.string.settings_title),
}

@Composable
fun TtsSettingsView(
    onClose: () -> Unit,
    onOpenSection: (TtsSettingsSection) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TtsViewModel = hiltViewModel(),
) {
    val state by viewModel.preferencesLoadState.collectAsStateWithLifecycle()
    val error by viewModel.toggleError.collectAsStateWithLifecycle()
    SettingsDetailScaffold(state = state, title = stringResource(R.string.tts_title), onClose = onClose,
        onRetry = viewModel::retryPreferences, modifier = modifier) { padding, preferences ->
        Column(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)
            .padding(horizontal = 16.dp)
            .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp)) {
            TtsSettingsCard {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(stringResource(R.string.tts_enabled)) },
                    trailingContent = { Switch(checked = preferences.settings.enabled, onCheckedChange = viewModel::enabled) },
                )
            }
            error?.let { ErrorDetailsText(it.message.asString()) }
            if (preferences.settings.enabled) TtsSettingsCard {
                TtsSettingsSection.entries.forEachIndexed { index, section ->
                    if (index > 0) HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    ListItem(
                        modifier = Modifier.clickable { onOpenSection(section) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        headlineContent = { Text(stringResource(section.titleRes)) },
                        trailingContent = { Icon(Icons.Rounded.ChevronRight, null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TtsSettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = hoshiSurfaces.group,
        border = hoshiContainerBorder(),
        tonalElevation = 0.dp,
    ) {
        Column(content = content)
    }
}

@Composable
fun TtsSettingsSectionView(section: TtsSettingsSection, onClose: () -> Unit, viewModel: TtsViewModel = hiltViewModel()) {
    val state by viewModel.preferencesLoadState.collectAsStateWithLifecycle()
    val enabled = (state as? SettingsLoadState.Loaded)?.value?.settings?.enabled
    LaunchedEffect(enabled) { if (enabled == false) onClose() }
    SettingsDetailScaffold(state = state, title = stringResource(section.titleRes), onClose = onClose,
        onRetry = viewModel::retryPreferences) { padding, preferences ->
        if (preferences.settings.enabled) TtsPanel(viewModel, null, null,
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), section)
    }
}
