package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class IssueOneContrastRegressionTest {
    @Test
    fun mainShellProvidesReadableContentColorOnAppBackground() {
        val source = File("src/main/java/moe/antimony/hoshi/features/bookshelf/BookshelfView.kt").readText()
        val shell = source.substringAfter("internal fun HoshiMainShell(")
            .substringBefore("private val NavigationRailInset")

        assertTrue(shell.contains("NavigationSuiteScaffold("))
        assertTrue(shell.contains("containerColor = MaterialTheme.colorScheme.background"))
        assertTrue(shell.contains("contentColor = MaterialTheme.colorScheme.onBackground"))
    }

    @Test
    fun chapterSheetProvidesReadableContentColorOnScrimmedSurface() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderChapterSheet.kt").readText()
        val sheet = source.substringAfter("ModalBottomSheet(")
            .substringBefore(") {\n        LazyColumn")

        assertTrue(sheet.contains("containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f)"))
        assertTrue(sheet.contains("contentColor = MaterialTheme.colorScheme.onSurface"))
    }

    @Test
    fun appearanceSettingsScreenHandlesSystemBackLikeToolbarBack() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderAppearanceView.kt").readText()
        val screen = source.substringAfter("internal fun ReaderAppearanceScreen(")
            .substringBefore("@OptIn(ExperimentalMaterial3Api::class)\n@Composable\ninternal fun ReaderAppearanceSheet(")

        assertTrue(screen.contains("BackHandler(onBack = onClose)"))
    }
}
