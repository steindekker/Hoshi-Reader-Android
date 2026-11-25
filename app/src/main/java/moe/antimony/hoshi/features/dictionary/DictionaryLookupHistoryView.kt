package moe.antimony.hoshi.features.dictionary

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDetailsText
import moe.antimony.hoshi.ui.HoshiAlertDialog
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.asString

@Composable
internal fun DictionaryLookupHistoryContent(
    searchState: DictionarySearchUiState,
    historyState: DictionaryLookupHistoryState,
    onSelect: (String) -> Unit,
    onDelete: (String) -> Unit,
    onClear: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!searchState.showLookupHistory) return
    if (historyState.entries.isEmpty() && historyState.loadError == null) return
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier) {
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    stringResource(R.string.dictionary_history_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (historyState.entries.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.action_clear)) }
                }
            }
        }
        historyState.loadError?.let { error ->
            item {
                Column(Modifier.padding(horizontal = 16.dp)) {
                    ErrorDetailsText(error.asString())
                    TextButton(onClick = onRetry) { Text(stringResource(R.string.dictionary_history_retry)) }
                }
            }
        }
        items(historyState.entries, key = { it }) { text ->
            Row(
                Modifier.fillMaxWidth().clickable { onSelect(text) }.padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                IconButton(onClick = { onDelete(text) }) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = stringResource(R.string.action_delete),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))
        }
    }
    if (confirmClear) {
        HoshiAlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.dictionary_history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; onClear() }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) }
            },
        )
    }
}

@Composable
internal fun DictionaryLookupHistoryError(error: UiText?, onDismiss: () -> Unit) {
    if (error == null) return
    HoshiAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.dialog_error_title)) },
        text = { ErrorDetailsText(error.asString()) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
