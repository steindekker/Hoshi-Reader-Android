package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookMetadata
import moe.antimony.hoshi.epub.BookEntry
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class MainShellUiTest {
    @Test
    fun mainTabsMatchIosOrder() {
        assertEquals(listOf("Books", "Dictionary", "Settings"), MainTab.entries.map { it.label })
    }

    @Test
    fun settingsGroupsMatchIosSettingsEntryOrder() {
        val groups = settingsGroups()

        assertEquals(
            listOf("Dictionaries", "Anki", "Appearance", "Advanced"),
            groups.first().map { it.label },
        )
        assertEquals(listOf("Report an Issue", "About"), groups.last().map { it.label })
    }

    @Test
    fun importedBooksAreDisplayedInUnshelvedSection() {
        val entries = listOf(
            BookEntry(
                root = File("book-a"),
                metadata = BookMetadata(
                    id = "a",
                    title = "屍人荘の殺人",
                    cover = null,
                    folder = "book-a",
                    lastAccess = 1.0,
                ),
            ),
        )

        val sections = bookshelfSections(entries)

        assertEquals(1, sections.size)
        assertEquals("Unshelved", sections.single().title)
        assertEquals(entries, sections.single().books)
    }

    @Test
    fun compactWindowsUseBottomNavigationAndTwoBookColumns() {
        val spec = MainShellLayoutSpec.forWidthDp(360)

        assertEquals(MainShellNavigationLayout.BottomBar, spec.navigationLayout)
        assertEquals(16, spec.pageHorizontalPaddingDp)
        assertEquals(2, spec.bookGridColumns(contentWidthDp = 360))
    }

    @Test
    fun mediumWindowsUseNavigationRailAndConstrainedBookGrid() {
        val spec = MainShellLayoutSpec.forWidthDp(700)

        assertEquals(MainShellNavigationLayout.NavigationRail, spec.navigationLayout)
        assertEquals(640, spec.contentMaxWidthDp)
        assertEquals(3, spec.bookGridColumns(contentWidthDp = 640))
    }

    @Test
    fun expandedWindowsUseNavigationRailAndCapBookGridColumns() {
        val spec = MainShellLayoutSpec.forWidthDp(1200)

        assertEquals(MainShellNavigationLayout.NavigationRail, spec.navigationLayout)
        assertEquals(1040, spec.contentMaxWidthDp)
        assertEquals(5, spec.bookGridColumns(contentWidthDp = 1040))
    }
}
