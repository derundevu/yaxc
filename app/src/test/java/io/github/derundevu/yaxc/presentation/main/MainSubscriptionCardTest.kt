package io.github.derundevu.yaxc.presentation.main

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainSubscriptionCardTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun quotaUsesBothDirectionsClampsOverageAndClearsPreviousSourceAnnouncement() {
        val metadata = mutableStateOf(SubscriptionMetadata(
            uploadBytes = 1_073_741_824L,
            downloadBytes = 4_294_967_296L,
            totalBytes = 21_474_836_480L,
            announce = "Provider maintenance tonight",
        ))
        compose.setContent {
            YaxcTheme { MainScreenTestContent(MainUiState(selectedSourceMetadata = metadata.value), {}) }
        }
        assertEquals(0.25f, progress(), 0.0001f)
        compose.onNodeWithTag("main:subscription-announce", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle {
            metadata.value = SubscriptionMetadata(downloadBytes = 30_064_771_072L, totalBytes = 21_474_836_480L)
        }
        assertEquals(1f, progress(), 0f)
        compose.onNodeWithTag("main:subscription-announce", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun unknownAndUnlimitedQuotasNeverShowAFictitiousProgressBar() {
        val metadata = mutableStateOf(SubscriptionMetadata(downloadBytes = 5_368_709_120L, totalBytes = 0L))
        compose.setContent {
            YaxcTheme { MainScreenTestContent(MainUiState(selectedSourceMetadata = metadata.value), {}) }
        }
        compose.onNodeWithTag("main:subscription-traffic", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("main:subscription-traffic-progress", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { metadata.value = SubscriptionMetadata(downloadBytes = 5_368_709_120L) }
        compose.onNodeWithTag("main:subscription-traffic-progress", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { metadata.value = SubscriptionMetadata(totalBytes = 21_474_836_480L) }
        compose.onNodeWithTag("main:subscription-traffic-progress", useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle { metadata.value = SubscriptionMetadata() }
        compose.onNodeWithTag("main:subscription-traffic", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun resizingMovesTheFollowingCardContinuouslyAndCanReverseBeforeSettling() {
        val subscription = SubscriptionMetadata(
            downloadBytes = 5_368_709_120L,
            totalBytes = 21_474_836_480L,
            announce = "Provider maintenance tonight. Your subscription remains available after maintenance.",
        )
        val metadata = mutableStateOf<SubscriptionMetadata?>(subscription)
        compose.setContent {
            YaxcTheme {
                MainScreenTestContent(
                    MainUiState(
                        tabs = listOf(Link(id = 1, name = "Subscription group")),
                        selectedSourceMetadata = metadata.value,
                        liveRoutingAvailable = true,
                    ), {},
                )
            }
        }
        val expanded = cardHeight()
        val expandedFollowingTop = followingTop()
        val expandedSourceTop = sourceTop()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { metadata.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        val shrinking = cardHeight()
        assertTrue("Height must interpolate instead of snapping", shrinking < expanded)
        assertEquals(expanded - shrinking, expandedFollowingTop - followingTop(), 2f)
        assertEquals(expanded - shrinking, expandedSourceTop - sourceTop(), 2f)
        compose.mainClock.advanceTimeBy(1_500)
        val collapsed = cardHeight()
        assertTrue("Shrink must have an intermediate height", shrinking > collapsed)

        compose.runOnIdle { metadata.value = subscription }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(64)
        val growing = cardHeight()
        assertTrue("Grow must have an intermediate height", growing > collapsed && growing < expanded)
        compose.runOnIdle { metadata.value = null }
        compose.mainClock.advanceTimeByFrame()
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_500)
        assertEquals(collapsed, cardHeight(), 1f)
        compose.mainClock.autoAdvance = true
    }

    private fun cardHeight(): Float = compose.onNodeWithTag("main:connection-analytics")
        .fetchSemanticsNode().boundsInRoot.height

    private fun followingTop(): Float = compose.onNodeWithTag("main:live-routing")
        .fetchSemanticsNode().boundsInRoot.top

    private fun sourceTop(): Float = compose.onNodeWithTag("source:1")
        .fetchSemanticsNode().boundsInRoot.top

    private fun progress(): Float = compose.onNodeWithTag("main:subscription-traffic-progress", useUnmergedTree = true)
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
}
