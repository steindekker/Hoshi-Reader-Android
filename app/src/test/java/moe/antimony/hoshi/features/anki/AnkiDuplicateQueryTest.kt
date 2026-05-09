package moe.antimony.hoshi.features.anki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnkiDuplicateQueryTest {
    @Test
    fun checksumStripsHtmlMediaAndUsesAnkiFirstFieldShaPrefix() {
        assertEquals(ankiFirstFieldChecksum("食べる"), ankiFirstFieldChecksum("<b>食べる</b>"))
        assertEquals(ankiFirstFieldChecksum(" image.png "), ankiFirstFieldChecksum("""<img src="image.png">"""))
        assertEquals(567984260L, ankiFirstFieldChecksum("食べる"))
    }

    @Test
    fun duplicateSelectionIncludesModelUnlessCheckingAllModels() {
        val scoped = ankiDuplicateNoteSelection(modelId = 7L, checksum = 1234L, checkAllModels = false)
        val allModels = ankiDuplicateNoteSelection(modelId = 7L, checksum = 1234L, checkAllModels = true)

        assertTrue(scoped.contains("mid=7"))
        assertTrue(scoped.contains("csum in (1234)"))
        assertFalse(allModels.contains("mid=7"))
        assertEquals("csum in (1234)", allModels)
    }

    @Test
    fun deckRootScopeIncludesSelectedDeckAndChildDecks() {
        val deckIds = ankiDuplicateScopeDeckIds(
            decksById = mapOf(
                1L to "Default",
                2L to "Mining",
                3L to "Mining::Light Novel",
                4L to "Other::Mining",
            ),
            selectedDeck = AnkiDeck(2L, "Mining"),
            duplicateScope = AnkiDuplicateScope.DeckRoot,
        )

        assertEquals(setOf(2L, 3L), deckIds)
    }

    @Test
    fun deckScopeIncludesOnlySelectedDeck() {
        val deckIds = ankiDuplicateScopeDeckIds(
            decksById = mapOf(
                2L to "Mining",
                3L to "Mining::Light Novel",
            ),
            selectedDeck = AnkiDeck(2L, "Mining"),
            duplicateScope = AnkiDuplicateScope.Deck,
        )

        assertEquals(setOf(2L), deckIds)
    }
}
