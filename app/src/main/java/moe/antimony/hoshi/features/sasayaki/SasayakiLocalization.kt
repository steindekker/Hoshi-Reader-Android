package moe.antimony.hoshi.features.sasayaki

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import moe.antimony.hoshi.R

@Composable
internal fun SasayakiReaderSkipButtonAction.labelText(): String =
    seconds?.let { stringResource(R.string.sasayaki_skip_seconds_format, it) }
        ?: stringResource(R.string.sasayaki_skip_cue)
