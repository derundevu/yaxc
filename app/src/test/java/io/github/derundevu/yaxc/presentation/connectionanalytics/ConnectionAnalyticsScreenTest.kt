package io.github.derundevu.yaxc.presentation.connectionanalytics

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTextInput
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.repository.ConnectionApp
import io.github.derundevu.yaxc.repository.ConnectionCaptureStatus
import io.github.derundevu.yaxc.repository.ConnectionHistoryState
import io.github.derundevu.yaxc.repository.ConnectionRecord
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConnectionAnalyticsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rootOnlyShowsSummariesAndSelectedScopeOpensCapturedDetailsOnDemand() {
        val old = ConnectionRecord(id = "before", uid = 123, startedAt = 100,
            apps = listOf(ConnectionApp("same.package", "Before app label")),
            profileId = 7, profileFingerprint = "captured-fingerprint", profileName = "Before config label",
            outboundTag = "before-outbound", route = "proxy", protocol = "tcp", destination = "203.0.113.1:443")
        val records = listOf(old, old.copy(id = "after", startedAt = 200,
            apps = listOf(ConnectionApp("same.package", "After app label")), profileName = "After config label"),
            old.copy(id = "foreign", uid = 999, apps = listOf(ConnectionApp("other.package", "Other app"))))
        val history = ConnectionHistoryState(records)
        val query = mutableStateOf(ConnectionAnalyticsQuery())
        val root = connectionAnalyticsState(history, query.value)
        val selected = root.summaries.first { it.totals.count == 2 }
        compose.setContent {
            YaxcTheme {
                ConnectionAnalyticsScreen(connectionAnalyticsState(history, query.value), {}, { query.value = it }, {}, {}, {})
            }
        }
        compose.onNodeWithTag("flow:before").assertDoesNotExist()
        compose.onNodeWithTag(selected.key).performClick()
        compose.onNodeWithTag("flow:before").assertIsDisplayed()
        compose.onNodeWithTag("flow:foreign").assertDoesNotExist()
        compose.onNodeWithTag("analytics:detail").assertDoesNotExist()
        compose.onNodeWithTag("flow:before").performClick()
        compose.onNodeWithTag("analytics:detail").assertIsDisplayed()
        compose.onNodeWithTag("analytics:detail").assertTextContains("before-outbound", substring = true)
            .assertTextContains("Before app label", substring = true)
            .assertTextContains("Before config label", substring = true)
        compose.onNodeWithTag("analytics:copy").performClick()
        compose.runOnIdle {
            val clipboard = RuntimeEnvironment.getApplication().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
            assertTrue(copied.contains("Before app label"))
            assertTrue(copied.contains("Before config label"))
            assertTrue(copied.contains("before-outbound"))
            assertTrue(copied.contains("203.0.113.1:443"))
        }
    }

    @Test
    fun distantSelectedFlowKeepsItsStableScrollAnchorAcrossCountersAndRecreation() {
        val records = List(500) { index ->
            ConnectionRecord(id = "retained-$index", uid = 123, apps = listOf(ConnectionApp("captured.package", "Captured app")),
                profileId = 7, profileName = "Captured config", startedAt = index.toLong(),
                destination = "203.0.113.1:443", protocol = "tcp", route = "proxy")
        }
        val root = connectionAnalyticsState(ConnectionHistoryState(records), ConnectionAnalyticsQuery())
        val query = root.query.copy(selection = root.summaries.single().identity)
        val state = mutableStateOf(connectionAnalyticsState(ConnectionHistoryState(records), query))
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            YaxcTheme { ConnectionAnalyticsScreen(state.value, {}, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("flow:retained-0").assertDoesNotExist()
        compose.onNodeWithTag("analytics:list").performScrollToKey("flow:retained-0")
        compose.onNodeWithTag("flow:retained-0").assertIsDisplayed()
        val before = scroll()
        val updated = connectionAnalyticsState(ConnectionHistoryState(records.map { it.copy(upload = 1234, download = 5678) }), query)
        compose.runOnIdle { state.value = updated }
        compose.onNodeWithTag("flow:retained-0").assertIsDisplayed()
        assertEquals(before, scroll(), 1f)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("flow:retained-0").assertIsDisplayed()
        assertEquals(before, scroll(), 1f)
    }

    @Test
    fun searchAndProtocolControlsCombineWithoutChangingTheRootFilterOnBack() {
        val records = listOf(
            ConnectionRecord(id = "tcp", uid = 1, protocol = "tcp", destination = "203.0.113.1:443", state = "closed"),
            ConnectionRecord(id = "udp", uid = 1, protocol = "udp", destination = "203.0.113.1:443", state = "closed"),
            ConnectionRecord(id = "elsewhere", uid = 1, protocol = "udp", destination = "198.51.100.1:53", state = "closed"))
        val history = ConnectionHistoryState(records)
        val root = connectionAnalyticsState(history, ConnectionAnalyticsQuery(rootFilter = ConnectionFilter.Closed))
        val query = mutableStateOf(root.query.copy(selection = root.summaries.single().identity, filter = ConnectionFilter.Closed))
        compose.setContent {
            YaxcTheme { ConnectionAnalyticsScreen(connectionAnalyticsState(history, query.value), {}, { query.value = it }, {}, {}, {}) }
        }
        compose.onNodeWithTag("analytics:search").performTextInput("443")
        compose.onNodeWithTag("analytics:choice:protocol").performClick()
        compose.onNodeWithTag("analytics:option:protocol:2").performClick()
        compose.onNodeWithTag("flow:tcp").assertDoesNotExist()
        compose.onNodeWithTag("flow:elsewhere").assertDoesNotExist()
        compose.onNodeWithTag("flow:udp").assertIsDisplayed()
        compose.onNodeWithTag("analytics:back").performClick()
        compose.runOnIdle {
            assertEquals(null, query.value.selection)
            assertEquals(ConnectionFilter.Closed, query.value.rootFilter)
        }
        compose.onNodeWithTag(root.summaries.single().key).assertIsDisplayed()
    }

    @Test
    fun captureSwitchWaitsForRepositoryTruthAndDisabledHistoryRemainsNavigable() {
        val record = ConnectionRecord(id = "retained", uid = 123, state = "interrupted",
            destination = "203.0.113.1:443", upload = 321, liveBytesExact = false)
        val history = mutableStateOf(ConnectionHistoryState(
            connections = listOf(record), status = ConnectionCaptureStatus.Running, persistOnDisk = true,
        ))
        val query = mutableStateOf(ConnectionAnalyticsQuery())
        val captureIntents = mutableListOf<Boolean>()
        val persistenceIntents = mutableListOf<Boolean>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            YaxcTheme {
                ConnectionAnalyticsScreen(
                    state = connectionAnalyticsState(history.value, query.value),
                    onBack = {},
                    onQuery = { query.value = it },
                    onCaptureEnabled = captureIntents::add,
                    onPersistence = persistenceIntents::add,
                    onClearHistory = {},
                )
            }
        }
        compose.onNodeWithTag("analytics:history").performClick()
        compose.onNodeWithTag("analytics:capture").assertIsOn().performClick()
        compose.runOnIdle {
            assertEquals(listOf(false), captureIntents)
            assertTrue(persistenceIntents.isEmpty())
            history.value = history.value.copy(status = ConnectionCaptureStatus.Error, error = "Toggle failed")
        }
        // A failed request must not optimistically change the switch or the independent disk policy.
        compose.onNodeWithTag("analytics:capture").assertIsOn()
        compose.onNodeWithTag("analytics:persistence").performScrollTo().assertIsOn()
        compose.runOnIdle {
            history.value = history.value.copy(status = ConnectionCaptureStatus.Disabled, captureEnabled = false, error = null)
        }
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("analytics:capture").performScrollTo().assertIsOff()
        compose.onNodeWithTag("analytics:persistence").performScrollTo().assertIsOn()
        val context = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(context.getString(R.string.ca_done)).performClick()
        compose.onNodeWithTag("analytics:status").assertTextContains(context.getString(R.string.ca_disabled))
        val summary = connectionAnalyticsState(history.value, query.value).summaries.single()
        compose.onNodeWithTag(summary.key).performClick()
        compose.onNodeWithTag("flow:retained").performClick()
        compose.onNodeWithTag("analytics:detail").assertIsDisplayed()
            .assertTextContains(record.destination, substring = true)
    }

    private fun scroll(): Float = compose.onNodeWithTag("analytics:list")
        .fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
}
