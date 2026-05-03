package moe.antimony.hoshi.features.reader

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ReaderWebViewSourceTest {
    @Test
    fun sasayakiPlayerReceivesCurrentBookCoverFile() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val playerCreation = source.substringAfter("SasayakiPlayer(")
            .substringBefore("matchData = sasayakiMatchData")

        assertTrue(source.contains("val sasayakiCoverFile = remember(bookRoot, book.coverHref)"))
        assertTrue(source.contains("private fun resolveBookCoverFile(bookRoot: File?, coverHref: String?): File?"))
        assertTrue(playerCreation.contains("bookCoverFile = sasayakiCoverFile"))
    }

    @Test
    fun sasayakiPlayerDisposalKeepsReaderLifecycleScopedInstance() {
        val source = File("src/main/java/moe/antimony/hoshi/features/reader/ReaderWebView.kt").readText()
        val playerSection = source.substringAfter("var sasayakiPlayer by remember")
            .substringBefore("LaunchedEffect(showReaderMenu, showSasayaki)")

        assertFalse(source.contains("rememberUpdatedState(sasayakiPlayer)"))
        assertFalse(playerSection.contains("currentSasayakiPlayer"))
        assertTrue(playerSection.contains("onDispose { sasayakiPlayer?.release() }"))
    }
}
