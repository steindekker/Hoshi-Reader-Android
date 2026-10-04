package moe.antimony.hoshi.features.texthooker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextHookerLineSelectionTest {
    private val one = TextHookerLine(1, "一")
    private val two = TextHookerLine(2, "二")
    private val three = TextHookerLine(3, "三")

    @Test
    fun firstLinesSelectTheLatest() {
        val selection = TextHookerLineSelection().reconcile(listOf(one, two), lookupActive = false)
        assertEquals(two, selection.displayedLine)
        assertTrue(selection.followLatest)
    }

    @Test
    fun followingAdvancesWhenNoLookupIsActive() {
        val selection = TextHookerLineSelection(two).reconcile(listOf(one, two, three), lookupActive = false)
        assertEquals(three, selection.displayedLine)
        assertFalse(selection.hasNewerLine(listOf(one, two, three)))
    }

    @Test
    fun activeLookupKeepsTheLineAndOffersNewerLine() {
        val lines = listOf(one, two, three)
        val selection = TextHookerLineSelection(two).reconcile(lines, lookupActive = true)
        assertEquals(two, selection.displayedLine)
        assertTrue(selection.hasNewerLine(lines))
        assertEquals(three, selection.jumpToLatest(lines).displayedLine)
    }

    @Test
    fun selectingAnOlderLinePinsItUntilJumpingToLatest() {
        val lines = listOf(one, two)
        val pinned = TextHookerLineSelection(two).select(one, lines)
        assertFalse(pinned.followLatest)
        val afterNewLine = pinned.reconcile(lines + three, lookupActive = false)
        assertEquals(one, afterNewLine.displayedLine)
        assertTrue(afterNewLine.hasNewerLine(lines + three))
        val latest = afterNewLine.jumpToLatest(lines + three)
        assertEquals(three, latest.displayedLine)
        assertTrue(latest.followLatest)
        assertTrue(TextHookerLineSelection(one).select(two, lines).followLatest)
    }
}
