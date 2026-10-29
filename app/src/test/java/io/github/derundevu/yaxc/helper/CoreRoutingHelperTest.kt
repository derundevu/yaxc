package io.github.derundevu.yaxc.helper

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CoreRoutingHelperTest {
    @Test
    fun allMatchersAreCombinedWithoutDroppingPortsNetworksOrCustomTags() {
        val parsed = CoreRoutingHelper.parseRoutingJson(
            """{"domainStrategy":"AsIs","rules":[{
                "domain":[" full:one.test ","domain:two.test"],
                "ip":["192.0.2.0/24","2001:db8::/32"],
                "port":"1, 443\n1000-2000,65535", "sourcePort":["53","1024-65535"],
                "protocol":["http","tls"], "network":"tcp", "outboundTag":" local-egress "
            }]}""",
        )
        val rule = parsed.rules.single()
        assertEquals("full:one.test\ndomain:two.test", rule.domainsText)
        assertEquals("192.0.2.0/24\n2001:db8::/32", rule.ipsText)
        assertEquals("http\ntls", rule.protocolsText)
        val routing = rebuilt(parsed)
        assertEquals("AsIs", routing.getString("domainStrategy"))
        val built = routing.getJSONArray("rules").getJSONObject(0)
        assertEquals("1,443,1000-2000,65535", built.getString("port"))
        assertEquals("53,1024-65535", built.getString("sourcePort"))
        assertEquals("tcp", built.getString("network"))
        assertEquals("local-egress", built.getString("outboundTag"))
        assertEquals(listOf("http", "tls"), built.getJSONArray("protocol").stringValues())
        assertEquals(listOf("192.0.2.0/24", "2001:db8::/32"), built.getJSONArray("ip").stringValues())
    }

    @Test
    fun blankMatchersNeverCreateCatchAllRulesAndDisabledMatchersOnlyAppearInExchange() {
        val blank = CoreRoutingRule(domainsText = " \n ", portsText = " , \n ", network = CoreRoutingTransport.Udp)
        val disabled = CoreRoutingRule(enabled = false, protocolsText = "tls", outboundTag = "block")
        val active = CoreRoutingRule(portsText = " 1, 65535 ", outboundTag = " ")
        val rules = listOf(blank, disabled, active)
        val native = JSONObject(CoreRoutingHelper.buildRoutingJson("AsIs", rules)).getJSONArray("rules")
        assertEquals(1, native.length())
        assertEquals("proxy", native.getJSONObject(0).getString("outboundTag"))
        assertEquals("1,65535", native.getJSONObject(0).getString("port"))
        assertFalse(native.getJSONObject(0).has("network"))
        val exchange = JSONArray(CoreRoutingHelper.buildRulesExchangeJson(rules))
        assertEquals(2, exchange.length())
        assertFalse(exchange.getJSONObject(0).getBoolean("enabled"))
        assertEquals(listOf("tls"), exchange.getJSONObject(0).getJSONArray("protocol").stringValues())
    }


    @Test
    fun individualMatcherFormsAndKnownNetworksRoundTripSemantically() {
        val matchers = listOf(
            "domain" to JSONArray().put("full:example.test"),
            "ip" to "198.51.100.0/24",
            "port" to 65535,
            "sourcePort" to 1,
            "protocol" to JSONArray().put("bittorrent"),
        )
        val networks = listOf<Any?>(null, "tcp", "udp", "tcp,udp", JSONArray().put("udp"), JSONArray().put("tcp").put("udp"))
        matchers.forEach { (matcher, value) ->
            networks.forEach { network ->
                val source = JSONObject().put(matcher, value).put("outboundTag", "custom-egress")
                if (network != null) source.put("network", network)
                val parsed = CoreRoutingHelper.parseImportedRules(source.toString())
                assertEquals("matcher=$matcher network=$network", 1, parsed.rules.size)
                assertEquals(0, parsed.unsupportedRuleCount)
                val built = rebuilt(parsed).getJSONArray("rules").getJSONObject(0)
                assertTrue("retains $matcher", built.has(matcher))
                assertEquals("custom-egress", built.getString("outboundTag"))
                val expectedNetwork = CoreRoutingTransport.fromJsonValue(network)
                assertEquals(expectedNetwork, CoreRoutingTransport.fromJsonValue(built.opt("network")))
                assertEquals(parsed.rules.single().copy(id = "semantic-id"), CoreRoutingHelper.parseImportedRules(built.toString()).rules.single().copy(id = "semantic-id"))
            }
        }
    }

    @Test
    fun unknownRulesAndTopLevelFieldsKeepFirstMatchOrderAndNestedValues() {
        val source = JSONObject(
            """{"domainStrategy":"IPOnDemand","domainMatcher":"hybrid","provider":{"revision":7,"flags":[true,null,"future"]},"rules":[
                {"domain":["full:overlap.test"],"outboundTag":"first","providerRule":{"priority":1}},
                {"domain":["domain:test"],"outboundTag":"second"},
                {"ip":["192.0.2.0/24"],"balancerTag":"third","future":[1,{"a":false}]},
                {"port":"443","outboundTag":"fourth"},
                {"outboundTag":"fallback"}
            ]}""",
        )
        val parsed = CoreRoutingHelper.parseRoutingJson(source.toString())
        assertEquals(2, parsed.rules.size)
        assertEquals(3, parsed.unsupportedRuleCount)
        val built = rebuilt(parsed)
        assertEquals(listOf("first", "second", "third", "fourth", "fallback"), tags(built.getJSONArray("rules")))
        assertJsonEquals(source.getJSONObject("provider"), built.getJSONObject("provider"))
        assertEquals("hybrid", built.getString("domainMatcher"))
        for (index in listOf(0, 2, 4)) {
            assertJsonEquals(source.getJSONArray("rules").getJSONObject(index), built.getJSONArray("rules").getJSONObject(index))
        }
        assertEquals("first", built.getJSONArray("rules").objectValues().first { it.optJSONArray("domain") != null }.getString("outboundTag"))
    }

    @Test
    fun disablingDeletingMovingAndAddingEditableRulesDoNotAppendPreservedRules() {
        val parsed = CoreRoutingHelper.parseRoutingJson(
            """{"rules":[
                {"domain":["full:a.test"],"outboundTag":"a"},
                {"inboundTag":["local"],"outboundTag":"unknown"},
                {"port":443,"outboundTag":"b"}
            ]}""",
        )
        val first = parsed.rules.first()
        val second = parsed.rules.last()
        assertEquals(listOf("unknown", "b"), tags(rebuilt(parsed.copy(rules = listOf(first.copy(enabled = false), second))).getJSONArray("rules")))
        assertEquals(listOf("unknown", "b"), tags(rebuilt(parsed.copy(rules = listOf(second))).getJSONArray("rules")))
        assertEquals(listOf("b", "unknown", "a"), tags(rebuilt(parsed.copy(rules = listOf(second, first))).getJSONArray("rules")))
        val added = CoreRoutingRule(protocolsText = "tls", outboundTag = "new")
        assertEquals(listOf("a", "unknown", "b", "new"), tags(rebuilt(parsed.copy(rules = parsed.rules + added)).getJSONArray("rules")))
    }

    @Test
    fun unsupportedNetworkConstraintsRemainRawInsteadOfBecomingAnyNetwork() {
        listOf<Any>("quic", "tcp,quic", JSONArray().put("udp").put("future"), 42).forEach { network ->
            val raw = JSONObject().put("port", "443").put("network", network).put("outboundTag", "restricted")
            val parsed = CoreRoutingHelper.parseImportedRules(raw.toString())
            assertTrue(parsed.rules.isEmpty())
            assertEquals(1, parsed.unsupportedRuleCount)
            assertJsonEquals(raw, rebuilt(parsed).getJSONArray("rules").getJSONObject(0))
        }
    }

    @Test
    fun uiSerializationRetainsIdentityDisabledStateAndEveryEditableField() {
        val rule = CoreRoutingRule(
            name = "Synthetic route", enabled = false, domainsText = "full:example.test",
            ipsText = "2001:db8::/32", portsText = "1,65535", sourcePortsText = "1024-2048",
            protocolsText = "tls\nhttp", network = CoreRoutingTransport.Udp, outboundTag = "custom-egress",
        )
        assertEquals(listOf(rule), CoreRoutingHelper.parseUiRules(CoreRoutingHelper.encodeUiRules(listOf(rule))))
        assertTrue(CoreRoutingHelper.parseUiRules("not-json").isEmpty())
        val encodedRule = JSONArray(CoreRoutingHelper.encodeUiRules(listOf(rule))).getJSONObject(0)
        assertEquals(listOf(rule), CoreRoutingHelper.parseUiRules(JSONArray().put(false).put(encodedRule).toString()))
    }

    @Test
    fun legacyUiMatchTypesAndTransportMigrateIntoSeparateFields() {
        CoreRoutingMatchType.entries.forEach { type ->
            val raw = JSONArray().put(JSONObject()
                .put("matchType", type.name).put("valuesText", "synthetic-value")
                .put("transport", "Tcp").put("enabled", false).put("outboundTag", "direct"))
            val rule = CoreRoutingHelper.parseUiRules(raw.toString()).single()
            val values = listOf(rule.domainsText, rule.ipsText, rule.portsText, rule.sourcePortsText, rule.protocolsText)
            assertEquals(listOf("synthetic-value"), values.filter(String::isNotBlank))
            assertEquals("synthetic-value", values[type.ordinal])
            assertEquals(CoreRoutingTransport.Tcp, rule.network)
            assertFalse(rule.enabled)
            assertEquals("direct", rule.outboundTag)
        }
    }

    @Test
    fun legacyGeodataMigrationsAreExactAndLeaveCustomizedListsIntact() {
        val old = CoreRoutingRule(domainsText = "geosite:ru", ipsText = "geoip:private")
        val migrated = CoreRoutingHelper.migrateLegacyRules(listOf(old)).single()
        assertEquals(old.id, migrated.id)
        assertEquals("geosite:category-ru", migrated.domainsText)
        val privateIps = migrated.ipsText.lines()
        assertTrue(privateIps.containsAll(listOf("10.0.0.0/8", "127.0.0.0/8", "::1/128", "fc00::/7")))
        assertFalse(privateIps.contains("geoip:private"))
        val customized = old.copy(domainsText = "geosite:ru\nfull:custom.test", ipsText = "geoip:private\n192.0.2.0/24")
        assertEquals(customized, CoreRoutingHelper.migrateLegacyRules(listOf(customized)).single())
        val fallback = old.copy(domainsText = "domain:ru\ndomain:xn--p1ai", ipsText = "geoip:ru")
        assertEquals("geosite:category-ru", CoreRoutingHelper.migrateLegacyRules(listOf(fallback)).single().domainsText)
        assertEquals("geoip:ru", CoreRoutingHelper.migrateLegacyRules(listOf(fallback)).single().ipsText)
    }

    @Test
    fun storedUiStateMatchesShapesOnceAndRestoresDisabledPositions() {
        val first = CoreRoutingRule(name = "One", domainsText = "full:one.test", outboundTag = "direct")
        val disabled = CoreRoutingRule(name = "Keep disabled", enabled = false, portsText = "53", network = CoreRoutingTransport.Udp)
        val last = CoreRoutingRule(name = "Two", portsText = "80,443", outboundTag = "block")
        val obsolete = CoreRoutingRule(name = "Obsolete enabled UI", portsText = "123")
        val stored = listOf(first, disabled, last, obsolete)
        val parsed = CoreRoutingHelper.parseRoutingJson(
            CoreRoutingHelper.buildRoutingJson("AsIs", listOf(first, last)), stored,
        )
        assertEquals(listOf(first, disabled, last), parsed.rules)
        assertEquals(listOf("direct", "block"), tags(rebuilt(parsed).getJSONArray("rules")))
        val reenabled = parsed.copy(rules = parsed.rules.map { it.copy(enabled = true) })
        assertEquals(listOf("direct", "proxy", "block"), tags(rebuilt(reenabled).getJSONArray("rules")))
        val duplicate = first.copy(name = "Separate identity")
        val duplicateParsed = CoreRoutingHelper.parseRoutingJson(
            CoreRoutingHelper.buildRoutingJson("AsIs", listOf(first, duplicate)), listOf(first, duplicate),
        )
        assertEquals(listOf(first.id, duplicate.id), duplicateParsed.rules.map { it.id })
    }

    @Test
    fun disabledDuplicateCannotStealEnabledNativeRulesIdentityOrSuppressItsMatcher() {
        val enabled = CoreRoutingRule(name = "Active duplicate", portsText = "443", outboundTag = "direct")
        val disabled = enabled.copy(id = CoreRoutingHelper.defaultRule(1).id, name = "Disabled duplicate", enabled = false)
        val parsed = CoreRoutingHelper.parseRoutingJson(
            CoreRoutingHelper.buildRoutingJson("AsIs", listOf(enabled)),
            listOf(disabled, enabled),
        )
        assertEquals(listOf(disabled, enabled), parsed.rules)
        assertEquals(listOf("direct"), tags(rebuilt(parsed).getJSONArray("rules")))
        assertEquals(enabled.id, parsed.rules.single { it.enabled }.id)
    }


    @Test
    fun importAcceptsObjectArrayAndRoutingRootAndExportKeepsDisabledAndUnsupportedOrder() {
        val supported = CoreRoutingRule(name = "Synthetic", enabled = false, portsText = "1,65535", network = CoreRoutingTransport.Tcp, outboundTag = "custom")
        val exchange = CoreRoutingHelper.buildRulesExchangeJson(listOf(supported))
        val imported = CoreRoutingHelper.parseImportedRules(exchange, "AsIs")
        assertEquals("AsIs", imported.domainStrategy)
        assertEquals(supported.copy(id = "semantic-id"), imported.rules.single().copy(id = "semantic-id"))
        assertEquals(0, rebuilt(imported).getJSONArray("rules").length())
        assertEquals(1, CoreRoutingHelper.parseImportedRules(JSONArray(exchange).getJSONObject(0).toString()).rules.size)
        val inherited = CoreRoutingHelper.parseImportedRules(
            """{"rules":[{"port":"443","outboundTag":"custom"}],"provider":{"kept":true}}""",
            fallbackDomainStrategy = "AsIs",
        )
        assertEquals("AsIs", inherited.domainStrategy)
        assertTrue(rebuilt(inherited).getJSONObject("provider").getBoolean("kept"))
        val interleaved = CoreRoutingHelper.parseImportedRules("""{"domainStrategy":"IPOnDemand","rules":[
            {"inboundTag":["local"],"outboundTag":"first"},
            {"domain":["full:example.test"],"outboundTag":"second","enabled":false},
            {"balancerTag":"third","protocol":["tls"]}
        ]}""")
        val exported = JSONArray(CoreRoutingHelper.buildRulesExchangeJson(interleaved.rules, ruleOrder = interleaved.ruleOrder))
        assertEquals(listOf("first", "second", "third"), tags(exported))
        assertFalse(exported.getJSONObject(1).getBoolean("enabled"))
        assertEquals("IPOnDemand", interleaved.domainStrategy)
        assertEquals(2, CoreRoutingHelper.parseImportedRules(exported.toString()).unsupportedRuleCount)
    }

    @Test
    fun seededUpgradeOnlyRecognizesUnmodifiedRoutingShapesAndPriority() {
        val old = CoreRoutingHelper.defaultSeededRules().filterNot { it.ipsText == "geoip:ru" }
        assertTrue(CoreRoutingHelper.shouldUpgradeSeededDefaults(old))
        assertFalse(CoreRoutingHelper.shouldUpgradeSeededDefaults(CoreRoutingHelper.defaultSeededRules()))
        val mutations = listOf<(CoreRoutingRule) -> CoreRoutingRule>(
            { it.copy(enabled = false) }, { it.copy(domainsText = "full:custom.test") },
            { it.copy(ipsText = "192.0.2.0/24") }, { it.copy(portsText = "65535") },
            { it.copy(sourcePortsText = "1024") }, { it.copy(protocolsText = "tls") },
            { it.copy(network = CoreRoutingTransport.Tcp) }, { it.copy(outboundTag = "custom") },
        )
        mutations.forEach { mutation ->
            assertFalse(CoreRoutingHelper.shouldUpgradeSeededDefaults(old.toMutableList().apply { this[0] = mutation(this[0]) }))
        }
        assertFalse(CoreRoutingHelper.shouldUpgradeSeededDefaults(old.reversed()))
        assertFalse(CoreRoutingHelper.shouldUpgradeSeededDefaults(old + CoreRoutingRule(portsText = "53")))
    }

    @Test
    fun onlyEffectiveRoutingOrCustomSettingsAreMeaningful() {
        assertFalse(CoreRoutingHelper.isMeaningfulRoutingJson("{}"))
        assertFalse(CoreRoutingHelper.isMeaningfulRoutingJson("not json"))
        assertFalse(CoreRoutingHelper.isMeaningfulRoutingJson("""{"domainStrategy":"IPIfNonMatch"}"""))
        assertFalse(CoreRoutingHelper.isMeaningfulRoutingJson("""{"domainStrategy":"IPIfNonMatch","rules":[]}"""))
        assertFalse(CoreRoutingHelper.isMeaningfulRoutingJson("""{"rules":[]}"""))
        assertTrue(CoreRoutingHelper.isMeaningfulRoutingJson("""{"domainStrategy":"AsIs"}"""))
        assertTrue(CoreRoutingHelper.isMeaningfulRoutingJson("""{"domainMatcher":"hybrid"}"""))
        assertTrue(CoreRoutingHelper.isMeaningfulRoutingJson("""{"rules":[{"outboundTag":"direct"}]}"""))
    }

    @Test(expected = IllegalArgumentException::class)
    fun importingScalarIsRejected() {
        CoreRoutingHelper.parseImportedRules("false")
    }

    private fun rebuilt(parsed: ParsedCoreRouting): JSONObject = JSONObject(CoreRoutingHelper.buildRoutingJson(
        parsed.domainStrategy, parsed.rules, parsed.ruleOrder, parsed.preservedTopLevel,
    ))

    private fun tags(array: JSONArray): List<String> = array.objectValues().map {
        it.optString("outboundTag").ifBlank { it.getString("balancerTag") }
    }
}
