package moe.antimony.hoshi.features.setup

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.asString

@Composable
internal fun SetupWelcomeDialog(viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var dismissed by rememberSaveable { mutableStateOf(false) }
    if (!state.visible || dismissed) return
    val dismissible = !state.working
    HoshiAlertDialog(
        onDismissRequest = { if (dismissible) dismissed = true },
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = false,
        ),
        title = { Text(stringResource(R.string.setup_welcome_title)) },
        text = { SetupWelcomeBody(state, viewModel) },
        confirmButton = {
            if (!state.working) {
                HoshiButton(onClick = viewModel::start, enabled = state.ready) {
                    Text(
                        stringResource(
                            if (state.failed) R.string.setup_welcome_retry else R.string.action_download,
                        ),
                    )
                }
            }
        },
        dismissButton = if (dismissible) {
            {
                TextButton(onClick = { dismissed = true }) {
                    Text(stringResource(R.string.setup_welcome_not_now))
                }
            }
        } else {
            null
        },
    )
}

@Composable
private fun SetupWelcomeBody(state: SetupWelcomeUiState, viewModel: SetupViewModel) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.setup_welcome_body))
        if (state.audioAvailable) {
            Row(
                modifier = Modifier.padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = state.includeAudio,
                    onCheckedChange = viewModel::setIncludeAudio,
                    enabled = !state.working,
                )
                Text(stringResource(R.string.setup_welcome_audio))
            }
        }
        val label = state.label?.asString()
        if (state.failed) {
            Text(
                text = stringResource(R.string.setup_welcome_failed),
                modifier = Modifier.padding(top = 12.dp),
            )
        } else if (state.working && label != null) {
            Text(
                text = if (state.importing) {
                    stringResource(R.string.setup_welcome_importing, label)
                } else {
                    stringResource(R.string.setup_welcome_downloading, label, state.percent ?: 0)
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}
