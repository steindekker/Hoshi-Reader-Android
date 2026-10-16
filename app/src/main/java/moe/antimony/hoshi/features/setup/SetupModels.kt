package moe.antimony.hoshi.features.setup

import kotlinx.serialization.Serializable
import moe.antimony.hoshi.BuildConfig
import moe.antimony.hoshi.ui.UiText

internal object SetupCatalog {
    val DefaultUrl: String = BuildConfig.HOSHI_SETUP_URL
    const val Format = 1
}

@Serializable
internal data class SetupManifest(
    val format: Int,
    val dictionaries: List<SetupAsset> = emptyList(),
    val sentence: SetupSentence? = null,
    /** The offline word-audio database, downloaded in the background when the user opts in. */
    val audio: SetupAsset? = null,
    /** The enrichment pack's `pack.json`, relative to the catalog. */
    val pack: String? = null,
    /** A Yomitan audio server used when offline audio is off, or has no clip for a word. */
    val audioServer: SetupAudioServer? = null,
) {
    /** Bytes the main setup download fetches (the enrichment pack is sized by its own manifest). */
    val downloadSize: Long
        get() = dictionaries.sumOf { it.size } + (sentence?.let { it.accent.size + it.dictionary.size } ?: 0L)
}

@Serializable
internal data class SetupAudioServer(
    val name: String,
    val url: String,
)

@Serializable
internal data class SetupSentence(
    val format: Int = SetupCatalog.Format,
    val accent: SetupAsset,
    val dictionary: SetupAsset,
)

@Serializable
internal data class SetupAsset(
    val file: String,
    val size: Long,
    val sha256: String,
    val id: String = "",
    val version: String = "",
    /** The dictionary's title (dated releases: the name before the date); see [isInstalled]. */
    val title: String = "",
)

internal data class SetupProgress(
    val label: UiText,
    val percent: Int? = null,
    val importing: Boolean = false,
)

internal class SetupFailure(val reason: Reason) : Exception() {
    enum class Reason { Unreachable, Damaged, Format }
}
