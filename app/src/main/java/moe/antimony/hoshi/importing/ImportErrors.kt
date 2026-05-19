package moe.antimony.hoshi.importing

import android.content.Context

fun Throwable.localizedImportMessage(context: Context, fallback: String): String =
    if (this is UnsupportedImportFileTypeException) {
        context.getString(messageRes)
    } else {
        localizedMessage ?: fallback
    }
