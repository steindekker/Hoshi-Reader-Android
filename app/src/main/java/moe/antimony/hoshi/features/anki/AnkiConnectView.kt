package moe.antimony.hoshi.features.anki

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import moe.antimony.hoshi.LocalHoshiAppContainer
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.ui.asString

@Composable
fun AnkiConnectView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val appContainer = LocalHoshiAppContainer.current
    val viewModel: AnkiViewModel = viewModel(
        factory = remember(appContainer) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AnkiViewModel(appContainer.ankiRepository) as T
            }
        },
    )
    val uiState by viewModel.uiState.collectAsState()
    val enabled = uiState.settings.backendKind == AnkiBackendKind.AnkiConnect
    var editingAddress by remember { mutableStateOf(false) }
    var addressInput by remember { mutableStateOf("") }

    if (editingAddress) {
        AlertDialog(
            onDismissRequest = { editingAddress = false },
            title = { Text(stringResource(R.string.anki_connect_address)) },
            text = {
                OutlinedTextField(
                    value = addressInput,
                    onValueChange = { addressInput = it },
                    label = { Text(stringResource(R.string.anki_connect_url)) },
                    placeholder = { Text("https://anki.example.com:8765") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.updateAnkiConnectUrl(addressInput.trim())
                        editingAddress = false
                    },
                ) {
                    Text(stringResource(R.string.action_save))
                }
            },
            dismissButton = {
                TextButton(onClick = { editingAddress = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            },
        )
    }

    SettingsDetailScaffold(
        title = stringResource(R.string.anki_connect_use),
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        ) {
            item {
                AnkiConnectCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                        headlineContent = { Text(stringResource(R.string.anki_connect_use)) },
                        supportingContent = {
                            Text(stringResource(R.string.anki_connect_use_description))
                        },
                        trailingContent = {
                            Switch(
                                checked = enabled,
                                onCheckedChange = {
                                    viewModel.updateBackendKind(
                                        if (it) AnkiBackendKind.AnkiConnect else AnkiBackendKind.AnkiDroid,
                                    )
                                },
                            )
                        },
                    )
                }
            }
            if (enabled) {
                item {
                    AnkiConnectCard {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            headlineContent = { Text(stringResource(R.string.anki_connect_address)) },
                            supportingContent = {
                                val noneLabel = stringResource(R.string.none)
                                Column {
                                    Text(uiState.settings.ankiConnectUrl.ifBlank { noneLabel })
                                    Text(
                                        text = stringResource(R.string.anki_connect_address_help),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            },
                            trailingContent = {
                                TextButton(
                                    onClick = {
                                        addressInput = uiState.settings.ankiConnectUrl
                                        editingAddress = true
                                    },
                                ) {
                                    Text(stringResource(R.string.action_edit))
                                }
                            },
                        )
                        HorizontalDivider()
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
                            headlineContent = { Text(stringResource(R.string.anki_connect_connection)) },
                            supportingContent = {
                                Text(
                                    uiState.ankiConnectMessage?.asString()
                                        ?: if (uiState.isAnkiConnectReachable) {
                                            stringResource(R.string.anki_connect_connected)
                                        } else {
                                            stringResource(R.string.anki_connect_not_connected)
                                        },
                                )
                            },
                            trailingContent = {
                                TextButton(
                                    onClick = viewModel::pingAnkiConnect,
                                    enabled = !uiState.isConnectingAnkiConnect,
                                ) {
                                    Text(
                                        if (uiState.isConnectingAnkiConnect) {
                                            stringResource(R.string.anki_connect_connecting)
                                        } else {
                                            stringResource(R.string.action_connect)
                                        },
                                    )
                                }
                            },
                        )
                    }
                }
                item {
                    AnkiConnectCard {
                        AnkiConnectDuplicateScopeRow(
                            scope = uiState.settings.duplicateScope,
                            onSelect = viewModel::updateDuplicateScope,
                        )
                        HorizontalDivider()
                        AnkiConnectSwitchRow(
                            label = stringResource(R.string.anki_connect_check_all_models),
                            checked = uiState.settings.checkDuplicatesAcrossAllModels,
                            onCheckedChange = viewModel::updateCheckDuplicatesAcrossAllModels,
                        )
                        HorizontalDivider()
                        AnkiConnectSwitchRow(
                            label = stringResource(R.string.anki_connect_force_sync),
                            checked = uiState.settings.ankiConnectForceSync,
                            onCheckedChange = viewModel::updateAnkiConnectForceSync,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AnkiConnectCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
    ) {
        Column {
            content()
        }
    }
}

@Composable
private fun AnkiConnectSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(label) },
        trailingContent = { Switch(checked = checked, onCheckedChange = onCheckedChange) },
    )
}

@Composable
private fun AnkiConnectDuplicateScopeRow(
    scope: AnkiDuplicateScope,
    onSelect: (AnkiDuplicateScope) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surface),
        headlineContent = { Text(stringResource(R.string.anki_connect_duplicate_scope)) },
        supportingContent = { Text(stringResource(scope.labelRes)) },
        trailingContent = {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(R.string.action_choose))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                AnkiDuplicateScope.entries.forEach { item ->
                    DropdownMenuItem(
                        text = { Text(stringResource(item.labelRes)) },
                        onClick = {
                            expanded = false
                            onSelect(item)
                        },
                    )
                }
            }
        },
    )
}
