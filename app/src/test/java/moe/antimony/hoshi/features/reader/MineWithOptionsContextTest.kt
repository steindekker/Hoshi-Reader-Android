package moe.antimony.hoshi.features.reader

import moe.antimony.hoshi.features.anki.AnkiMiningContext
import moe.antimony.hoshi.features.anki.AnkiScreenshotRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MineWithOptionsContextTest {
    private val base = AnkiMiningContext(
        sentence = "in-book sentence",
        documentTitle = "Book",
        coverPath = "cover.jpg",
        sentenceOffset = 3,
    )

    @Test
    fun pickedSentenceOverridesSentenceAndClearsOffset() {
        val result = augmentedMiningContext(base, picked = "例文です。", image = MineImageChoice.Cover)
        assertEquals("例文です。", result.sentence)
        assertNull(result.sentenceOffset)
        assertEquals("Book", result.documentTitle)
    }

    @Test
    fun coverChoiceKeepsCoverAndClearsWebImage() {
        val result = augmentedMiningContext(base, picked = null, image = MineImageChoice.Cover)
        assertEquals("cover.jpg", result.coverPath)
        assertNull(result.webImageUrl)
        assertEquals("in-book sentence", result.sentence)  // null pick keeps the base sentence…
        assertEquals(3, result.sentenceOffset)              // …and its offset
    }

    @Test
    fun webChoiceSetsUrlAndDropsCover() {
        val result = augmentedMiningContext(base, picked = null, image = MineImageChoice.Web("https://x/a.jpg"))
        assertEquals("https://x/a.jpg", result.webImageUrl)
        assertNull(result.coverPath)
        assertEquals("in-book sentence", result.sentence)
    }

    @Test
    fun noneChoiceClearsBothImages() {
        val result = augmentedMiningContext(base, picked = "例文です。", image = MineImageChoice.None)
        assertNull(result.coverPath)
        assertNull(result.webImageUrl)
        assertEquals("例文です。", result.sentence)
    }

    private val vnBase = AnkiMiningContext(
        sentence = "game sentence",
        sentenceOffset = 2,
        screenshot = AnkiScreenshotRequest(lineId = 7),
    )

    @Test
    fun screenshotChoiceKeepsRequestWithPreviewPath() {
        val result = augmentedMiningContext(vnBase, picked = null, image = MineImageChoice.Screenshot("/cache/p.jpg"))
        assertEquals(AnkiScreenshotRequest(lineId = 7, localPath = "/cache/p.jpg"), result.screenshot)
        assertNull(result.webImageUrl)
        assertEquals(2, result.sentenceOffset)
    }

    @Test
    fun webAndNoneChoicesDropTheScreenshot() {
        val web = augmentedMiningContext(vnBase, picked = null, image = MineImageChoice.Web("https://x/a.jpg"))
        assertNull(web.screenshot)
        assertEquals("https://x/a.jpg", web.webImageUrl)
        assertNull(augmentedMiningContext(vnBase, picked = null, image = MineImageChoice.None).screenshot)
    }
}
