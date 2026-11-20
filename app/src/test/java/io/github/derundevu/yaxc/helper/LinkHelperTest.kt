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
    // Native share-link fallback is exercised on Android; VLESS and full JSON import are host-safe.
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
    fun encodedFullConfigIsImportedBeforeNativeConversionAndKeepsAllRootSections() {
        val raw = JSONObject(
            """{"remarks":"  Кодированный 🌍  ","outbounds":[
                {"protocol":"freedom","tag":"first","future":{"value":9007199254740993}},
                {"protocol":"blackhole","tag":"second"}],
                "routing":{"rules":[{"domain":["full:localhost"],"outboundTag":"second"}]},
                "futureRoot":{"ordered":["one","two"],"enabled":true}}""",
        )
        val encoded = java.util.Base64.getMimeEncoder(24, "\r\n".toByteArray())
            .encodeToString(raw.toString().toByteArray(StandardCharsets.UTF_8))
        val helper = LinkHelper(settings, " \r\n$encoded\r\n ")

        assertTrue(helper.isValid())
        assertEquals("Кодированный 🌍", helper.remark())
        raw.remove("remarks")
        assertJsonEquals(raw, JSONObject(helper.json()))
        assertEquals(2, JSONObject(helper.json()).getJSONArray("outbounds").length())
    }

    @Test
    fun clipboardWhitespaceAndBomDoNotChangeVlessCredentialsOrUnicodeRemark() {
        val helper = LinkHelper(
            settings,
            " \uFEFF\r\n\tvless://00000000-0000-4000-8000-000000000006@127.0.0.1:9443" +
                "?security=tls&type=ws&path=%2Fsynthetic%3Fx%3Da%2Bb#%D0%A2%D0%B5%D1%81%D1%82 \t\r\n",
        )

        assertTrue(helper.isValid())
        assertEquals("Тест", helper.remark())
        val outbound = JSONObject(helper.json()).getJSONArray("outbounds").getJSONObject(0)
        val endpoint = outbound.getJSONObject("settings").getJSONArray("vnext").getJSONObject(0)
        assertEquals("127.0.0.1", endpoint.getString("address"))
        assertEquals(9443, endpoint.getInt("port"))
        assertEquals("00000000-0000-4000-8000-000000000006", endpoint.getJSONArray("users").getJSONObject(0).getString("id"))
        assertEquals("/synthetic?x=a+b", outbound.getJSONObject("streamSettings")
            .getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun naiveHttpsDecodesPasswordBeforeColonSplittingAndPreservesPlusIpv6AndSni() {
        val helper = LinkHelper(settings,
            "naive+https://test%2Buser:test%3Apass%2Bword@[2001:db8::1]" +
                "?padding=1&tfo=1&sni=proxy.example.org#Naive%20%2B+")
        assertTrue(helper.isValid())
        assertEquals("Naive ++", helper.remark())
        val outbound = JSONObject(helper.json()).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("naive", outbound.getString("protocol"))
        val options = outbound.getJSONObject("settings")
        assertEquals("2001:db8::1", options.getString("address"))
        assertEquals(443, options.getInt("port"))
        assertEquals("test+user", options.getString("user"))
        assertEquals("test:pass+word", options.getString("pass"))
        assertFalse(options.getBoolean("quic"))
        assertFalse(options.getBoolean("udpOverTcp"))
        val stream = outbound.getJSONObject("streamSettings")
        assertEquals("raw", stream.getString("network"))
        assertEquals("proxy.example.org", stream.getJSONObject("tlsSettings").getString("serverName"))
        assertTrue(stream.getJSONObject("sockopt").getBoolean("tcpFastOpen"))
    }

    @Test
    fun naiveQuicKeepsHttp3SeparateFromUdpDestinationSupport() {
        val helper = LinkHelper(settings,
            "naive+quic://test-user:test-pass@proxy.example.org:8443?padding=1&tfo=0#HTTP3")
        val outbound = JSONObject(helper.json()).getJSONArray("outbounds").getJSONObject(0)
        assertEquals(8443, outbound.getJSONObject("settings").getInt("port"))
        assertTrue(outbound.getJSONObject("settings").getBoolean("quic"))
        assertFalse(outbound.getJSONObject("settings").getBoolean("udpOverTcp"))
    }

    @Test
    fun naiveUrisWithoutAuthenticationPreserveTheNoAuthExportRoundtrip() {
        listOf("naive+https", "naive+quic").forEach { scheme ->
            val helper = LinkHelper(settings, "$scheme://proxy.example.org#No%20auth")
            assertTrue(helper.isValid())
            assertEquals("No auth", helper.remark())
            val options = JSONObject(helper.json()).getJSONArray("outbounds").getJSONObject(0)
                .getJSONObject("settings")
            assertEquals("", options.getString("user"))
            assertEquals("", options.getString("pass"))
            assertEquals(scheme == "naive+quic", options.getBoolean("quic"))
        }
    }

    @Test
    fun naiveUnsupportedSemanticsAndMalformedCredentialsNeverFallBackToNative() {
        listOf("padding=0", "fp=chrome", "alpn=h2", "allowInsecure=1", "tfo=1")
            .forEach { option ->
                assertTrue(runCatching {
                    LinkHelper(settings, "naive+quic://test-user:test-pass@proxy.example.org?$option")
                }.exceptionOrNull() is UnsupportedProfileException)
            }
        listOf(
            "naive+https://test-user@proxy.example.org",
            "naive+https://test%3Auser:test-pass@proxy.example.org",
            "naive+https://test-user:test-pass@proxy.example.org:0",
            "naive+https://test-user:test-pass@proxy.example.org?padding=maybe",
            "naive+https://test-user:test-pass@proxy.example.org?padding=1&padding=0",
            "naive+https://test-user:test%xxpass@proxy.example.org",
        ).forEach { value ->
            val error = runCatching { LinkHelper(settings, value) }.exceptionOrNull()
            assertTrue(error is IllegalArgumentException && error !is UnsupportedProfileException)
        }
    }

    @Test
    fun fullRawConfigKeepsNullsAndUnsupportedOutboundForExplicitCoreValidation() {
        val raw = """{"outbounds":[{"protocol":"future-protocol","settings":{"value":null}}],"future":null}"""
        assertJsonEquals(JSONObject(raw), JSONObject(LinkHelper(settings, raw).json()))
    }

    @Test
    fun inlineSingBoxIsNeverMistakenForAnXrayDocument() {
        val value = """{"outbounds":[{"type":"naive","server":"proxy.example.org","server_port":443,
            "username":"test-user","password":"test-pass","tls":{"enabled":true}}]}"""
        assertTrue(runCatching { LinkHelper(settings, value) }.exceptionOrNull() is UnsupportedProfileException)
    }

    @Test
    fun base64UtilityDecodesUtf8AndReturnsMalformedInputUnchanged() {
        assertEquals("Привет", LinkHelper.tryDecodeBase64("0J/RgNC40LLQtdGC"))
        assertEquals("a", LinkHelper.tryDecodeBase64("a"))
    }
}
