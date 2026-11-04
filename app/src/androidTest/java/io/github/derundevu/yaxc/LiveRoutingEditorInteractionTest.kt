package io.github.derundevu.yaxc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.derundevu.yaxc.activity.LiveRoutingActivity
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.LiveRoutingPlan
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.viewmodel.LiveRoutingViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LiveRoutingEditorInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun expandedGroupMovesPastShorterGroupWithoutChangingTargetsOrSavingDraft() {
        val first = ProxyGroup(id = "editor-first", name = "First group", profileIds = listOf(4242L))
        val last = ProxyGroup(id = "editor-last", name = "Last group", profileIds = listOf(4242L))
        val rule = AppDomainRule(id = "editor-rule", name = "Domain rule", domains = listOf("example.org"), target = first.id)
        withEditor(LiveRoutingPlan(listOf(first, last), listOf(rule), last.id)) { draft, settings, persisted ->
                val content = compose.onNodeWithTag("live-routing:content")
                content.performScrollToKey("group:${first.id}")
                compose.onNodeWithTag("live-routing:group-header:${first.id}").performClick()
                content.performScrollToKey("group:${first.id}")
                compose.waitForIdle()
                val from = compose.onNodeWithTag("live-routing:group-header:${first.id}")
                val to = compose.onNodeWithTag("live-routing:group-header:${last.id}")
                from.assertIsDisplayed()
                to.assertIsDisplayed()
                val distance = to.fetchSemanticsNode().boundsInRoot.center.y -
                    from.fetchSemanticsNode().boundsInRoot.center.y
                from.performTouchInput {
                    down(Offset(width / 4f, height / 2f))
                    advanceEventTime(700)
                    moveBy(Offset(0f, distance), delayMillis = 400)
                }
                compose.runOnIdle {
                    assertEquals(listOf(first, last), draft.plan.groups)
                    assertEquals(last.id, draft.plan.defaultGroupId)
                    assertEquals(listOf(rule), draft.plan.rules)
                    assertEquals(persisted, settings.liveRoutingPlan)
                }
                from.performTouchInput { up() }
                compose.runOnIdle {
                    assertEquals(listOf(last, first), draft.plan.groups)
                    assertEquals(last.id, draft.plan.defaultGroupId)
                    assertEquals(listOf(rule), draft.plan.rules)
                    assertEquals(persisted, settings.liveRoutingPlan)
                }
                val firstBounds = compose.onNodeWithTag("live-routing:group:${first.id}").fetchSemanticsNode().boundsInRoot
                val lastBounds = compose.onNodeWithTag("live-routing:group:${last.id}").fetchSemanticsNode().boundsInRoot
                assertTrue("Dropped expanded card must occupy the final slot", lastBounds.top < firstBounds.top)
        }
    }

    @Test
    fun cancellingRulePreviewAtSectionBoundaryKeepsTargetsDefaultsAndPrecedence() {
        val group = ProxyGroup(id = "cancel-group", name = "Proxy group", profileIds = listOf(4242L))
        val first = AppDomainRule(id = "cancel-first", name = "First rule", domains = listOf("first.invalid"), target = group.id)
        val last = AppDomainRule(id = "cancel-last", name = "Last rule", domains = listOf("last.invalid"), target = "direct", enabled = false)
        val original = LiveRoutingPlan(listOf(group), listOf(first, last), group.id)
        withEditor(original) { draft, settings, persisted ->
            compose.onNodeWithTag("live-routing:content").performScrollToKey("rule:${first.id}")
            val from = compose.onNodeWithTag("live-routing:rule-header:${first.id}")
            val to = compose.onNodeWithTag("live-routing:rule-header:${last.id}")
            val distance = to.fetchSemanticsNode().boundsInRoot.top - from.fetchSemanticsNode().boundsInRoot.top
            from.performTouchInput {
                down(Offset(width / 4f, height / 2f))
                advanceEventTime(700)
                moveBy(Offset(0f, distance + 200f), delayMillis = 400)
            }
            compose.runOnIdle {
                assertEquals(original, draft.plan)
                assertEquals(persisted, settings.liveRoutingPlan)
            }
            from.performTouchInput { cancel() }
            compose.runOnIdle {
                assertEquals(original, draft.plan)
                assertEquals(persisted, settings.liveRoutingPlan)
            }
            val firstBounds = compose.onNodeWithTag("live-routing:rule:${first.id}").fetchSemanticsNode().boundsInRoot
            val lastBounds = compose.onNodeWithTag("live-routing:rule:${last.id}").fetchSemanticsNode().boundsInRoot
            assertTrue("Cancelled card must return to its original slot", firstBounds.top < lastBounds.top)
        }
    }

    private fun withEditor(plan: LiveRoutingPlan, block: (LiveRoutingViewModel, Settings, String) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.derundevu.yaxc.regression")
        check(!TProxyService.isActive()) { "Editor interaction must not interrupt an active session" }
        val settings = Settings(context)
        val previousPlan = settings.liveRoutingPlan
        val previousRoot = settings.transparentProxy
        val previousTun = settings.tun2socks
        val persisted = LiveRoutingHelper.encode(plan)
        settings.transparentProxy = false
        settings.tun2socks = true
        settings.liveRoutingPlan = persisted
        try {
            ActivityScenario.launch(LiveRoutingActivity::class.java).use { scenario ->
                lateinit var draft: LiveRoutingViewModel
                scenario.onActivity { draft = ViewModelProvider(it)[LiveRoutingViewModel::class.java] }
                compose.waitUntil(20_000) { !draft.isLoading && !draft.appsLoading }
                block(draft, settings, persisted)
            }
        } finally {
            settings.liveRoutingPlan = previousPlan
            settings.transparentProxy = previousRoot
            settings.tun2socks = previousTun
        }
    }
}
