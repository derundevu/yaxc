package io.github.derundevu.yaxc

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.derundevu.yaxc.activity.LiveRoutingActivity
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.LiveRoutingPlan
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.viewmodel.LiveRoutingViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LiveRoutingEditorInteractionTest {
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun expandedGroupMovesPastShorterGroupWithoutChangingTargetsOrSavingDraft() {
        val first = ProxyGroup(id = "editor-first", name = "First group", profileIds = listOf(4242L))
        val last = ProxyGroup(id = "editor-last", name = "Last group", profileIds = listOf(4242L))
        val rule = AppDomainRule(id = "editor-rule", name = "Domain rule", domains = listOf("example.org"), target = first.id)
        withEditor(LiveRoutingPlan(listOf(first, last), listOf(rule), last.id)) { draft, settings, persisted, _ ->
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
        withEditor(original) { draft, settings, persisted, _ ->
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

    @Test
    fun profilePickerKeepsMembershipOrderUntilClosedAcrossRecreation() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.derundevu.yaxc.regression")
        check(!TProxyService.isActive()) { "Editor interaction must not interrupt an active session" }
        val db = YaxcDatabase.ref(context)
        val source = Link(name = "picker-source-${UUID.randomUUID()}")
        source.id = runBlocking { db.linkDao().insertAtEnd(source) }
        try {
            val availableName = "picker-available-${UUID.randomUUID()}"
            val (available, checked) = runBlocking {
                db.profileDao().insert(Profile(linkId = source.id, index = 0, name = availableName)) to
                    db.profileDao().insert(Profile(linkId = source.id, index = 1, name = "Picker member"))
            }
            val missing = Long.MAX_VALUE
            val group = ProxyGroup(
                id = "picker-group", name = "Picker group", profileIds = listOf(checked, missing),
                selectedProfileId = checked,
            )
            withEditor(LiveRoutingPlan(groups = listOf(group))) { draft, settings, persisted, scenario ->
                val search = compose.onNodeWithTag("live-routing:profile-search")
                val list = compose.onNodeWithTag("live-routing:profiles-list")
                fun indexOf(id: Long) = list.fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("profile:$id")
                fun row(id: Long) = compose.onNodeWithTag("live-routing:profile:$id")
                fun openPicker(count: Int) {
                    compose.onNodeWithText(context.getString(R.string.liveRoutingChooseProfiles, count))
                        .performScrollTo().performClick()
                    compose.waitUntil(5_000) { indexOf(available) >= 0 }
                }

                compose.onNodeWithTag("live-routing:content").performScrollToKey("group:${group.id}")
                compose.onNodeWithTag("live-routing:group-header:${group.id}").performClick()
                openPicker(2)
                assertEquals(1, indexOf(missing))
                assertEquals(2, indexOf(checked))
                assertTrue(indexOf(available) > indexOf(checked))
                list.performScrollToKey("profile:$missing")
                compose.onNodeWithText(context.getString(R.string.liveRoutingMissingProfileLabel)).assertIsDisplayed()

                search.performTextReplacement(" ${availableName.uppercase()} ")
                compose.waitUntil(5_000) { indexOf(available) == 1 && indexOf(checked) == -1 && indexOf(missing) == -1 }
                search.performTextReplacement(source.name.uppercase())
                compose.waitUntil(5_000) { indexOf(checked) == 1 && indexOf(available) == 2 }
                list.performScrollToKey("profile:$available")
                row(available).assertIsOff().performClick()
                assertEquals(1, indexOf(checked))
                assertEquals(2, indexOf(available))

                scenario.recreate()
                compose.waitUntil(5_000) { indexOf(available) >= 0 }
                assertEquals(-1, indexOf(missing))
                assertEquals(1, indexOf(checked))
                assertEquals(2, indexOf(available))
                list.performScrollToKey("profile:$available")
                row(available).assertIsOn()
                compose.onNodeWithText(context.getString(R.string.ok)).performClick()
                val confirmed = group.copy(profileIds = listOf(checked, missing, available))
                compose.runOnIdle {
                    assertEquals(confirmed, draft.plan.groups.single())
                    assertEquals(persisted, settings.liveRoutingPlan)
                }

                val transient = Profile(linkId = source.id, index = 2, name = "Temporary picker profile")
                val transientId = runBlocking { db.profileDao().insert(transient) }
                openPicker(3)
                assertEquals(listOf(1, 2, 3), listOf(missing, available, checked).map(::indexOf))
                compose.waitUntil(5_000) { indexOf(transientId) >= 0 }
                list.performScrollToKey("profile:$transientId")
                row(transientId).assertIsOff().performClick()
                runBlocking { db.profileDao().delete(transient.copy(id = transientId)) }
                compose.waitUntil(5_000) { indexOf(transientId) == 2 }
                list.performScrollToKey("profile:$transientId")
                row(transientId).assertIsOn().performClick()
                compose.waitUntil(5_000) { indexOf(transientId) == -1 }

                list.performScrollToKey("profile:$missing")
                row(missing).assertIsOn().performClick()
                row(missing).assertIsOff()
                assertEquals(1, indexOf(missing))
                compose.onNodeWithContentDescription(context.getString(R.string.cancel)).performClick()
                search.assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals(confirmed, draft.plan.groups.single())
                    assertEquals(persisted, settings.liveRoutingPlan)
                }
            }
        } finally {
            runBlocking { db.linkDao().delete(source) }
        }
    }

    private fun withEditor(
        plan: LiveRoutingPlan,
        block: (LiveRoutingViewModel, Settings, String, ActivityScenario<LiveRoutingActivity>) -> Unit,
    ) {
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
                block(draft, settings, persisted, scenario)
            }
        } finally {
            settings.liveRoutingPlan = previousPlan
            settings.transparentProxy = previousRoot
            settings.tun2socks = previousTun
        }
    }
}
