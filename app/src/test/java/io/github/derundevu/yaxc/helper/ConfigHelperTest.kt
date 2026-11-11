package io.github.derundevu.yaxc.helper

import android.app.Application
import io.github.derundevu.yaxc.database.Config as GlobalConfig
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
class ConfigHelperTest : HelperSettingsTest() {
    @Test
    fun mergeOverlaysNestedFieldsAndProtocolMatchedOutboundsWithoutLosingProfileData() {
        val profile = baseProfile()
        val global = GlobalConfig(
            log = """{"error":"merged-error.log"}""",
            logMode = GlobalConfig.Mode.Merge,
            dns = """{"hosts":{"overlay.internal":"198.51.100.10"},"queryStrategy":"UseIPv4"}""",
            dnsMode = GlobalConfig.Mode.Merge,
            outbounds = """[
                {"protocol":"vless","streamSettings":{"wsSettings":{"path":"/overlay"}},"mux":{"enabled":true}},
                {"protocol":"socks","tag":"backup-egress","settings":{"servers":[{"address":"127.0.0.1","port":19081}]}}
            ]""",
            outboundsMode = GlobalConfig.Mode.Merge,
            routing = """{"domainStrategy":"IPOnDemand","rules":[
                {"ruleTag":"overlay-route","domain":["full:overlay.internal"],"outboundTag":"backup-egress"}
            ]}""",
            routingMode = GlobalConfig.Mode.Merge,
        )

        val generated = generate(global, profile)

        assertEquals("profile-access.log", generated.getJSONObject("log").getString("access"))
        assertEquals("merged-error.log", generated.getJSONObject("log").getString("error"))
        val dns = generated.getJSONObject("dns")
        assertEquals("192.0.2.10", dns.getJSONObject("hosts").getString("profile.internal"))
        assertEquals("198.51.100.10", dns.getJSONObject("hosts").getString("overlay.internal"))
        assertEquals("UseIPv4", dns.getString("queryStrategy"))
        assertTrue(dns.getJSONObject("futureDns").getBoolean("cache"))
        val outbounds = generated.getJSONArray("outbounds").objectValues()
        assertEquals(listOf("primary-egress", "lan-egress", "reject-egress", "backup-egress"), outbounds.map { it.getString("tag") })
        val proxy = outbounds.first()
        assertEquals("127.0.0.1", proxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"))
        assertTrue(proxy.getJSONObject("mux").getBoolean("enabled"))
        val stream = proxy.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("/overlay", stream.getJSONObject("wsSettings").getString("path"))
        assertEquals("kept", stream.getJSONObject("wsSettings").getJSONObject("headers").getString("X-Profile"))
        val routing = generated.getJSONObject("routing")
        assertEquals("IPOnDemand", routing.getString("domainStrategy"))
        val rules = routing.getJSONArray("rules")
        assertEquals("profile-route", rules.getJSONObject(0).getString("ruleTag"))
        assertEquals("overlay-route", rules.getJSONObject(1).getString("ruleTag"))
        assertEquals("backup-egress", rules.getJSONObject(1).getString("outboundTag"))
        assertTrue(rules.getJSONObject(0).getJSONObject("futureRule").getBoolean("enabled"))
        assertJsonEquals(profile.getJSONObject("futureRoot"), generated.getJSONObject("futureRoot"))
    }

    @Test
    fun replaceDropsOldSectionFieldsAndOutboundsButKeepsManagedRuntimeRequirements() {
        val global = GlobalConfig(
            log = """{"error":"replacement-error.log"}""",
            logMode = GlobalConfig.Mode.Replace,
            dns = """{"hosts":{"replacement.internal":"203.0.113.10"},"queryStrategy":"UseIP"}""",
            dnsMode = GlobalConfig.Mode.Replace,
            outbounds = """[
                {"protocol":"socks","tag":"replacement-egress","settings":{"servers":[{"address":"127.0.0.1","port":19082}]}}
            ]""",
            outboundsMode = GlobalConfig.Mode.Replace,
            routing = """{"domainStrategy":"IPIfNonMatch","rules":[
                {"ruleTag":"replacement-route","domain":["full:replacement.internal"],"outboundTag":"proxy"}
            ]}""",
            routingMode = GlobalConfig.Mode.Replace,
        )

        val generated = generate(global)

        val log = generated.getJSONObject("log")
        assertFalse(log.has("access"))
        assertFalse(log.has("futureLog"))
        assertEquals("replacement-error.log", log.getString("error"))
        val dns = generated.getJSONObject("dns")
        assertFalse(dns.has("futureDns"))
        assertEquals(setOf("replacement.internal"), dns.getJSONObject("hosts").keys().asSequence().toSet())
        assertEquals(listOf("192.0.2.53", "198.51.100.53"), dns.getJSONArray("servers").stringValues())
        val outbounds = generated.getJSONArray("outbounds").objectValues()
        assertEquals(listOf("replacement-egress", "direct", "block"), outbounds.map { it.getString("tag") })
        val rules = generated.getJSONObject("routing").getJSONArray("rules").objectValues()
        assertEquals("replacement-route", rules.first().getString("ruleTag"))
        assertFalse(rules.any { it.optString("ruleTag") == "profile-route" })
        assertEquals("replacement-egress", rules.first().getString("outboundTag"))
        assertEquals("replacement-egress", rules.single { it.optInt("port") == 53 }.getString("outboundTag"))
        assertEquals("socks", generated.getJSONArray("inbounds").getJSONObject(0).getString("protocol"))
        assertTrue(generated.getJSONObject("futureRoot").getBoolean("preserved"))
    }

    @Test
    fun disabledOverlaysAreNotParsedAndDoNotEraseProfileSections() {
        val disabled = GlobalConfig(
            log = "not JSON",
            dns = "not JSON",
            inbounds = "not JSON",
            outbounds = "not JSON",
            routing = "not JSON",
        )

        val generated = generate(disabled)

        assertEquals("profile-access.log", generated.getJSONObject("log").getString("access"))
        assertEquals("192.0.2.10", generated.getJSONObject("dns").getJSONObject("hosts").getString("profile.internal"))
        assertEquals("primary-egress", generated.getJSONArray("outbounds").getJSONObject(0).getString("tag"))
        assertEquals("profile-route", generated.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).getString("ruleTag"))
        // Disable means no global overlay, not bypassing the app's active runtime listener settings.
        assertEquals(19080, generated.getJSONArray("inbounds").getJSONObject(0).getInt("port"))
    }

    @Test
    fun configuredIpv4AndOptionalIpv6DnsAreUsedByResolverAndProxyDnsRule() {
        for (ipv6Enabled in listOf(false, true)) {
            settings.enableIpV6 = ipv6Enabled
            val expected = mutableListOf("192.0.2.53", "198.51.100.53")
            if (ipv6Enabled) expected += listOf("2001:db8::53", "2001:db8::54")

            val generated = generate()

            val dns = generated.getJSONObject("dns")
            assertEquals("resolver servers with IPv6=$ipv6Enabled", expected, dns.getJSONArray("servers").stringValues())
            assertEquals("192.0.2.10", dns.getJSONObject("hosts").getString("profile.internal"))
            val dnsRule = generated.getJSONObject("routing").getJSONArray("rules").objectValues()
                .single { it.optInt("port") == 53 }
            assertEquals("routing destinations with IPv6=$ipv6Enabled", expected, dnsRule.getJSONArray("ip").stringValues())
            assertEquals("primary-egress", dnsRule.getString("outboundTag"))
        }
    }

    @Test
    fun runtimeSocksListenerOverridesProfileAndGlobalListenersUsingTrimmedCredentials() {
        settings.socksAddress = "127.0.0.2"
        settings.socksPort = "22123"
        settings.socksUsername = "  local user  "
        settings.socksPassword = "  local secret  "
        val global = GlobalConfig(
            inbounds = """[{"protocol":"http","listen":"0.0.0.0","port":18080,"tag":"global-listener"}]""",
            inboundsMode = GlobalConfig.Mode.Replace,
        )

        val inbounds = generate(global).getJSONArray("inbounds")

        assertEquals(1, inbounds.length())
        val socks = inbounds.getJSONObject(0)
        assertEquals("socks", socks.getString("protocol"))
        assertEquals("socks", socks.getString("tag"))
        assertEquals("127.0.0.2", socks.getString("listen"))
        assertEquals(22123, socks.getInt("port"))
        val socksSettings = socks.getJSONObject("settings")
        assertTrue(socksSettings.getBoolean("udp"))
        assertEquals("password", socksSettings.getString("auth"))
        assertJsonEquals(
            JSONArray("""[{"user":"local user","pass":"local secret"}]"""),
            socksSettings.getJSONArray("accounts"),
        )
        val sniffing = socks.getJSONObject("sniffing")
        assertTrue(sniffing.getBoolean("enabled"))
        assertEquals(listOf("http", "tls", "quic"), sniffing.getJSONArray("destOverride").stringValues())
    }

    @Test
    fun incompleteSocksCredentialsProduceNoauthWithoutLeakingPartialAccounts() {
        for ((username, password) in listOf("" to "secret", "user" to "   ", "" to "")) {
            settings.socksUsername = username
            settings.socksPassword = password

            val socks = generate().getJSONArray("inbounds").getJSONObject(0)
            val socksSettings = socks.getJSONObject("settings")

            assertEquals("noauth", socksSettings.getString("auth"))
            assertFalse(socksSettings.has("accounts"))
            assertEquals(19080, socks.getInt("port"))
        }
    }

    @Test
    fun routingPreservesUserPriorityAndCustomTagsWhileResolvingManagedAliases() {
        val profile = baseProfile()
        profile.getJSONArray("outbounds").put(
            JSONObject("""{"protocol":"socks","tag":"backup-egress","settings":{"servers":[{"address":"127.0.0.1","port":19083}]}}"""),
        )
        profile.getJSONObject("routing").put(
            "rules",
            JSONArray(
                """[
                    {"ruleTag":"explicit-backup","domain":["full:backup.internal"],"outboundTag":"backup-egress","future":17},
                    {"ruleTag":"explicit-direct","domain":["full:lan.internal"],"outboundTag":"DIRECT"},
                    {"ruleTag":"explicit-block","domain":["full:blocked.internal"],"outboundTag":"block"},
                    {"ruleTag":"explicit-proxy","domain":["full:proxy.internal"],"outboundTag":"proxy"}
                ]""",
            ),
        )

        val generated = generate(profile = profile)

        val outbounds = generated.getJSONArray("outbounds").objectValues()
        assertEquals(listOf("primary-egress", "lan-egress", "reject-egress", "backup-egress"), outbounds.map { it.getString("tag") })
        val routing = generated.getJSONObject("routing")
        assertEquals("AsIs", routing.getString("domainStrategy"))
        val rules = routing.getJSONArray("rules").objectValues()
        assertEquals(6, rules.size)
        assertEquals(
            listOf("explicit-backup", "explicit-direct", "explicit-block", "explicit-proxy"),
            rules.take(4).map { it.getString("ruleTag") },
        )
        assertEquals(
            listOf("backup-egress", "lan-egress", "reject-egress", "primary-egress"),
            rules.take(4).map { it.getString("outboundTag") },
        )
        assertEquals(17, rules.first().getInt("future"))
        assertEquals(listOf("full:backup.internal"), rules.first().getJSONArray("domain").stringValues())
        assertEquals(53, rules[4].getInt("port"))
        assertEquals("primary-egress", rules[4].getString("outboundTag"))
        assertEquals("lan-egress", rules[5].getString("outboundTag"))
    }

    @Test
    fun transparentRuntimeUsesTproxyPortAndExistingDnsOutboundWithoutSocksListener() {
        settings.transparentProxy = true
        settings.tproxyAddress = "127.0.0.3"
        settings.tproxyPort = 23123
        val profile = baseProfile()
        profile.getJSONArray("outbounds").put(JSONObject("""{"protocol":"dns","tag":"custom-resolver"}"""))

        val generated = generate(profile = profile)

        val inbounds = generated.getJSONArray("inbounds")
        assertEquals(1, inbounds.length())
        val tproxy = inbounds.getJSONObject(0)
        assertEquals("dokodemo-door", tproxy.getString("protocol"))
        assertEquals("all-in", tproxy.getString("tag"))
        assertEquals("127.0.0.3", tproxy.getString("listen"))
        assertEquals(23123, tproxy.getInt("port"))
        assertEquals("tcp,udp", tproxy.getJSONObject("settings").getString("network"))
        assertTrue(tproxy.getJSONObject("settings").getBoolean("followRedirect"))
        assertEquals("tproxy", tproxy.getJSONObject("streamSettings").getJSONObject("sockopt").getString("tproxy"))
        val dnsOutbounds = generated.getJSONArray("outbounds").objectValues().filter { it.getString("protocol") == "dns" }
        assertEquals(listOf("custom-resolver"), dnsOutbounds.map { it.getString("tag") })
        val dnsRule = generated.getJSONObject("routing").getJSONArray("rules").objectValues()
            .single { it.optInt("port") == 53 }
        assertEquals("udp", dnsRule.getString("network"))
        assertEquals(listOf("all-in"), dnsRule.getJSONArray("inboundTag").stringValues())
        assertEquals("custom-resolver", dnsRule.getString("outboundTag"))
        assertFalse(dnsRule.has("ip"))
    }

    @Test
    fun hotspotOrTetheringMakesTransparentListenerAvailableBeyondLoopback() {
        settings.transparentProxy = true
        settings.tproxyAddress = "127.0.0.1"
        settings.tproxyPort = 24123
        for ((hotspot, tethering) in listOf(true to false, false to true)) {
            settings.tproxyHotspot = hotspot
            settings.tproxyTethering = tethering

            val inbounds = generate().getJSONArray("inbounds")

            assertEquals(1, inbounds.length())
            val inbound = inbounds.getJSONObject(0)
            assertFalse("shared listener must not remain bound to loopback", inbound.has("listen"))
            assertEquals(24123, inbound.getInt("port"))
            assertEquals("dokodemo-door", inbound.getString("protocol"))
        }
    }

    @Test
    fun generatedPrivateRoutingUsesLiteralIpv4AndIpv6CidrsWithoutGeoAssets() {
        val profile = JSONObject().put("outbounds", baseProfile().getJSONArray("outbounds"))

        val generated = generate(profile = profile)

        val privateRule = generated.getJSONObject("routing").getJSONArray("rules").objectValues()
            .single { it.has("ip") && !it.has("port") }
        val ranges = privateRule.getJSONArray("ip").stringValues()
        assertTrue(ranges.containsAll(listOf("10.0.0.0/8", "192.168.0.0/16", "::1/128", "fc00::/7", "fe80::/10")))
        assertEquals("lan-egress", privateRule.getString("outboundTag"))
        assertFalse(generated.toString().contains("geoip:", ignoreCase = true))
        assertFalse(generated.toString().contains("geosite:", ignoreCase = true))
    }

    @Test
    fun localAntifilterRulesReplaceStaleManagedRulesAfterUserRulesAndRespectDisabledSetting() {
        settings.antifilterEnabled = true
        settings.antifilterFile().writeText("203.0.113.0/24\n198.51.100.7\n")
        val profile = baseProfile()
        profile.getJSONObject("routing").put(
            "rules",
            JSONArray(
                """[
                    {"ruleTag":"user-exception","ip":["203.0.113.4"],"outboundTag":"block"},
                    {"ruleTag":"yaxc-antifilter-proxy","ip":["192.0.2.0/24"],"outboundTag":"proxy"},
                    {"ruleTag":"yaxc-antifilter-direct","ip":["192.0.2.1"],"outboundTag":"direct"}
                ]""",
            ),
        )

        val rules = generate(profile = profile).getJSONObject("routing").getJSONArray("rules").objectValues()

        assertEquals(4, rules.size)
        assertEquals("user-exception", rules[0].getString("ruleTag"))
        assertEquals("reject-egress", rules[0].getString("outboundTag"))
        assertEquals("yaxc-antifilter-proxy", rules[1].getString("ruleTag"))
        assertEquals(listOf("203.0.113.0/24", "198.51.100.7"), rules[1].getJSONArray("ip").stringValues())
        assertEquals("primary-egress", rules[1].getString("outboundTag"))
        assertEquals(53, rules[2].getInt("port"))
        assertEquals("lan-egress", rules[3].getString("outboundTag"))
        assertFalse(rules.any { it.optString("ruleTag") == "yaxc-antifilter-direct" })

        settings.antifilterEnabled = false
        val disabledRules = generate(profile = profile).getJSONObject("routing").getJSONArray("rules").objectValues()
        assertEquals(3, disabledRules.size)
        assertEquals("user-exception", disabledRules.first().getString("ruleTag"))
        assertFalse(disabledRules.any { it.optString("ruleTag").startsWith("yaxc-antifilter-") })
    }

    @Test
    fun routingMergeSkipsOnlyExactStructuralDuplicatesKeepingProfilePriority() {
        val profile = baseProfile().put("routing", JSONObject("""{
            "domainStrategy":"AsIs","rules":[
                {"domain":["full:first.test"],"outboundTag":"lan-egress","attrs":{"b":2,"a":1}},
                {"domain":["full:shared.test"],"outboundTag":"lan-egress"}
            ]
        }"""))
        val global = GlobalConfig(routingMode = GlobalConfig.Mode.Merge, routing = """{
            "domainStrategy":"IPOnDemand","rules":[
                {"attrs":{"a":1,"b":2},"outboundTag":"lan-egress","domain":["full:first.test"]},
                {"domain":["full:shared.test"],"outboundTag":"reject-egress"}
            ]
        }""")
        val routing = generate(global, profile).getJSONObject("routing")
        assertEquals("IPOnDemand", routing.getString("domainStrategy"))
        val rules = routing.getJSONArray("rules").objectValues()
        assertEquals(5, rules.size)
        assertEquals(listOf("lan-egress", "lan-egress", "reject-egress"),
            rules.take(3).map { it.getString("outboundTag") })
        assertEquals("full:first.test", rules.first().getJSONArray("domain").getString(0))
    }

    @Test
    fun coreOnlyReplacesEntireProfileRoutingWhileAllModesKeepManagedRules() {
        val profile = baseProfile().put("routing", JSONObject("""{
            "domainStrategy":"AsIs","domainMatcher":"linear",
            "balancers":[{"tag":"profile-balancer","selector":["primary"]}],
            "rules":[{"port":"80","outboundTag":"lan-egress"}]
        }"""))
        val core = """{"domainStrategy":"IPOnDemand","rules":[{"port":"443","outboundTag":"block"}]}"""
        GlobalConfig.Mode.entries.forEach { mode ->
            val routing = generate(GlobalConfig(routing = core, routingMode = mode), profile).getJSONObject("routing")
            val rules = routing.getJSONArray("rules").objectValues()
            assertTrue(rules.any { it.optInt("port") == 53 })
            assertTrue(rules.any { it.optJSONArray("ip")?.stringValues()?.contains("10.0.0.0/8") == true })
            when (mode) {
                GlobalConfig.Mode.Disable -> {
                    assertTrue(routing.has("balancers"))
                    assertEquals("AsIs", routing.getString("domainStrategy"))
                    assertEquals("80", rules.first().getString("port"))
                }
                GlobalConfig.Mode.Replace -> {
                    assertFalse(routing.has("balancers"))
                    assertFalse(routing.has("domainMatcher"))
                    assertEquals("IPOnDemand", routing.getString("domainStrategy"))
                    assertEquals("443", rules.first().getString("port"))
                }
                GlobalConfig.Mode.Merge -> {
                    assertTrue(routing.has("balancers"))
                    assertEquals("IPOnDemand", routing.getString("domainStrategy"))
                    assertEquals(listOf("80", "443"), rules.take(2).map { it.getString("port") })
                }
            }
        }
    }

    @Test
    fun mergeDeduplicatesOnlyAfterCoreRoleResolvesToLiteralProfileDestination() {
        val profile = baseProfile()
            .put("outbounds", JSONArray("""[{"tag":"exit","protocol":"freedom"},{"tag":"upstream","protocol":"vless"}]"""))
            .put("routing", JSONObject("""{"rules":[{"domain":["full:same.test"],"outboundTag":"exit","meta":{"b":2,"a":1}}]}"""))
        val config = GlobalConfig(routingMode = GlobalConfig.Mode.Merge, routing = """{"rules":[
            {"meta":{"a":1,"b":2},"outboundTag":"direct","domain":["full:same.test"]},
            {"domain":["full:same.test"],"outboundTag":"block","meta":{"a":1,"b":2}}
        ]}""")
        val rules = generate(config, profile).getJSONObject("routing").getJSONArray("rules").objectValues()
        assertEquals(4, rules.size)
        assertEquals("exit", rules[0].getString("outboundTag"))
        assertEquals("block", rules[1].getString("outboundTag"))
        assertJsonEquals(JSONObject(profile.getJSONObject("routing").getJSONArray("rules").getJSONObject(0).toString()), rules[0])
    }

    @Test
    fun coreAndManagedRolesIgnoreWrongProtocolReservedTagsWithoutChangingProfileTags() {
        val profile = baseProfile().put("outbounds", JSONArray("""[
            {"tag":"block","protocol":"vless"},{"tag":"direct","protocol":"trojan"},
            {"tag":"proxy","protocol":"blackhole"},{"tag":"exit","protocol":"freedom"},
            {"tag":"reject","protocol":"blackhole"}
        ]""")).put("routing", JSONObject("""{"rules":[
            {"domain":["full:literal.test"],"outboundTag":"block"}
        ]}"""))
        val config = GlobalConfig(routingMode = GlobalConfig.Mode.Merge, routing = """{"rules":[
            {"domain":["full:blocked.test"],"outboundTag":"block"},
            {"domain":["full:direct.test"],"outboundTag":"direct"},
            {"domain":["full:proxy.test"],"outboundTag":"proxy"}
        ]}""")
        val generated = generate(config, profile)
        val protocols = generated.getJSONArray("outbounds").objectValues().associate { it.getString("tag") to it.getString("protocol") }
        val rules = generated.getJSONObject("routing").getJSONArray("rules").objectValues()
        assertEquals("block", rules[0].getString("outboundTag"))
        assertEquals("vless", protocols[rules[0].getString("outboundTag")])
        assertEquals("blackhole", protocols[rules[1].getString("outboundTag")])
        assertEquals("freedom", protocols[rules[2].getString("outboundTag")])
        assertEquals("vless", protocols[rules[3].getString("outboundTag")])
        assertEquals("vless", protocols[rules[4].getString("outboundTag")])
        assertEquals("freedom", protocols[rules[5].getString("outboundTag")])
        assertEquals("trojan", protocols["direct"])
        assertEquals("blackhole", protocols["proxy"])
    }

    @Test
    fun managedRoleCreationAvoidsOccupiedTagsAndRedirectedFreedomIsNeverDirect() {
        settings.transparentProxy = true
        val profile = baseProfile().put("outbounds", JSONArray("""[
            {"tag":"proxy","protocol":"freedom","settings":{"redirect":"127.0.0.1:8080"}},
            {"tag":"direct","protocol":"vless"},{"tag":"block","protocol":"trojan"},
            {"tag":"dns-out","protocol":"vless"},{"tag":"yaxc-direct","protocol":"vless"}
        ]""")).put("routing", JSONObject("""{"rules":[]}"""))
        val config = GlobalConfig(routingMode = GlobalConfig.Mode.Merge, routing = """{"rules":[
            {"domain":["full:direct.test"],"outboundTag":"direct"},{"domain":["full:block.test"],"outboundTag":"block"}
        ]}""")
        val generated = generate(config, profile)
        val outbounds = generated.getJSONArray("outbounds").objectValues().associateBy { it.getString("tag") }
        val rules = generated.getJSONObject("routing").getJSONArray("rules").objectValues()
        assertEquals("yaxc-direct-2", rules[0].getString("outboundTag"))
        assertEquals("yaxc-block", rules[1].getString("outboundTag"))
        assertEquals("yaxc-dns-out", rules[2].getString("outboundTag"))
        assertEquals("freedom", outbounds.getValue("yaxc-direct-2").getString("protocol"))
        assertFalse(outbounds.getValue("yaxc-direct-2").has("settings"))
        assertEquals("blackhole", outbounds.getValue("yaxc-block").getString("protocol"))
        assertEquals("dns", outbounds.getValue("yaxc-dns-out").getString("protocol"))
        assertEquals("vless", outbounds.getValue("direct").getString("protocol"))
        assertEquals("trojan", outbounds.getValue("block").getString("protocol"))
        assertEquals("127.0.0.1:8080", outbounds.getValue("proxy").getJSONObject("settings").getString("redirect"))
    }

    @Test
    fun directOnlyPrimaryIsAValidProxyRoleAndManagedDnsDestination() {
        listOf("proxy", "direct-exit").forEach { primaryTag ->
            val profile = baseProfile().put("outbounds", JSONArray().put(JSONObject()
                .put("tag", primaryTag).put("protocol", "freedom")))
                .put("routing", JSONObject().put("rules", JSONArray()))
            val config = GlobalConfig(routingMode = GlobalConfig.Mode.Replace,
                routing = """{"rules":[{"domain":["full:default.test"],"outboundTag":"proxy"}]}""")
            val generated = generate(config, profile)
            val rules = generated.getJSONObject("routing").getJSONArray("rules").objectValues()
            assertEquals(primaryTag, rules[0].getString("outboundTag"))
            assertEquals(primaryTag, rules[1].getString("outboundTag"))
            assertEquals(53, rules[1].getInt("port"))
            assertEquals("freedom", generated.getJSONArray("outbounds").objectValues()
                .first { it.getString("tag") == primaryTag }.getString("protocol"))
        }
    }

    @Test
    fun emptyProfilesUseManagedDirectForCoreProxyAndDnsWithoutInventingAProxyOutbound() {
        listOf("""{"revision":1}""", """{"revision":1,"outbounds":[]}""").forEach { raw ->
            listOf(false, true).forEach { transparent ->
                settings.transparentProxy = transparent
                GlobalConfig.Mode.entries.forEach { mode ->
                    val config = GlobalConfig(routingMode = mode, routing = """{"rules":[
                        {"ruleTag":"core-proxy","domain":["full:default.test"],"outboundTag":"proxy"}
                    ]}""")
                    val generated = generate(config, JSONObject(raw))
                    val outbounds = generated.getJSONArray("outbounds").objectValues()
                    assertEquals(if (transparent) listOf("freedom", "blackhole", "dns") else listOf("freedom", "blackhole"),
                        outbounds.map { it.getString("protocol") })
                    assertEquals("direct", outbounds.first().getString("tag"))
                    assertFalse(outbounds.any { it.getString("tag") == "proxy" })
                    assertEquals(1, generated.getInt("revision"))
                    val rules = generated.getJSONObject("routing").getJSONArray("rules").objectValues()
                    assertEquals(if (transparent) "dns-out" else "direct",
                        rules.single { it.optInt("port") == 53 }.getString("outboundTag"))
                    if (mode != GlobalConfig.Mode.Disable) {
                        assertEquals("direct", rules.single { it.optString("ruleTag") == "core-proxy" }.getString("outboundTag"))
                    }
                    assertTrue(rules.all { rule -> outbounds.any { it.getString("tag") == rule.getString("outboundTag") } })
                }
            }
        }
    }

    @Test
    fun standaloneLiveBaseKeepsCoreAndDnsProxyRolesUntilSelectedGroupCompilation() {
        listOf("[]", """[{"tag":"proxy","protocol":"freedom"}]""",
            """[{"tag":"proxy","protocol":"blackhole"}]""").forEach { sharedOutbounds ->
            val global = GlobalConfig(
                outbounds = sharedOutbounds, outboundsMode = GlobalConfig.Mode.Merge,
                routingMode = GlobalConfig.Mode.Merge, routing = """{"rules":[
                    {"ruleTag":"core-proxy","domain":["full:core.test"],"outboundTag":"proxy"},
                    {"ruleTag":"core-direct","domain":["full:direct.test"],"outboundTag":"direct"},
                    {"ruleTag":"core-block","domain":["full:block.test"],"outboundTag":"block"}
                ]}""",
            )
            val base = ConfigHelper(settings, global, "{}", target = ConfigHelper.Target.LiveRoutingBase).toString()
            val baseRules = JSONObject(base).getJSONObject("routing").getJSONArray("rules").objectValues()
            assertEquals("proxy", baseRules.single { it.optString("ruleTag") == "core-proxy" }.getString("outboundTag"))
            assertEquals("proxy", baseRules.single { it.optInt("port") == 53 }.getString("outboundTag"))
            val profiles = (1L..2L).associateWith { id ->
                io.github.derundevu.yaxc.database.Profile(id = id, name = "Member $id",
                    config = """{"outbounds":[{"tag":"member-exit","protocol":"freedom"}]}""")
            }
            val group = ProxyGroup(id = "default", profileIds = listOf(1, 2), selectedProfileId = 2)
            val plan = LiveRoutingPlan(groups = listOf(group), rules = listOf(
                AppDomainRule(id = "live-first", domains = listOf("full:core.test"), target = "block"),
            ), defaultGroupId = group.id)
            val compiled = JSONObject(LiveRoutingHelper.compile(base, plan, profiles, emptyMap(), "", ""))
            val rules = compiled.getJSONObject("routing").getJSONArray("rules").objectValues()
            val selectedTarget = rules.last().getString("outboundTag")
            assertTrue(selectedTarget.contains("profile.2.outbound.0."))
            assertEquals(selectedTarget, rules.single { it.optString("ruleTag") == "core-proxy" }.getString("outboundTag"))
            assertEquals(selectedTarget, rules.single { it.optInt("port") == 53 }.getString("outboundTag"))
            val protocols = compiled.getJSONArray("outbounds").objectValues().associate {
                it.getString("tag") to it.getString("protocol")
            }
            assertEquals("freedom", protocols[rules.single { it.optString("ruleTag") == "core-direct" }.getString("outboundTag")])
            assertFalse(rules.single { it.optString("ruleTag") == "core-direct" }.getString("outboundTag") == selectedTarget)
            assertEquals("blackhole", protocols[rules.single { it.optString("ruleTag") == "core-block" }.getString("outboundTag")])
            assertEquals("full:core.test", rules.first().getJSONArray("domain").getString(0))
            assertTrue(rules.first().getString("ruleTag").contains("rule.0."))
        }
    }

    private fun generate(config: GlobalConfig = GlobalConfig(), profile: JSONObject = baseProfile()): JSONObject {
        return JSONObject(ConfigHelper(settings, config, profile.toString()).toString())
    }

    private fun baseProfile(): JSONObject = JSONObject(
        """
            {
              "log": {"loglevel":"error","access":"profile-access.log","futureLog":{"enabled":true}},
              "dns": {"hosts":{"profile.internal":"192.0.2.10"},"futureDns":{"cache":true}},
              "inbounds": [{"protocol":"socks","listen":"127.0.0.1","port":1080,"tag":"profile-listener"}],
              "outbounds": [
                {
                  "protocol":"vless", "tag":"primary-egress",
                  "settings":{"vnext":[{"address":"127.0.0.1","port":443,"users":[
                    {"id":"00000000-0000-4000-8000-000000000001","encryption":"none"}
                  ]}]},
                  "streamSettings":{"network":"ws","security":"none","wsSettings":{"path":"/profile","headers":{"X-Profile":"kept"}}}
                },
                {"protocol":"freedom","tag":"lan-egress"},
                {"protocol":"blackhole","tag":"reject-egress"}
              ],
              "routing":{"domainStrategy":"AsIs","rules":[
                {"ruleTag":"profile-route","domain":["full:profile.internal"],"outboundTag":"primary-egress","futureRule":{"enabled":true}}
              ]},
              "futureRoot":{"preserved":true,"values":["second","first"]}
            }
        """.trimIndent(),
    )
}
