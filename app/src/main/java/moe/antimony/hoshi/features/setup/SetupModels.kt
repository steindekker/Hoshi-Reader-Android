package moe.antimony.hoshi.features.setup

import kotlinx.serialization.Serializable
import moe.antimony.hoshi.ui.UiText

internal object SetupCatalog {
    const val DefaultUrl = "http://<host>:8094/setup.json"
    const val Format = 1
}

@Serializable
internal data class SetupManifest(
    val format: Int,
    val dictionaries: List<SetupAsset> = emptyList(),
    val sentence: SetupSentence? = null,
    val audio: SetupAsset? = null,
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
)

internal data class SetupProgress(
    val label: UiText,
    val percent: Int? = null,
    val importing: Boolean = false,
)

internal class SetupFailure(val reason: Reason) : Exception() {
    enum class Reason { Unreachable, Damaged, Format }
}
