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

enum class MainShellTextStyle {
    BodyLarge,
    TitleMedium,
    TitleLarge,
}

enum class MainShellFontWeight {
    Normal,
    Medium,
    SemiBold,
}

data class MainShellLayoutSpec(
    val navigationLayout: MainShellNavigationLayout,
    val pageHorizontalPaddingDp: Int,
    val contentMaxWidthDp: Int,
    val bookGridMinCellWidthDp: Int,
    val bookGridMaxColumns: Int,
    val bookGridSpacingDp: Int,
    val compactNavigationHeightDp: Int,
    val shelfTitleTextStyle: MainShellTextStyle,
    val shelfTitleFontWeight: MainShellFontWeight,
    val shelfCountTextStyle: MainShellTextStyle,
    val shelfHeaderVerticalPaddingDp: Int,
    val bookGridTopPaddingDp: Int,
    val bookGridVerticalSpacingDp: Int,
    val bookGridBottomPaddingDp: Int,
    val bookTitleTextStyle: MainShellTextStyle,
    val bookTitleFontWeight: MainShellFontWeight,
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
                compactNavigationHeightDp = 64,
                shelfTitleTextStyle = MainShellTextStyle.TitleLarge,
                shelfTitleFontWeight = MainShellFontWeight.SemiBold,
                shelfCountTextStyle = MainShellTextStyle.TitleMedium,
                shelfHeaderVerticalPaddingDp = 0,
                bookGridTopPaddingDp = 0,
                bookGridVerticalSpacingDp = 12,
                bookGridBottomPaddingDp = 0,
                bookTitleTextStyle = MainShellTextStyle.BodyLarge,
                bookTitleFontWeight = MainShellFontWeight.Normal,
            )
            widthDp < ExpandedWidthBreakpointDp -> MainShellLayoutSpec(
                navigationLayout = MainShellNavigationLayout.NavigationRail,
                pageHorizontalPaddingDp = 24,
                contentMaxWidthDp = 640,
                bookGridMinCellWidthDp = 176,
                bookGridMaxColumns = 3,
                bookGridSpacingDp = 20,
                compactNavigationHeightDp = 64,
                shelfTitleTextStyle = MainShellTextStyle.TitleLarge,
                shelfTitleFontWeight = MainShellFontWeight.SemiBold,
                shelfCountTextStyle = MainShellTextStyle.TitleMedium,
                shelfHeaderVerticalPaddingDp = 0,
                bookGridTopPaddingDp = 0,
                bookGridVerticalSpacingDp = 12,
                bookGridBottomPaddingDp = 0,
                bookTitleTextStyle = MainShellTextStyle.BodyLarge,
                bookTitleFontWeight = MainShellFontWeight.Normal,
            )
            else -> MainShellLayoutSpec(
                navigationLayout = MainShellNavigationLayout.NavigationRail,
                pageHorizontalPaddingDp = 32,
                contentMaxWidthDp = 1040,
                bookGridMinCellWidthDp = 176,
                bookGridMaxColumns = 5,
                bookGridSpacingDp = 24,
                compactNavigationHeightDp = 64,
                shelfTitleTextStyle = MainShellTextStyle.TitleLarge,
                shelfTitleFontWeight = MainShellFontWeight.SemiBold,
                shelfCountTextStyle = MainShellTextStyle.TitleMedium,
                shelfHeaderVerticalPaddingDp = 0,
                bookGridTopPaddingDp = 0,
                bookGridVerticalSpacingDp = 12,
                bookGridBottomPaddingDp = 0,
                bookTitleTextStyle = MainShellTextStyle.BodyLarge,
                bookTitleFontWeight = MainShellFontWeight.Normal,
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
    Diagnostics,
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
        SettingsRowModel("Diagnostics", SettingsDestination.Diagnostics),
        SettingsRowModel("About", SettingsDestination.About),
    ),
)

fun bookshelfSections(entries: List<BookEntry>): List<BookshelfSectionModel> =
    if (entries.isEmpty()) {
        emptyList()
    } else {
        listOf(BookshelfSectionModel("Unshelved", entries))
    }
