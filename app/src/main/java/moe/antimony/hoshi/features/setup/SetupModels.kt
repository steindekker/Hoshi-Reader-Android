package moe.antimony.hoshi.features.setup

import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.ui.UiText

/** This build's setup catalog; the catalog format, parser and downloads live in hoshi-card (`hoshi.card.setup`). */
internal object SetupCatalogConfig {
    val DefaultUrl: String = BuildConfig.HOSHI_SETUP_URL
}

internal data class SetupProgress(
    val label: UiText,
    val percent: Int? = null,
    val importing: Boolean = false,
)
