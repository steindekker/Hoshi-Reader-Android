package moe.antimony.hoshi.features.bookshelf

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainShellAdaptiveUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun compactWindowUsesBottomNavigationAndReservesBottomSpace() {
        setShellContent(widthDp = 360, heightDp = 780)

        composeRule.onNodeWithTag(LayoutTag).assertTextEquals(MainShellNavigationLayout.BottomBar.name)

        val rootBounds = composeRule.onNodeWithTag(RootTag).getUnclippedBoundsInRoot()
        val contentBounds = composeRule.onNodeWithTag(ContentTag).getUnclippedBoundsInRoot()

        assertTrue(contentBounds.bottom < rootBounds.bottom)
    }

    @Test
    fun mediumWindowUsesNavigationRailAndReservesStartSpace() {
        setShellContent(widthDp = 700, heightDp = 780)

        composeRule.onNodeWithTag(LayoutTag).assertTextEquals(MainShellNavigationLayout.NavigationRail.name)

        val contentBounds = composeRule.onNodeWithTag(ContentTag).getUnclippedBoundsInRoot()

        assertTrue(contentBounds.left > 0.dp)
    }

    @Test
    fun expandedSettingsListStaysVisibleAndConstrained() {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 840.dp, height = 900.dp)
                        .testTag(RootTag),
                ) {
                    SettingsTab(
                        onDestination = {},
                        layoutSpec = MainShellLayoutSpec.forWidthDp(840),
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }

        val rootBounds = composeRule.onNodeWithTag(RootTag).getUnclippedBoundsInRoot()
        val firstRowBounds = composeRule.onNodeWithText("Dictionaries").getUnclippedBoundsInRoot()
        val lastRowBounds = composeRule.onNodeWithText("About").getUnclippedBoundsInRoot()

        assertTrue(firstRowBounds.top > 0.dp)
        assertTrue(lastRowBounds.bottom <= rootBounds.bottom)
        assertTrue(firstRowBounds.width < rootBounds.width)
    }

    private fun setShellContent(widthDp: Int, heightDp: Int) {
        composeRule.setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredSize(width = widthDp.dp, height = heightDp.dp)
                        .testTag(RootTag),
                ) {
                    HoshiMainShell(
                        selectedTab = MainTab.Books,
                        onSelectedTabChange = {},
                        modifier = Modifier.fillMaxSize(),
                    ) { modifier, layoutSpec ->
                        Box(modifier = modifier.testTag(ContentTag)) {
                            Text(
                                text = layoutSpec.navigationLayout.name,
                                modifier = Modifier.testTag(LayoutTag),
                            )
                        }
                    }
                }
            }
        }
    }

    private val DpRect.width
        get() = right - left

    private companion object {
        const val RootTag = "main-shell-root"
        const val ContentTag = "main-shell-content"
        const val LayoutTag = "main-shell-layout"
    }
}
