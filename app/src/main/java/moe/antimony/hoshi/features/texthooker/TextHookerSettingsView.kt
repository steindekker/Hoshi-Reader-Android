package moe.antimony.hoshi.features.texthooker

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.input.TextObfuscationMode
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.features.settings.SettingsDetailScaffold
import moe.antimony.hoshi.ui.HoshiDropdownMenu as DropdownMenu
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.hoshiOutlinedTextFieldColors
import moe.antimony.hoshi.ui.hoshiSingleLineTextFieldLineLimits
import moe.antimony.hoshi.ui.rememberSyncedTextFieldState
import moe.antimony.hoshi.ui.theme.hoshiContainerBorder
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

@Composable
internal fun TextHookerSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TextHookerSettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_texthooker),
        onClose = onClose,
        modifier = modifier,
    ) { innerPadding ->
        if (!state.loaded) return@SettingsDetailScaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.texthooker_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                SettingsCard {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        SettingsTextField(
                            value = state.form.host,
                            onValueChange = viewModel::updateHost,
                            label = stringResource(R.string.texthooker_settings_host),
                            supporting = state.hostError?.let { stringResource(it.messageRes()) }
                                ?: stringResource(R.string.texthooker_settings_host_hint),
                            isError = state.hostError != null,
                        )
                        SettingsTextField(
                            value = state.form.port,
                            onValueChange = viewModel::updatePort,
                            label = stringResource(R.string.texthooker_settings_port),
                            supporting = state.portError?.let { stringResource(it.messageRes()) },
                            isError = state.portError != null,
                            keyboardType = KeyboardType.Number,
                        )
                        val tokenState = rememberSyncedTextFieldState(
                            value = state.form.token,
                            onValueChange = viewModel::updateToken,
                        )
                        OutlinedSecureTextField(
                            state = tokenState,
                            label = { Text(stringResource(R.string.texthooker_settings_token)) },
                            supportingText = {
                                Text(
                                    state.tokenError?.let { stringResource(it.messageRes()) }
                                        ?: stringResource(R.string.texthooker_settings_token_hint),
                                )
                            },
                            isError = state.tokenError != null,
                            textObfuscationMode = TextObfuscationMode.RevealLastTyped,
                            colors = hoshiOutlinedTextFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(onClick = viewModel::resetToDefaults) {
                                Text(stringResource(R.string.texthooker_settings_use_defaults))
                            }
                            OutlinedButton(
                                onClick = viewModel::testConnection,
                                enabled = state.isValid && state.formSettings()?.isConfigured == true && !state.isTesting,
                            ) {
                                if (state.isTesting) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                    Spacer(Modifier.size(8.dp))
                                }
                                Text(stringResource(R.string.texthooker_settings_test))
                            }
                            Button(
                                onClick = viewModel::save,
                                enabled = state.isValid && state.hasChanges,
                            ) {
                                Text(stringResource(R.string.action_save))
                            }
                        }
                        state.testMessage?.let { message ->
                            Text(
                                text = message.asString(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (state.testSucceeded) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.error
                                },
                            )
                        }
                    }
                }
            }
            item {
                SettingsCard {
                    ScreenshotWidthRow(
                        width = state.saved.screenshotMaxWidth,
                        onSelect = viewModel::updateScreenshotMaxWidth,
                    )
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.texthooker_settings_screenshot_help),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    supporting: String?,
    isError: Boolean,
    keyboardType: KeyboardType = KeyboardType.Uri,
) {
    val state = rememberSyncedTextFieldState(value = value, onValueChange = onValueChange)
    OutlinedTextField(
        state = state,
        label = { Text(label) },
        supportingText = supporting?.let { { Text(it) } },
        isError = isError,
        lineLimits = hoshiSingleLineTextFieldLineLimits(),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, autoCorrectEnabled = false),
        colors = hoshiOutlinedTextFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ScreenshotWidthRow(
    width: Int,
    onSelect: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ListItem(
        colors = ListItemDefaults.colors(containerColor = hoshiSurfaces.group),
        headlineContent = { Text(stringResource(R.string.texthooker_settings_screenshot_width)) },
        supportingContent = { Text(screenshotWidthLabel(width)) },
        trailingContent = {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(R.string.action_choose))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                TextHookerDefaults.ScreenshotWidthOptions.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(screenshotWidthLabel(option)) },
                        onClick = {
                            expanded = false
                            onSelect(option)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun screenshotWidthLabel(width: Int): String =
    if (width <= 0) {
        stringResource(R.string.texthooker_settings_screenshot_original)
    } else {
        stringResource(R.string.texthooker_settings_screenshot_width_value, width)
    }

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = hoshiSurfaces.group,
        tonalElevation = 0.dp,
        border = hoshiContainerBorder(),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column { content() }
    }
}

private fun TextHookerSettingsError.messageRes(): Int = when (this) {
    TextHookerSettingsError.HostInvalid -> R.string.texthooker_settings_error_host
    TextHookerSettingsError.PortInvalid -> R.string.texthooker_settings_error_port
    TextHookerSettingsError.TokenInvalid -> R.string.texthooker_settings_error_token
}
