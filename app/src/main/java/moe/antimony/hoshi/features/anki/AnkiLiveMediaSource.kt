package moe.antimony.hoshi.features.anki

import moe.antimony.hoshi.ui.UiText

/**
 * A cue of a Live session (hoshi-bridge): the bridge resolves the media times
 * of `{image}` and `{sentence-audio}` from it.
 */
data class AnkiLiveCueRef(
    val hostId: String,
    val sessionId: String,
    val trackId: String,
    val cueId: Long,
)

/**
 * A Live picture requested for the merged `{image}` handlebar: an animated clip of the
 * cue ([animated], video sessions) or a still screenshot. [localPath] is an already
 * fetched preview that is attached as-is instead of fetching again.
 */
data class AnkiScreenshotRequest(
    val cue: AnkiLiveCueRef,
    val animated: Boolean = false,
    val localPath: String? = null,
)

sealed interface AnkiLiveMediaResult {
    class Success(val bytes: ByteArray, val mimeType: String) : AnkiLiveMediaResult

    data class Failure(val message: UiText) : AnkiLiveMediaResult
}

/** Supplies Live pictures and sentence audio at mine time; failures never block mining. */
interface AnkiLiveMediaSource {
    suspend fun fetchScreenshot(request: AnkiScreenshotRequest): AnkiLiveMediaResult

    suspend fun fetchSentenceAudio(cue: AnkiLiveCueRef): AnkiLiveMediaResult
}

/** The outcome of a mine: whether the note was added, its id when the backend returned one, plus non-blocking media warnings. */
data class AnkiMineResult(
    val added: Boolean,
    val noteId: Long? = null,
    val warnings: List<UiText> = emptyList(),
)
