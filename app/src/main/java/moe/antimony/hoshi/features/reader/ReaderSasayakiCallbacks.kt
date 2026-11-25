package moe.antimony.hoshi.features.reader

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import moe.antimony.hoshi.epub.SasayakiMatch
import moe.antimony.hoshi.features.sasayaki.SasayakiCueRevealSource

internal class ReaderSasayakiCallbacks(
    val onCue: (SasayakiMatch, Boolean, SasayakiCueRevealSource) -> Unit,
    val onClearCue: () -> Unit,
)

@Composable
internal fun rememberReaderSasayakiCallbacks(
    onCue: (SasayakiMatch, Boolean, SasayakiCueRevealSource) -> Unit,
    onClearCue: () -> Unit,
): ReaderSasayakiCallbacks {
    // The service keeps its attachment while statistics load and reader settings change.
    // Capturing local function references can compare equal despite different captures.
    // A fresh holder makes both callbacks update together without comparing the functions.
    val current = rememberUpdatedState(ReaderSasayakiCallbacks(onCue, onClearCue))
    return remember {
        ReaderSasayakiCallbacks(
            onCue = { cue, reveal, source -> current.value.onCue(cue, reveal, source) },
            onClearCue = { current.value.onClearCue() },
        )
    }
}
