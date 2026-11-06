package io.github.derundevu.yaxc.helper

import android.app.Application
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Profile
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LiveRoutingHelperTest : HelperSettingsTest() {
    @Test
    fun availabilityIsStrictOptInAndRoundTripsWithoutChangingLegacyGroupSelection() {
        val legacy = """{"groups":[{"id":"group","profileIds":[2,1],"selectedProfileId":1}]}"""
        val disabled = LiveRoutingHelper.decode(legacy)
        assertFalse(disabled.groups.single().checkAvailability)
        val profiles = (1L..2L).associateWith { profile(it) }
        val legacyJson = compile(disabled, profiles, pingAddress = "not a URL")
        assertFalse(legacyJson.has("_yaxcLiveHealth"))
        assertFalse(legacyJson.getJSONObject("routing").has("balancers"))
        assertTrue(legacyJson.getJSONObject("routing").getJSONArray("rules").objectValues().last()
            .getString("outboundTag").contains("profile.1."))
        assertEquals(disabled, LiveRoutingHelper.decode(LiveRoutingHelper.encode(disabled)))
        val enabled = disabled.copy(groups = disabled.groups.map { it.copy(checkAvailability = true) })
        assertEquals(enabled, LiveRoutingHelper.decode(LiveRoutingHelper.encode(enabled)))
        assertTrue(JSONObject(LiveRoutingHelper.encode(enabled)).getJSONArray("groups").getJSONObject(0)
            .get("checkAvailability") is Boolean)
        listOf("\"true\"", "\"false\"", "0", "1", "null", "[]", "{}").forEach { value ->
            expectError("boolean", value) {
                LiveRoutingHelper.decode("""{"groups":[{"id":"group","profileIds":[1],"checkAvailability":$value}]}""")
            }
        }
    }

    @Test
    fun leastPingRequiresHealthRatherThanSilentlyReinterpretingStoredOrProgrammaticPlans() {
        val invalid = LiveRoutingPlan(listOf(group(ProxyGroupStrategy.LeastPing)))
        expectError("requires availability checks") { LiveRoutingHelper.encode(invalid) }
        expectError("requires availability checks") { compile(invalid, mapOf(1L to profile(1))) }
        listOf("", ""","checkAvailability":false""").forEach { availability ->
            expectError("requires availability checks") {
                LiveRoutingHelper.decode("""{"groups":[{"id":"group","profileIds":[1],"strategy":"LeastPing"$availability}]}""")
            }
        }
        val valid = invalid.copy(groups = invalid.groups.map { it.copy(checkAvailability = true) })
        assertEquals(valid, LiveRoutingHelper.decode(LiveRoutingHelper.encode(valid)))
        expectError("ping URL") { compile(valid, mapOf(1L to profile(1))) }
    }

    @Test
    fun invalidHealthIntervalsCannotProduceLiveRoutingConfiguration() {
        val plan = LiveRoutingPlan(listOf(group(ProxyGroupStrategy.LeastPing, listOf(2, 1))
            .copy(checkAvailability = true)))
        val profiles = (1L..2L).associateWith { profile(it) }
        listOf(Int.MIN_VALUE, -1, 0, 4, 3601, Int.MAX_VALUE).forEach { seconds ->
            assertThrows(IllegalArgumentException::class.java) {
                compile(plan, profiles, pingAddress = "https://probe.test/204", healthIntervalSeconds = seconds)
            }
        }
    }

    @Test
    fun availabilityStartsBlockedForEveryStrategyAndRoutesEveryIndependentTargetThroughItsBalancer() {
        val profiles = (1L..3L).associateWith { profile(it) }
        ProxyGroupStrategy.entries.forEach { strategy ->
            val group = group(strategy, listOf(3, 1, 2), 2).copy(checkAvailability = true)
            val base = base().apply {
                getJSONObject("routing").getJSONArray("rules").put(JSONObject(
                    """{"ruleTag":"core-proxy","domain":["full:core.test"],"outboundTag":"proxy"}""",
                ))
            }
            val plan = LiveRoutingPlan(listOf(group), listOf(
                rule(id = "explicit", packages = listOf("com.example.member"), target = group.id),
                rule(id = "proxy-alias", domains = listOf("full:alias.test"), target = "proxy"),
            ), group.id)
            val built = compile(plan, profiles, base, uids = mapOf("com.example.member" to 10001), pingAddress = "https://probe.test/204?q=one")
            val health = built.getJSONObject("_yaxcLiveHealth")
            assertEquals("https://probe.test/204?q=one", health.getString("url"))
            assertEquals(10, health.getInt("intervalSeconds"))
            val metadata = health.getJSONArray("groups").getJSONObject(0)
            assertEquals(group.id, metadata.getString("id"))
            assertEquals(strategy.name, metadata.getString("strategy"))
            assertEquals(2L, metadata.getLong("selectedProfileId"))
            val members = metadata.getJSONArray("members").objectValues()
            val expectedOrder = if (strategy == ProxyGroupStrategy.Select) listOf(2L, 3L, 1L) else listOf(3L, 1L, 2L)
            assertEquals(expectedOrder, members.map { it.getLong("profileId") })
            val allOutbounds = built.getJSONArray("outbounds").objectValues()
            members.forEach { member ->
                val id = member.getLong("profileId")
                assertEquals(LiveRoutingHelper.profileFingerprint(profiles.getValue(id).config), member.getString("fingerprint"))
                assertTrue(allOutbounds.any { it.getString("tag") == member.getString("tag") })
                assertTrue(member.getString("tag").contains("profile.$id.outbound.0."))
            }
            val blockTag = metadata.getString("blockTag")
            assertEquals("blackhole", allOutbounds.single { it.getString("tag") == blockTag }.getString("protocol"))
            val balancer = built.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0)
            assertEquals(metadata.getString("balancerTag"), balancer.getString("tag"))
            assertEquals(listOf(blockTag), balancer.getJSONArray("selector").stringValues())
            assertFalse(balancer.has("fallbackTag"))
            assertEquals(if (strategy == ProxyGroupStrategy.RoundRobin) "roundrobin" else "random",
                balancer.getJSONObject("strategy").getString("type"))
            val rules = built.getJSONObject("routing").getJSONArray("rules").objectValues()
            assertEquals(5, rules.size)
            assertEquals(listOf("yaxc.uid.10001"), rules.first().getJSONArray("user").stringValues())
            assertEquals(listOf("domain:example.test"), rules.first().getJSONArray("domain").stringValues())
            rules.filterNot { it.optString("ruleTag") == "base-rule" }.forEach { rule ->
                assertEquals(balancer.getString("tag"), rule.getString("balancerTag"))
                assertFalse(rule.has("outboundTag"))
            }
            assertEquals("direct", rules.single { it.optString("ruleTag") == "base-rule" }.getString("outboundTag"))
        }
    }

    @Test
    fun regularGroupRulesAndHotPriorityChangesKeepAllRealOutboundGraphsAndUseDedicatedBlocks() {
        val profiles = mapOf(
            1L to profile(1, outbounds = """[
                {"tag":"proxy","protocol":"socks","streamSettings":{"sockopt":{"dialerProxy":"hop"}}},
                {"tag":"hop","protocol":"freedom"}
            ]"""),
            2L to profile(2), 3L to profile(3),
        )
        val health = group(members = listOf(1, 2, 3), selected = 1).copy(checkAvailability = true)
        val unchanged = group(ProxyGroupStrategy.Random, listOf(3, 2)).copy(id = "legacy-balanced")
        fun build(group: ProxyGroup) = compile(
            LiveRoutingPlan(listOf(group, unchanged), listOf(rule(target = group.id))),
            profiles, pingAddress = "http://127.0.0.1:18881/probe",
        )
        val first = build(health)
        val reordered = build(health.copy(profileIds = listOf(3, 2, 1), selectedProfileId = 2))
        val firstOutbounds = first.getJSONArray("outbounds").objectValues().associateBy { it.getString("tag") }
        val reorderedOutbounds = reordered.getJSONArray("outbounds").objectValues().associateBy { it.getString("tag") }
        assertEquals(firstOutbounds.keys, reorderedOutbounds.keys)
        firstOutbounds.forEach { (tag, outbound) -> assertJsonEquals(outbound, reorderedOutbounds.getValue(tag)) }
        val metadata = reordered.getJSONObject("_yaxcLiveHealth").getJSONArray("groups")
        assertEquals(1, metadata.length())
        assertEquals(listOf(2L, 3L, 1L), metadata.getJSONObject(0).getJSONArray("members").objectValues().map { it.getLong("profileId") })
        val rules = reordered.getJSONObject("routing").getJSONArray("rules")
        assertEquals(metadata.getJSONObject(0).getString("balancerTag"), rules.getJSONObject(0).getString("balancerTag"))
        assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"))
        val balancers = reordered.getJSONObject("routing").getJSONArray("balancers").objectValues()
        assertEquals(2, balancers[1].getJSONArray("selector").length())
        assertTrue(balancers[1].getJSONArray("selector").stringValues().all { it.contains("profile.") })
        val disabled = build(health.copy(checkAvailability = false))
        assertFalse(disabled.has("_yaxcLiveHealth"))
        assertTrue(disabled.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag").contains("profile.1."))
        assertEquals(1, disabled.getJSONObject("routing").getJSONArray("balancers").length())
    }

    @Test
    fun fingerprintsTrackExactSourceBytesAndHealthUrlValidationDoesNotAffectOptedOutPlans() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            LiveRoutingHelper.profileFingerprint("abc"))
        val source = profile(1)
        val group = group().copy(checkAvailability = true)
        fun build() = compile(LiveRoutingPlan(listOf(group)), mapOf(1L to source), pingAddress = "https://probe.test/204")
        val first = build()
        source.config += "\n"
        val changed = build()
        assertJsonEquals(first.getJSONArray("outbounds"), changed.getJSONArray("outbounds"))
        fun fingerprint(value: JSONObject) = value.getJSONObject("_yaxcLiveHealth").getJSONArray("groups")
            .getJSONObject(0).getJSONArray("members").getJSONObject(0).getString("fingerprint")
        assertFalse(fingerprint(first) == fingerprint(changed))
        assertEquals(64, fingerprint(changed).length)
        listOf("", "probe.test", "ftp://probe.test/check", "https:///check", "https://user:pass@probe.test/check",
            "https://probe.test/check#fragment").forEach { url ->
            expectError("ping URL", url) {
                compile(LiveRoutingPlan(listOf(group)), mapOf(1L to source), pingAddress = url)
            }
            assertFalse(compile(LiveRoutingPlan(listOf(group.copy(checkAvailability = false))),
                mapOf(1L to source), pingAddress = url).has("_yaxcLiveHealth"))
        }
        val noPreferred = build().getJSONObject("_yaxcLiveHealth").getJSONArray("groups").getJSONObject(0)
        assertTrue(noPreferred.isNull("selectedProfileId"))
    }

    @Test
    fun runtimeJsonPreservesEligibilityCheckingErrorsAndExactProfileIdentities() {
        val id = 9007199254740993L
        val raw = """{"running":true,"groups":[{"id":"health","strategy":"Select","selectedProfileId":$id,
            "state":"available","activeProfileId":$id,"eligibleProfileIds":[$id],"members":[
            {"profileId":$id,"fingerprint":"source-hash","state":"available","lastCheckedAt":1760000000123,"delayMs":42},
            {"profileId":2,"fingerprint":"other-hash","state":"checking","lastCheckedAt":0,"delayMs":0}]}]}"""
        val status = LiveRoutingHelper.decodeRuntimeStatus(raw)
        assertTrue(status.running)
        val group = status.groups.single()
        assertEquals(ProxyGroupStrategy.Select, group.strategy)
        assertEquals(id, group.selectedProfileId)
        assertEquals(id, group.activeProfileId)
        assertEquals(listOf(id), group.eligibleProfileIds)
        assertEquals("source-hash", group.members.first().fingerprint)
        assertEquals(1760000000123L, group.members.first().lastCheckedAt)
        assertEquals(42L, group.members.first().delayMs)
        assertEquals("checking", group.members.last().state)
        assertEquals(LiveRoutingRuntimeStatus(), LiveRoutingHelper.decodeRuntimeStatus("""{"running":false,"groups":[]}"""))
        listOf(raw.replace("\"running\":true", "\"running\":\"true\""),
            raw.replace("\"state\":\"available\"", "\"state\":\"healthy\""),
            raw.replace("\"profileId\":2", "\"profileId\":2.0"),
            raw.replace("\"running\":true", "\"running\":false")).forEach { invalid ->
            expectError("") { LiveRoutingHelper.decodeRuntimeStatus(invalid) }
        }
    }

    @Test
    fun persistedDefaultGroupControlsProxyAndUnmatchedRoutesWhileLegacyPlansUseFirstGroup() {
        val legacyRaw = """{"groups":[
            {"id":"first","name":"First","profileIds":[1,2],"selectedProfileId":2},
            {"id":"second","name":"Second","profileIds":[3]}
        ],"rules":[{"id":"proxy-rule","domains":["full:proxy.test"],"target":"proxy"}]}"""
        val legacy = LiveRoutingHelper.decode(legacyRaw)
        assertEquals(legacy.groups.first(), legacy.defaultGroup)
        assertEquals(null, legacy.defaultGroupId)
        assertEquals(legacy, LiveRoutingHelper.decode(JSONObject(legacyRaw).put("defaultGroupId", JSONObject.NULL).toString()))
        val profiles = (1L..3L).associateWith { profile(it) }
        val implicit = compile(legacy, profiles)
        val implicitRules = implicit.getJSONObject("routing").getJSONArray("rules")
        assertTrue(implicitRules.getJSONObject(0).getString("outboundTag").contains("profile.2."))
        assertEquals(implicitRules.getJSONObject(0).getString("outboundTag"),
            implicitRules.getJSONObject(implicitRules.length() - 1).getString("outboundTag"))

        val explicit = legacy.copy(defaultGroupId = "second")
        settings.liveRoutingPlan = LiveRoutingHelper.encode(explicit)
        val restored = LiveRoutingHelper.decode(Settings(RuntimeEnvironment.getApplication()).liveRoutingPlan)
        assertEquals(explicit, restored)
        assertEquals(restored.groups.last(), restored.defaultGroup)
        val built = compile(restored, profiles)
        val rules = built.getJSONObject("routing").getJSONArray("rules")
        assertTrue(rules.getJSONObject(0).getString("outboundTag").contains("profile.3."))
        assertEquals(rules.getJSONObject(0).getString("outboundTag"),
            rules.getJSONObject(rules.length() - 1).getString("outboundTag"))
        assertJsonEquals(implicit.getJSONArray("outbounds"), built.getJSONArray("outbounds"))
        assertEquals("second", LiveRoutingHelper.decode(LiveRoutingHelper.encode(
            restored.copy(groups = restored.groups.reversed()),
        )).defaultGroup?.id)
    }

    @Test
    fun corruptRowsAndUnknownFieldsNeverSilentlyBecomeAnEmptyOrPartialPlan() {
        listOf(
            "", "[]", "null", "{} {}", "{\"groups\":null}", "{\"rules\":[false]}",
            "{\"groups\":[{\"id\":\"g\",\"profileIds\":[1,\"2\"]}]}",
            "{\"rules\":[{\"id\":\"r\",\"enabled\":\"false\",\"domains\":[\"one.test\"]}]}",
            "{\"rules\":[{\"id\":\"r\",\"domains\":[\"one.test\",42]}]}",
            "{\"rules\":[{\"id\":\"r\",\"domains\":[\"one.test\"],\"futureMatcher\":true}]}",
            "{\"groups\":[{\"id\":\"g\",\"profileIds\":[1],\"strategy\":\"Future\"}]}",
            "{\"version\":2}", "{\"rules\":[],\"rules\":[]}", "{'rules':[]}", "{rules:[]}",
            "{\"rules\":[],}", "{\"rules\":[] /* comment */}",
        ).forEach { raw -> expectError("", raw) { LiveRoutingHelper.decode(raw) } }
    }

    @Test
    fun profileIdsCannotBeRoundedCoercedOrOverflowedDuringDecode() {
        listOf("1.0", "1e0", "9223372036854775808", "\"1\"", "true", "null").forEach { id ->
            expectError("integer", id) {
                LiveRoutingHelper.decode("{\"groups\":[{\"id\":\"g\",\"profileIds\":[$id]}]}")
            }
        }
        val exact = LiveRoutingHelper.decode("{\"groups\":[{\"id\":\"g\",\"profileIds\":[9007199254740993]}]}")
        assertEquals(9007199254740993L, exact.groups.single().profileIds.single())
    }

    @Test
    fun missingPersistentIdsAreNotRandomlyRegeneratedOnEachRefresh() {
        expectError("id") { LiveRoutingHelper.decode("{\"rules\":[{\"domains\":[\"one.test\"]}]}") }
        expectError("id") { LiveRoutingHelper.decode("{\"groups\":[{\"profileIds\":[1]}]}") }
    }

    @Test
    fun validationRejectsDanglingTargetsDuplicateIdentityAndInvalidMembership() {
        val g = group()
        listOf(
            LiveRoutingPlan(groups = listOf(g, g)),
            LiveRoutingPlan(groups = listOf(g.copy(id = "proxy"))),
            LiveRoutingPlan(groups = listOf(g.copy(profileIds = emptyList()))),
            LiveRoutingPlan(groups = listOf(g.copy(profileIds = listOf(1, 1)))),
            LiveRoutingPlan(groups = listOf(g.copy(profileIds = listOf(0)))),
            LiveRoutingPlan(groups = listOf(g.copy(selectedProfileId = 2))),
            LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(), rule())),
            LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(target = "deleted-group"))),
            LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(enabled = false, target = "deleted-group"))),
            LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(packages = emptyList(), domains = emptyList()))),
            LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(packages = listOf("com.bad package")))),
        ).forEach { plan -> expectError("") { LiveRoutingHelper.validate(plan) } }
    }

    @Test
    fun invalidDefaultGroupNeverFallsBackToFirstGroupOrDirect() {
        val g = group()
        listOf("", "deleted", "proxy").forEach { id ->
            val invalid = LiveRoutingPlan(listOf(g), defaultGroupId = id)
            assertEquals(null, invalid.defaultGroup)
            expectError("default group") { LiveRoutingHelper.validate(invalid) }
            expectError("default group") { LiveRoutingHelper.encode(invalid) }
            expectError("default group") { compile(invalid, mapOf(1L to profile(1))) }
            expectError("default group") {
                LiveRoutingHelper.decode("""{"groups":[{"id":"group","profileIds":[1]}],"defaultGroupId":"$id"}""")
            }
        }
        expectError("string") { LiveRoutingHelper.decode("""{"defaultGroupId":true}""") }
        expectError("default proxy group") { compile(LiveRoutingPlan()) }
        expectError("missing profile 1") { compile(LiveRoutingPlan(listOf(g)), profiles = emptyMap()) }
        expectError("routing script") {
            compile(LiveRoutingPlan(listOf(g)), mapOf(1L to profile(1)),
                base().apply { getJSONObject("routing").put("script", "custom.lua") })
        }
    }

    @Test
    fun standaloneDefaultStrategiesReplaceProxyTargetsWithoutDefeatingCoreRulePrecedence() {
        ProxyGroupStrategy.entries.forEach { strategy ->
            val fallback = group(strategy, listOf(1, 2), 2)
                .copy(checkAvailability = strategy == ProxyGroupStrategy.LeastPing)
            val other = group(members = listOf(3)).copy(id = "other")
            val base = base()
            base.getJSONArray("outbounds").getJSONObject(0).put("tag", "global-proxy")
            val routing = base.getJSONObject("routing")
            val existingBalancer = JSONObject("""{"tag":"existing","selector":["global-proxy"],"strategy":{"type":"random"}}""")
            routing.put("balancers", JSONArray().put(existingBalancer))
            routing.getJSONArray("rules")
                .put(JSONObject("""{"ruleTag":"core-proxy","domain":["full:core.test"],"outboundTag":"global-proxy","balancerTag":"existing"}"""))
                .put(JSONObject("""{"ruleTag":"proxy-alias","domain":["full:alias.test"],"outboundTag":"Proxy"}"""))
                .put(JSONObject("""{"ruleTag":"core-balancer","domain":["full:balanced.test"],"balancerTag":"existing"}"""))
                .put(JSONObject("""{"ruleTag":"private","ip":["geoip:private"],"outboundTag":"direct"}"""))
                .put(JSONObject("""{"ruleTag":"core-block","port":"25","outboundTag":"block"}"""))
            val plan = LiveRoutingPlan(listOf(other, fallback), listOf(
                rule(id = "live-proxy"), rule(id = "live-direct", target = "direct"), rule(id = "live-block", target = "block"),
            ), fallback.id)
            val built = compile(plan, (1L..3L).associateWith { profile(it) }, base, pingAddress = "https://probe.test/204")
            val rules = built.getJSONObject("routing").getJSONArray("rules")
            val key = if (strategy == ProxyGroupStrategy.Select) "outboundTag" else "balancerTag"
            val opposite = if (key == "outboundTag") "balancerTag" else "outboundTag"
            val target = rules.getJSONObject(0).getString(key)
            if (strategy == ProxyGroupStrategy.Select) {
                assertTrue(target.contains("profile.2."))
            } else {
                val balancer = built.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0)
                assertEquals(target, balancer.getString("tag"))
                assertEquals(if (strategy == ProxyGroupStrategy.RoundRobin) "roundrobin" else "random",
                    balancer.getJSONObject("strategy").getString("type"))
                assertEquals(if (strategy == ProxyGroupStrategy.LeastPing) 1 else 2,
                    balancer.getJSONArray("selector").length())
            }
            assertEquals(10, rules.length())
            listOf(0, 4, 5, 9).forEach { index ->
                assertEquals(target, rules.getJSONObject(index).getString(key))
                assertFalse(rules.getJSONObject(index).has(opposite))
            }
            assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"))
            assertEquals("block", rules.getJSONObject(2).getString("outboundTag"))
            assertEquals("base-rule", rules.getJSONObject(3).getString("ruleTag"))
            assertEquals("existing", rules.getJSONObject(6).getString("balancerTag"))
            assertEquals("direct", rules.getJSONObject(7).getString("outboundTag"))
            assertEquals("block", rules.getJSONObject(8).getString("outboundTag"))
            assertEquals("tcp,udp", rules.getJSONObject(9).getString("network"))
            assertJsonEquals(base.getJSONArray("inbounds"), built.getJSONArray("inbounds"))
            assertJsonEquals(base.getJSONArray("outbounds").getJSONObject(0), built.getJSONArray("outbounds").getJSONObject(0))
        }
    }

    @Test
    fun profilesAcrossSubscriptionsShareCompleteNamespacedOutboundGraphs() {
        val one = profile(1, linkId = 40, outbounds = """[
            {"tag":"proxy","protocol":"socks","settings":{"address":"192.0.2.1","port":1080},
             "streamSettings":{"sockopt":{"dialerProxy":"hop"},"xhttpSettings":{"extra":{"downloadSettings":{"sockopt":{"dialerProxy":"exit"}}}}}},
            {"tag":"hop","protocol":"socks","streamSettings":{"sockopt":{"dialerProxy":"exit"}},"mux":{"enabled":true}},
            {"tag":"exit","protocol":"freedom","settings":{"domainStrategy":"UseIPv4"}}
        ]""")
        val two = profile(2, linkId = 70, outbounds = """[
            {"tag":"proxy","protocol":"http","settings":{"address":"192.0.2.2","port":8080}},
            {"tag":"exit","protocol":"blackhole"}
        ]""")
        val g1 = group(ProxyGroupStrategy.Select, listOf(1)).copy(id = "one")
        val g2 = group(ProxyGroupStrategy.Random, listOf(1, 2)).copy(id = "mixed")
        val sourceConfig = one.config
        val built = compile(LiveRoutingPlan(listOf(g1, g2), listOf(rule(target = "one"))), mapOf(1L to one, 2L to two))
        val all = built.getJSONArray("outbounds").objectValues()
        assertEquals(8, all.size)
        val cloned = all.drop(3)
        assertEquals(cloned.size, cloned.map { it.getString("tag") }.toSet().size)
        val primary = cloned[0]
        assertEquals(cloned[1].getString("tag"), primary.getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        val download = primary.getJSONObject("streamSettings").getJSONObject("xhttpSettings").getJSONObject("extra").getJSONObject("downloadSettings")
        assertEquals(cloned[2].getString("tag"), download.getJSONObject("sockopt").getString("dialerProxy"))
        assertEquals(cloned[2].getString("tag"), cloned[1].getJSONObject("streamSettings").getJSONObject("sockopt").getString("dialerProxy"))
        assertTrue(cloned[1].getJSONObject("mux").getBoolean("enabled"))
        assertEquals("UseIPv4", cloned[2].getJSONObject("settings").getString("domainStrategy"))
        assertEquals(primary.getString("tag"), built.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        assertEquals(listOf(primary.getString("tag"), cloned[3].getString("tag")),
            built.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getJSONArray("selector").stringValues())
        assertEquals(sourceConfig, one.config)
    }


    @Test
    fun selectingMembersAndStrategiesKeepsCompleteOutboundGraphsLoaded() {
        val original = group(ProxyGroupStrategy.Select, listOf(1, 2), 1)
        val profiles = mapOf(1L to profile(1), 2L to profile(2))
        fun build(g: ProxyGroup) = compile(LiveRoutingPlan(listOf(g), listOf(rule(target = g.id))), profiles)
        val first = build(original)
        val second = build(original.copy(selectedProfileId = 2))
        val balanced = build(original.copy(strategy = ProxyGroupStrategy.RoundRobin))
        assertJsonEquals(first.getJSONArray("outbounds"), second.getJSONArray("outbounds"))
        assertJsonEquals(first.getJSONArray("outbounds"), balanced.getJSONArray("outbounds"))
        val firstTarget = first.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag")
        val secondTarget = second.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("outboundTag")
        assertTrue(firstTarget.contains("profile.1."))
        assertTrue(secondTarget.contains("profile.2."))
        assertFalse(firstTarget == secondTarget)
        assertEquals(setOf(firstTarget, secondTarget),
            balanced.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0).getJSONArray("selector").stringValues().toSet())
    }

    @Test
    fun selectWithoutManualMemberUsesFirstMemberAndStillRejectsMissingUnselectedMembers() {
        val g = group(ProxyGroupStrategy.Select, listOf(2, 1))
        val result = compile(LiveRoutingPlan(listOf(g)), mapOf(1L to profile(1), 2L to profile(2)))
        assertTrue(result.getJSONArray("outbounds").getJSONObject(3).getString("tag").contains("profile.2."))
        expectError("missing profile 1") { compile(LiveRoutingPlan(listOf(g)), mapOf(2L to profile(2))) }
    }

    @Test
    fun randomAndRoundRobinSelectOnlyPrimaryMembersNotAncillaryOrPrefixNeighborProfiles() {
        listOf(ProxyGroupStrategy.Random to "random", ProxyGroupStrategy.RoundRobin to "roundrobin").forEach { (strategy, type) ->
            val g = group(strategy, listOf(1, 10))
            val result = compile(LiveRoutingPlan(listOf(g), listOf(rule(target = g.id))), mapOf(
                1L to profile(1, outbounds = """[{"protocol":"socks"},{"protocol":"freedom"}]"""),
                10L to profile(10),
            ))
            val balancer = result.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0)
            assertEquals(type, balancer.getJSONObject("strategy").getString("type"))
            val selectors = balancer.getJSONArray("selector").stringValues()
            val selected = result.getJSONArray("outbounds").objectValues().map { it.getString("tag") }
                .filter { tag -> selectors.any(tag::startsWith) }
            assertEquals(selectors.toSet(), selected.toSet())
            assertEquals(2, selected.size)
            assertEquals(balancer.getString("tag"), result.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("balancerTag"))
        }
    }

    @Test
    fun appAndDomainCriteriaAreAndedWhileEachListIsOrAndRuleOrderIsPreserved() {
        val first = rule(packages = listOf("com.example.one", "com.example.shared", "com.example.two"),
            domains = listOf("one.test", "full:two.test"), target = "block")
        val second = rule(id = "second", domains = listOf("domain:test"), target = "direct")
        val result = compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(first, second)), uids = mapOf(
            "com.example.one" to 10001, "com.example.shared" to 10001, "com.example.two" to 10002,
        ))
        val rules = result.getJSONObject("routing").getJSONArray("rules")
        assertEquals(4, rules.length())
        val native = rules.getJSONObject(0)
        assertEquals(listOf("yaxc.uid.10001", "yaxc.uid.10002"), native.getJSONArray("user").stringValues())
        assertEquals(listOf("domain:one.test", "full:two.test"), native.getJSONArray("domain").stringValues())
        assertEquals("block", native.getString("outboundTag"))
        assertEquals("direct", rules.getJSONObject(1).getString("outboundTag"))
        assertFalse(rules.getJSONObject(1).has("user"))
        assertEquals("base-rule", rules.getJSONObject(2).getString("ruleTag"))
    }

    @Test
    fun disabledDraftsCannotCreateCatchAllRulesOrResolveUninstalledPackages() {
        val disabled = rule(enabled = false, packages = listOf("com.uninstalled"), domains = emptyList())
        val blank = rule(id = "blank", enabled = false, domains = emptyList())
        val plan = LiveRoutingPlan(groups = listOf(group()), rules = listOf(disabled, blank, rule(id = "active")))
        val result = compile(plan)
        assertEquals(3, result.getJSONObject("routing").getJSONArray("rules").length())
        assertEquals(plan, LiveRoutingHelper.decode(LiveRoutingHelper.encode(plan)))
    }

    @Test
    fun nativeDomainPrefixesArePreservedAndRawDomainsBecomeSuffixMatches() {
        val domains = listOf(" example.test ", "full:exact.test", "domain:suffix.test", "regexp:^api[0-9]+\\.test$", "geosite:category-test", "ext:custom.dat:tag")
        val result = compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(domains = domains))))
        assertEquals(listOf("domain:example.test") + domains.drop(1), result.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getJSONArray("domain").stringValues())
        listOf("", "https://example.test/path", "domain:", "full:bad host", "ext:file", "regexp:", "*.test", "geosite:bad tag").forEach {
            expectError("") { LiveRoutingHelper.validate(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(domains = listOf(it))))) }
        }
    }

    @Test
    fun internationalizedHostnamesUseTheSameAsciiIdentityAsNetworkDestinations() {
        val result = compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(domains = listOf(
            "BÜCHER.example", "domain:bücher.example", "full:BÜCHER.example", "regexp:bücher",
        )))))
        assertEquals(listOf("domain:xn--bcher-kva.example", "full:xn--bcher-kva.example", "regexp:bücher"),
            result.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getJSONArray("domain").stringValues())
    }

    @Test
    fun allInstalledUidAccountsExistBeforeTheFirstApplicationRuleAndAreStable() {
        val installed = linkedMapOf("com.example.z" to 10009, "com.example.a" to 10001, "com.example.shared" to 10001)
        val result = compile(LiveRoutingPlan(listOf(group())), uids = installed)
        val accounts = result.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("accounts")
        assertEquals(listOf("test-user", "yaxc.uid.10001", "yaxc.uid.10009"), accounts.objectValues().map { it.getString("user") })
        assertEquals(listOf("test-password", "test-password", "test-password"), accounts.objectValues().map { it.getString("pass") })
        assertEquals(result.toString(), compile(LiveRoutingPlan(listOf(group())), uids = installed.entries.reversed().associate { it.toPair() }).toString())
    }

    @Test
    fun existingAccountsArePreservedAndSharedUidAccountsAreNotDuplicated() {
        val base = base()
        val accounts = base.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("accounts")
        accounts.put(JSONObject().put("user", "other-client").put("pass", "other-password").put("metadata", true))
        accounts.put(JSONObject().put("user", "yaxc.uid.10001").put("pass", "test-password"))
        val result = compile(LiveRoutingPlan(listOf(group())), base = base, uids = mapOf("com.one" to 10001, "com.two" to 10001))
        val actual = result.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("accounts")
        assertJsonEquals(accounts, actual)
    }

    @Test
    fun missingPackageFailsInsteadOfDroppingItsMatcherAndBroadeningTheRule() {
        expectError("com.uninstalled") {
            compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(packages = listOf("com.uninstalled"), domains = listOf("one.test")))))
        }
    }

    @Test
    fun noAuthDomainRoutingWorksButEnabledApplicationRulesRequireAuthentication() {
        val base = base()
        val inbound = base.getJSONArray("inbounds").getJSONObject(0)
        inbound.getJSONObject("settings").put("auth", "noauth").remove("accounts")
        val result = compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule())), base = base, username = "", password = "")
        assertJsonEquals(inbound, result.getJSONArray("inbounds").getJSONObject(0))
        expectError("credentials") {
            compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(packages = listOf("com.one")))), base = base, uids = mapOf("com.one" to 10001), username = "", password = "")
        }
        expectError("authenticated SOCKS") {
            compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(packages = listOf("com.one")))), base = base, uids = mapOf("com.one" to 10001))
        }
    }

    @Test
    fun unrelatedBaseFieldsInboundsOutboundsAndRoutingArePreserved() {
        val base = base().put("dns", JSONObject("""{"servers":["192.0.2.53"],"hosts":{"local.test":"192.0.2.9"}}"""))
            .put("policy", JSONObject("""{"levels":{"0":{"connIdle":300}}}"""))
            .put("future", JSONArray("""[null,{"a":false},"preserved"]"""))
        base.getJSONObject("routing").put("futureRouter", JSONObject().put("enabled", true))
        val g = group()
        val result = compile(LiveRoutingPlan(listOf(g), listOf(rule(target = g.id))), mapOf(1L to profile(1)), base)
        listOf("log", "dns", "policy", "future").forEach { assertJsonEquals(base.get(it), result.get(it)) }
        assertJsonEquals(base.getJSONArray("inbounds"), result.getJSONArray("inbounds"))
        for (index in 0..2) assertJsonEquals(base.getJSONArray("outbounds").get(index), result.getJSONArray("outbounds").get(index))
        assertEquals("IPIfNonMatch", result.getJSONObject("routing").getString("domainStrategy"))
        assertJsonEquals(base.getJSONObject("routing").get("futureRouter"), result.getJSONObject("routing").get("futureRouter"))
        assertJsonEquals(base.getJSONObject("routing").getJSONArray("rules").get(0), result.getJSONObject("routing").getJSONArray("rules").get(1))
    }

    @Test
    fun namespaceAvoidsExistingTagsAndPrefixBalancersWithoutChangingTheirMembers() {
        val base = base()
        base.getJSONArray("outbounds").put(JSONObject().put("protocol", "freedom").put("tag", "yaxc.live.profile.1.outbound.0."))
        val existing = JSONObject().put("tag", "old-balancer").put("selector", JSONArray().put("yaxc"))
            .put("strategy", JSONObject().put("type", "random"))
        base.getJSONObject("routing").put("balancers", JSONArray().put(existing))
        val g = group(ProxyGroupStrategy.Random)
        val result = compile(LiveRoutingPlan(listOf(g)), mapOf(1L to profile(1)), base)
        val tags = result.getJSONArray("outbounds").objectValues().map { it.getString("tag") }
        assertEquals(tags.size, tags.toSet().size)
        assertFalse(tags.last().startsWith("yaxc"))
        assertJsonEquals(existing, result.getJSONObject("routing").getJSONArray("balancers").getJSONObject(1))
    }

    @Test
    fun baseDuplicateTagsAndCatchAllBalancerSelectorsCannotCaptureManagedProfiles() {
        val duplicate = base()
        duplicate.getJSONArray("outbounds").put(JSONObject().put("tag", "proxy").put("protocol", "freedom"))
        expectError("duplicate") { compile(LiveRoutingPlan(listOf(group())), base = duplicate) }
        val broad = base()
        broad.getJSONObject("routing").put("balancers", JSONArray().put(JSONObject().put("tag", "b").put("selector", JSONArray().put(""))))
        expectError("empty selector") { compile(LiveRoutingPlan(listOf(group())), mapOf(1L to profile(1)), broad) }
    }

    @Test
    fun unsupportedIncompleteDanglingAndCyclicGraphsGiveActionableErrors() {
        listOf(
            "[]" to "no outbounds",
            "[false]" to "must be an object",
            "[{\"protocol\":\"loopback\",\"settings\":{\"inboundTag\":\"in\"}}]" to "unsupported protocol",
            "[{\"tag\":\"same\",\"protocol\":\"freedom\"},{\"tag\":\"same\",\"protocol\":\"freedom\"}]" to "duplicate outbound",
            "[{\"protocol\":\"socks\",\"proxySettings\":{\"tag\":\"absent\"}}]" to "streamSettings.sockopt.dialerProxy",
            "[{\"protocol\":\"socks\",\"streamSettings\":{\"sockopt\":{\"dialerProxy\":\"base-only\"}}}]" to "missing outbound 'base-only'",
            "[{\"tag\":\"a\",\"protocol\":\"socks\",\"streamSettings\":{\"sockopt\":{\"dialerProxy\":\"b\"}}},{\"tag\":\"b\",\"protocol\":\"socks\",\"streamSettings\":{\"sockopt\":{\"dialerProxy\":\"a\"}}}]" to "cyclic outbound",
            "[{\"protocol\":\"vless\",\"settings\":{\"reverse\":{\"tag\":\"r\"}}}]" to "outside the outbound graph",
        ).forEach { (outbounds, diagnostic) ->
            expectError(diagnostic) { compile(LiveRoutingPlan(listOf(group())), mapOf(1L to profile(1, outbounds = outbounds))) }
        }
    }

    @Test
    fun missingBuiltinTargetDoesNotFallbackToADifferentOutbound() {
        val base = base()
        base.put("outbounds", JSONArray().put(JSONObject().put("tag", "proxy").put("protocol", "socks")))
        expectError("no tagged 'block'") { compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule(target = "block"))), base = base) }
    }

    @Test
    fun scriptBasedBaseRoutingIsRejectedRatherThanSilentlyOverridden() {
        val base = base()
        base.getJSONObject("routing").put("script", "custom.lua")
        expectError("routing script") { compile(LiveRoutingPlan(groups = listOf(group()), rules = listOf(rule())), base = base) }
    }

    @Test
    fun unchangedRefreshProducesIdenticalTextRegardlessOfProfileAndUidMapIterationOrder() {
        val g = group(ProxyGroupStrategy.RoundRobin, listOf(2, 1))
        val plan = LiveRoutingPlan(listOf(g), listOf(rule(packages = listOf("com.one"), target = g.id)))
        val first = LiveRoutingHelper.compile(base().toString(), plan, linkedMapOf(1L to profile(1), 2L to profile(2)),
            linkedMapOf("com.one" to 10001, "com.two" to 10002), "test-user", "test-password")
        val second = LiveRoutingHelper.compile(base().toString(), LiveRoutingHelper.decode(LiveRoutingHelper.encode(plan)),
            linkedMapOf(2L to profile(2), 1L to profile(1)), linkedMapOf("com.two" to 10002, "com.one" to 10001), "test-user", "test-password")
        assertEquals(first, second)
    }

    @Test
    fun legacyUsersAliasIsNotMutatedWhenAccountsAreAdded() {
        val base = base()
        val settings = base.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        val users = settings.getJSONArray("accounts")
        settings.remove("accounts")
        settings.put("users", users)
        val result = compile(LiveRoutingPlan(listOf(group())), base = base, uids = mapOf("com.one" to 10001))
        val actual = result.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings")
        assertJsonEquals(users, actual.getJSONArray("users"))
        assertEquals(2, actual.getJSONArray("accounts").length())
        assertEquals("yaxc.uid.10001", actual.getJSONArray("accounts").getJSONObject(1).getString("user"))
    }

    @Test
    fun conflictingUidAccountAndDefaultCredentialMismatchAreNotSilentlyReplaced() {
        val conflicting = base()
        conflicting.getJSONArray("inbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("accounts")
            .put(JSONObject().put("user", "yaxc.uid.10001").put("pass", "different-password"))
        expectError("conflicts") { compile(LiveRoutingPlan(listOf(group())), base = conflicting, uids = mapOf("com.one" to 10001)) }
        expectError("default credentials") { compile(LiveRoutingPlan(listOf(group())), password = "different-password") }
    }

    @Test
    fun reverseAndGlobalTransportDependenciesAreRejectedInsteadOfPartiallyCloned() {
        listOf("reverse" to """{"bridges":[{"tag":"reverse","domain":"reverse.test"}]}""",
            "transport" to """{"tcpSettings":{"header":{"type":"http"}}}""").forEach { (field, value) ->
            val profile = profile(1)
            profile.config = JSONObject(profile.config).put(field, JSONObject(value)).toString()
            expectError(field) { compile(LiveRoutingPlan(listOf(group())), mapOf(1L to profile)) }
        }
    }

    @Test
    fun graphReferencesAreRewrittenOnlyInRealStreamFieldsNotHeaderOrProviderNames() {
        val source = profile(1, outbounds = """[
            {"tag":"proxy","protocol":"socks","streamSettings":{
                "wsSettings":{"headers":{"dialerProxy":"literal","proxySettings":"literal","reverse":"literal","inboundTag":"literal"}},
                "splithttpSettings":{"downloadSettings":{"sockopt":{"dialerProxy":"hop"}}}},
                "provider":{"dialerProxy":"literal","reverse":{"tag":"literal"}}},
            {"tag":"hop","protocol":"freedom"}
        ]""")
        val original = JSONObject(source.config).getJSONArray("outbounds").getJSONObject(0)
        val result = compile(LiveRoutingPlan(listOf(group())), mapOf(1L to source)).getJSONArray("outbounds")
        val primary = result.getJSONObject(3)
        assertJsonEquals(original.getJSONObject("provider"), primary.getJSONObject("provider"))
        assertJsonEquals(original.getJSONObject("streamSettings").getJSONObject("wsSettings"),
            primary.getJSONObject("streamSettings").getJSONObject("wsSettings"))
        assertEquals(result.getJSONObject(4).getString("tag"),
            primary.getJSONObject("streamSettings").getJSONObject("splithttpSettings")
                .getJSONObject("downloadSettings").getJSONObject("sockopt").getString("dialerProxy"))
    }

    @Test
    fun baseOutboundAndBalancerMayShareATagBecauseXrayNamespacesAreSeparate() {
        val base = base()
        val balancer = JSONObject().put("tag", "proxy").put("selector", JSONArray().put("proxy"))
            .put("strategy", JSONObject().put("type", "random"))
        base.getJSONObject("routing").put("balancers", JSONArray().put(balancer))
        val result = compile(LiveRoutingPlan(listOf(group())), mapOf(1L to profile(1)), base)
        assertJsonEquals(balancer, result.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0))
    }

    @Test
    fun scalarBaseBalancerSelectorsRemainValidAndCannotCaptureManagedProfiles() {
        val base = base()
        val balancer = JSONObject().put("tag", "existing").put("selector", "proxy,yaxc")
            .put("strategy", JSONObject().put("type", "random"))
        base.getJSONObject("routing").put("balancers", JSONArray().put(balancer))
        val result = compile(LiveRoutingPlan(listOf(group())), mapOf(1L to profile(1)), base)
        assertJsonEquals(balancer, result.getJSONObject("routing").getJSONArray("balancers").getJSONObject(0))
        assertFalse(result.getJSONArray("outbounds").getJSONObject(3).getString("tag").startsWith("yaxc"))
    }

    private fun compile(
        plan: LiveRoutingPlan, profiles: Map<Long, Profile> = mapOf(1L to profile(1)), base: JSONObject = base(),
        uids: Map<String, Int> = emptyMap(), username: String = "test-user", password: String = "test-password",
        pingAddress: String = "", healthIntervalSeconds: Int = 10,
    ): JSONObject = JSONObject(LiveRoutingHelper.compile(
        base.toString(), plan, profiles, uids, username, password, pingAddress, healthIntervalSeconds,
    ))

    private fun group(
        strategy: ProxyGroupStrategy = ProxyGroupStrategy.Select, members: List<Long> = listOf(1), selected: Long? = null,
    ) = ProxyGroup(id = "group", name = "Synthetic group", profileIds = members, strategy = strategy, selectedProfileId = selected)

    private fun rule(
        id: String = "rule", enabled: Boolean = true, packages: List<String> = emptyList(),
        domains: List<String> = listOf("example.test"), target: String = "proxy",
    ) = AppDomainRule(id = id, name = "Synthetic rule", enabled = enabled, packages = packages, domains = domains, target = target)

    private fun profile(id: Long, linkId: Long = 10, outbounds: String = """[{"protocol":"socks","settings":{"address":"192.0.2.1","port":1080}}]""") =
        Profile(id = id, linkId = linkId, name = "Synthetic $id", config = "{\"outbounds\":$outbounds}")

    private fun base() = JSONObject("""{
        "log":{"loglevel":"warning"},
        "inbounds":[{"tag":"socks","listen":"127.0.0.1","port":10808,"protocol":"socks",
            "settings":{"auth":"password","udp":true,"accounts":[{"user":"test-user","pass":"test-password"}]},
            "sniffing":{"enabled":true,"destOverride":["http","tls"]}}],
        "outbounds":[{"tag":"proxy","protocol":"socks","settings":{"address":"192.0.2.10","port":1080}},
            {"tag":"direct","protocol":"freedom"},{"tag":"block","protocol":"blackhole"}],
        "routing":{"domainStrategy":"IPIfNonMatch","rules":[{"ruleTag":"base-rule","ip":["192.0.2.0/24"],"outboundTag":"direct"}]}
    }""")

    private fun expectError(fragment: String, context: String = "", action: () -> Unit) {
        try {
            action()
        } catch (error: IllegalArgumentException) {
            assertTrue("$context expected '$fragment' in '${error.message}'", error.message.orEmpty().contains(fragment, ignoreCase = true))
            return
        }
        throw AssertionError("$context expected IllegalArgumentException containing '$fragment'")
    }
}
