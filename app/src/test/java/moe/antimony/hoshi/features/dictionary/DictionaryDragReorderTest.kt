package moe.antimony.hoshi.features.dictionary

import org.junit.Assert.assertEquals
import org.junit.Test

class DictionaryDragReorderTest {
    @Test
    fun draggedCenterPastNextRowTargetsNextDictionary() {
        val target = DictionaryDragReorder.targetIndex(
            startIndex = 0,
            draggedCenterY = 150f,
            visibleRows = listOf(
                DictionaryDragReorder.RowBounds(index = 0, top = 0f, bottom = 100f),
                DictionaryDragReorder.RowBounds(index = 1, top = 100f, bottom = 200f),
            ),
        )

        assertEquals(1, target)
    }

    @Test
    fun draggedCenterBeforePreviousRowTargetsPreviousDictionary() {
        val target = DictionaryDragReorder.targetIndex(
            startIndex = 1,
            draggedCenterY = 50f,
            visibleRows = listOf(
                DictionaryDragReorder.RowBounds(index = 0, top = 0f, bottom = 100f),
                DictionaryDragReorder.RowBounds(index = 1, top = 100f, bottom = 200f),
            ),
        )

        assertEquals(0, target)
    }

    @Test
    fun missingVisibleRowsKeepOriginalIndex() {
        val target = DictionaryDragReorder.targetIndex(
            startIndex = 1,
            draggedCenterY = 500f,
            visibleRows = emptyList(),
        )

        assertEquals(1, target)
    }
}
