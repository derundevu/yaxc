package io.github.derundevu.yaxc.presentation.profile

import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.testing.MainDispatcherRule
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import io.github.derundevu.yaxc.viewmodel.ProfileEditorViewModel
import io.github.derundevu.yaxc.viewmodel.ProfileSaveResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
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

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
class ProfileVisualConfigTest : RoomTestSupport() {
    @get:Rule val mainDispatcher = MainDispatcherRule()

    @Test
    fun parseReturnsNullOnInvalidJsonAndDefaultsOnBlank() {
        assertNull(ProfileVisualConfigHelper.parse("{broken"))
        assertNull(ProfileVisualConfigHelper.parse("[]"))

        val blank = requireNotNull(ProfileVisualConfigHelper.parse(""))
        assertEquals(1, blank.outbounds.size)
        assertEquals("proxy", blank.activeOutbound.tag)
        assertEquals("vless", blank.activeOutbound.protocol)
        assertFalse(blank.hadExplicitOutbounds)
    }

    @Test
    fun roundtripVlessRealityXhttpPreservesUnknownExtrasAndUntouchedOutbounds() {
        val raw = """
            {
              "log": {"loglevel": "warning"},
              "outbounds": [
                {
                  "tag": "proxy",
                  "protocol": "vless",
                  "settings": {
                    "vnext": [
                      {
                        "address": "example.com",
                        "port": 443,
                        "users": [
                          {
                            "id": "11111111-1111-1111-1111-111111111111",
                            "encryption": "none",
                            "flow": "xtls-rprx-vision"
                          }
                        ]
                      }
                    ]
                  },
                  "streamSettings": {
                    "network": "xhttp",
                    "security": "reality",
                    "realitySettings": {
                      "serverName": "cdn.example.org",
                      "fingerprint": "chrome",
                      "publicKey": "pub-key",
                      "shortId": "abcd",
                      "spiderX": "/custom"
                    },
                    "xhttpSettings": {
                      "path": "/xhttp",
                      "mode": "auto",
                      "extra": {"xmux": {"maxConcurrency": 16}}
                    }
                  }
                },
                {
                  "tag": "direct",
                  "protocol": "freedom",
                  "settings": {"domainStrategy": "UseIP"}
                }
              ],
              "routing": {"domainStrategy": "AsIs"}
            }
        """.trimIndent()

        val parsed = requireNotNull(ProfileVisualConfigHelper.parse(raw))
        assertEquals(2, parsed.outbounds.size)
        assertEquals(0, parsed.selectedOutboundIndex)
        val proxy = parsed.activeOutbound
        assertEquals("example.com", proxy.address)
        assertEquals("443", proxy.port)
        assertEquals("11111111-1111-1111-1111-111111111111", proxy.userId)
        assertEquals("xtls-rprx-vision", proxy.vlessFlow)
        assertEquals("xhttp", proxy.network)
        assertEquals("reality", proxy.security)
        assertEquals("cdn.example.org", proxy.sni)
        assertEquals("chrome", proxy.fingerprint)
        assertEquals("pub-key", proxy.realityPublicKey)
        assertEquals("abcd", proxy.realityShortId)
        assertEquals("/custom", proxy.realitySpiderX)
        assertEquals("/xhttp", proxy.transportPath)
        assertEquals("auto", proxy.xhttpMode)

        val edited = parsed.updateActiveOutbound {
            it.copy(
                address = "new.example.net",
                sni = "updated.example.org",
                fingerprint = "firefox",
                muxEnabled = true,
                muxConcurrency = "8",
            )
        }
        val rebuilt = JSONObject(ProfileVisualConfigHelper.buildJson(edited))
        assertEquals("AsIs", rebuilt.getJSONObject("routing").getString("domainStrategy"))
        assertEquals("warning", rebuilt.getJSONObject("log").getString("loglevel"))
        val outbounds = rebuilt.getJSONArray("outbounds")
        assertEquals(2, outbounds.length())
        assertEquals("freedom", outbounds.getJSONObject(1).getString("protocol"))
        assertEquals("UseIP", outbounds.getJSONObject(1).getJSONObject("settings").getString("domainStrategy"))

        val updatedProxy = outbounds.getJSONObject(0)
        assertEquals(
            "new.example.net",
            updatedProxy.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getString("address"),
        )
        val stream = updatedProxy.getJSONObject("streamSettings")
        assertEquals("updated.example.org", stream.getJSONObject("realitySettings").getString("serverName"))
        assertEquals("firefox", stream.getJSONObject("realitySettings").getString("fingerprint"))
        assertEquals(
            16,
            stream.getJSONObject("xhttpSettings").getJSONObject("extra").getJSONObject("xmux").getInt("maxConcurrency"),
        )
        assertTrue(updatedProxy.getJSONObject("mux").getBoolean("enabled"))
        assertEquals(8, updatedProxy.getJSONObject("mux").getInt("concurrency"))
    }

    @Test
    fun buildJsonSupportsNaiveAndShadowsocksAndTrojanFromScratch() {
        val naive = ProfileOutboundDraft(
            tag = "proxy",
            protocol = "naive",
            dirty = true,
            address = "naive.example.org",
            port = "443",
            username = "u",
            password = "p",
            naiveQuic = true,
            naiveUdpOverTcp = true,
            naiveCongestionControl = "bbr2",
            network = "raw",
            security = "tls",
            sni = "naive.example.org",
        )
        val naiveObj = JSONObject(
            ProfileVisualConfigHelper.buildJson(ProfileVisualConfig(outbounds = listOf(naive))),
        ).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("naive", naiveObj.getString("protocol"))
        assertEquals("bbr2", naiveObj.getJSONObject("settings").getString("quicCongestionControl"))
        assertTrue(naiveObj.getJSONObject("settings").getBoolean("quic"))

        val ss = ProfileOutboundDraft(
            tag = "proxy",
            protocol = "shadowsocks",
            dirty = true,
            address = "ss.example.org",
            port = "8388",
            ssMethod = "2022-blake3-aes-128-gcm",
            password = "secret",
            tcpFastOpen = true,
        )
        val ssObj = JSONObject(
            ProfileVisualConfigHelper.buildJson(ProfileVisualConfig(outbounds = listOf(ss))),
        ).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("shadowsocks", ssObj.getString("protocol"))
        val ssServer = ssObj.getJSONObject("settings").getJSONArray("servers").getJSONObject(0)
        assertEquals("2022-blake3-aes-128-gcm", ssServer.getString("method"))
        assertEquals("secret", ssServer.getString("password"))
        assertTrue(ssObj.getJSONObject("streamSettings").getJSONObject("sockopt").getBoolean("tcpFastOpen"))
    }

    @Test
    fun viewModelSyncsVisualToJsonRejectsInvalidJsonSwitchAndSavesVisualEdits() = runTest(mainDispatcher.dispatcher) {
        val editor = ProfileEditorViewModel(app) { _, _ -> "" }
        editor.initialize(0L, "", "", null, null)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (editor.isLoading) delay(1) }
        }
        assertEquals(ProfileEditorMode.Visual, editor.editorMode)

        editor.name = "Visual Profile"
        editor.updateVisualConfig { config ->
            config.updateActiveOutbound {
                it.copy(
                    protocol = "trojan",
                    address = "trojan.example.org",
                    port = "443",
                    password = "pw",
                    security = "tls",
                    sni = "trojan.example.org",
                    fingerprint = "chrome",
                )
            }
        }

        assertTrue(editor.switchMode(ProfileEditorMode.Json))
        assertEquals(ProfileEditorMode.Json, editor.editorMode)
        val json = JSONObject(editor.document.text)
        val outbound = json.getJSONArray("outbounds").getJSONObject(0)
        assertEquals("trojan", outbound.getString("protocol"))
        assertEquals("trojan.example.org", outbound.getJSONObject("settings").getJSONArray("servers").getJSONObject(0).getString("address"))
        assertEquals("chrome", outbound.getJSONObject("streamSettings").getJSONObject("tlsSettings").getString("fingerprint"))

        editor.document.text = "{not-valid-json"
        assertFalse(editor.switchMode(ProfileEditorMode.Visual))
        assertEquals(ProfileEditorMode.Json, editor.editorMode)

        editor.document.text = """{"outbounds":[{"tag":"proxy","protocol":"vless","settings":{"vnext":[{"address":"v.example.com","port":8443,"users":[{"id":"abc"}]}]}}]}"""
        assertTrue(editor.switchMode(ProfileEditorMode.Visual))
        assertEquals(ProfileEditorMode.Visual, editor.editorMode)
        assertEquals("v.example.com", editor.visualConfig.activeOutbound.address)
        assertEquals("8443", editor.visualConfig.activeOutbound.port)

        editor.updateVisualConfig { config ->
            config.updateActiveOutbound { it.copy(port = "443") }
        }
        assertEquals(ProfileSaveResult.Saved, editor.save(check = true))
        val saved = app.profileRepository.all.first().single()
        val stored = JSONObject(saved.config)
        assertEquals(
            443,
            stored.getJSONArray("outbounds").getJSONObject(0).getJSONObject("settings").getJSONArray("vnext").getJSONObject(0).getInt("port"),
        )
        assertNotNull(ProfileVisualConfigHelper.parse(editor.document.text))
    }

    @Test
    fun malformedStoredProfileOpensDirectlyInJsonMode() = runTest(mainDispatcher.dispatcher) {
        val id = app.profileRepository.create(Profile(name = "Broken", config = "{broken"))
        val editor = ProfileEditorViewModel(app) { _, _ -> "" }
        editor.initialize(id, "", "", null, null)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) { while (editor.isLoading) delay(1) }
        }

        assertEquals(ProfileEditorMode.Json, editor.editorMode)
        assertEquals("{broken", editor.document.text)
    }
}
