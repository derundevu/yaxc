package io.github.derundevu.yaxc.presentation.main

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.ProfileList
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w1024dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AdaptiveMainUiTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun tvTwoPaneUsesSharedConnectionCardActionBubblesAndFloatingConnectButton() {
        val app = RuntimeEnvironment.getApplication()
        val actions = mutableListOf<MainAction>()
        val source = Link(id = 10L, name = "Shared Sub")
        val profile = MainProfileItem(
            profile = ProfileList(id = 20L, index = 0, name = "Node Alpha", link = 10L),
            summary = "vless",
            pingState = MainPingState.Idle,
        )
        val state = MainUiState(
            tabs = listOf(source),
            selectedTabId = 10L,
            selectedSourceId = 10L,
            selectedSourceName = "Shared Sub",
            selectedSourceMetadata = SubscriptionMetadata(
                downloadBytes = 2_147_483_648L,
                totalBytes = 10_737_418_240L,
                announce = "TV banner",
            ),
            selectedProfileId = 20L,
            selectedProfileName = "Node Alpha",
            filteredProfiles = listOf(profile),
        )

        compose.setContent {
            val tvConfig = Configuration(LocalConfiguration.current).apply {
                uiMode = (uiMode and Configuration.UI_MODE_TYPE_MASK.inv()) or Configuration.UI_MODE_TYPE_TELEVISION
            }
            CompositionLocalProvider(LocalConfiguration provides tvConfig) {
                YaxcTheme(style = YaxcThemeStyle.Mint) {
                    MainScreenTestContent(state = state, onAction = actions::add)
                }
            }
        }

        compose.onNodeWithTag("main:connection-analytics").assertIsDisplayed()
        compose.onNodeWithTag("main:connection-ping").assertIsDisplayed().performClick()
        assertTrue(MainAction.PingClicked in actions)
        compose.onNodeWithText(app.getString(R.string.mainConnectionInfo)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.refreshLinks)).assertIsDisplayed()

        compose.onNodeWithTag("main:subscription-traffic", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("main:subscription-announce", useUnmergedTree = true).assertIsDisplayed()

        compose.onNodeWithTag("main:adaptive:connect").assertIsDisplayed().performClick()
        assertTrue(MainAction.ToggleVpnClicked in actions)

        compose.onNodeWithTag("source:10:menu").assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.mainBatchPingAction)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.connectionDismiss)).performClick()
        compose.onNodeWithTag("profile:20:menu").assertIsDisplayed()

        compose.onNodeWithTag("main:adaptive:add").assertIsDisplayed().performClick()
        compose.onNodeWithText(app.getString(R.string.deviceTransferTitle)).assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.connectionDismiss)).performClick()

        compose.onNodeWithTag("main:rail:routing").performClick()
        compose.onNodeWithText(app.getString(R.string.liveRoutingEntryLead)).assertIsDisplayed()
        compose.onNodeWithTag("main:rail:settings").performClick()
        compose.onNodeWithTag("main:tv:settings:about").assertIsDisplayed().performClick()
        compose.onNodeWithTag("settings:versions").assertIsDisplayed()
    }

    @Test
    fun singlePaneTabletUsesSharedBottomConnectBarAndConnectionCard() {
        val actions = mutableListOf<MainAction>()
        val source = Link(id = 1L, name = "Tablet Sub")
        val state = MainUiState(
            tabs = listOf(source),
            selectedTabId = 1L,
            selectedSourceId = 1L,
            selectedSourceName = "Tablet Sub",
            selectedProfileName = "Tablet Node",
        )

        compose.setContent {
            YaxcTheme(style = YaxcThemeStyle.Mint) {
                Box(Modifier.size(width = 700.dp, height = 900.dp)) {
                    Box(Modifier.fillMaxSize()) {
                        MainScreenTestContent(state = state, onAction = actions::add)
                    }
                }
            }
        }

        compose.onNodeWithTag("main:connection-analytics").assertIsDisplayed()
        compose.onNodeWithTag("main:adaptive:add").assertIsDisplayed()
        compose.onNodeWithTag("main:adaptive:connect").assertIsDisplayed().performClick()
        assertTrue(MainAction.ToggleVpnClicked in actions)
    }
}
