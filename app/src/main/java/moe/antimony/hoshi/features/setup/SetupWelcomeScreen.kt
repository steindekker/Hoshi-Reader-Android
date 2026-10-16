package moe.antimony.hoshi.features.setup

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FormatQuote
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.MenuBook
import androidx.compose.material.icons.rounded.Style
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.HoshiButton
import moe.antimony.hoshi.ui.asString
import moe.antimony.hoshi.ui.theme.hoshiSurfaces

private const val AnkiDroidPermission = "com.ichi2.anki.permission.READ_WRITE_DATABASE"

/**
 * Full-screen first-run setup: what the download brings, the offline-audio choice and the
 * AnkiDroid connection. Asks for AnkiDroid access (and, for offline audio, notifications)
 * before the download starts, so cards land in the right note type without a manual fetch.
 */
@Composable
internal fun SetupWelcomeScreen(viewModel: SetupViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    if (!state.visible) return
    val context = LocalContext.current
    val start = { viewModel.start(context.isGranted(AnkiDroidPermission)) }
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        start()
    }
    val requestNotificationsThenStart = {
        if (
            state.offlineAudio &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !context.isGranted(Manifest.permission.POST_NOTIFICATIONS)
        ) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            start()
        }
    }
    val ankiLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        requestNotificationsThenStart()
    }
    val begin = {
        if (state.ankiDroidInstalled && !context.isGranted(AnkiDroidPermission)) {
            ankiLauncher.launch(AnkiDroidPermission)
        } else {
            requestNotificationsThenStart()
        }
    }
    BackHandler(enabled = state.phase != SetupPhase.Working) { viewModel.dismiss() }

    Surface(modifier = Modifier.fillMaxSize(), color = hoshiSurfaces.page) {
        Box(modifier = Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.TopCenter) {
            Column(
                modifier = Modifier
                    .widthIn(max = 560.dp)
                    .fillMaxSize()
                    .padding(horizontal = 24.dp),
            ) {
                AnimatedContent(
                    targetState = state.phase == SetupPhase.Finished,
                    modifier = Modifier.weight(1f),
                    label = "setup-phase",
                ) { finished ->
                    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                        if (finished) FinishedContent(state) else WelcomeContent(state, viewModel)
                    }
                }
                Footer(state, onStart = begin, onDismiss = viewModel::dismiss)
            }
        }
    }
}

@Composable
private fun ColumnScope.WelcomeContent(state: SetupWelcomeUiState, viewModel: SetupViewModel) {
    Spacer(Modifier.height(40.dp))
    HeroIcon(Icons.Rounded.AutoStories)
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.setup_welcome_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.CenterHorizontally),
        textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.setup_welcome_subtitle),
        style = MaterialTheme.typography.bodyLarge,
        color = hoshiSurfaces.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))
    Group {
        FeatureRow(Icons.Rounded.MenuBook, R.string.setup_feature_dictionaries_title, R.string.setup_feature_dictionaries_body)
        FeatureRow(Icons.Rounded.GraphicEq, R.string.setup_feature_sentences_title, R.string.setup_feature_sentences_body)
        FeatureRow(Icons.Rounded.FormatQuote, R.string.setup_feature_examples_title, R.string.setup_feature_examples_body)
        FeatureRow(Icons.Rounded.Style, R.string.setup_feature_card_title, R.string.setup_feature_card_body)
    }
    Spacer(Modifier.height(16.dp))
    Group {
        if (state.audioAvailable) {
            val size = formatBytes(LocalContext.current, state.audioBytes)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                RowIcon(Icons.Rounded.Headphones)
                Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp)) {
                    Text(stringResource(R.string.setup_offline_audio_title), style = MaterialTheme.typography.titleSmall)
                    Text(
                        text = stringResource(R.string.setup_offline_audio_body, size) +
                            if (state.hasAudioServer) "\n" + stringResource(R.string.setup_offline_audio_streaming) else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = hoshiSurfaces.muted,
                    )
                }
                Switch(
                    checked = state.offlineAudio,
                    onCheckedChange = viewModel::setOfflineAudio,
                    enabled = state.phase == SetupPhase.Welcome,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            RowIcon(Icons.Rounded.Style)
            Column(modifier = Modifier.weight(1f).padding(start = 16.dp)) {
                Text(stringResource(R.string.setup_anki_title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(
                        if (state.ankiDroidInstalled) R.string.setup_anki_body_ask else R.string.setup_anki_body_missing,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = hoshiSurfaces.muted,
                )
            }
        }
    }
    Spacer(Modifier.height(24.dp))
}

@Composable
private fun ColumnScope.FinishedContent(state: SetupWelcomeUiState) {
    Spacer(Modifier.height(72.dp))
    HeroIcon(Icons.Rounded.CheckCircle)
    Spacer(Modifier.height(20.dp))
    Text(
        text = stringResource(R.string.setup_finished_title),
        style = MaterialTheme.typography.headlineMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.align(Alignment.CenterHorizontally),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = stringResource(R.string.setup_finished_body),
        style = MaterialTheme.typography.bodyLarge,
        color = hoshiSurfaces.muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(28.dp))
    Group {
        val anki = when (state.ankiResult) {
            SetupAnkiResult.Connected -> R.string.setup_finished_anki_connected
            SetupAnkiResult.Unavailable -> R.string.setup_anki_body_missing
            else -> R.string.setup_finished_anki_failed
        }
        StatusRow(Icons.Rounded.Style, stringResource(anki))
        if (state.offlineAudio) {
            StatusRow(Icons.Rounded.Headphones, stringResource(R.string.setup_finished_audio))
        }
    }
}

@Composable
private fun Footer(state: SetupWelcomeUiState, onStart: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Column(modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 20.dp)) {
        when (state.phase) {
            SetupPhase.Working -> {
                val label = state.label?.asString()
                Text(
                    text = when {
                        label == null -> stringResource(R.string.setup_welcome_preparing)
                        state.importing -> stringResource(R.string.setup_welcome_importing, label)
                        else -> stringResource(R.string.setup_welcome_downloading, label, state.percent ?: 0)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = hoshiSurfaces.muted,
                )
                Spacer(Modifier.height(10.dp))
                val percent = state.percent
                if (percent == null || state.importing) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.setup_welcome_keep_open),
                    style = MaterialTheme.typography.bodySmall,
                    color = hoshiSurfaces.muted,
                )
            }
            SetupPhase.Finished -> {
                HoshiButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.setup_finished_done))
                }
            }
            SetupPhase.Welcome -> {
                if (state.failed) {
                    Text(
                        text = stringResource(R.string.setup_welcome_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                } else if (state.ready && state.downloadBytes > 0) {
                    Text(
                        text = stringResource(R.string.setup_welcome_download_size, formatBytes(context, state.downloadBytes)),
                        style = MaterialTheme.typography.bodySmall,
                        color = hoshiSurfaces.muted,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    )
                }
                HoshiButton(
                    onClick = onStart,
                    enabled = state.ready,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text(stringResource(if (state.failed) R.string.setup_welcome_retry else R.string.setup_welcome_start))
                }
                TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    Text(stringResource(R.string.setup_welcome_not_now))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.HeroIcon(icon: ImageVector) {
    Box(
        modifier = Modifier
            .align(Alignment.CenterHorizontally)
            .size(88.dp)
            .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(44.dp))
    }
}

@Composable
private fun Group(content: @Composable ColumnScope.() -> Unit) {
    Surface(color = hoshiSurfaces.group, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            content()
        }
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: Int, body: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RowIcon(icon)
        Column(modifier = Modifier.padding(start = 16.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
            Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = hoshiSurfaces.muted)
        }
    }
}

@Composable
private fun StatusRow(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        RowIcon(icon)
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
    }
}

@Composable
private fun RowIcon(icon: ImageVector) {
    Box(
        modifier = Modifier.size(40.dp).background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.size(22.dp))
    }
}

private fun Context.isGranted(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun formatBytes(context: Context, bytes: Long): String = Formatter.formatShortFileSize(context, bytes)
