package moe.antimony.hoshi.features.dictionary

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import java.io.File
import moe.antimony.hoshi.ui.theme.hoshiSurfaces
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import moe.antimony.hoshi.R
import moe.antimony.hoshi.ui.UiText
import moe.antimony.hoshi.ui.theme.HoshiReaderTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Isolated history fixtures: never reads or modifies the installed app's history. */
class DictionaryLookupHistoryViewTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun historyReplaysFullTextAndSupportsDeleteAndConfirmedClear() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val history = DictionaryLookupHistoryRepository(DictionaryHistoryDeviceStore(), scope, Dispatchers.Unconfined)
        val fullText = "猫のいる長い文章。".repeat(30) + "\n次の行も残す。"
        history.record(fullText)
        history.record("later")
        val selected = mutableListOf<String>()
        val search = mutableStateOf(DictionarySearchUiState())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            composeRule.setContent {
                val state by history.state.collectAsState()
                HoshiReaderTheme {
                    DictionaryLookupHistoryContent(
                        searchState = search.value,
                        historyState = state,
                        onSelect = { selected += it; history.record(it); search.value = DictionarySearchUiState(query = it) },
                        onDelete = history::delete,
                        onClear = history::clear,
                        onRetry = history::retryLoad,
                    )
                }
            }
            composeRule.onNodeWithText(fullText).performClick()
            composeRule.runOnIdle { assertEquals(listOf(fullText), selected) }
            composeRule.onNodeWithText(context.getString(R.string.dictionary_history_title)).assertDoesNotExist()
            composeRule.runOnIdle { search.value = DictionarySearchUiState() }
            composeRule.runOnIdle { assertEquals(listOf(fullText, "later"), history.state.value.entries) }
            composeRule.onAllNodesWithContentDescription(context.getString(R.string.action_delete))[0].performClick()
            composeRule.runOnIdle { assertEquals(listOf("later"), history.state.value.entries) }
            composeRule.onNodeWithText(context.getString(R.string.action_clear)).performClick()
            composeRule.onNodeWithText(context.getString(R.string.dictionary_history_clear_confirm)).assertIsDisplayed()
            composeRule.onNodeWithText(context.getString(R.string.action_cancel)).performClick()
            composeRule.runOnIdle { assertEquals(listOf("later"), history.state.value.entries) }
            composeRule.onNodeWithText(context.getString(R.string.action_clear)).performClick()
            composeRule.onAllNodesWithText(context.getString(R.string.action_clear))[1].performClick()
            composeRule.runOnIdle { assertTrue(history.state.value.entries.isEmpty()) }
            composeRule.onNodeWithText(context.getString(R.string.dictionary_history_title)).assertDoesNotExist()
        } finally { scope.cancel() }
    }

    @Test fun darkAndEInkHistoryRemainReadable() {
        val eInk = mutableStateOf(false)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            HoshiReaderTheme(darkTheme = true, eInkMode = eInk.value) {
                DictionaryLookupHistoryContent(
                    searchState = DictionarySearchUiState(),
                    historyState = DictionaryLookupHistoryState(
                        entries = listOf("窓の向こうに、猫がいる。", "A complete original sentence.\nIts second line stays intact."),
                        isLoaded = true,
                    ),
                    onSelect = {}, onDelete = {}, onClear = {}, onRetry = {},
                    modifier = Modifier.fillMaxSize().background(hoshiSurfaces.page),
                )
            }
        }
        for ((ink, name) in listOf(false to "dark", true to "eink")) {
            composeRule.runOnIdle { eInk.value = ink }
            composeRule.onNodeWithText("窓の向こうに、猫がいる。").assertIsDisplayed()
            val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
            File(context.cacheDir, "lookup-history-$name.png").outputStream().use {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }

    @Test fun hidesHistoryWhileTypingSearchingOrShowingQueryError() {
        val search = mutableStateOf(DictionarySearchUiState())
        composeRule.setContent {
            HoshiReaderTheme(eInkMode = true) {
                DictionaryLookupHistoryContent(
                    searchState = search.value,
                    historyState = DictionaryLookupHistoryState(entries = listOf("saved sentence"), isLoaded = true),
                    onSelect = {}, onDelete = {}, onClear = {}, onRetry = {},
                )
            }
        }
        composeRule.onNodeWithText("saved sentence").assertIsDisplayed()
        listOf(
            DictionarySearchUiState(query = "typing"),
            DictionarySearchUiState(isSearching = true),
            DictionarySearchUiState(lastQuery = "missing", hasSearched = true),
            DictionarySearchUiState(errorMessage = UiText.Literal("lookup failed")),
        ).forEach { state ->
            composeRule.runOnIdle { search.value = state }
            composeRule.onNodeWithText("saved sentence").assertDoesNotExist()
        }
        composeRule.runOnIdle { search.value = DictionarySearchUiState() }
        composeRule.onNodeWithText("saved sentence").assertIsDisplayed()
    }
}

internal class DictionaryHistoryDeviceStore : DataStore<Preferences> {
    override val data = MutableStateFlow(emptyPreferences())
    override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
        return transform(data.value).also { data.value = it }
    }
}
