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
