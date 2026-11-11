package io.github.derundevu.yaxc.helper

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoreRoutingImportHelperTest {
    private val source = """{"dns":{"hosts":{"private":"127.0.0.1"}},"inbounds":[{"tag":"local","protocol":"socks"}],"outbounds":[
        {"tag":"not-direct","protocol":"freedom"}, {"tag":"not-block","protocol":"blackhole"},
        {"tag":"not-proxy","protocol":"vless"}
    ],"routing":{"domainStrategy":"AsIs","rules":[
        {"type":"field","domain":["full:first.test"],"outboundTag":"not-direct","ruleTag":"first"},
        {"inboundTag":["local"],"port":"443","outboundTag":"not-proxy"},
        {"network":"udp","outboundTag":"not-block"}
    ]}}"""

    private fun preview(config: String = source) = CoreRoutingImportHelper.preview(CoreRoutingImportProfile(1, "Source", config))

    @Test fun subsetImportPreservesOrderCompleteRulesAndSourceWithoutDnsOrImplicitStrategy() {
        val preview = preview()
        val result = CoreRoutingImportHelper.importIntoDraft(preview,
            """{"domainStrategy":"IPOnDemand","provider":{"kept":true},"rules":[{"port":"80","outboundTag":"block"}]}""",
            setOf(2, 0), emptyMap(), emptyMap(), "socks", false, false)
        val routing = JSONObject(result.routing)
        val rules = routing.getJSONArray("rules")
        assertEquals(listOf("block", "direct", "block"), (0 until rules.length()).map { rules.getJSONObject(it).getString("outboundTag") })
        assertEquals("field", rules.getJSONObject(1).getString("type"))
        assertEquals("first", rules.getJSONObject(1).getString("ruleTag"))
        assertEquals("IPOnDemand", routing.getString("domainStrategy"))
        assertTrue(routing.getJSONObject("provider").getBoolean("kept"))
        assertFalse(routing.has("dns"))
        assertEquals(source, preview.profile.config)
        assertEquals(2, result.added)
    }

    @Test fun repeatImportSkipsExactRulesButRetainsDifferentConditionsTargetsAndArrayOrder() {
        val preview = preview()
        val first = CoreRoutingImportHelper.importIntoDraft(preview, "{}", setOf(0, 2), emptyMap(), emptyMap(), "socks", false, false)
        val second = CoreRoutingImportHelper.importIntoDraft(preview, first.routing, setOf(0, 2), emptyMap(), emptyMap(), "socks", false, false)
        assertEquals(0, second.added)
        assertEquals(2, second.skipped)
        assertJsonEquals(JSONObject(first.routing), JSONObject(second.routing))
        val rules = JsonHelper.distinctRules(JSONArray("""[
            {"domain":["a","b"],"outboundTag":"direct"},
            {"outboundTag":"direct","domain":["a","b"]},
            {"domain":["b","a"],"outboundTag":"direct"},
            {"domain":["a","b"],"outboundTag":"block"},
            {"domain":["a","b"],"outboundTag":"direct","ruleTag":"different"}
        ]"""))
        assertEquals(4, rules.length())
    }

    @Test fun replaceRulesAndStrategyAreIndependentAndDoNotCopyOtherRoutingMembers() {
        val preview = preview()
        val result = CoreRoutingImportHelper.importIntoDraft(preview,
            """{"domainStrategy":"IPIfNonMatch","provider":true,"rules":[{"port":"1","outboundTag":"proxy"}]}""",
            setOf(2), emptyMap(), emptyMap(), "socks", true, true)
        val routing = JSONObject(result.routing)
        assertEquals(1, routing.getJSONArray("rules").length())
        assertEquals("AsIs", routing.getString("domainStrategy"))
        assertTrue(routing.getBoolean("provider"))
        val strategyOnly = CoreRoutingImportHelper.importIntoDraft(preview, "{}", emptySet(), emptyMap(), emptyMap(), "socks", false, true)
        assertEquals("AsIs", JSONObject(strategyOnly.routing).getString("domainStrategy"))
        assertEquals(0, strategyOnly.added)
    }

    @Test fun inboundRequiresExplicitMappingAndOnlyCurrentListenerIsAccepted() {
        val preview = preview()
        assertThrows(IllegalArgumentException::class.java) {
            CoreRoutingImportHelper.importIntoDraft(preview, "{}", setOf(1), emptyMap(), emptyMap(), "socks", false, false)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CoreRoutingImportHelper.importIntoDraft(preview, "{}", setOf(1), emptyMap(), mapOf("local" to "all-in"), "socks", false, false)
        }
        val imported = CoreRoutingImportHelper.importIntoDraft(preview, "{}", setOf(1), emptyMap(), mapOf("local" to "socks"), "socks", false, false)
        val rule = JSONObject(imported.routing).getJSONArray("rules").getJSONObject(0)
        assertEquals("proxy", rule.getString("outboundTag"))
        assertEquals("socks", rule.getJSONArray("inboundTag").getString(0))
    }

    @Test fun multipleProxiesAndRedirectedFreedomRequireExplicitDestination() {
        val config = JSONObject(source)
        config.getJSONArray("outbounds").put(JSONObject("""{"tag":"second-proxy","protocol":"trojan"}"""))
        config.getJSONArray("outbounds").getJSONObject(0).put("settings", JSONObject().put("redirect", "127.0.0.1:8080"))
        val preview = preview(config.toString())
        assertNull(preview.rules[0].outbound!!.suggestedTarget)
        assertNull(preview.rules[1].outbound!!.suggestedTarget)
        assertNull(preview.rules[0].problem)
        assertThrows(IllegalArgumentException::class.java) {
            CoreRoutingImportHelper.importIntoDraft(preview, "{}", setOf(0), emptyMap(), emptyMap(), "socks", false, false)
        }
        val result = CoreRoutingImportHelper.importIntoDraft(preview, "{}", setOf(0), mapOf("not-direct" to "proxy"), emptyMap(), "socks", false, false)
        assertEquals("proxy", JSONObject(result.routing).getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
    }

    @Test fun masqueMapsToProxyWhileUnsupportedProtocolsCannotBeMappedAround() {
        fun profile(protocol: String) = """{"outbounds":[{"tag":"source-exit","protocol":"$protocol"}],
            "routing":{"rules":[{"domain":["full:example.test"],"outboundTag":"source-exit"}]}}"""
        val supported = preview(profile("masque"))
        assertNull(supported.rules.single().problem)
        assertEquals("proxy", supported.rules.single().outbound!!.suggestedTarget)
        val result = CoreRoutingImportHelper.importIntoDraft(
            supported, "{}", setOf(0), emptyMap(), emptyMap(), "socks", false, false)
        val rule = JSONObject(result.routing).getJSONArray("rules").getJSONObject(0)
        assertEquals("proxy", rule.getString("outboundTag"))
        assertEquals("full:example.test", rule.getJSONArray("domain").getString(0))
        listOf("hysteria2", "tuic", "unknown-forwarder").forEach { protocol ->
            val unsupported = preview(profile(protocol))
            assertNotNull(protocol, unsupported.rules.single().problem)
            assertThrows(IllegalArgumentException::class.java) {
                CoreRoutingImportHelper.importIntoDraft(
                    unsupported, "{}", setOf(0), mapOf("source-exit" to "proxy"), emptyMap(), "socks", false, false)
            }
        }
    }

    @Test fun unsupportedGraphsDanglingReferencesAndConflictingTargetsCannotBeImported() {
        val cases = listOf(
            """{"outbounds":[{"tag":"a","protocol":"vless","proxySettings":{"tag":"missing"}}],"routing":{"rules":[{"outboundTag":"a"}]}}""",
            """{"outbounds":[{"tag":"a","protocol":"vless","streamSettings":{"sockopt":{"dialerProxy":"a"}}}],"routing":{"rules":[{"outboundTag":"a"}]}}""",
            """{"outbounds":[{"tag":"a","protocol":"loopback"}],"routing":{"rules":[{"outboundTag":"a"}]}}""",
            """{"routing":{"rules":[{"outboundTag":"missing"}]}}""",
            """{"outbounds":[{"tag":"a","protocol":"freedom"}],"routing":{"rules":[{"outboundTag":"a","inboundTag":["missing"]}]}}""",
            """{"outbounds":[{"tag":"a","protocol":"freedom"},{"tag":"a","protocol":"blackhole"}],"routing":{"rules":[{"outboundTag":"a"}]}}""",
            """{"outbounds":[{"tag":"a","protocol":"freedom"}],"routing":{"balancers":[{"tag":"b","selector":["a"]}],"rules":[{"balancerTag":"b"}]}}""",
            """{"routing":{"rules":[{"outboundTag":"a","balancerTag":"b"}]}}""",
        )
        cases.forEach { config ->
            val preview = preview(config)
            assertNotNull(config, preview.rules.single().problem)
            assertThrows(IllegalArgumentException::class.java) {
                CoreRoutingImportHelper.importIntoDraft(preview, "{}", setOf(0), mapOf("a" to "proxy", "missing" to "direct"), mapOf("missing" to "socks"), "socks", false, false)
            }
        }
    }

    @Test fun routingMergeOnlyDeduplicatesCompleteRulesAndKeepsProfileFirstAndGlobalScalars() {
        val profile = JSONObject("""{"domainStrategy":"AsIs","other":[1,1],"rules":[{"outboundTag":"direct","domain":["a"],"meta":{"b":2,"a":1}}]}""")
        val global = JSONObject("""{"domainStrategy":"IPOnDemand","other":[1],"rules":[{"meta":{"a":1,"b":2},"domain":["a"],"outboundTag":"direct"},{"domain":["a"],"outboundTag":"block"}]}""")
        val merged = JsonHelper.mergeRouting(profile, global)
        assertEquals(2, merged.getJSONArray("rules").length())
        assertEquals("direct", merged.getJSONArray("rules").getJSONObject(0).getString("outboundTag"))
        assertEquals("block", merged.getJSONArray("rules").getJSONObject(1).getString("outboundTag"))
        assertEquals("IPOnDemand", merged.getString("domainStrategy"))
        assertEquals(3, merged.getJSONArray("other").length())
        assertEquals(3, JsonHelper.mergeObjects(profile, global).getJSONArray("other").length())
        assertEquals(3, JsonHelper.mergeObjects(profile, global).getJSONArray("rules").length())
    }
}
