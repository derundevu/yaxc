package io.github.derundevu.yaxc.transfer

import android.content.Context
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.HttpHelper
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.LiveRoutingPlan
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config as RobolectricConfig

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = RegressionApplication::class, sdk = [34])
class DeviceTransferStoreTest : RoomTestSupport() {
    private val store get() = DeviceTransferStore(app)
    private val links get() = database.linkDao()
    private val profiles get() = database.profileDao()
    private val prefs get() = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    @Test
    fun subscriptionsMergeAdditivelyInProviderOrderWithoutReplacingLocalState() = runBlocking {
        val source = addLink(Link(name = "Provider", address = "https://EXAMPLE.invalid:443/sub",
            type = Link.Type.Subscription, isActive = false, userAgent = "portable-agent", xHwid = "source-hwid",
            customHeaders = "Authorization: source-token\nX-Device-Model: secret-model",
            subscriptionMetadata = "source-cache", lastRefreshedAt = 71, refreshIntervalMinutes = 45))
        addProfile(source.id, 0, "A", "alpha", "source-inbound-secret")
        addProfile(source.id, 1, "B", "remote-profile-secret", "source-inbound-secret")
        addProfile(source.id, 2, "C", "charlie", "source-inbound-secret")
        settings.pingTimeout = 20
        settings.bootAutoStart = true
        settings.transparentProxy = true
        assertEquals(listOf(TransferGroup(source.id, "Provider", 3)), store.groups())
        val payload = store.export(TransferSelection(setOf(source.id), settings = true, routing = false))
        assertTrue(payload.contains("source-token") && payload.contains("remote-profile-secret"))
        listOf("source-hwid", "secret-model", "source-cache", "source-inbound-secret", "bootAutoStart",
            "transparentProxy", "regression-password").forEach { assertFalse(payload.contains(it)) }
        links.delete(source)

        val earlier = addLink(Link(name = "Earlier group"))
        val target = addLink(Link(name = "Local title", address = "https://example.invalid/sub", xHwid = "target-hwid",
            customHeaders = "Authorization: old-token\nX-Keep: keep-me\nX-HWID: local-override",
            subscriptionMetadata = "target-cache", lastRefreshedAt = 92))
        val a = addProfile(target.id, 0, "A", "alpha", "local-a")
        val unrelated = addProfile(earlier.id, 1, "Unrelated", "other")
        val c = addProfile(target.id, 2, "C", "old-charlie", "local-c")
        settings.selectedLink = target.id
        settings.selectedProfile = c
        settings.bootAutoStart = false
        settings.transparentProxy = false
        settings.pingTimeout = 5
        settings.liveRoutingHealthIntervalSeconds = 24

        val summary = store.importPayload(payload)
        assertEquals(TransferSummary(0, 1, 1, 1, settings = true, routing = false), summary)
        assertEquals(summary, TransferSummary.decode(summary.encode()))
        assertEquals(listOf(earlier.id, target.id), links.orderedIds())
        val merged = checkNotNull(links.find(target.id))
        assertEquals("Provider", merged.name)
        assertTrue(merged.isActive)
        assertEquals("target-hwid", merged.xHwid)
        assertEquals("target-cache", merged.subscriptionMetadata)
        assertEquals(92L, merged.lastRefreshedAt)
        assertEquals(45, merged.refreshIntervalMinutes)
        assertEquals(mapOf("authorization" to "source-token", "x-keep" to "keep-me", "x-hwid" to "local-override"),
            HttpHelper.parseHeaders(merged.customHeaders))
        val ordered = profiles.transferProfiles()
        assertEquals(listOf("A", "Unrelated", "B", "C"), ordered.map(Profile::name))
        assertEquals(listOf(a, unrelated, c), ordered.filter { it.name != "B" }.map(Profile::id))
        assertTrue(profiles.find(a).config.contains("local-a"))
        assertTrue(profiles.find(c).config.contains("local-c") && profiles.find(c).config.contains("charlie"))
        assertEquals(target.id, settings.selectedLink)
        assertEquals(c, settings.selectedProfile)
        assertEquals(20, settings.pingTimeout)
        assertFalse(settings.bootAutoStart)
        assertFalse(settings.transparentProxy)
        assertEquals(24, settings.liveRoutingHealthIntervalSeconds)
        assertEquals(TransferSummary(0, 0, 0, 0, settings = true, routing = false), store.importPayload(payload))
    }

    @Test
    fun liveRoutingRemapsDuplicateOccurrencesAndKeepsUnrelatedCoreConfig() = runBlocking {
        val first = addProfile(null, 0, "Same", "endpoint")
        val second = addProfile(null, 1, "Same", "endpoint")
        settings.liveRoutingPlan = plan(first, second)
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.example.app"
        settings.liveRoutingHealthIntervalSeconds = 27
        val localConfig = Config(dns = """{"servers":["10.0.0.53"]}""", dnsMode = Config.Mode.Replace,
            routing = """{"domainStrategy":"AsIs","rules":[{"type":"field","outboundTag":"direct"}]}""",
            routingMode = Config.Mode.Replace)
        database.configDao().insert(localConfig)
        val payload = store.export(TransferSelection(setOf(null), settings = false, routing = true))
        profiles.delete(profiles.find(first))
        profiles.delete(profiles.find(second))
        val existing = addProfile(null, 0, "Same", "different-endpoint")
        settings.selectedProfile = existing
        settings.liveRoutingPlan = "{}"
        settings.appsRouting = "com.example.other"
        settings.liveRoutingHealthIntervalSeconds = 5
        val targetCoreConfig = localConfig.copy(dns = """{"servers":["10.0.0.54"]}""", routing = "{}")
        database.configDao().update(targetCoreConfig)

        assertEquals(TransferSummary(0, 0, 2, 0, settings = false, routing = true), store.importPayload(payload))
        val newIds = profiles.transferProfiles().map(Profile::id).filter { it != existing }
        assertEquals(2, newIds.size)
        assertNotEquals(listOf(first, second), newIds)
        val importedPlan = LiveRoutingHelper.decode(settings.liveRoutingPlan)
        assertEquals(newIds, importedPlan.groups.single().profileIds)
        assertEquals(newIds.last(), importedPlan.groups.single().selectedProfileId)
        assertEquals("portable-group", importedPlan.defaultGroupId)
        assertEquals("portable-group", importedPlan.rules.single().target)
        assertEquals(targetCoreConfig.copy(routing = localConfig.routing), database.configDao().get())
        assertEquals("com.example.app", settings.appsRouting)
        assertEquals(Settings.AppsRoutingMode.Include, settings.appsRoutingMode)
        assertEquals(27, settings.liveRoutingHealthIntervalSeconds)
        assertEquals(existing, settings.selectedProfile)
    }

    @Test
    fun anOmittedLiveDependencyRejectsAllSelectedCategoriesBeforeMakingChanges() = runBlocking {
        val first = addProfile(null, 0, "Required", "endpoint")
        val group = addLink(Link(name = "Selected group"))
        addProfile(group.id, 1, "Portable", "another-endpoint")
        settings.liveRoutingPlan = plan(first, first, single = true)
        val payload = store.export(TransferSelection(setOf(group.id), settings = true, routing = true))
        profiles.delete(profiles.find(first))
        links.delete(group)
        settings.pingTimeout = 33
        settings.liveRoutingPlan = "{}"
        val oldPreferences = prefs.all

        val failure = runCatching { store.importPayload(payload) }.exceptionOrNull()
        assertTrue(failure is TransferRoutingDependencyException)
        assertTrue(failure?.message.orEmpty().contains("Required"))
        assertTrue(links.orderedLinks().isEmpty())
        assertTrue(profiles.transferProfiles().isEmpty())
        assertEquals(null, database.configDao().get())
        assertEquals(oldPreferences, prefs.all)
    }

    @Test
    fun rejectsDuplicateKeysWrongTypesAndInjectedLocalCredentials() = runBlocking {
        addProfile(null, 0, "Manual", "endpoint")
        val payload = store.export(TransferSelection(setOf(null), settings = true, routing = false))
        val withDuplicateVersion = "{\"version\":1," + payload.drop(1)
        val withWrongVersionType = JSONObject(payload).put("version", "1").toString()
        val withInboundCredentials = JSONObject(payload).apply {
            getJSONArray("groups").getJSONObject(0).getJSONArray("profiles").getJSONObject(0)
                .put("config", nativeConfig("endpoint", "forbidden-local-password"))
        }.toString()
        val before = profiles.transferProfiles()
        val oldPreferences = prefs.all
        for (invalid in listOf(withDuplicateVersion, withWrongVersionType, withInboundCredentials)) {
            assertTrue(runCatching { store.importPayload(invalid) }.exceptionOrNull() is IllegalArgumentException)
            assertEquals(before, profiles.transferProfiles())
            assertEquals(oldPreferences, prefs.all)
        }
    }

    @Test
    fun httpSubscriptionsStayPausedUntilAcceptedLocallyAndNewHeadersRequireReview() = runBlocking {
        val source = addLink(Link(name = "HTTP", address = "http://example.invalid/sub",
            customHeaders = "Authorization: incoming"))
        val payload = store.export(TransferSelection(setOf(source.id), settings = false, routing = false))
        links.delete(source)
        assertEquals(1, store.importPayload(payload).groupsAdded)
        val added = links.orderedLinks().single()
        assertFalse(added.isActive)
        assertEquals("authorization: incoming", added.customHeaders)
        val reviewed = added.copy(isActive = true, customHeaders = "Authorization: local")
        links.update(reviewed)
        assertTrue(runCatching { store.importPayload(payload) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(reviewed, links.find(reviewed.id))
    }

    private suspend fun addLink(link: Link): Link = link.also { it.id = links.insertAtEnd(it) }

    private suspend fun addProfile(linkId: Long?, index: Int, name: String, endpoint: String, password: String? = null): Long =
        profiles.insert(Profile(linkId = linkId, index = index, name = name, config = nativeConfig(endpoint, password)))

    private fun nativeConfig(endpoint: String, password: String?): String {
        val inbounds = if (password == null) "[]" else
            """[{"protocol":"socks","settings":{"auth":"password","accounts":[{"user":"local","pass":"$password"}]}}]"""
        return """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"endpoint":"$endpoint"}}],"inbounds":$inbounds}"""
    }

    private fun plan(first: Long, second: Long, single: Boolean = false): String = LiveRoutingHelper.encode(LiveRoutingPlan(
        groups = listOf(ProxyGroup(id = "portable-group", name = "Group", selectedProfileId = second,
            profileIds = if (single) listOf(first) else listOf(first, second))),
        rules = listOf(AppDomainRule(id = "portable-rule", domains = listOf("example.invalid"), target = "portable-group")),
        defaultGroupId = "portable-group",
    ))
}
