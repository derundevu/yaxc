package io.github.derundevu.yaxc.viewmodel

import androidx.lifecycle.viewModelScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.LiveRoutingPlan
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class LiveRoutingViewModelTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val models = mutableListOf<LiveRoutingViewModel>()

    private fun clearModels() {
        models.forEach { it.viewModelScope.cancel() }
    }

    @Test
    fun nativeRuntimeTransitionsExposeOnlyObservedParticipantsAndFailuresClearHealthyStatus() = runEditorTest {
        val group = ProxyGroup(id = "health", name = "Health", profileIds = listOf(7, 8),
            selectedProfileId = 7, checkAvailability = true)
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(groups = listOf(group)))
        var active = true
        var reads = 0
        var failure = false
        var response = runtimeJson("checking")
        val editor = editor(
            runtimeStatusReader = {
                reads++
                check(!failure) { "controlled status failure" }
                response
            },
            runtimeActive = { active },
        )
        fun tick() {
            mainDispatcher.dispatcher.scheduler.advanceTimeBy(1_000)
            mainDispatcher.dispatcher.scheduler.runCurrent()
        }
        assertTrue(editor.runtimeStatus.running)
        assertEquals("checking", editor.runtimeStatus.groups.single().state)
        assertNull(editor.runtimeStatus.groups.single().activeProfileId)
        assertTrue(editor.runtimeStatus.groups.single().eligibleProfileIds.isEmpty())

        response = runtimeJson("available", active = 8, eligible = listOf(8), first = "unavailable", second = "available")
        tick()
        val available = editor.runtimeStatus.groups.single()
        assertEquals(7L, available.selectedProfileId)
        assertEquals(8L, available.activeProfileId)
        assertEquals(listOf(8L), available.eligibleProfileIds)
        assertEquals("fingerprint-8", available.members.last().fingerprint)
        assertEquals("unavailable", available.members.first().state)
        assertEquals(120L, available.members.last().delayMs)

        failure = true
        tick()
        val failed = editor.runtimeStatus.groups.single()
        assertEquals("error", failed.state)
        assertNull(failed.activeProfileId)
        assertTrue(failed.eligibleProfileIds.isEmpty())
        assertTrue(failed.members.all { it.state == "checking" })

        failure = false
        response = runtimeJson("unavailable", first = "unavailable", second = "unavailable")
        tick()
        assertEquals("unavailable", editor.runtimeStatus.groups.single().state)
        assertTrue(editor.runtimeStatus.groups.single().eligibleProfileIds.isEmpty())
        response = runtimeJson("available", active = 7, eligible = listOf(7, 8), first = "available", second = "available")
        tick()
        assertEquals(7L, editor.runtimeStatus.groups.single().activeProfileId)

        active = false
        val beforeStopped = reads
        tick()
        assertFalse(editor.runtimeStatus.running)
        assertTrue(editor.runtimeStatus.groups.isEmpty())
        assertEquals(beforeStopped, reads)
        editor.viewModelScope.cancel()
        active = true
        tick()
        assertEquals(beforeStopped, reads)
    }

    @Test
    fun malformedNativeStatusNeverBecomesAvailableAndPollingRecoversWithoutReplacingDraft() = runEditorTest {
        var response = """{"running":"true","groups":[]}"""
        val editor = editor(runtimeStatusReader = { response }, runtimeActive = { true })
        editor.addRule()
        val draft = editor.plan
        assertTrue(editor.runtimeStatus.running)
        assertTrue(editor.runtimeStatus.groups.isEmpty())
        response = runtimeJson("available", active = 7, eligible = listOf(7), first = "available")
        mainDispatcher.dispatcher.scheduler.advanceTimeBy(1_000)
        mainDispatcher.dispatcher.scheduler.runCurrent()
        assertEquals(7L, editor.runtimeStatus.groups.single().activeProfileId)
        assertEquals(draft, editor.plan)
        assertTrue(editor.hasChanges)
    }

    @Test
    fun availabilityToggleAndOrderedMembershipStayUnsavedUntilSuccessfulSaveAndReopen() = runEditorTest {
        val one = database.profileDao().insert(Profile(name = "First member", config = "{}"))
        val two = database.profileDao().insert(Profile(name = "Second member", config = "{}"))
        val group = ProxyGroup(id = "health", name = "Health", profileIds = listOf(one, two), selectedProfileId = one)
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(groups = listOf(group)))
        val editor = editor()
        assertEquals(group, editor.savedPlan?.groups?.single())
        editor.updateGroup(group.copy(checkAvailability = true))
        editor.selectProfiles(group.id, listOf(two, one), two)
        assertFalse(editor.savedPlan!!.groups.single().checkAvailability)
        assertEquals(listOf(one, two), editor.savedPlan!!.groups.single().profileIds)
        assertTrue(editor.hasChanges)
        save(editor)
        val saved = LiveRoutingHelper.decode(settings.liveRoutingPlan)
        assertTrue(saved.groups.single().checkAvailability)
        assertEquals(listOf(two, one), saved.groups.single().profileIds)
        assertEquals(two, saved.groups.single().selectedProfileId)
        assertEquals(saved, editor.savedPlan)
        assertEquals(saved, editor().plan)
        assertFalse(editor.runtimeStatus.running)
    }

    @Test
    fun visibleAndInactiveProfileFingerprintsChangeWhenSameLogicalIdGetsDifferentSourceConfig() = runEditorTest {
        val source = database.linkDao().insert(Link(name = "Inactive source", isActive = false))
        val local = Profile(name = "Visible", config = """{"outbounds":[{"protocol":"freedom"}]}""")
        val inactive = Profile(linkId = source, name = "Inactive", config = """{"outbounds":[{"protocol":"blackhole"}]}""")
        val localId = database.profileDao().insert(local)
        val inactiveId = database.profileDao().insert(inactive)
        val editor = editor()
        fun awaitFingerprints(expected: Map<Long, String>) {
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10)
            while (expected.any { (id, fingerprint) -> editor.profiles.none { it.id == id && it.fingerprint == fingerprint } }) {
                mainDispatcher.dispatcher.scheduler.runCurrent()
                check(System.nanoTime() < deadline) { "Profile display fingerprints did not update" }
                Thread.sleep(1)
            }
        }
        awaitFingerprints(mapOf(localId to LiveRoutingHelper.profileFingerprint(local.config),
            inactiveId to LiveRoutingHelper.profileFingerprint(inactive.config)))
        val first = editor.profiles.associate { it.id to it.fingerprint }
        val changedLocal = local.copy(id = localId, config = local.config + "\n")
        val changedInactive = inactive.copy(id = inactiveId, config = inactive.config + "\n")
        database.profileDao().update(changedLocal)
        database.profileDao().update(changedInactive)
        awaitFingerprints(mapOf(localId to LiveRoutingHelper.profileFingerprint(changedLocal.config),
            inactiveId to LiveRoutingHelper.profileFingerprint(changedInactive.config)))
        assertFalse(first.getValue(localId) == editor.profiles.single { it.id == localId }.fingerprint)
        assertFalse(first.getValue(inactiveId) == editor.profiles.single { it.id == inactiveId }.fingerprint)
    }

    @Test
    fun orderedRulesDisabledDraftAndMultilineEditingSurviveSaveAndReopen() = runEditorTest {
        val first = AppDomainRule(name = "First", domains = listOf("first.test"), target = "direct")
        val disabled = AppDomainRule(name = "Disabled draft", enabled = false)
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(rules = listOf(first, disabled)))
        val editor = editor()
        editor.addRule()
        val added = editor.plan.rules.last()
        editor.updateDomains(added.id, "domain:second.test\nfull:exact.test\n")
        assertEquals("domain:second.test\nfull:exact.test\n", editor.domainText(editor.plan.rules.last()))
        editor.selectTarget(added.id, "block")
        editor.moveRule(2, 0)
        save(editor)

        assertFalse(editor.hasChanges)
        assertNotNull(editor.notice)
        val reopened = editor()
        assertEquals(listOf(added.id, first.id, disabled.id), reopened.plan.rules.map { it.id })
        assertEquals(listOf("domain:second.test", "full:exact.test"), reopened.plan.rules.first().domains)
        assertEquals("block", reopened.plan.rules.first().target)
        assertEquals(disabled, reopened.plan.rules.last())
        reopened.deleteRule(first.id)
        save(reopened)
        assertEquals(listOf(added.id, disabled.id), editor().plan.rules.map { it.id })
    }

    @Test
    fun reorderingGroupsChangesOnlyAutomaticDefaultAndPreservesExplicitDefaultAndRuleTargets() = runEditorTest {
        val first = ProxyGroup(id = "first", name = "First", profileIds = listOf(7))
        val second = ProxyGroup(id = "second", name = "Second", profileIds = listOf(7))
        val third = ProxyGroup(id = "third", name = "Third", profileIds = listOf(7))
        val rules = listOf(
            AppDomainRule(id = "first-rule", name = "First rule", domains = listOf("first.test"), target = first.id),
            AppDomainRule(id = "second-rule", name = "Second rule", enabled = false, target = second.id),
        )
        val initial = LiveRoutingPlan(groups = listOf(first, second, third), rules = rules)
        val persisted = LiveRoutingHelper.encode(initial)
        settings.liveRoutingPlan = persisted
        val editor = editor()

        editor.moveGroup(-1, 0)
        editor.moveGroup(0, 3)
        assertEquals(initial, editor.plan)
        assertFalse(editor.hasChanges)

        editor.moveGroup(2, 0)
        assertEquals(listOf(third, first, second), editor.plan.groups)
        assertNull(editor.plan.defaultGroupId)
        assertEquals(third, editor.plan.defaultGroup)
        assertEquals(rules, editor.plan.rules)
        assertEquals(initial, editor.savedPlan)
        assertEquals(persisted, settings.liveRoutingPlan)
        assertTrue(editor.hasChanges)

        editor.selectDefaultGroup(second.id)
        editor.moveGroup(2, 0)
        assertEquals(listOf(second, third, first), editor.plan.groups)
        assertEquals(second.id, editor.plan.defaultGroupId)
        assertEquals(second, editor.plan.defaultGroup)
        editor.moveGroup(0, 2)
        assertEquals(listOf(third, first, second), editor.plan.groups)
        assertEquals(second.id, editor.plan.defaultGroupId)
        assertEquals(second, editor.plan.defaultGroup)
        assertEquals(rules, editor.plan.rules)
        assertEquals(persisted, settings.liveRoutingPlan)
    }

    @Test
    fun disabledReferencesPreventGroupDeletionUntilRuleRemoved() = runEditorTest {
        val group = ProxyGroup(name = "Synthetic group", profileIds = listOf(7), selectedProfileId = 7)
        val rule = AppDomainRule(enabled = false, target = group.id)
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(listOf(group), listOf(rule)))
        val editor = editor()
        editor.deleteGroup(group.id)
        assertEquals(listOf(group), editor.plan.groups)
        assertEquals(R.string.liveRoutingGroupInUse, editor.error)
        editor.deleteRule(rule.id)
        editor.deleteGroup(group.id)
        assertTrue(editor.plan.groups.isEmpty())
        save(editor)
        assertTrue(LiveRoutingHelper.decode(settings.liveRoutingPlan).groups.isEmpty())
    }

    @Test
    fun explicitDefaultGroupPersistsWithoutSelectingAProfileAndRequiresReassignmentBeforeRemoval() =
        runEditorTest {
            val id = database.profileDao().insert(Profile(name = "Synthetic member", config = "{}"))
            val first = ProxyGroup(name = "First", profileIds = listOf(id))
            val second = ProxyGroup(name = "Second", profileIds = listOf(id))
            settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(groups = listOf(first, second)))
            settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
            val editor = editor()
            assertEquals(first, editor.plan.defaultGroup)
            assertNull(editor.plan.defaultGroupId)

            editor.selectDefaultGroup("missing-group")
            assertFalse(editor.hasChanges)
            assertEquals(first, editor.plan.defaultGroup)
            editor.selectDefaultGroup(second.id)
            assertTrue(editor.hasChanges)
            assertEquals(second, editor.plan.defaultGroup)
            editor.deleteGroup(second.id)
            assertEquals(listOf(first, second), editor.plan.groups)
            assertEquals(R.string.liveRoutingDefaultGroupInUse, editor.error)
            save(editor)

            assertEquals(LiveRoutingHelper.CONNECTION_PROFILE_ID, settings.selectedProfile)
            val reopened = editor()
            assertEquals(second.id, reopened.plan.defaultGroupId)
            assertEquals(second, reopened.plan.defaultGroup)
            reopened.selectDefaultGroup(null)
            reopened.deleteGroup(second.id)
            save(reopened)
            val automatic = editor()
            assertNull(automatic.plan.defaultGroupId)
            assertEquals(listOf(first), automatic.plan.groups)
            assertEquals(first, automatic.plan.defaultGroup)
            assertEquals(LiveRoutingHelper.CONNECTION_PROFILE_ID, settings.selectedProfile)
        }

    @Test
    fun removingImplicitDefaultPromotesNextGroupAndPersistsAutomaticSelection() = runEditorTest {
        val id = database.profileDao().insert(Profile(name = "Synthetic member", config = "{}"))
        val first = ProxyGroup(name = "First", profileIds = listOf(id))
        val second = ProxyGroup(name = "Second", profileIds = listOf(id))
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(groups = listOf(first, second)))
        val editor = editor()
        editor.deleteGroup(first.id)
        assertNull(editor.plan.defaultGroupId)
        assertEquals(second, editor.plan.defaultGroup)
        save(editor)

        val reopened = editor()
        assertNull(reopened.plan.defaultGroupId)
        assertEquals(second, reopened.plan.defaultGroup)
        reopened.deleteGroup(second.id)
        save(reopened)
        val empty = editor()
        assertTrue(empty.plan.groups.isEmpty())
        assertNull(empty.plan.defaultGroup)
    }

    @Test
    fun staleDraftNeverOverwritesAnotherEditorsSave() = runEditorTest {
        val rule = AppDomainRule(name = "Original", domains = listOf("original.test"))
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(rules = listOf(rule)))
        val first = editor()
        val stale = editor()
        first.updateDomains(rule.id, "first.test")
        stale.updateDomains(rule.id, "stale.test")
        save(first)
        val saved = settings.liveRoutingPlan
        save(stale)
        assertEquals(saved, settings.liveRoutingPlan)
        assertEquals(R.string.liveRoutingStaleDraft, stale.error)
        assertTrue(stale.canReload)
        assertTrue(stale.hasChanges)
        stale.reload()
        mainDispatcher.dispatcher.scheduler.runCurrent()
        assertEquals(first.plan, stale.plan)
        assertFalse(stale.hasChanges)
    }

    @Test
    fun invalidRuleAndCorruptStoredPlanAreNeverWrittenOver() = runEditorTest {
        val editor = editor()
        val before = settings.liveRoutingPlan
        editor.addRule()
        save(editor)
        assertEquals(R.string.liveRoutingEmptyRule, editor.error)
        assertEquals(before, settings.liveRoutingPlan)

        settings.liveRoutingPlan = "not-json"
        val corrupt = editor()
        assertEquals(R.string.liveRoutingLoadFailed, corrupt.error)
        corrupt.addRule()
        save(corrupt)
        assertEquals("not-json", settings.liveRoutingPlan)
    }

    @Test
    fun savingFreezesDraftAndIgnoresDuplicateSave() = runEditorTest {
        val rule = AppDomainRule(name = "Captured", domains = listOf("captured.test"))
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(rules = listOf(rule)))
        val editor = editor()
        val collectors = children(editor)
        editor.save()
        assertTrue(editor.isBusy)
        editor.updateRule(rule.copy(name = "Must not replace snapshot"))
        editor.addGroup()
        editor.deleteRule(rule.id)
        editor.updateDomains(rule.id, "newer.test")
        editor.save()
        children(editor).filterNot { it in collectors }.joinAll()
        assertEquals(listOf(rule), LiveRoutingHelper.decode(settings.liveRoutingPlan).rules)
        assertEquals(listOf(rule), editor.plan.rules)
        assertFalse(editor.isBusy)
    }

    @Test
    fun missingGroupMemberFailsWithoutSelectingReplacement() = runEditorTest {
        val group = ProxyGroup(name = "Missing", profileIds = listOf(701, 702), selectedProfileId = 701)
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(groups = listOf(group)))
        val before = settings.liveRoutingPlan
        val editor = editor()
        editor.updateGroup(group.copy(name = "Edited missing"))
        save(editor)
        assertEquals(R.string.liveRoutingMissingProfiles, editor.error)
        assertEquals(before, settings.liveRoutingPlan)
        assertEquals(701L, editor.plan.groups.single().selectedProfileId)
        assertEquals(listOf(701L, 702L), editor.plan.groups.single().profileIds)
    }

    @Test
    fun inactiveSubscriptionMembersRemainValidSavedGroupMembers() = runEditorTest {
        val source = database.linkDao().insert(Link(name = "Inactive synthetic source", isActive = false))
        val id = database.profileDao().insert(Profile(linkId = source, name = "Synthetic profile", config = "{}"))
        val group = ProxyGroup(name = "Across subscriptions", profileIds = listOf(id), selectedProfileId = id)
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(groups = listOf(group)))
        val editor = editor()
        editor.updateGroup(group.copy(name = "Still available"))
        save(editor)
        assertEquals("Still available", LiveRoutingHelper.decode(settings.liveRoutingPlan).groups.single().name)
        assertEquals(id, LiveRoutingHelper.decode(settings.liveRoutingPlan).groups.single().selectedProfileId)
    }

    @Test
    fun rootModeRejectsSaveAndSocksModeAcceptsDomainsButRejectsAppCriteria() = runEditorTest {
        val rule = AppDomainRule(name = "Domain", domains = listOf("example.test"))
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(rules = listOf(rule)))
        val before = settings.liveRoutingPlan
        val editor = editor()
        editor.updateRule(rule.copy(name = "Edited"))
        settings.transparentProxy = true
        save(editor)
        assertEquals(R.string.liveRoutingRootUnavailable, editor.error)
        assertEquals(before, settings.liveRoutingPlan)
        settings.transparentProxy = false
        settings.tun2socks = false
        save(editor)
        assertEquals("Edited", LiveRoutingHelper.decode(settings.liveRoutingPlan).rules.single().name)
        val domainsSaved = settings.liveRoutingPlan
        editor.selectPackages(rule.id, listOf("synthetic.application"))
        save(editor)
        assertEquals(R.string.liveRoutingAppsUnavailable, editor.error)
        assertEquals(domainsSaved, settings.liveRoutingPlan)
    }

    @Test
    @Config(sdk = [28])
    fun applicationCriteriaCannotBeSavedBelowAndroidQ() = runEditorTest {
        val rule = AppDomainRule(name = "App", packages = listOf("synthetic.application"))
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(rules = listOf(rule)))
        val before = settings.liveRoutingPlan
        val editor = editor()
        editor.updateRule(rule.copy(name = "Edited"))
        save(editor)
        assertEquals(R.string.liveRoutingAppsUnavailable, editor.error)
        assertEquals(before, settings.liveRoutingPlan)
    }

    private fun runEditorTest(block: suspend TestScope.() -> Unit) = runTest(mainDispatcher.dispatcher) {
        try {
            block()
        } finally {
            // Stop recurring pollers before runTest drains its virtual-time scheduler.
            clearModels()
        }
    }

    private fun editor(
        runtimeStatusReader: () -> String = { error("Stopped JVM fixture must never load native status") },
        runtimeActive: () -> Boolean = { false },
    ): LiveRoutingViewModel {
        val dispatcher = StandardTestDispatcher(mainDispatcher.dispatcher.scheduler)
        return LiveRoutingViewModel(app, dispatcher, dispatcher, runtimeStatusReader, runtimeActive).also {
            models += it
            mainDispatcher.dispatcher.scheduler.runCurrent()
            assertFalse(it.isLoading)
        }
    }

    private fun children(editor: LiveRoutingViewModel): List<Job> =
        editor.viewModelScope.coroutineContext[Job]!!.children.toList()

    private suspend fun save(editor: LiveRoutingViewModel) {
        val collectors = children(editor)
        editor.save()
        children(editor).filterNot { it in collectors }.joinAll()
        assertFalse(editor.isBusy)
    }

    private fun runtimeJson(
        state: String, active: Long? = null, eligible: List<Long> = emptyList(),
        first: String = "checking", second: String = "checking",
    ): String = JSONObject().put("running", true).put("groups", JSONArray().put(JSONObject()
        .put("id", "health").put("strategy", "Select").put("selectedProfileId", 7L)
        .put("state", state).put("activeProfileId", active ?: JSONObject.NULL)
        .put("eligibleProfileIds", JSONArray().apply { eligible.forEach(::put) })
        .put("members", JSONArray().apply {
            listOf(7L to first, 8L to second).forEach { (id, memberState) ->
                put(JSONObject().put("profileId", id).put("fingerprint", "fingerprint-$id")
                    .put("state", memberState).put("lastCheckedAt", 1760000000123L).put("delayMs", 120L))
            }
        }))).toString()
}
