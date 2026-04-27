package moe.antimony.hoshi.features.reader

internal data class ReaderChapterPosition(
    val index: Int,
    val progress: Double = 0.0,
) {
    fun nextOrNull(lastIndex: Int): ReaderChapterPosition? =
        if (index < lastIndex) copy(index = index + 1, progress = 0.0) else null

    fun previousOrNull(): ReaderChapterPosition? =
        if (index > 0) copy(index = index - 1, progress = 1.0) else null
}
