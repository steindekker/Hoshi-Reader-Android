package moe.antimony.hoshi.features.tts

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import moe.antimony.hoshi.ui.ErrorDiagnosticEvents
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TtsSynthesisRetryTest {
    @Test fun transientFailuresRecoverWithoutAPlaybackTransitionAndKeepDiagnostics() = runTest {
        ErrorDiagnosticEvents.buffer.drain()
        val first = TtsException(TtsFailure.Network, message = "connection reset")
        val second = TtsException(TtsFailure.Timeout, message = "response timed out")
        val times = mutableListOf<Long>()
        val result = retryTtsSynthesis {
            times += currentTime
            when (times.size) { 1 -> throw first; 2 -> throw second; else -> "audio" }
        }
        assertEquals("audio", result)
        assertEquals(listOf(0L, 500L, 1500L), times)
        assertEquals(listOf(first, second), ErrorDiagnosticEvents.buffer.drain().map { it.error })
    }

    @Test fun permanentErrorsAndExhaustedRetriesRetainTheOriginalFailure() = runTest {
        for (failure in listOf(TtsFailure.Authentication, TtsFailure.Configuration, TtsFailure.InvalidResponse, TtsFailure.Network)) {
            val error = TtsException(failure, message = "server diagnostic")
            var attempts = 0
            val actual = runCatching { retryTtsSynthesis { attempts++; throw error } }.exceptionOrNull()
            assertSame(error, actual)
            assertEquals(if (failure == TtsFailure.Network) 3 else 1, attempts)
        }
    }

    @Test fun rateLimitHonorsServerDelayAndCancellationStopsBackoff() = runTest {
        var attempts = 0
        val task = launch {
            retryTtsSynthesis {
                attempts++
                throw TtsException(TtsFailure.RateLimited, retryAfterMillis = 5000)
            }
        }
        runCurrent(); advanceTimeBy(4999); runCurrent()
        assertEquals(1, attempts)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, attempts)
        task.cancelAndJoin(); advanceTimeBy(10000); runCurrent()
        assertEquals(2, attempts)
    }

    @Test fun operationTimeoutCanRecoverButCallerCancellationCannot() = runTest {
        var attempts = 0
        assertEquals("ready", retryTtsSynthesis {
            if (++attempts == 1) withTimeout(10) { awaitCancellation() }
            "ready"
        })
        var canceledAttempts = 0
        val job = launch { retryTtsSynthesis { canceledAttempts++; awaitCancellation() } }
        runCurrent(); job.cancelAndJoin(); advanceUntilIdle()
        assertEquals(1, canceledAttempts)
    }

    @Test fun retryAfterParsesBothHeaderFormsWithoutShorteningLongCooldowns() {
        assertEquals(90000L, ttsRetryAfterMillis("90"))
        assertEquals(5000L, ttsRetryAfterMillis("Thu, 01 Jan 1970 00:00:10 GMT", 5000))
        assertEquals(0L, ttsRetryAfterMillis("Thu, 01 Jan 1970 00:00:01 GMT", 5000))
        assertNull(ttsRetryAfterMillis("invalid"))
        assertNull(ttsRetryAfterMillis("-1"))
    }
}
