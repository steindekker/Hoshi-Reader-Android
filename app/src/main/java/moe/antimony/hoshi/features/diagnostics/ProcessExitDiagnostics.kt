package moe.antimony.hoshi.features.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.annotation.RequiresApi
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

enum class ProcessExitReason(val label: String) {
    Anr("ANR"),
    JavaCrash("Java crash"),
    NativeCrash("Native crash"),
    LowMemory("Low memory kill"),
    ExcessiveResourceUsage("Excessive resource usage"),
    InitializationFailure("Initialization failure"),
    UserRequested("User requested"),
    Signaled("Signaled"),
    Other("Other"),
    Unknown("Unknown"),
}

data class ProcessExitRecord(
    val timestampMillis: Long,
    val reason: ProcessExitReason,
    val status: Int,
    val importance: Int,
    val pssKb: Long,
    val rssKb: Long,
    val description: String?,
    val trace: String?,
)

data class ProcessExitDiagnosticsReport(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val sdkInt: Int,
    val records: List<ProcessExitRecord>,
) {
    fun toShareText(): String = buildString {
        appendLine("Hoshi Diagnostics")
        appendLine("Package: $packageName")
        appendLine("Version: $versionName ($versionCode)")
        appendLine("Android SDK: $sdkInt")
        appendLine()

        if (records.isEmpty()) {
            if (sdkInt < Build.VERSION_CODES.R) {
                appendLine("Process exit history is available on Android 11 and later.")
            } else {
                appendLine("No recent process exits recorded by Android.")
            }
            return@buildString
        }

        records.forEachIndexed { index, record ->
            appendLine("Exit ${index + 1}")
            appendLine("Time: ${Instant.ofEpochMilli(record.timestampMillis)}")
            appendLine("Reason: ${record.reason.label}")
            appendLine("Status: ${record.status}")
            appendLine("Importance: ${record.importance}")
            appendLine("PSS: ${record.pssKb} kB")
            appendLine("RSS: ${record.rssKb} kB")
            record.description?.takeIf { it.isNotBlank() }?.let {
                appendLine("Description: $it")
            }
            record.trace?.readableDiagnosticTextOrNull()?.let {
                appendLine("Trace:")
                appendLine(it.takeBoundedTrace())
            }
            if (index != records.lastIndex) appendLine()
        }
    }

    private fun String.takeBoundedTrace(): String =
        if (length <= MAX_TRACE_CHARS) {
            this
        } else {
            "${takeLast(MAX_TRACE_CHARS)}\n[trace truncated to last $MAX_TRACE_CHARS characters]"
        }

    companion object {
        const val MAX_TRACE_CHARS = 12_000
        const val MAX_TRACE_BYTES = 64_000
    }
}

fun loadProcessExitDiagnosticsReport(context: Context, maxRecords: Int = 10): ProcessExitDiagnosticsReport {
    val packageInfo = context.packageManager.getHoshiPackageInfo(context.packageName)
    val records = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        loadProcessExitRecords(context, maxRecords)
    } else {
        emptyList()
    }
    return ProcessExitDiagnosticsReport(
        packageName = context.packageName,
        versionName = packageInfo.versionName ?: "unknown",
        versionCode = packageInfo.hoshiLongVersionCode(),
        sdkInt = Build.VERSION.SDK_INT,
        records = records,
    )
}

fun diagnosticsExportFileName(nowMillis: Long = System.currentTimeMillis()): String =
    "hoshi-diagnostics-${DiagnosticsFileNameFormatter.format(Instant.ofEpochMilli(nowMillis))}.txt"

@RequiresApi(Build.VERSION_CODES.R)
private fun loadProcessExitRecords(context: Context, maxRecords: Int): List<ProcessExitRecord> {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    return activityManager
        .getHistoricalProcessExitReasons(context.packageName, 0, maxRecords)
        .map { it.toProcessExitRecord() }
}

@RequiresApi(Build.VERSION_CODES.R)
private fun ApplicationExitInfo.toProcessExitRecord(): ProcessExitRecord = ProcessExitRecord(
    timestampMillis = timestamp,
    reason = reason.toProcessExitReason(),
    status = status,
    importance = importance,
    pssKb = pss,
    rssKb = rss,
    description = description,
    trace = readTraceText(),
)

@RequiresApi(Build.VERSION_CODES.R)
private fun ApplicationExitInfo.readTraceText(): String? =
    try {
        traceInputStream
            ?.use { it.readBytes(ProcessExitDiagnosticsReport.MAX_TRACE_BYTES) }
            ?.toString(Charsets.UTF_8)
            ?.readableDiagnosticTextOrNull()
    } catch (_: IOException) {
        null
    }

private fun String.readableDiagnosticTextOrNull(): String? {
    if (isBlank()) return null
    val badCharacters = count {
        it == '\uFFFD' || (Character.isISOControl(it) && it != '\n' && it != '\r' && it != '\t')
    }
    if (badCharacters.toDouble() / length.toDouble() > 0.05) return null

    val recognizableCharacters = count {
        it.isLetterOrDigit() ||
            it.isWhitespace() ||
            when (it) {
                '.', ',', ':', ';', '/', '\\', '-', '_', '+', '#', '$', '%',
                '(', ')', '[', ']', '{', '}', '<', '>', '=', '"', '\'' -> true
                else -> false
            }
    }
    return if (recognizableCharacters.toDouble() / length.toDouble() >= 0.75) this else null
}

private fun java.io.InputStream.readBytes(maxBytes: Int): ByteArray {
    val buffer = ByteArray(maxBytes)
    var offset = 0
    while (offset < maxBytes) {
        val read = read(buffer, offset, maxBytes - offset)
        if (read == -1) break
        offset += read
    }
    return buffer.copyOf(offset)
}

@RequiresApi(Build.VERSION_CODES.R)
private fun Int.toProcessExitReason(): ProcessExitReason = when (this) {
    ApplicationExitInfo.REASON_ANR -> ProcessExitReason.Anr
    ApplicationExitInfo.REASON_CRASH -> ProcessExitReason.JavaCrash
    ApplicationExitInfo.REASON_CRASH_NATIVE -> ProcessExitReason.NativeCrash
    ApplicationExitInfo.REASON_LOW_MEMORY -> ProcessExitReason.LowMemory
    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ProcessExitReason.ExcessiveResourceUsage
    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ProcessExitReason.InitializationFailure
    ApplicationExitInfo.REASON_USER_REQUESTED -> ProcessExitReason.UserRequested
    ApplicationExitInfo.REASON_SIGNALED -> ProcessExitReason.Signaled
    ApplicationExitInfo.REASON_OTHER -> ProcessExitReason.Other
    else -> ProcessExitReason.Unknown
}

@Suppress("DEPRECATION")
private fun android.content.pm.PackageManager.getHoshiPackageInfo(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, android.content.pm.PackageManager.PackageInfoFlags.of(0))
    } else {
        getPackageInfo(packageName, 0)
    }

@Suppress("DEPRECATION")
private fun PackageInfo.hoshiLongVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

private val DiagnosticsFileNameFormatter: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)
