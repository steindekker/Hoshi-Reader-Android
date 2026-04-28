package moe.antimony.hoshi.features.dictionary

internal object DictionaryDragReorder {
    data class RowBounds(
        val index: Int,
        val top: Float,
        val bottom: Float,
    )

    fun targetIndex(
        startIndex: Int,
        draggedCenterY: Float,
        visibleRows: List<RowBounds>,
    ): Int {
        if (visibleRows.isEmpty()) return startIndex
        return visibleRows
            .minBy { row ->
                val center = (row.top + row.bottom) / 2f
                kotlin.math.abs(center - draggedCenterY)
            }
            .index
    }
}
