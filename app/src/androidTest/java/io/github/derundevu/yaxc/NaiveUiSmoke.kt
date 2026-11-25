package io.github.derundevu.yaxc

import XrayCore.XrayCore
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.core.os.LocaleListCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.derundevu.yaxc.activity.LinksManagerActivity
import io.github.derundevu.yaxc.activity.MainActivity
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.helper.XrayValidationHelper
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class NaiveUiSmoke {
    @get:Rule val compose = createEmptyComposeRule()

    @Test fun mixedHttpSourceReportsAndActualChromiumTransportsAndLicensesWork(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        check(context.packageName == "io.github.derundevu.yaxc.regression")
        check(!TProxyService.isActive())
        assertEquals("26.11.9.4", XrayCore.version())
        val settings = Settings(context)
        val oldLanguage = settings.languageTag
        val oldRefresh = settings.refreshLinksOnOpen
        val oldLocales = AppCompatDelegate.getApplicationLocales()
        val database = YaxcDatabase.ref(context)
        var ownedSource: Link? = null
        try {
            settings.languageTag = "en"
            settings.refreshLinksOnOpen = false
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en")) }
            SourcePeer().use { peer ->
                ActivityScenario.launch<LinksManagerActivity>(LinksManagerActivity.openLink(context,
                    Link(name = "Synthetic Naive smoke", address = peer.url))).use {
                    compose.onNodeWithText(context.getString(R.string.createSource)).performScrollTo().performClick()
                    waitText(context.getString(R.string.subscriptionHttpWarningTitle))
                    assertEquals(0, peer.requests.get())
                    compose.onNodeWithText(context.getString(R.string.cancel)).performClick()
                    assertEquals(0, peer.requests.get())
                    compose.onNodeWithText(context.getString(R.string.createSource)).performScrollTo().performClick()
                    compose.onNodeWithText(context.getString(R.string.subscriptionHttpConfirm)).performClick()
                    waitText("Unavailable node · anytls")
                    capture("naive-source-warning")
                    compose.onNodeWithText(context.getString(R.string.close)).performClick()
                }
                val source = withContext(Dispatchers.IO) { database.linkDao().all().first().single { it.address == peer.url } }
                ownedSource = source
                val profiles = withContext(Dispatchers.IO) { database.profileDao().linkProfiles(source.id) }
                assertEquals(setOf("Naive HTTP2", "Naive QUIC"), profiles.map { it.name }.toSet())
                for (profile in profiles) assertEquals("", XrayValidationHelper.validate(context, profile.config))
                ActivityScenario.launch<LinksManagerActivity>(LinksManagerActivity.refreshLink(context, source.id)).use {
                    waitText("Unavailable node · anytls")
                    compose.onNodeWithText(context.getString(R.string.subscriptionRefreshNothingNew)).assertDoesNotExist()
                    compose.onNodeWithText(context.getString(R.string.close)).performClick()
                }
                assertEquals(2, peer.requests.get())
                withContext(Dispatchers.IO) { assertTrue(settings.pendingSubscriptionWarnings().none { it.sourceId == source.id }) }
                val arguments = InstrumentationRegistry.getArguments()
                val root = File("/data/local/tmp/yaxc-naive-smoke-root.pem").readText()
                for (quic in listOf(false, true)) {
                    val profile = profiles.single { it.name == if (quic) "Naive QUIC" else "Naive HTTP2" }
                    val config = JSONObject(profile.config)
                    config.put("log", JSONObject().put("loglevel", "none"))
                    config.remove("routing")
                    val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
                    config.put("inbounds", JSONArray().put(JSONObject().put("listen", "127.0.0.1")
                        .put("port", localPort).put("protocol", "http")))
                    val outbound = config.getJSONArray("outbounds").getJSONObject(0)
                    val nativeSettings = outbound.getJSONObject("settings")
                    nativeSettings.put("address", "10.0.2.2")
                    nativeSettings.put("port", arguments.getString(if (quic) "naiveQuicPort" else "naiveHttpsPort")!!.toInt())
                    nativeSettings.put("user", "test-user").put("pass", "test-pass")
                    if (quic) nativeSettings.put("quicCongestionControl", "bbr")
                    outbound.getJSONObject("streamSettings").put("tlsSettings", JSONObject().put("serverName", "proxy.example.org")
                        .put("certificates", JSONArray().put(JSONObject().put("usage", "verify").put("certificate", JSONArray(root.lines())))))
                    val configFile = File(context.cacheDir, "naive-transport-smoke.json")
                    configFile.writeText(config.toString())
                    try {
                        assertEquals("", XrayCore.start(context.filesDir.absolutePath, configFile.absolutePath))
                        val connection = withContext(Dispatchers.IO) {
                            URL("http://payload.fixture.invalid/smoke").openConnection(
                                Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", localPort))) as java.net.HttpURLConnection
                        }
                        try {
                            connection.connectTimeout = 10_000
                            connection.readTimeout = 10_000
                            val payload = withContext(Dispatchers.IO) { connection.inputStream.bufferedReader().use { it.readText() } }
                            assertEquals("android naive smoke payload", payload)
                        } finally { connection.disconnect() }
                    } finally {
                        assertEquals("", XrayCore.stop())
                        configFile.delete()
                    }
                }
                ActivityScenario.launch(MainActivity::class.java).use {
                    waitText("Settings")
                    compose.onNodeWithText("Settings").performClick()
                    compose.onNodeWithTag("settings:licenses").performScrollTo().performClick()
                    compose.waitUntil(15_000) { compose.onAllNodesWithText("NOTICE.txt").fetchSemanticsNodes().isNotEmpty() }
                    compose.onNodeWithTag("licenses:dialog").assertIsDisplayed()
                    capture("naive-licenses")
                    compose.onNodeWithTag("licenses:content").performScrollToNode(hasText("GPL-3.0.txt"))
                    compose.onNodeWithText("GPL-3.0.txt").assertIsDisplayed()
                    compose.onNodeWithTag("licenses:content").performScrollToNode(hasText("MIT.txt"))
                    compose.onNodeWithText("MIT.txt").assertIsDisplayed()
                    compose.onNodeWithTag("licenses:close").performClick()
                }
            }
        } finally {
            withContext(Dispatchers.IO) { ownedSource?.let { database.linkDao().delete(it) } }
            settings.languageTag = oldLanguage
            settings.refreshLinksOnOpen = oldRefresh
            instrumentation.runOnMainSync { AppCompatDelegate.setApplicationLocales(oldLocales) }
        }
    }

    private fun waitText(text: String) = compose.waitUntil(20_000) {
        compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(instrumentation.targetContext.cacheDir, "$name.png").outputStream().use {
            check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
        } } finally { image.recycle() }
    }
    private class SourcePeer : Closeable {
        private val listener = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        val requests = AtomicInteger()
        val url = "http://127.0.0.1:${listener.localPort}/synthetic"
        init { executor.execute {
            while (!listener.isClosed) {
                val socket = try { listener.accept() } catch (_: Exception) { break }
                executor.execute { socket.use {
                    it.soTimeout = 5000
                    val reader = it.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    requests.incrementAndGet()
                    val body = "naive+https://test-user:test-pass@proxy.example.org:443#Naive%20HTTP2\nnaive+quic://test-user:test-pass@proxy.example.org:443#Naive%20QUIC\nanytls://test-pass@proxy.example.org:443#Unavailable%20node".toByteArray()
                    it.getOutputStream().apply {
                        write("HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body); flush()
                    }
                } }
            }
        } }
        override fun close() { listener.close(); executor.shutdownNow() }
    }
}
