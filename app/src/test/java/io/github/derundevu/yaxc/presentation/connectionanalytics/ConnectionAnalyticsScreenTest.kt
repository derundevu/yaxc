package io.github.derundevu.yaxc.presentation.connectionanalytics

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToKey
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.repository.ConnectionApp
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.repository.ConnectionHistoryState
import io.github.derundevu.yaxc.repository.ConnectionRecord
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
class ConnectionAnalyticsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun distantFlowHasItsOwnLazyKeyAndLiveCountersAndRecreationKeepItsScrollAnchor() {
        val records = List(500) { index ->
            ConnectionRecord(
                id = "retained-$index", uid = 123, apps = listOf(ConnectionApp("captured.package", "Captured app")),
                profileId = 7, profileName = "Captured config", startedAt = index.toLong(),
                destination = "203.0.113.1:443", protocol = "tcp", route = "proxy",
            )
        }
        val state = mutableStateOf(connectionAnalyticsState(ConnectionHistoryState(records), ConnectionGrouping.App, ConnectionFilter.All))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            YaxcTheme {
                ConnectionAnalyticsScreen(state.value, rememberLazyListState(), {}, {}, {}, {}, {})
            }
        }
        compose.onNodeWithTag("flow:retained-499").assertDoesNotExist()
        compose.onNodeWithTag("analytics:list").performScrollToKey("flow:retained-499")
        compose.onNodeWithTag("flow:retained-499").assertIsDisplayed()
        val before = scroll()
        val updated = connectionAnalyticsState(
            ConnectionHistoryState(records.map { it.copy(upload = 1234, download = 5678) }),
            ConnectionGrouping.App, ConnectionFilter.All,
        )
        compose.runOnIdle { state.value = updated }
        compose.onNodeWithTag("flow:retained-499").assertIsDisplayed()
        assertEquals(before, scroll(), 1f)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("flow:retained-499").assertIsDisplayed()
        assertEquals(before, scroll(), 1f)
    }

    @Test
    fun renamedAppAndConfigLabelsStillRenderBothFlowsWithTheirOwnCapturedRoute() {
        val older = ConnectionRecord(
            id = "before-rename", uid = 123, startedAt = 100,
            apps = listOf(ConnectionApp("same.package", "Before app label")),
            profileId = 7, profileFingerprint = "same-fingerprint", profileName = "Before config label",
            outboundTag = "before-outbound", route = "proxy", protocol = "tcp", destination = "203.0.113.1:443",
        )
        val newer = older.copy(
            id = "after-rename", startedAt = 200,
            apps = listOf(ConnectionApp("same.package", "After app label")),
            profileName = "After config label", outboundTag = "after-outbound", route = "direct",
        )
        val history = ConnectionHistoryState(listOf(newer, older))
        val state = mutableStateOf(connectionAnalyticsState(history, ConnectionGrouping.App, ConnectionFilter.All))
        compose.setContent {
            YaxcTheme {
                ConnectionAnalyticsScreen(state.value, rememberLazyListState(), {}, {}, {}, {}, {})
            }
        }
        val app = RuntimeEnvironment.getApplication()
        ConnectionGrouping.entries.forEach { grouping ->
            val presentation = connectionAnalyticsState(history, grouping, ConnectionFilter.All)
            compose.runOnIdle { state.value = presentation }
            listOf(older, newer).forEach { record ->
                val tag = "flow:${record.id}"
                compose.onNodeWithTag("analytics:list").performScrollToKey(tag)
                compose.onNode(hasText(record.apps.single().name, substring = true) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
                compose.onNode(hasText(record.profileName) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
                compose.onNode(hasText(app.getString(R.string.connectionAnalyticsOutbound, record.outboundTag)) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
                val route = app.getString(if (record.route == "proxy") R.string.connectionAnalyticsProxy else R.string.connectionAnalyticsDirect)
                compose.onNode(hasText(app.getString(R.string.connectionAnalyticsRoute, route)) and hasAnyAncestor(hasTestTag(tag))).assertIsDisplayed()
            }
        }
    }

    private fun scroll(): Float = compose.onNodeWithTag("analytics:list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
