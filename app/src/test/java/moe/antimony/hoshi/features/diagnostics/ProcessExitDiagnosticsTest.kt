package moe.antimony.hoshi.features.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcessExitDiagnosticsTest {
    @Test
    fun shareTextIncludesAppDeviceAndRecentExitDetails() {
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.1.5",
            versionCode = 105,
            sdkInt = 35,
            records = listOf(
                ProcessExitRecord(
                    timestampMillis = 1_700_000_000_000,
                    reason = ProcessExitReason.JavaCrash,
                    status = 0,
                    importance = 100,
                    pssKb = 12_345,
                    rssKb = 67_890,
                    description = "FATAL EXCEPTION: main",
                    trace = "java.lang.IllegalStateException: boom",
                ),
            ),
        )

        val text = report.toShareText()

        assertTrue(text.contains("Hoshi Diagnostics"))
        assertTrue(text.contains("Package: moe.antimony.hoshi.debug"))
        assertTrue(text.contains("Version: 0.1.5 (105)"))
        assertTrue(text.contains("Android SDK: 35"))
        assertTrue(text.contains("Reason: Java crash"))
        assertTrue(text.contains("Description: FATAL EXCEPTION: main"))
        assertTrue(text.contains("java.lang.IllegalStateException: boom"))
    }

    @Test
    fun shareTextUsesAHelpfulUnsupportedMessageWhenExitHistoryIsUnavailable() {
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.1.5",
            versionCode = 105,
            sdkInt = 29,
            records = emptyList(),
        )

        val text = report.toShareText()

        assertTrue(text.contains("Android SDK: 29"))
        assertTrue(text.contains("Process exit history is available on Android 11 and later."))
    }

    @Test
    fun shareTextIsBoundedForAndroidShareTargets() {
        val longTrace = "x".repeat(ProcessExitDiagnosticsReport.MAX_TRACE_CHARS + 500)
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.1.5",
            versionCode = 105,
            sdkInt = 35,
            records = listOf(
                ProcessExitRecord(
                    timestampMillis = 1_700_000_000_000,
                    reason = ProcessExitReason.NativeCrash,
                    status = 11,
                    importance = 100,
                    pssKb = 0,
                    rssKb = 0,
                    description = null,
                    trace = longTrace,
                ),
            ),
        )

        val text = report.toShareText()

        assertTrue(text.contains("[trace truncated to last ${ProcessExitDiagnosticsReport.MAX_TRACE_CHARS} characters]"))
        assertFalse(text.contains(longTrace))
        assertEquals(ProcessExitDiagnosticsReport.MAX_TRACE_CHARS, text.substringAfter("Trace:\n").takeWhile { it == 'x' }.length)
    }

    @Test
    fun shareTextOmitsUnreadableBinaryTracePayloads() {
        val report = ProcessExitDiagnosticsReport(
            packageName = "moe.antimony.hoshi.debug",
            versionName = "0.1.5",
            versionCode = 105,
            sdkInt = 35,
            records = listOf(
                ProcessExitRecord(
                    timestampMillis = 1_700_000_000_000,
                    reason = ProcessExitReason.NativeCrash,
                    status = 11,
                    importance = 100,
                    pssKb = 0,
                    rssKb = 0,
                    description = "crash",
                    trace = "\u0000\u0001\u0002\u0003\u0004\u0005binary tombstone payload",
                ),
            ),
        )

        val text = report.toShareText()

        assertTrue(text.contains("Reason: Native crash"))
        assertTrue(text.contains("Description: crash"))
        assertFalse(text.contains("Trace:"))
        assertFalse(text.contains("binary tombstone payload"))
    }

    @Test
    fun exportFileNameIsStableAndSafeForAndroidDocumentPicker() {
        assertEquals(
            "hoshi-diagnostics-20231114-221320.txt",
            diagnosticsExportFileName(1_700_000_000_000),
        )
    }
}
