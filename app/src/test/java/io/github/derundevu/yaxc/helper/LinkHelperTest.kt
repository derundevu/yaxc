package io.github.derundevu.yaxc.helper

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class LinkHelperTest : HelperSettingsTest() {
    // Only VLESS and raw JSON bypass XrayCore.json. Other schemes/base64 imports require Android Go.
    @Test
    fun realityXhttpImportKeepsCredentialsAndDecodedTransportOptions() {
        val extra = """{"xmux":{"maxConcurrency":16},"noSSEHeader":true,"future":{"token":"a+b"}}"""
        val encodedExtra = URLEncoder.encode(extra, StandardCharsets.UTF_8.name())
        val helper = LinkHelper(
            settings,
            "vless://00000000-0000-4000-8000-000000000001@edge.example:8443" +
                "?encryption=none&flow=xtls-rprx-vision&security=reality&type=XHTTP" +
                "&sni=front.example&fp=chrome&pbk=key%2Bwith%2Fslash&sid=0123abcd" +
                "&pqv=verify-key&spx=%2Fprobe%3Fx%3D1%26y%3D2" +
                "&host=cdn.example&path=%2Fstream%3Fed%3D2048&mode=packet-up&extra=$encodedExtra" +
                "#Primary%20%2B%20%D0%A2%D0%B5%D1%81%D1%82",
        )

        assertTrue(helper.isValid())
        assertEquals("Primary + Тест", helper.remark())
        val outbound = JSONObject(helper.json()).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vless", outbound.getString("protocol"))
        assertEquals("proxy", outbound.getString("tag"))
        val server = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("edge.example", server.getString("address"))
        assertEquals(8443, server.getInt("port"))
        val user = server.getJSONArray("users").getJSONObject(0)
        assertEquals("00000000-0000-4000-8000-000000000001", user.getString("id"))
        assertEquals("none", user.getString("encryption"))
        assertEquals("xtls-rprx-vision", user.getString("flow"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("xhttp", stream.getString("network"))
        assertEquals("reality", stream.getString("security"))
        assertJsonEquals(
            JSONObject(
                """{"serverName":"front.example","fingerprint":"chrome","publicKey":"key+with/slash",
                    "shortId":"0123abcd","mldsa65Verify":"verify-key","spiderX":"/probe?x=1&y=2"}""",
            ),
            stream.getJSONObject("realitySettings"),
        )
        val xhttp = stream.getJSONObject("xhttpSettings")
        assertEquals("cdn.example", xhttp.getString("host"))
        assertEquals("/stream?ed=2048", xhttp.getString("path"))
        assertEquals("packet-up", xhttp.getString("mode"))
        assertJsonEquals(JSONObject(extra), xhttp.getJSONObject("extra"))
    }

    @Test
    fun tlsWebsocketImportKeepsTlsVerificationAndEncodedPath() {
        val helper = LinkHelper(
            settings,
            "vless://00000000-0000-4000-8000-000000000002@ws.example:443" +
                "?security=tls&type=ws&sni=tls.example&fp=firefox&alpn=h2%2C%20http%2F1.1%2C" +
                "&ech=ech%2Bdata&pcs=sha256-pin&vcn=verify.example" +
                "&host=front.example&path=%2Fsocket%3Ftoken%3Da%2Bb#Websocket",
        )

        assertTrue(helper.isValid())
        val outbound = JSONObject(helper.json()).getJSONArray("outbounds").getJSONObject(0)
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("ws", stream.getString("network"))
        assertEquals("tls", stream.getString("security"))
        val tls = stream.getJSONObject("tlsSettings")
        assertEquals("tls.example", tls.getString("serverName"))
        assertEquals("firefox", tls.getString("fingerprint"))
        assertEquals(listOf("h2", "http/1.1"), tls.getJSONArray("alpn").stringValues())
        assertEquals("ech+data", tls.getString("echConfigList"))
        assertEquals("sha256-pin", tls.getString("pinnedPeerCertSha256"))
        assertEquals("verify.example", tls.getString("verifyPeerCertByName"))
        assertEquals("front.example", stream.getJSONObject("wsSettings").getString("host"))
        assertEquals("/socket?token=a+b", stream.getJSONObject("wsSettings").getString("path"))
        assertFalse(stream.has("realitySettings"))
    }

    @Test
    fun gunTransportAliasGeneratesGrpcAuthorityServiceAndMultiMode() {
        val helper = LinkHelper(
            settings,
            "vless://00000000-0000-4000-8000-000000000003@grpc.example:9443" +
                "?security=tls&type=gun&authority=authority.example&serviceName=api%2Fproxy&mode=multi#Grpc",
        )

        assertTrue(helper.isValid())
        val stream = JSONObject(helper.json()).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
        assertEquals("grpc", stream.getString("network"))
        assertJsonEquals(
            JSONObject("""{"authority":"authority.example","serviceName":"api/proxy","multiMode":true}"""),
            stream.getJSONObject("grpcSettings"),
        )
    }

    @Test
    fun rawTransportAliasKeepsHttpHeaderHostAndPathLists() {
        val helper = LinkHelper(
            settings,
            "vless://00000000-0000-4000-8000-000000000004@tcp.example:8080" +
                "?type=raw&headerType=http&host=one.example%2C%20two.example&path=%2Ffirst%2C%20%2Fsecond#Tcp",
        )

        assertTrue(helper.isValid())
        val stream = JSONObject(helper.json()).getJSONArray("outbounds").getJSONObject(0)
            .getJSONObject("streamSettings")
        assertEquals("tcp", stream.getString("network"))
        val header = stream.getJSONObject("tcpSettings").getJSONObject("header")
        assertEquals("http", header.getString("type"))
        val request = header.getJSONObject("request")
        assertEquals(listOf("/first", "/second"), request.getJSONArray("path").stringValues())
        assertEquals(
            listOf("one.example", "two.example"),
            request.getJSONObject("headers").getJSONArray("Host").stringValues(),
        )
    }

    @Test
    fun fullRawConfigPreservesUnknownFieldsAndAllOutboundsWithoutRuntimeRewrite() {
        val raw = """
            {
              "remarks": "  Imported profile  ",
              "remark": "Lower-priority title",
              "log": {"loglevel": "error", "futureLog": {"enabled": true}},
              "dns": {"servers": ["203.0.113.53"], "hosts": {"internal.example": "192.0.2.10"}},
              "inbounds": [{"protocol": "http", "tag": "user-listener", "port": 18080, "future": [1, 2]}],
              "outbounds": [
                {"protocol": "vless", "tag": "user-egress", "settings": {"vnext": [
                  {"address": "edge.example", "port": 443, "users": [{"id": "00000000-0000-4000-8000-000000000005", "encryption": "none", "futureUser": "kept"}]}
                ]}, "futureTransport": {"counter": 9007199254740993, "flags": [true, false, {"token": "a+b"}]}},
                {"protocol": "freedom", "tag": "user-direct", "settings": {"domainStrategy": "UseIPv4"}}
              ],
              "routing": {"domainStrategy": "AsIs", "rules": [
                {"domain": ["full:internal.example"], "outboundTag": "user-direct", "futureRule": "kept"}
              ]},
              "futureRoot": {"ordered": ["second", "first"], "nested": {"enabled": true}}
            }
        """.trimIndent()
        val helper = LinkHelper(settings, "\n  $raw  \n")

        assertTrue(helper.isValid())
        assertEquals("Imported profile", helper.remark())
        val expected = JSONObject(raw).apply {
            remove("remarks")
            remove("remark")
        }
        assertJsonEquals(expected, JSONObject(helper.json()))
    }

    @Test
    fun base64UtilityDecodesUtf8AndReturnsMalformedInputUnchanged() {
        assertEquals("Привет", LinkHelper.tryDecodeBase64("0J/RgNC40LLQtdGC"))
        assertEquals("a", LinkHelper.tryDecodeBase64("a"))
    }
}
