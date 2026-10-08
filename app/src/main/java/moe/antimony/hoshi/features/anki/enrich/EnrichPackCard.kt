package moe.antimony.hoshi.features.anki.enrich

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

internal data class EnrichPackUiState(
    val manifestUrl: String = "",
    val status: EnrichPackStatus = EnrichPackStatus.Idle,
    val info: EnrichPackInfo? = null,
) {
    val isBusy: Boolean
        get() = status is EnrichPackStatus.Checking || status is EnrichPackStatus.Downloading
}

@HiltViewModel
internal class EnrichPackViewModel @Inject constructor(
    private val repository: EnrichPackRepository,
    private val settings: EnrichPackSettingsRepository,
) : ViewModel() {
    val uiState: StateFlow<EnrichPackUiState> =
        combine(settings.manifestUrl, repository.status, repository.info, ::EnrichPackUiState)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EnrichPackUiState())

    fun setManifestUrl(url: String) {
        viewModelScope.launch { settings.setManifestUrl(url.trim()) }
    }

    fun update() = repository.startUpdate()
}

/** Anki settings card for the anki-enrich pack: installed version, server URL, and Update. */
@Composable
internal fun EnrichPackCard() {
    val viewModel: EnrichPackViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var editingUrl by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        shape = RoundedCornerShape(24.dp),
        color = hoshiSurfaces.group,
        border = hoshiContainerBorder(),
    ) {
        Column {
            ListItem(
                colors = ListItemDefaults.colors(containerColor = hoshiSurfaces.group),
                headlineContent = { Text(stringResource(R.string.enrich_pack_title)) },
                supportingContent = {
                    Column {
                        Text(
                            state.info?.let { info ->
                                pluralStringResource(
                                    R.plurals.enrich_pack_installed,
                                    info.entries,
                                    info.builtAt.take(10),
                                    info.entries,
                                )
                            } ?: stringResource(R.string.enrich_pack_not_installed),
                        )
                        statusText(state.status)?.let { Text(it) }
                    }
                },
                trailingContent = {
                    TextButton(onClick = viewModel::update, enabled = !state.isBusy) {
                        Text(stringResource(R.string.action_update))
                    }
                },
            )
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ListItem(
                modifier = Modifier.clickable { editingUrl = true },
                colors = ListItemDefaults.colors(containerColor = hoshiSurfaces.group),
                headlineContent = { Text(stringResource(R.string.enrich_pack_url)) },
                supportingContent = { Text(state.manifestUrl.ifBlank { stringResource(R.string.none) }) },
            )
        }
    }
    if (editingUrl) {
        var draft by remember { mutableStateOf(state.manifestUrl) }
        AlertDialog(
            onDismissRequest = { editingUrl = false },
            title = { Text(stringResource(R.string.enrich_pack_url)) },
            text = {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(onClick = { editingUrl = false; viewModel.setManifestUrl(draft) }) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingUrl = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
private fun statusText(status: EnrichPackStatus): String? = when (status) {
    EnrichPackStatus.Idle -> null
    EnrichPackStatus.Checking -> stringResource(R.string.enrich_pack_checking)
    is EnrichPackStatus.Downloading -> stringResource(R.string.enrich_pack_downloading, status.percent)
    EnrichPackStatus.UpToDate -> stringResource(R.string.enrich_pack_up_to_date)
    EnrichPackStatus.Updated -> stringResource(R.string.enrich_pack_updated)
    is EnrichPackStatus.Failed -> status.message.asString()
}
