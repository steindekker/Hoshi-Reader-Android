package moe.antimony.hoshi.features.tts

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.*
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import moe.antimony.hoshi.ui.UiText

internal const val ttsSynthesisConcurrency = 2

/** Initial attempt plus two retries; waiting does not occupy a synthesis permit. */
internal suspend fun <T> retryTtsSynthesis(block: suspend () -> T): T {
    var retries = 0
    while (true) {
        try { return block() }
        catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            val failure = when (error) {
                is TimeoutCancellationException -> TtsException(TtsFailure.Timeout, cause = error)
                is CancellationException -> throw error
                else -> error
            }
            val retryable = failure is TtsException && failure.failure in setOf(TtsFailure.Network, TtsFailure.Timeout, TtsFailure.RateLimited)
            if (!retryable || retries >= 2) throw failure
            ErrorDiagnosticEvents.buffer.record(UiText.Resource(R.string.tts_failed), failure)
            val backoff = 500L shl retries++
            delay(maxOf(backoff, failure.retryAfterMillis ?: 0L))
        }
    }
}

/** Retry-After accepts non-negative delay seconds or an HTTP date. Never shorten server cooldown. */
internal fun ttsRetryAfterMillis(value: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val text = value?.trim() ?: return null
    text.toLongOrNull()?.takeIf { it >= 0 }?.let { return it.coerceAtMost(Long.MAX_VALUE / 1000) * 1000 }
    return runCatching { (ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis).coerceAtLeast(0) }.getOrNull()
}
