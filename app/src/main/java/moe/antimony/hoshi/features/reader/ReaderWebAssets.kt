package moe.antimony.hoshi.features.reader

import android.content.Context

internal data class ReaderWebAssets(
    val selectionJs: String,
    val readerPaginatedJs: String,
    val readerContinuousJs: String,
    val highlightsJs: String,
    val readerCss: String,
) {
    companion object {
        @Volatile
        private var cached: ReaderWebAssets? = null

        fun load(context: Context): ReaderWebAssets =
            cached ?: synchronized(this) {
                cached ?: read(context.applicationContext).also { cached = it }
            }

        private fun read(context: Context): ReaderWebAssets =
            ReaderWebAssets(
                selectionJs = context.readAsset("hoshi-web/shared/selection.js"),
                readerPaginatedJs = context.readAsset("hoshi-web/reader/reader-paginated.js"),
                readerContinuousJs = context.readAsset("hoshi-web/reader/reader-continuous.js"),
                highlightsJs = context.readAsset("hoshi-web/reader/highlights.js"),
                readerCss = context.readAsset("hoshi-web/reader/reader.css"),
            )

        private fun Context.readAsset(path: String): String =
            assets.open(path)
                .bufferedReader()
                .use { it.readText() }
    }
}
