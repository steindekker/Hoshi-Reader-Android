package moe.antimony.hoshi.features.live

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedSecureTextField
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
internal fun LiveSettingsView(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: LiveSettingsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    SettingsDetailScaffold(
        title = stringResource(R.string.settings_live),
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
                    text = stringResource(R.string.live_settings_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.live_settings_hosts),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            item {
                SettingsCard {
                    if (state.saved.hosts.isEmpty()) {
                        Text(
                            text = stringResource(R.string.live_settings_no_hosts),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(16.dp),
                        )
                    }
                    state.saved.hosts.forEachIndexed { index, host ->
                        if (index > 0) HorizontalDivider()
                        HostRow(
                            host = host,
                            test = state.tests[host.id],
                            onEdit = { viewModel.startEditing(host.id) },
                            onTest = { viewModel.testHost(host.id) },
                            onRemove = { viewModel.removeHost(host.id) },
                        )
                    }
                    HorizontalDivider()
                    if (state.form == null) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = hoshiSurfaces.group),
                            leadingContent = { Icon(Icons.Rounded.Add, contentDescription = null) },
                            headlineContent = { Text(stringResource(R.string.live_settings_add_host)) },
                            modifier = Modifier.clickable(onClick = viewModel::startAdding),
                        )
                    }
                }
            }
            state.form?.let { form ->
                item {
                    HostForm(
                        form = form,
                        state = state,
                        test = state.tests[FormTestKey],
                        onHost = viewModel::updateHost,
                        onPort = viewModel::updatePort,
                        onToken = viewModel::updateToken,
                        onTest = viewModel::testForm,
                        onCancel = viewModel::cancelForm,
                        onSave = viewModel::saveForm,
                    )
                }
            }
            item {
                SettingsCard {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = hoshiSurfaces.group),
                        headlineContent = { Text(stringResource(R.string.live_settings_auto_pause)) },
                        supportingContent = { Text(stringResource(R.string.live_settings_auto_pause_help)) },
                        trailingContent = {
                            Switch(checked = state.saved.autoPause, onCheckedChange = viewModel::setAutoPause)
                        },
                    )
                    HorizontalDivider()
                    ScreenshotWidthRow(
                        width = state.saved.screenshotMaxWidth,
                        onSelect = viewModel::updateScreenshotMaxWidth,
                    )
                    HorizontalDivider()
                    Text(
                        text = stringResource(R.string.live_settings_screenshot_help),
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
private fun HostRow(
    host: LiveHost,
    test: LiveHostTest?,
    onEdit: () -> Unit,
    onTest: () -> Unit,
    onRemove: () -> Unit,
) {
    Column {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = hoshiSurfaces.group),
            headlineContent = { Text(host.displayAddress) },
            supportingContent = if (host.token.isNotBlank()) {
                { Text(stringResource(R.string.live_settings_token_set)) }
            } else {
                null
            },
            trailingContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onTest, enabled = test?.running != true) {
                        if (test?.running == true) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(R.string.live_settings_test))
                        }
                    }
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Rounded.Delete, contentDescription = null)
                    }
                }
            },
            modifier = Modifier.clickable(onClick = onEdit),
        )
        test?.message?.let { TestMessage(it.asString(), test.succeeded, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) }
    }
}

@Composable
private fun HostForm(
    form: LiveHostForm,
    state: LiveSettingsUiState,
    test: LiveHostTest?,
    onHost: (String) -> Unit,
    onPort: (String) -> Unit,
    onToken: (String) -> Unit,
    onTest: () -> Unit,
    onCancel: () -> Unit,
    onSave: () -> Unit,
) {
    SettingsCard {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(if (form.editingId == null) R.string.live_settings_add_host else R.string.live_settings_edit_host),
                style = MaterialTheme.typography.titleSmall,
            )
            SettingsTextField(
                value = form.host,
                onValueChange = onHost,
                label = stringResource(R.string.live_settings_host),
                supporting = state.hostError?.takeIf { form.host.isNotBlank() }?.let { stringResource(it.messageRes()) }
                    ?: stringResource(R.string.live_settings_host_hint),
                isError = state.hostError != null && form.host.isNotBlank(),
            )
            SettingsTextField(
                value = form.port,
                onValueChange = onPort,
                label = stringResource(R.string.live_settings_port),
                supporting = state.portError?.let { stringResource(it.messageRes()) },
                isError = state.portError != null,
                keyboardType = KeyboardType.Number,
            )
            val tokenState = rememberSyncedTextFieldState(value = form.token, onValueChange = onToken)
            OutlinedSecureTextField(
                state = tokenState,
                label = { Text(stringResource(R.string.live_settings_token)) },
                supportingText = {
                    Text(
                        state.tokenError?.let { stringResource(it.messageRes()) }
                            ?: stringResource(R.string.live_settings_token_hint),
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
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.action_cancel))
                }
                OutlinedButton(onClick = onTest, enabled = state.isFormValid && test?.running != true) {
                    if (test?.running == true) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(stringResource(R.string.live_settings_test))
                }
                Button(onClick = onSave, enabled = state.isFormValid) {
                    Text(stringResource(R.string.action_save))
                }
            }
            test?.message?.let { TestMessage(it.asString(), test.succeeded) }
        }
    }
}

@Composable
private fun TestMessage(text: String, succeeded: Boolean, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
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
        headlineContent = { Text(stringResource(R.string.live_settings_screenshot_width)) },
        supportingContent = { Text(screenshotWidthLabel(width)) },
        trailingContent = {
            TextButton(onClick = { expanded = true }) {
                Text(stringResource(R.string.action_choose))
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                LiveDefaults.ScreenshotWidthOptions.forEach { option ->
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
        stringResource(R.string.live_settings_screenshot_original)
    } else {
        stringResource(R.string.live_settings_screenshot_width_value, width)
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

private fun LiveSettingsError.messageRes(): Int = when (this) {
    LiveSettingsError.HostInvalid -> R.string.live_settings_error_host
    LiveSettingsError.HostDuplicate -> R.string.live_settings_error_duplicate
    LiveSettingsError.PortInvalid -> R.string.live_settings_error_port
    LiveSettingsError.TokenInvalid -> R.string.live_settings_error_token
}
