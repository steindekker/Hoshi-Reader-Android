package moe.antimony.hoshi.features.anki

import moe.antimony.hoshi.ui.UiText

/**
 * A game screenshot requested for the merged `{image}` handlebar. [lineId] identifies the
 * line whose cached screenshot the source should prefer; [localPath] is an already-fetched
 * preview that is attached as-is instead of fetching again.
 */
data class AnkiScreenshotRequest(
    val lineId: Long?,
    val localPath: String? = null,
)

sealed interface AnkiScreenshotResult {
    class Success(val bytes: ByteArray, val mimeType: String) : AnkiScreenshotResult

    data class Failure(val message: UiText) : AnkiScreenshotResult
}

/** Supplies screenshots at mine time; failures never block mining. */
fun interface AnkiScreenshotSource {
    suspend fun fetchScreenshot(request: AnkiScreenshotRequest): AnkiScreenshotResult
}

/** The outcome of a mine: whether the note was added plus non-blocking media warnings. */
data class AnkiMineResult(
    val added: Boolean,
    val warnings: List<UiText> = emptyList(),
)
