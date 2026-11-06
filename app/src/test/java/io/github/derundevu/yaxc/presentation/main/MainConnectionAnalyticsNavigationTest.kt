package io.github.derundevu.yaxc.presentation.main

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainConnectionAnalyticsNavigationTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun cardOpensInHostWhilePingAndInfoRemainSeparateAndBackRestoresBothScrollPositions() {
        val actions = mutableListOf<MainAction>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            YaxcTheme {
                MainScreenTestContent(
                    MainUiState(tabs = List(20) { Link(id = it + 1L, name = "Source $it") }),
                    actions::add,
                )
            }
        }
        compose.onNodeWithTag("main:connection-ping").performClick()
        assertEquals(listOf(MainAction.PingClicked), actions)
        compose.onNodeWithTag("analytics:screen").assertDoesNotExist()
        compose.onNodeWithContentDescription(RuntimeEnvironment.getApplication().getString(R.string.mainConnectionInfo)).performClick()
        assertEquals(MainAction.OpenConnectionInfoClicked, actions.last())
        compose.onNodeWithTag("analytics:screen").assertDoesNotExist()

        compose.onNodeWithTag("main:profiles").performTouchInput {
            down(center)
            moveBy(Offset(0f, -40f), delayMillis = 200)
            advanceEventTime(200)
            up()
        }
        compose.waitForIdle()
        val mainScroll = scroll("main:profiles")
        compose.onNodeWithTag("main:connection-analytics").performClick()
        compose.onNodeWithTag("analytics:screen").assertIsDisplayed()
        assertEquals(2, actions.size)
        compose.onNodeWithTag("analytics:list").performScrollToKey("privacy")
        val analyticsScroll = scroll("analytics:list")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("analytics:screen").assertIsDisplayed()
        assertEquals(analyticsScroll, scroll("analytics:list"), 1f)
        compose.onNodeWithTag("analytics:back").performClick()
        compose.onNodeWithTag("analytics:screen").assertDoesNotExist()
        assertEquals(mainScroll, scroll("main:profiles"), 1f)
        compose.onNodeWithTag("main:connection-analytics").performClick()
        assertEquals(analyticsScroll, scroll("analytics:list"), 1f)
    }

    private fun scroll(tag: String): Float = compose.onNodeWithTag(tag)
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
