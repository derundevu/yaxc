package io.github.derundevu.yaxc

import XrayCore.XrayCore
import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.helper.CoreRoutingHelper
import io.github.derundevu.yaxc.helper.LinkHelper
import io.github.derundevu.yaxc.helper.XrayBatchPingHelper
import io.github.derundevu.yaxc.helper.XrayValidationHelper
import io.github.derundevu.yaxc.testing.LoopbackTarget
import io.github.derundevu.yaxc.viewmodel.ProfileEditorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual Go library and worker processes; no VPN, TUN, root, public server or user fixtures. */
@RunWith(AndroidJUnit4::class)
class NativeIntegrationTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var directory: File
    private var ownsCore = false

    @Before
    fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.derundevu.yaxc.regression")
        settings = Settings(context)
        directory = File(context.cacheDir, "native-regression-${UUID.randomUUID()}")
        check(directory.mkdir())
    }

    @After
    fun tearDown() {
        if (ownsCore) assertEquals("native cleanup", "", XrayCore.stop())
        directory.deleteRecursively()
    }

    @Test
    fun nativeTrojanImportRetainsPasswordAndTlsTransport() {
        val imported = LinkHelper(settings, "trojan://synthetic-password@127.0.0.1:443?security=tls&sni=fixture.invalid&type=ws&path=%2Ffixture#Trojan")
        assertTrue(imported.isValid())
        val outbound = JSONObject(imported.json()).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("trojan", outbound.getString("protocol"))
        val server = outbound.getJSONObject("settings")
        assertEquals("synthetic-password", server.getString("password"))
        assertEquals("127.0.0.1", server.getString("address"))
        assertEquals(443, server.getInt("port"))
        val transport = outbound.getJSONObject("streamSettings")
        assertEquals("tls", transport.getString("security"))
        assertEquals("fixture.invalid", transport.getJSONObject("tlsSettings").getString("serverName"))
        assertEquals("/fixture", transport.getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun nativeSocksImportRetainsCredentialsAndEndpoint() {
        val credentials = Base64.encodeToString("fixture-user:fixture-password".toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING)
        val imported = LinkHelper(settings, "socks://$credentials@127.0.0.1:1080#SOCKS")
        assertTrue(imported.isValid())
        val outbound = JSONObject(imported.json()).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("socks", outbound.getString("protocol"))
        val server = outbound.getJSONObject("settings")
        assertEquals("127.0.0.1", server.getString("address"))
        assertEquals(1080, server.getInt("port"))
        assertEquals("fixture-user", server.getString("user"))
        assertEquals("fixture-password", server.getString("pass"))
    }

    @Test
    fun nativeLegacyVmessQrRetainsIdentityAndWebsocket() {
        val identity = "00000000-0000-4000-8000-000000000001"
        val qr = """{"v":"2","ps":"QR 東京","add":"127.0.0.1","port":"443","id":"$identity","scy":"auto","net":"ws","host":"cdn.invalid","path":"/fixture","tls":"tls","sni":"fixture.invalid"}"""
        val imported = LinkHelper(settings, "vmess://" + Base64.encodeToString(qr.toByteArray(), Base64.NO_WRAP))
        assertTrue(imported.isValid())
        val outbound = JSONObject(imported.json()).getJSONArray("outbounds").getJSONObject(0)
        assertEquals("vmess", outbound.getString("protocol"))
        val peer = outbound.getJSONObject("settings")
        assertEquals(identity, peer.getString("id"))
        assertEquals("127.0.0.1", peer.getString("address"))
        assertEquals("/fixture", outbound.getJSONObject("streamSettings").getJSONObject("wsSettings").getString("path"))
    }

    @Test
    fun base64FullConfigDoesNotBecomeALossySingleOutboundImport() {
        val raw = """{"remarks":"Full 東京","outbounds":[{"protocol":"freedom","tag":"first"},{"protocol":"blackhole","tag":"second"}],"futureRoot":{"provider":"kept","values":[2,1]},"routing":{"domainStrategy":"AsIs"}}"""
        val imported = LinkHelper(settings, Base64.encodeToString(raw.toByteArray(), Base64.NO_WRAP))
        assertTrue(imported.isValid())
        val actual = JSONObject(imported.json())
        assertEquals("kept", actual.getJSONObject("futureRoot").getString("provider"))
        assertEquals(2, actual.getJSONArray("outbounds").length())
        assertEquals("second", actual.getJSONArray("outbounds").getJSONObject(1).getString("tag"))
        assertEquals("Full 東京", imported.remark())
    }

    @Test
    fun isolatedValidationPreservesTrafficAndStopReleasesListener() = runBlocking {
        LoopbackTarget().use { target ->
            val port = reservePort()
            val config = proxyConfig(port, target.port)
            startCore(config)
            requireTransfer(target, port, "before-validation")
            val before = validationFiles()
            assertEquals("", withTimeout(10_000) { XrayValidationHelper.validate(context, config) })
            requireTransfer(target, port, "after-valid")
            val invalid = """{"outbounds":[{"protocol":"not-a-protocol"}]}"""
            assertTrue(withTimeout(10_000) { XrayValidationHelper.validate(context, invalid) }.contains("not-a-protocol"))
            requireTransfer(target, port, "after-invalid")
            assertEquals(before, validationFiles())
            stopCore()
            ServerSocket(port, 1, InetAddress.getByName("127.0.0.1")).close()
            startCore(config)
            requireTransfer(target, port, "after-restart")
            assertTrue(target.failures.toString(), target.failures.isEmpty())
        }
    }

    @Test
    fun missingExternalGeodataRecoversAfterInstallingTheReferencedFixture() = runBlocking {
        val filename = "regression-geosite-${UUID.randomUUID()}.dat"
        val asset = File(context.filesDir, filename)
        val config = """{"log":{"loglevel":"none"},"outbounds":[{"tag":"direct","protocol":"freedom"}],"routing":{"rules":[{"domain":["ext:$filename:FIXTURE"],"outboundTag":"direct"}]}}"""
        try {
            val missing = withTimeout(10_000) { XrayValidationHelper.validate(context, config) }
            assertTrue(missing, missing.contains(filename))
            asset.writeBytes(geositeFixture())
            assertEquals("", withTimeout(10_000) { XrayValidationHelper.validate(context, config) })
            asset.writeBytes(byteArrayOf(0x0a, 0x7f))
            val corrupt = withTimeout(10_000) { XrayValidationHelper.validate(context, config) }
            assertTrue(corrupt, corrupt.contains(filename))
        } finally {
            asset.delete()
        }
    }

    @Test
    fun realPingGetHeadAndTcpLeaveAnIndependentCoreTransferring() = runBlocking {
        LoopbackTarget().use { target ->
            val port = reservePort()
            startCore(proxyConfig(port, target.port))
            val profile = Profile(id = 101L, config = """{"outbounds":[${directOutbound(target.port)}]}""")
            for (type in Settings.PingType.entries) {
                val result = withTimeout(15_000) {
                    XrayBatchPingHelper.measureProfileDelay(context, settings, Config(), profile,
                        XrayBatchPingHelper.Options(5, "${target.url}/ping-${type.value}", type))
                }
                assertTrue("${type.value}: $result", Regex("\\d+\\s*(ms|мс)").containsMatchIn(result))
                if (type != Settings.PingType.Tcp) {
                    val request = target.requests.poll(5, TimeUnit.SECONDS)
                    assertNotNull("Ping did not reach controlled target", request)
                    assertEquals(type.value.uppercase(), request!!.method)
                    assertEquals("/ping-${type.value}", request.path)
                }
                requireTransfer(target, port, "sentinel-${type.value}")
            }
            assertTrue(File(context.cacheDir, "batch-ping").listFiles().orEmpty().isEmpty())
            assertTrue(target.failures.toString(), target.failures.isEmpty())
        }
    }

    @Test
    fun cancellingFiveNativePingsClosesPeersRecoversPoolAndKeepsSentinelAlive() = runBlocking {
        LoopbackTarget().use { target ->
            val port = reservePort()
            startCore(proxyConfig(port, target.port))
            val profile = Profile(id = 202L, config = """{"outbounds":[${directOutbound(target.port)}]}""")
            coroutineScope {
                val calls = (1..5).map { index ->
                    async(Dispatchers.Default) { XrayBatchPingHelper.measureProfileDelay(context, settings, Config(), profile.copy(id = index.toLong()),
                        XrayBatchPingHelper.Options(10, "${target.url}/hold-$index", Settings.PingType.Get)) }
                }
                repeat(5) {
                    val request = target.requests.poll(8, TimeUnit.SECONDS)
                    assertNotNull("Worker did not reach held target", request)
                    assertTrue(request!!.path.startsWith("/hold-"))
                }
                requireTransfer(target, port, "five-held")
                val queued = (6..8).map { index ->
                    async(Dispatchers.Default) { XrayBatchPingHelper.measureProfileDelay(context, settings, Config(), profile.copy(id = index.toLong()),
                        XrayBatchPingHelper.Options(10, "${target.url}/hold-$index", Settings.PingType.Get)) }
                }
                queued.forEach { it.cancelAndJoin() }
                calls.forEach { withTimeout(10_000) { it.cancelAndJoin() } }
                repeat(5) { assertNotNull("Cancelled native request left its peer open", target.closedHolds.poll(5, TimeUnit.SECONDS)) }
                assertEquals(5, target.maximumHeld.get())
                val recovered = withTimeout(15_000) {
                    XrayBatchPingHelper.measureProfileDelay(context, settings, Config(), profile,
                        XrayBatchPingHelper.Options(5, "${target.url}/recovered", Settings.PingType.Get))
                }
                assertTrue(recovered, Regex("\\d+\\s*(ms|мс)").containsMatchIn(recovered))
                assertEquals("/recovered", target.requests.poll(5, TimeUnit.SECONDS)!!.path)
                requireTransfer(target, port, "after-cancellation")
            }
            assertTrue(File(context.cacheDir, "batch-ping").listFiles().orEmpty().isEmpty())
            assertTrue(target.failures.toString(), target.failures.isEmpty())
        }
    }

    @Test
    fun routingVisualRoundtripKeepsAnUnsupportedFirstMatchAheadOfEditableRules() = runBlocking {
        LoopbackTarget().use { target ->
            val parsed = CoreRoutingHelper.parseRoutingJson("""{"domainStrategy":"AsIs","rules":[{"inboundTag":["native-in"],"ip":["127.0.0.1/32"],"outboundTag":"blocked"},{"ip":["127.0.0.1/32"],"outboundTag":"direct"}]}""")
            val routing = CoreRoutingHelper.buildRoutingJson(parsed.domainStrategy, parsed.rules, parsed.ruleOrder, parsed.preservedTopLevel)
            val port = reservePort()
            val config = JSONObject(proxyConfig(port, target.port))
            config.put("routing", JSONObject(routing))
            config.getJSONArray("outbounds").put(JSONObject("""{"protocol":"blackhole","tag":"blocked"}"""))
            startCore(config.toString())
            try {
                transfer(target.url, port, "blocked")
                fail("Visual roundtrip moved the blocking first-match rule behind the editable allow rule")
            } catch (_: IOException) {
                assertNull(target.requests.poll())
            }
            stopCore()
            startCore(proxyConfig(port, target.port))
            requireTransfer(target, port, "allowed-control")
        }
    }

    @Test
    fun checkedProfileSavePersistsRawDraftAndRejectedEditDoesNotReplaceIt() = runBlocking {
        val app = context.applicationContext as Yaxc
        val db = YaxcDatabase.ref(context)
        val previous = db.configDao().get()
        if (previous == null) db.configDao().insert(Config()) else db.configDao().update(Config())
        val source = Link(name = "native-save-${UUID.randomUUID()}", address = "", type = Link.Type.Json)
        source.id = db.linkDao().insertAtEnd(source)
        val valid = """{"outbounds":[{"protocol":"freedom","tag":"direct"}],"futureRoot":{"retained":true}}"""
        try {
            val vm = withContext(Dispatchers.Main) {
                ProfileEditorViewModel(app).also { it.initialize(0L, "Synthetic native save", valid, source.id, null) }
            }
            withTimeout(10_000) { while (withContext(Dispatchers.Main) { vm.isLoading }) yield() }
            withTimeout(10_000) { withContext(Dispatchers.Main) { vm.save(check = true) } }
            val saved = db.profileDao().linkProfiles(source.id).single()
            assertEquals(valid, saved.config)
            assertEquals("Synthetic native save", saved.name)
            withContext(Dispatchers.Main) { vm.name = "Edited native save" }
            withTimeout(10_000) { withContext(Dispatchers.Main) { vm.save(check = true) } }
            val edited = db.profileDao().linkProfiles(source.id).single()
            assertEquals(saved.id, edited.id)
            assertEquals(saved.index, edited.index)
            assertEquals("Edited native save", edited.name)
            assertEquals(valid, edited.config)
            withContext(Dispatchers.Main) { vm.document.text = """{"outbounds":[{"protocol":"not-a-protocol"}]}""" }
            withTimeout(10_000) { withContext(Dispatchers.Main) { vm.save(check = true) } }
            val retained = db.profileDao().linkProfiles(source.id).single()
            assertEquals(saved.id, retained.id)
            assertEquals(valid, retained.config)
            assertEquals("Edited native save", retained.name)
            assertFalse(withContext(Dispatchers.Main) { vm.isSaving })
        } finally {
            db.linkDao().delete(source)
            if (previous != null) db.configDao().update(previous)
        }
    }

    private fun validationFiles(): Set<String> = context.cacheDir.listFiles().orEmpty()
        .filter { it.name.startsWith("validation-") }.mapTo(mutableSetOf()) { it.name }

    private fun startCore(config: String) {
        val file = File(directory, "core.json").apply { writeText(config) }
        val diagnostic = XrayCore.start(directory.absolutePath, file.absolutePath)
        assertEquals(diagnostic, "", diagnostic)
        ownsCore = true
    }

    private fun stopCore() {
        assertEquals("", XrayCore.stop())
        ownsCore = false
    }

    private fun proxyConfig(port: Int, targetPort: Int): String = """{"log":{"loglevel":"none"},"inbounds":[{"tag":"native-in","listen":"127.0.0.1","port":$port,"protocol":"http"}],"outbounds":[${directOutbound(targetPort)}]}"""

    private fun directOutbound(targetPort: Int): String = """{"tag":"direct","protocol":"freedom","settings":{"domainStrategy":"AsIs","finalRules":[{"action":"allow","ip":["127.0.0.1/32"],"port":"$targetPort"}]}}"""

    private fun reservePort(): Int = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }

    private fun requireTransfer(target: LoopbackTarget, proxyPort: Int, stage: String) {
        assertArrayEquals(target.payload, transfer(target.url, proxyPort, stage))
        val delivered = target.requests.poll(5, TimeUnit.SECONDS)
        assertNotNull("Core returned without delivering request", delivered)
        assertEquals("POST", delivered!!.method)
        assertEquals("/sentinel?stage=$stage", delivered.path)
        assertArrayEquals("request\u0000東京".toByteArray(), delivered.body)
    }

    private fun transfer(target: String, proxyPort: Int, stage: String): ByteArray {
        val connection = URL("$target/sentinel?stage=$stage").openConnection(
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", proxyPort))) as HttpURLConnection
        connection.connectTimeout = 3_000
        connection.readTimeout = 3_000
        connection.requestMethod = "POST"
        connection.doOutput = true
        val body = "request\u0000東京".toByteArray()
        connection.setFixedLengthStreamingMode(body.size)
        return try {
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode != 200) throw IOException("Proxy rejected fixture request: HTTP ${connection.responseCode}")
            connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun geositeFixture(): ByteArray {
        fun field(tag: Int, value: ByteArray): ByteArray {
            val bytes = ByteArrayOutputStream()
            bytes.write(tag)
            var length = value.size
            while (length >= 0x80) { bytes.write((length and 0x7f) or 0x80); length = length ushr 7 }
            bytes.write(length)
            bytes.write(value)
            return bytes.toByteArray()
        }
        val domain = byteArrayOf(0x08, 0x03) + field(0x12, "fixture.invalid".toByteArray())
        return field(0x0a, field(0x0a, "FIXTURE".toByteArray()) + field(0x12, domain))
    }
}
