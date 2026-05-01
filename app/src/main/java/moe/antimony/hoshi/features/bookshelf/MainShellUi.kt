package moe.antimony.hoshi.features.bookshelf

import moe.antimony.hoshi.epub.BookEntry

enum class MainTab(val label: String) {
    Books("Books"),
    Dictionary("Dictionary"),
    Settings("Settings"),
}

enum class MainShellNavigationLayout {
    BottomBar,
    NavigationRail,
}

data class MainShellLayoutSpec(
    val navigationLayout: MainShellNavigationLayout,
    val pageHorizontalPaddingDp: Int,
    val contentMaxWidthDp: Int,
    val bookGridMinCellWidthDp: Int,
    val bookGridMaxColumns: Int,
    val bookGridSpacingDp: Int,
) {
    fun constrainedContentWidthDp(windowWidthDp: Int): Int =
        windowWidthDp.coerceAtMost(contentMaxWidthDp)

    fun bookGridColumns(contentWidthDp: Int): Int {
        val available = (contentWidthDp - pageHorizontalPaddingDp * 2).coerceAtLeast(bookGridMinCellWidthDp)
        val columns = (available + bookGridSpacingDp) / (bookGridMinCellWidthDp + bookGridSpacingDp)
        return columns.coerceIn(1, bookGridMaxColumns)
    }

    companion object {
        fun forWidthDp(widthDp: Int): MainShellLayoutSpec = when {
            widthDp < MediumWidthBreakpointDp -> MainShellLayoutSpec(
                navigationLayout = MainShellNavigationLayout.BottomBar,
                pageHorizontalPaddingDp = 16,
                contentMaxWidthDp = widthDp,
                bookGridMinCellWidthDp = 148,
                bookGridMaxColumns = 2,
                bookGridSpacingDp = 16,
            )
            widthDp < ExpandedWidthBreakpointDp -> MainShellLayoutSpec(
                navigationLayout = MainShellNavigationLayout.NavigationRail,
                pageHorizontalPaddingDp = 24,
                contentMaxWidthDp = 640,
                bookGridMinCellWidthDp = 176,
                bookGridMaxColumns = 3,
                bookGridSpacingDp = 20,
            )
            else -> MainShellLayoutSpec(
                navigationLayout = MainShellNavigationLayout.NavigationRail,
                pageHorizontalPaddingDp = 32,
                contentMaxWidthDp = 1040,
                bookGridMinCellWidthDp = 176,
                bookGridMaxColumns = 5,
                bookGridSpacingDp = 24,
            )
        }
    }
}

private const val MediumWidthBreakpointDp = 600
private const val ExpandedWidthBreakpointDp = 840

enum class SettingsDestination {
    Dictionaries,
    Anki,
    Appearance,
    Advanced,
    ReportIssue,
    About,
}

data class SettingsRowModel(
    val label: String,
    val destination: SettingsDestination,
)

data class BookshelfSectionModel(
    val title: String,
    val books: List<BookEntry>,
)

fun settingsGroups(): List<List<SettingsRowModel>> = listOf(
    listOf(
        SettingsRowModel("Dictionaries", SettingsDestination.Dictionaries),
        SettingsRowModel("Anki", SettingsDestination.Anki),
        SettingsRowModel("Appearance", SettingsDestination.Appearance),
        SettingsRowModel("Advanced", SettingsDestination.Advanced),
    ),
    listOf(
        SettingsRowModel("Report an Issue", SettingsDestination.ReportIssue),
        SettingsRowModel("About", SettingsDestination.About),
    ),
)

fun bookshelfSections(entries: List<BookEntry>): List<BookshelfSectionModel> =
    if (entries.isEmpty()) {
        emptyList()
    } else {
        listOf(BookshelfSectionModel("Unshelved", entries))
    }
