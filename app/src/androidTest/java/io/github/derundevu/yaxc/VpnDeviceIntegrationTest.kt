package io.github.derundevu.yaxc

import XrayCore.XrayCore
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.derundevu.yaxc.activity.MainActivity
import io.github.derundevu.yaxc.activity.CoreRoutingActivity
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.helper.ConnectionFailureState
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.LiveRoutingPlan
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.helper.ProxyGroupStrategy
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.helper.XrayValidationHelper
import io.github.derundevu.yaxc.repository.ConnectionCaptureStatus
import io.github.derundevu.yaxc.repository.ConnectionRecord
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionFilter
import io.github.derundevu.yaxc.presentation.connectionanalytics.ConnectionGrouping
import io.github.derundevu.yaxc.viewmodel.ConnectionAnalyticsViewModel
import io.github.derundevu.yaxc.viewmodel.CoreRoutingEditorViewModel
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.testing.ChainedStreamTarget
import io.github.derundevu.yaxc.testing.DeadlineInputStream
import io.github.derundevu.yaxc.testing.LoopbackTarget
import io.github.derundevu.yaxc.testing.ReloadSubscriptionTarget
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Explicit opt-in AND emulator-only: must never take over a physical device's VPN. */
@RunWith(AndroidJUnit4::class)
class VpnDeviceIntegrationTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var db: YaxcDatabase
    private lateinit var source: Link
    private lateinit var profile: Profile
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var target: LoopbackTarget
    private var routingRelay: LoopbackConnectProxy? = null
    private lateinit var previousPreferences: Map<String, *>
    private var previousConfig: Config? = null
    private val events = LinkedBlockingQueue<Intent>()
    private var registered = false
    private var socksPort = 0
    private var routedNetwork: Network? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) { events.offer(Intent(intent)) }
    }

    @Before
    fun prepareDisposableVpn() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowVpn") == "true") {
            "VPN device tests require explicit allowVpn=true on a dedicated emulator"
        }
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
            "VPN tests refuse physical devices, even with allowVpn=true"
        }
        check(Build.VERSION.SDK_INT >= 31) { "Bidirectional shell fixture requires API 31 or newer" }
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.derundevu.yaxc.regression")
        check(!TProxyService.isActive()) { "Regression package already owns a session; refusing to replace it" }
        val preferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        previousPreferences = preferences.all
        settings = Settings(context)
        settings.liveRoutingPlan = "{}"
        settings.transparentProxy = false
        settings.tun2socks = true
        settings.enableIpV6 = false
        settings.bypassLan = false
        settings.appsRoutingMode = Settings.AppsRoutingMode.Exclude
        settings.appsRouting = ""
        settings.tunOwnerDefense = true
        settings.socksAddress = "127.0.0.1"
        socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        settings.socksPort = socksPort.toString()
        settings.socksUsername = ""
        settings.socksPassword = ""
        settings.refreshLinksOnOpen = false
        target = LoopbackTarget()
        db = YaxcDatabase.ref(context)
        previousConfig = db.configDao().get()
        if (previousConfig == null) db.configDao().insert(Config()) else db.configDao().update(Config())
        source = Link(name = "isolated-vpn-${UUID.randomUUID()}")
        source.id = db.linkDao().insertAtEnd(source)
        profile = Profile(linkId = source.id, name = "Synthetic emulator VPN", config = profileConfig())
        profile.id = db.profileDao().create(profile)
        settings.selectedProfile = profile.id
        ConnectionFailureState.clear()
        val filter = IntentFilter().apply {
            addAction(TProxyService.START_VPN_SERVICE_ACTION_NAME)
            addAction(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
            addAction(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            addAction(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        shell("cmd appops set ${context.packageName} ACTIVATE_VPN allow")
        assertNull("Emulator did not grant isolated test VPN consent", VpnService.prepare(context))
    }

    @After
    fun releaseDisposableVpn() = runBlocking {
        // Guards above run before any mutation. Cleanup must not operate after a guard rejection.
        if (!::settings.isInitialized) return@runBlocking
        try {
            TProxyService.stop(context)
            if (TProxyService.isActive()) await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        } finally {
            if (registered) context.unregisterReceiver(receiver)
            if (::scenario.isInitialized) scenario.close()
            if (::source.isInitialized && source.id != 0L) db.linkDao().delete(source)
            previousConfig?.let { db.configDao().update(it) }
            routingRelay?.close()
            if (::target.isInitialized) target.close()
            val editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
            previousPreferences.forEach { (key, value) ->
                when (value) {
                    is String -> editor.putString(key, value)
                    is Long -> editor.putLong(key, value)
                    is Int -> editor.putInt(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }
            editor.commit()
            ConnectionFailureState.clear()
        }
    }

    @Test
    fun anotherUidTransfersThroughTunAndRejectedReloadKeepsTheSameWorkingSession() = runBlocking {
        start()
        requireTunTransfer("before-reload")
        val applied = settings.xrayConfig().readBytes()
        db.profileDao().update(profile.copy(config = """{"outbounds":[{"protocol":"not-a-protocol"}]}"""))
        TProxyService.newConfig(context)
        await(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        assertNotNull(ConnectionFailureState.failure.value)
        assertTrue(TProxyService.isActive())
        assertArrayEquals(applied, settings.xrayConfig().readBytes())
        requireTunTransfer("after-rejected-reload")
        TProxyService.stop(context)
        await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        assertFalse(TProxyService.isActive())
        requireSocksListenerReleased()
        assertTrue(target.failures.toString(), target.failures.isEmpty())
    }

    @Test
    fun oneEstablishedTcpFlowSurvivesUnchangedMetadataAndUnselectedNetworkRefreshes() = runBlocking {
        ChainedStreamTarget(frames = 4).use { peer ->
            profile = profile.copy(config = profileConfig(peer.port))
            db.profileDao().update(profile)
            start()
            val applied = settings.xrayConfig().readBytes()
            assertTrue(settings.xrayConfig().setLastModified(1_600_000_000_000L))
            val timestamp = settings.xrayConfig().lastModified()
            ShellTcpFlow(peer.port).use { flow ->
                fun exchange(stage: String) {
                    val payload = byteArrayOf(0, 1, 0x7f, 0xff.toByte()) + "$stage 東京\n".toByteArray()
                    flow.assertNextChainedResponse(payload)
                    assertArrayEquals(payload, peer.received.poll(5, TimeUnit.SECONDS))
                    assertArrayEquals(applied, settings.xrayConfig().readBytes())
                    assertEquals(timestamp, settings.xrayConfig().lastModified())
                }

                exchange("established-before-reload")
                TProxyService.newConfig(context)
                awaitUnchangedAcknowledgement(profile.name)
                exchange("after-actual-unchanged-command")

                val renamed = "Renamed by real subscription refresh"
                ReloadSubscriptionTarget(JSONObject(profile.config).put("remarks", renamed).toString()).use { subscription ->
                    source.address = subscription.url
                    db.linkDao().update(source)
                    assertEquals(
                        1,
                        SubscriptionRefreshHelper(context, settings).refreshLinks(source.id).refreshed,
                    )
                    subscription.awaitServed()
                    awaitUnchangedAcknowledgement(renamed)
                    profile = db.profileDao().find(profile.id)
                    source = db.linkDao().find(source.id)!!
                    assertEquals(renamed, profile.name)
                    assertEquals(profile.id, settings.selectedProfile)
                    assertNotNull(source.subscriptionMetadata)
                    assertTrue(source.lastRefreshedAt > 0L)
                }
                exchange("after-selected-metadata-network-refresh")

                val otherSource = Link(name = "unselected-refresh-${UUID.randomUUID()}")
                otherSource.id = db.linkDao().insertAtEnd(otherSource)
                try {
                    val other = Profile(linkId = otherSource.id, name = "Old unselected", config = """{"outbounds":[{"protocol":"blackhole"}]}""")
                    other.id = db.profileDao().create(other)
                    val refreshed = """{"remarks":"Refreshed unselected","outbounds":[{"protocol":"freedom"}]}"""
                    ReloadSubscriptionTarget(refreshed).use { subscription ->
                        otherSource.address = subscription.url
                        db.linkDao().update(otherSource)
                        assertEquals(
                            1,
                            SubscriptionRefreshHelper(context, settings).refreshLinks(otherSource.id).refreshed,
                        )
                        subscription.awaitServed()
                        awaitUnchangedAcknowledgement(renamed)
                        val stored = db.profileDao().linkProfiles(otherSource.id).single()
                        assertEquals("Refreshed unselected", stored.name)
                        assertEquals("freedom", JSONObject(stored.config).getJSONArray("outbounds").getJSONObject(0).getString("protocol"))
                        assertEquals(profile.id, settings.selectedProfile)
                    }
                    exchange("after-unselected-network-refresh")
                } finally {
                    db.linkDao().delete(otherSource)
                }
                peer.awaitFinished()
            }
        }
        // A real effective change must still replace the core and apply the new destination.
        ReloadSubscriptionTarget(JSONObject(profileConfig()).put("remarks", profile.name).toString()).use { subscription ->
            source.address = subscription.url
            db.linkDao().update(source)
            assertEquals(
                1,
                SubscriptionRefreshHelper(context, settings).refreshLinks(source.id).refreshed,
            )
            subscription.awaitServed()
            await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
            requireTunTransfer("after-effective-config-refresh")
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse(events.any {
                it.action == TProxyService.START_VPN_SERVICE_ACTION_NAME ||
                    it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME ||
                    it.action == TProxyService.STOP_VPN_SERVICE_ACTION_NAME
            })
        }
        assertNull(ConnectionFailureState.failure.value)
        assertTrue(TProxyService.isActive())
        TProxyService.stop(context)
        await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        requireSocksListenerReleased()
    }

    @Test
    fun stopTearsDownTunWhileNativeValidationWaitsForItsLogSink() = runBlocking {
        start()
        requireTunTransfer("before-blocked-validation")
        val token = UUID.randomUUID()
        val accessSink = File(context.filesDir, "regression-access-$token.fifo")
        val errorSink = File(context.filesDir, "regression-error-$token.fifo")
        Os.mkfifo(accessSink.absolutePath, 0x180)
        Os.mkfifo(errorSink.absolutePath, 0x180)
        val executor = Executors.newSingleThreadExecutor()
        val firstReader = executor.submit<FileDescriptor> { Os.open(accessSink.absolutePath, OsConstants.O_RDONLY, 0) }
        var accessReader: FileDescriptor? = null
        var errorReader: FileDescriptor? = null
        try {
            val candidate = """{"log":{"access":"${accessSink.absolutePath}","error":"${errorSink.absolutePath}"},"outbounds":[{"tag":"proxy","protocol":"freedom"}]}"""
            db.profileDao().update(profile.copy(config = candidate))
            TProxyService.newConfig(context)
            // Xray opens access before error. This rendezvous proves core.New has entered native
            // logger construction; the second sink cannot open until after STOP completes.
            accessReader = firstReader.get(15, TimeUnit.SECONDS)
            TProxyService.stop(context)
            await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            assertFalse(TProxyService.isActive())
            requireSocksListenerReleased()
            errorReader = Os.open(errorSink.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            db.profileDao().update(profile)
            start()
            requireTunTransfer("after-late-validation")
            assertNull(ConnectionFailureState.failure.value)
        } finally {
            // Also unblock construction if an assertion failed before either rendezvous.
            val releaseAccess = Os.open(accessSink.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            val releaseError = Os.open(errorSink.absolutePath, OsConstants.O_RDWR or OsConstants.O_NONBLOCK, 0)
            try {
                if (accessReader == null) accessReader = firstReader.get(5, TimeUnit.SECONDS)
                withTimeout(15_000) {
                    assertEquals("", XrayValidationHelper.validate(context, """{"outbounds":[{"protocol":"freedom"}]}"""))
                }
            } finally {
                accessReader?.let { Os.close(it) }
                errorReader?.let { Os.close(it) }
                Os.close(releaseAccess)
                Os.close(releaseError)
                executor.shutdownNow()
                check(executor.awaitTermination(5, TimeUnit.SECONDS))
                accessSink.delete()
                errorSink.delete()
            }
        }
    }

    @Test
    fun immediateRestartReleasesOldTunnelAndCanAcquireAnotherNativeOwner() = runBlocking {
        start()
        requireTunTransfer("first-owner")
        TProxyService.restart(context)
        await(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        requireTunTransfer("replacement-owner")
        settings.tun2socks = false
        TProxyService.stop(context)
        await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        assertFalse(TProxyService.isActive())
        requireSocksListenerReleased()
        settings.tun2socks = true
        start()
        requireTunTransfer("new-owner-after-edited-setting")
    }

    @Test
    fun analyticsHistorySwitchCutsOffOnlyObservationsWhileEstablishedTcpAndUdpKeepForwarding() = runBlocking {
        val repository = (context.applicationContext as Yaxc).connectionRepository
        // Exercise the absent-preference default, not a fixture that preselects On.
        assertTrue(context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .remove("connectionAnalyticsEnabled").commit())
        assertTrue(Settings(context).connectionAnalyticsEnabled)
        repository.setCaptureEnabled(true)
        repository.setPersistence(false)
        repository.clearHistory()
        compose.waitUntil(10_000) {
            repository.state.value.captureEnabled && !repository.state.value.persistOnDisk &&
                repository.state.value.connections.isEmpty()
        }
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.android.shell"
        settings.socksUdp = true
        settings.socksUsername = "capture-fixture"
        settings.socksPassword = "capture-fixture-password"
        val shellUid = context.packageManager.getApplicationInfo("com.android.shell", 0).uid
        suspend fun awaitFlow(predicate: (ConnectionRecord) -> Boolean): ConnectionRecord = withTimeout(15_000) {
            repository.state.first { state -> state.connections.any(predicate) }.connections.first(predicate)
        }
        UdpEchoPeer(0x67).use { udp ->
            ChainedStreamTarget(frames = 6, idleTimeoutMillis = 120_000).use { stream ->
                fun outbound(tag: String, port: Int) =
                    """{"tag":"$tag","protocol":"freedom","settings":{"redirect":"127.0.0.1:$port","finalRules":[{"action":"allow","ip":["127.0.0.1/32"],"port":"$port"}]}}"""
                profile = profile.copy(config = """{
                    "log":{"loglevel":"none"},
                    "outbounds":[${outbound("proxy", stream.port)},${outbound("fixture-http", target.port)},
                        ${outbound("fixture-udp", udp.port)},{"tag":"fixture-blocked","protocol":"blackhole"}],
                    "routing":{"domainStrategy":"AsIs","rules":[
                        {"ip":["198.18.0.1/32","127.0.0.1/32"],"port":"${stream.port}","network":"tcp","outboundTag":"proxy"},
                        {"ip":["198.18.0.1/32","127.0.0.1/32"],"port":"${target.port}","network":"tcp","outboundTag":"fixture-http"},
                        {"ip":["198.18.0.1/32"],"port":"${udp.port}","network":"udp","outboundTag":"fixture-udp"},
                        {"network":"tcp,udp","outboundTag":"fixture-blocked"}]}
                }""")
                db.profileDao().update(profile)
                start()
                val network = checkNotNull(routedNetwork)
                val initial = JSONObject(XrayCore.connectionSnapshot())
                assertTrue(initial.toString(), initial.getBoolean("enabled"))
                val epoch = initial.getString("epoch")
                val generation = initial.getLong("generation")
                val captureGeneration = initial.getLong("captureGeneration")
                val applied = settings.xrayConfig().readBytes()
                val timestamp = settings.xrayConfig().lastModified()
                fun assertSameRuntime(stage: String) {
                    val native = JSONObject(XrayCore.connectionSnapshot())
                    assertTrue("$stage: native stopped: $native", native.getBoolean("running"))
                    assertEquals("$stage: native core replaced", epoch, native.getString("epoch"))
                    assertEquals("$stage: TUN replaced", network, routedNetwork)
                    assertVpnNetworkStillPublished(network)
                    assertArrayEquals("$stage: generated config changed", applied, settings.xrayConfig().readBytes())
                    assertEquals("$stage: generated config rewritten", timestamp, settings.xrayConfig().lastModified())
                    assertTrue("$stage: VPN stopped", TProxyService.isActive())
                    assertNull("$stage: service failure", ConnectionFailureState.failure.value)
                    assertNoLifecycleEvents(stage)
                }
                ShellTcpFlow(stream.port, idleSeconds = 120).use { tcp ->
                    ShellUdpFlow(udp.port).use { datagrams ->
                        fun exchangeTcp(stage: String): Int {
                            val payload = byteArrayOf(0, 0xff.toByte()) + "$stage 東京\n".toByteArray()
                            tcp.assertNextChainedResponse(payload)
                            assertArrayEquals("$stage: controlled TCP peer saw wrong frame",
                                payload, stream.received.poll(5, TimeUnit.SECONDS))
                            return payload.size
                        }
                        val firstPayloadSize = exchangeTcp("before-capture-off")
                        val udpSender = datagrams.assertNextResponse(udp, "before-capture-off")
                        val tcpBefore = awaitFlow {
                            it.uid == shellUid && it.protocol == "tcp" && it.destination.endsWith(":${stream.port}") &&
                                it.upload == firstPayloadSize + 4L
                        }
                        val udpBefore = awaitFlow {
                            it.uid == shellUid && it.protocol == "udp" && it.destinationKind == "association" &&
                                it.upload == 512L && it.download == 513L
                        }
                        assertEquals("active", tcpBefore.state)
                        assertEquals("active", udpBefore.state)
                        assertTrue(udpBefore.apps.any { it.packageName == "com.android.shell" })
                        ActivityScenario.launch(MainActivity::class.java).use { activity ->
                            lateinit var model: ConnectionAnalyticsViewModel
                            activity.onActivity { model = ViewModelProvider(it)[ConnectionAnalyticsViewModel::class.java] }
                            compose.onNodeWithTag("main:connection-analytics").performClick()
                            compose.waitUntil(10_000) { model.uiState.value.summaries.isNotEmpty() }
                            // A status command is a serial service boundary, not a delay to hide lifecycle events.
                            TProxyService.status(context)
                            await(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
                            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                            assertNoLifecycleEvents("before UI capture toggle")
                            compose.onNodeWithTag("analytics:history").performClick()
                            compose.onNodeWithTag("analytics:capture").performScrollTo().assertIsOn().performClick()
                            compose.waitUntil(10_000) {
                                !settings.connectionAnalyticsEnabled && !repository.state.value.captureEnabled &&
                                    repository.state.value.status == ConnectionCaptureStatus.Disabled &&
                                    repository.state.value.connections.filter { it.id == tcpBefore.id || it.id == udpBefore.id }
                                        .let { it.size == 2 && it.all { row -> row.state == "interrupted" && !row.liveBytesExact } }
                            }
                            assertFalse(Settings(context).connectionAnalyticsEnabled)
                            val cutoff = JSONObject(XrayCore.connectionSnapshot())
                            assertFalse(cutoff.toString(), cutoff.getBoolean("enabled"))
                            assertEquals(generation, cutoff.getLong("generation"))
                            assertEquals(captureGeneration + 1, cutoff.getLong("captureGeneration"))
                            val cutoffRows = repository.state.value.connections
                            val cutoffCounters = repository.state.value.counters
                            val oldRows = cutoffRows.filter { it.id == tcpBefore.id || it.id == udpBefore.id }
                            assertEquals(tcpBefore.upload, oldRows.single { it.id == tcpBefore.id }.upload)
                            assertTrue("Cutoff TCP download exceeded bytes actually echoed",
                                oldRows.single { it.id == tcpBefore.id }.download <= firstPayloadSize + 4L)
                            assertEquals(512L, oldRows.single { it.id == udpBefore.id }.upload)
                            assertEquals(513L, oldRows.single { it.id == udpBefore.id }.download)
                            fun assertFrozen(stage: String, enabled: Boolean) {
                                val native = JSONObject(XrayCore.connectionSnapshot())
                                assertEquals("$stage: wrong native capture state", enabled, native.getBoolean("enabled"))
                                assertEquals("$stage: history changed", cutoff.getJSONArray("connections").toString(),
                                    native.getJSONArray("connections").toString())
                                assertEquals("$stage: native evictions grew", cutoff.getLong("nativeEvicted"), native.getLong("nativeEvicted"))
                                assertEquals("$stage: native rejections grew", cutoff.getLong("nativeRejected"), native.getLong("nativeRejected"))
                                assertEquals("$stage: repository history changed", cutoffRows, repository.state.value.connections)
                                assertEquals("$stage: repository counters changed", cutoffCounters, repository.state.value.counters)
                                assertSameRuntime(stage)
                            }
                            captureActivitySurface(activity, "analytics-capture-disabled-history")
                            compose.onNodeWithText(context.getString(R.string.ca_done)).performClick()
                            compose.onNodeWithTag("analytics:status").assertTextContains(context.getString(R.string.ca_disabled))
                            val shellSummary = model.uiState.value.summaries.single { "com.android.shell" in it.identity.packages }
                            compose.onNodeWithTag("analytics:list").performScrollToKey(shellSummary.key)
                            compose.onNodeWithTag(shellSummary.key).performClick()
                            compose.waitUntil(10_000) { model.uiState.value.flows.any { it.record.id == tcpBefore.id } }
                            compose.onNodeWithTag("analytics:list").performScrollToKey("flow:${tcpBefore.id}")
                            compose.onNodeWithTag("flow:${tcpBefore.id}").performClick()
                            compose.onNodeWithTag("analytics:detail").assertTextContains(tcpBefore.profileName, substring = true)
                                .assertTextContains(context.getString(R.string.connectionAnalyticsStateInterrupted), substring = true)
                            captureActivitySurface(activity, "analytics-capture-disabled-retained-detail")
                            compose.onNodeWithText(context.getString(R.string.ca_done)).performClick()
                            compose.onNodeWithTag("analytics:back").performClick()
                            activity.recreate()
                            activity.onActivity { model = ViewModelProvider(it)[ConnectionAnalyticsViewModel::class.java] }
                            compose.onNodeWithTag("analytics:screen").assertIsDisplayed()
                            compose.onNodeWithTag("analytics:history").performClick()
                            compose.onNodeWithTag("analytics:capture").performScrollTo().assertIsOff()
                            assertFalse(Settings(context).connectionAnalyticsEnabled)
                            val disk = File(context.noBackupFilesDir, "connection-history.json")
                            compose.onNodeWithTag("analytics:persistence").performScrollTo().performClick()
                            compose.waitUntil(10_000) {
                                settings.connectionHistoryOnDisk && repository.state.value.persistOnDisk && disk.isFile
                            }
                            assertFalse("Disk persistence enabled capture", repository.state.value.captureEnabled)
                            compose.onNodeWithTag("analytics:capture").performScrollTo().assertIsOff()
                            compose.onNodeWithTag("analytics:persistence").performScrollTo().performClick()
                            compose.waitUntil(10_000) {
                                !settings.connectionHistoryOnDisk && !repository.state.value.persistOnDisk && !disk.exists()
                            }
                            ShellUdpFlow(udp.port).use { startedWhileOff ->
                                val offSender = startedWhileOff.assertNextResponse(udp, "new-association-while-off")
                                assertNotEquals("Off-period fixture reused the old UDP association", udpSender, offSender)
                                repeat(2) { index ->
                                    val bytesBefore = tunByteCounts(network)
                                    exchangeTcp("off-old-tcp-$index")
                                    assertEquals(udpSender, datagrams.assertNextResponse(udp, "off-old-udp-$index"))
                                    requireTunTransfer("capture-off-new-tcp-$index")
                                    assertArrayEquals(target.payload,
                                        transferSocksHttp("capture-off-listener-$index", target.port, "198.18.0.1", authenticate = true))
                                    assertEquals("/vpn-capture-off-listener-$index",
                                        target.requests.poll(5, TimeUnit.SECONDS)!!.path)
                                    assertFrozen("disabled response boundary $index", enabled = false)
                                    assertTunBytesIncrease("disabled response boundary $index", bytesBefore, tunByteCounts(network))
                                }
                                compose.onNodeWithTag("analytics:capture").performScrollTo().performClick()
                                compose.waitUntil(10_000) {
                                    settings.connectionAnalyticsEnabled && repository.state.value.captureEnabled &&
                                        repository.state.value.status == ConnectionCaptureStatus.Running
                                }
                                val resumed = JSONObject(XrayCore.connectionSnapshot())
                                assertEquals(captureGeneration + 2, resumed.getLong("captureGeneration"))
                                assertEquals(generation, resumed.getLong("generation"))
                                compose.onNodeWithTag("analytics:capture").assertIsOn()
                                captureActivitySurface(activity, "analytics-capture-resumed-history")
                                compose.onNodeWithTag("analytics:persistence").performScrollTo().performClick()
                                compose.waitUntil(10_000) {
                                    settings.connectionHistoryOnDisk && repository.state.value.persistOnDisk && disk.isFile
                                }
                                assertTrue("Disk persistence disabled capture", repository.state.value.captureEnabled)
                                assertEquals(captureGeneration + 2,
                                    JSONObject(XrayCore.connectionSnapshot()).getLong("captureGeneration"))
                                compose.onNodeWithTag("analytics:persistence").performScrollTo().performClick()
                                compose.waitUntil(10_000) {
                                    !settings.connectionHistoryOnDisk && !repository.state.value.persistOnDisk && !disk.exists()
                                }
                                compose.onNodeWithTag("analytics:capture").performScrollTo().assertIsOn()
                                repeat(2) { index ->
                                    val bytesBefore = tunByteCounts(network)
                                    exchangeTcp("resumed-old-tcp-$index")
                                    assertEquals(udpSender, datagrams.assertNextResponse(udp, "resumed-old-udp-$index"))
                                    assertEquals(offSender, startedWhileOff.assertNextResponse(udp, "resumed-off-udp-$index"))
                                    assertFrozen("resumed old-flow response boundary $index", enabled = true)
                                    assertTunBytesIncrease("resumed old-flow response boundary $index", bytesBefore, tunByteCounts(network))
                                }
                                requireTunTransfer("capture-on-new-tcp")
                                val freshTcp = awaitFlow {
                                    it.uid == shellUid && it.protocol == "tcp" && it.id !in cutoffRows.map { row -> row.id } &&
                                        it.destination.endsWith(":${target.port}") && it.upload > 0 && it.download > 0
                                }
                                ShellUdpFlow(udp.port).use { fresh ->
                                    val newSender = fresh.assertNextResponse(udp, "capture-on-new-udp")
                                    assertNotEquals(udpSender, newSender)
                                    assertNotEquals(offSender, newSender)
                                    val freshUdp = awaitFlow {
                                        it.uid == shellUid && it.protocol == "udp" && it.id != udpBefore.id &&
                                            it.upload == 512L && it.download == 513L
                                    }
                                    assertNotEquals(tcpBefore.id, freshTcp.id)
                                    assertEquals(oldRows, repository.state.value.connections.filter {
                                        it.id == tcpBefore.id || it.id == udpBefore.id
                                    })
                                    assertEquals("Only newly admitted TCP and UDP should be added", cutoffRows.size + 2,
                                        repository.state.value.connections.size)
                                    assertEquals("association", freshUdp.destinationKind)
                                    assertFalse("Capture toggle changed disk policy", settings.connectionHistoryOnDisk)
                                    assertSameRuntime("new observations after resume")
                                    compose.onNodeWithTag("analytics:capture").performClick()
                                    compose.waitUntil(10_000) {
                                        !repository.state.value.captureEnabled &&
                                            repository.state.value.connections.none { it.state == "active" }
                                    }
                                    val beforeClear = JSONObject(XrayCore.connectionSnapshot())
                                    compose.onNodeWithTag("analytics:clear").performScrollTo().performClick()
                                    compose.waitUntil(10_000) { repository.state.value.connections.isEmpty() }
                                    compose.onNodeWithTag("analytics:capture").performScrollTo().assertIsOff()
                                    assertFalse(Settings(context).connectionAnalyticsEnabled)
                                    val cleared = JSONObject(XrayCore.connectionSnapshot())
                                    assertFalse(cleared.getBoolean("enabled"))
                                    assertEquals(beforeClear.getLong("captureGeneration"), cleared.getLong("captureGeneration"))
                                    assertTrue(cleared.getLong("generation") > beforeClear.getLong("generation"))
                                    exchangeTcp("clear-while-off-old-tcp")
                                    assertEquals(udpSender, datagrams.assertNextResponse(udp, "clear-while-off-old-udp"))
                                    fresh.assertNextResponse(udp, "clear-while-off-resumed-udp")
                                    requireTunTransfer("clear-while-off-new-tcp")
                                    assertEquals(0, JSONObject(XrayCore.connectionSnapshot()).getJSONArray("connections").length())
                                    assertTrue(repository.state.value.connections.isEmpty())
                                    assertEquals(ConnectionCaptureStatus.Disabled, repository.state.value.status)
                                    assertSameRuntime("clear while capture disabled")
                                }
                            }
                        }
                    }
                }
                stream.awaitFinished()
            }
        }
    }

    @Test
    fun persistedAnalyticsOffStartsAndRestartsOrdinaryAndLiveVpnWithoutCapturingRealTraffic() = runBlocking {
        val repository = (context.applicationContext as Yaxc).connectionRepository
        repository.setCaptureEnabled(false)
        repository.setPersistence(false)
        repository.clearHistory()
        compose.waitUntil(10_000) {
            !settings.connectionAnalyticsEnabled && !repository.state.value.captureEnabled &&
                repository.state.value.connections.isEmpty()
        }
        assertFalse(Settings(context).connectionAnalyticsEnabled)
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.android.shell"
        LoopbackTarget().use { livePeer ->
            livePeer.payload[0] = 0x4c
            val liveProfile = Profile(linkId = source.id, name = "Capture Off Live peer", config = profileConfig(livePeer.port))
            liveProfile.id = db.profileDao().create(liveProfile)
            val group = ProxyGroup(id = "capture-off-default", name = "Capture Off", profileIds = listOf(liveProfile.id))
            settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(
                groups = listOf(group), rules = emptyList(), defaultGroupId = group.id,
            ))
            listOf(profile.id, LiveRoutingHelper.CONNECTION_PROFILE_ID).forEach { selected ->
                val expectedPeer = if (selected == profile.id) target else livePeer
                val otherPeer = if (selected == profile.id) livePeer else target
                settings.selectedProfile = selected
                start()
                compose.waitUntil(10_000) { repository.state.value.status == ConnectionCaptureStatus.Disabled }
                repeat(2) { pass ->
                    val before = JSONObject(XrayCore.connectionSnapshot())
                    assertFalse("Startup installed analytics: $before", before.getBoolean("enabled"))
                    assertTrue(before.getBoolean("running"))
                    assertEquals(0, before.getJSONArray("connections").length())
                    val bytesBefore = tunByteCounts(checkNotNull(routedNetwork))
                    repeat(3) { request ->
                        val stage = "persisted-off-$selected-$pass-$request"
                        assertArrayEquals(expectedPeer.payload, transferTunHttp(stage, target.port, "198.18.0.1"))
                        assertEquals("/vpn-$stage", expectedPeer.requests.poll(5, TimeUnit.SECONDS)!!.path)
                        assertNull("Off changed the selected ordinary/Live route", otherPeer.requests.poll())
                    }
                    val after = JSONObject(XrayCore.connectionSnapshot())
                    assertEquals("Off startup admitted observations", before.toString(), after.toString())
                    assertTunBytesIncrease("persisted Off $selected/$pass", bytesBefore, tunByteCounts(checkNotNull(routedNetwork)))
                    assertTrue(repository.state.value.connections.isEmpty())
                    assertFalse(repository.state.value.captureEnabled)
                    assertFalse(Settings(context).connectionAnalyticsEnabled)
                    assertEquals(selected, settings.selectedProfile)
                    if (pass == 0) {
                        val previousNetwork = routedNetwork
                        TProxyService.stop(context)
                        await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
                        requireSocksListenerReleased()
                        start()
                        compose.waitUntil(10_000) { repository.state.value.status == ConnectionCaptureStatus.Disabled }
                        val restarted = JSONObject(XrayCore.connectionSnapshot())
                        assertNotEquals("Restart did not replace VPN Network", previousNetwork, routedNetwork)
                        assertEquals("History epoch changed on restart", before.getString("epoch"), restarted.getString("epoch"))
                        assertEquals("Restart changed capture generation", before.getLong("captureGeneration"),
                            restarted.getLong("captureGeneration"))
                    }
                }
                TProxyService.stop(context)
                await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
                requireSocksListenerReleased()
            }
        }
    }

    @Test
    fun connectionAnalyticsTracksRealAppAndConfigAcrossLiveRoutingTcpUdpAndActualScreen() = runBlocking {
        settings.connectionHistoryOnDisk = false
        settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
        settings.socksUsername = "analytics-fixture"
        settings.socksPassword = "analytics-fixture-password"
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.android.shell"
        db.configDao().update(Config(
            routingMode = Config.Mode.Replace,
            routing = """{"rules":[{"port":"53,853","outboundTag":"block"}]}""",
        ))
        val repository = (context.applicationContext as Yaxc).connectionRepository
        val shellUid = context.packageManager.getApplicationInfo("com.android.shell", 0).uid
        suspend fun awaitFlow(predicate: (ConnectionRecord) -> Boolean): ConnectionRecord = withTimeout(15_000) {
            repository.state.first { state -> state.connections.any(predicate) }.connections.first(predicate)
        }
        LoopbackTarget().use { first ->
            LoopbackTarget().use { second ->
                UdpEchoPeer(0x53).use { udp ->
                    ChainedStreamTarget(frames = 3).use { stream ->
                        suspend fun member(name: String, port: Int): Profile =
                            Profile(linkId = source.id, name = name, config = profileConfig(port)).also {
                                it.id = db.profileDao().create(it)
                            }
                        val retained = member("Analytics retained TCP", stream.port)
                        val a = member("Analytics HTTP A", first.port)
                        val b = member("Analytics HTTP B", second.port)
                        val udpMember = member("Analytics UDP", udp.port)
                        val streamGroup = ProxyGroup(id = "analytics-stream", name = "Retained", profileIds = listOf(retained.id))
                        var httpGroup = ProxyGroup(id = "analytics-http", name = "HTTP",
                            profileIds = listOf(a.id, b.id), selectedProfileId = a.id)
                        val udpGroup = ProxyGroup(id = "analytics-udp", name = "UDP", profileIds = listOf(udpMember.id))
                        fun save(default: String = streamGroup.id) {
                            settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(
                                groups = listOf(streamGroup, httpGroup, udpGroup),
                                rules = listOf(AppDomainRule(id = "analytics-http-rule", packages = listOf("com.android.shell"),
                                    domains = listOf("full:analytics.invalid"), target = httpGroup.id)),
                                defaultGroupId = default,
                            ))
                        }
                        save()
                        start()
                        val network = routedNetwork
                        var expectedUpload = 0L
                        var expectedDownload = 0L
                        var cumulativePayload = 0
                        var streamId = ""
                        ShellTcpFlow(stream.port).use { flow ->
                            suspend fun exchange(stage: String, expectActive: Boolean = true) {
                                val payload = "$stage 東京\\n".toByteArray()
                                flow.assertNextChainedResponse(payload)
                                assertArrayEquals(payload, stream.received.poll(5, TimeUnit.SECONDS))
                                cumulativePayload += payload.size
                                expectedUpload += 4 + payload.size
                                expectedDownload += 4 + cumulativePayload
                                val observed = awaitFlow {
                                    it.profileId == retained.id && it.uid == shellUid && it.upload == expectedUpload
                                }
                                streamId = observed.id
                                assertEquals(retained.name, observed.profileName)
                                assertEquals(LiveRoutingHelper.profileFingerprint(retained.config), observed.profileFingerprint)
                                assertTrue(observed.apps.any { it.packageName == "com.android.shell" })
                                assertTrue(observed.download <= expectedDownload)
                                if (observed.liveBytesExact) assertEquals(expectedDownload, observed.download)
                                if (expectActive) assertEquals("active", observed.state)
                                assertEquals(network, routedNetwork)
                            }
                            exchange("before-selection")
                            assertArrayEquals(first.payload, transferTunHttp("analytics-a", first.port, "analytics.invalid"))
                            assertEquals("/vpn-analytics-a", first.requests.poll(5, TimeUnit.SECONDS)!!.path)
                            val oldHttp = awaitFlow { it.profileId == a.id && it.uid == shellUid && it.domain == "analytics.invalid" }
                            httpGroup = httpGroup.copy(selectedProfileId = b.id)
                            save()
                            TProxyService.applyRouting(context)
                            await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
                            assertArrayEquals(second.payload, transferTunHttp("analytics-b", first.port, "analytics.invalid"))
                            assertEquals("/vpn-analytics-b", second.requests.poll(5, TimeUnit.SECONDS)!!.path)
                            val newHttp = awaitFlow { it.profileId == b.id && it.uid == shellUid && it.domain == "analytics.invalid" }
                            assertEquals(a.name, repository.state.value.connections.single { it.id == oldHttp.id }.profileName)
                            assertEquals(b.name, newHttp.profileName)
                            exchange("after-selection")

                            save(udpGroup.id)
                            TProxyService.applyRouting(context)
                            await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
                            requireUdpReply(udp, "analytics-udp")
                            val datagram = awaitFlow {
                                it.profileId == udpMember.id && it.uid == shellUid && it.protocol == "udp" &&
                                    it.upload == 512L && it.download == 513L
                            }
                            assertEquals("association", datagram.destinationKind)
                            assertTrue(datagram.apps.any { it.packageName == "com.android.shell" })
                            exchange("after-default-and-udp", expectActive = false)

                            ActivityScenario.launch(MainActivity::class.java).use { activity ->
                                lateinit var model: ConnectionAnalyticsViewModel
                                activity.onActivity { model = ViewModelProvider(it)[ConnectionAnalyticsViewModel::class.java] }
                                compose.onNodeWithTag("main:connection-analytics").performClick()
                                compose.waitUntil(10_000) {
                                    model.uiState.value.summaries.any { "com.android.shell" in it.identity.packages }
                                }
                                assertTrue("Root must not flatten every captured flow", model.uiState.value.flows.isEmpty())
                                val shellSummary = model.uiState.value.summaries.single { "com.android.shell" in it.identity.packages }
                                compose.onNodeWithTag("analytics:list").performScrollToKey(shellSummary.key)
                                compose.onNodeWithTag(shellSummary.key).assertIsDisplayed()
                                captureActivitySurface(activity, "analytics-by-app-root")
                                compose.onNodeWithTag(shellSummary.key).performClick()
                                compose.waitUntil(10_000) {
                                    model.uiState.value.query.selection == shellSummary.identity &&
                                        model.uiState.value.flows.any { it.record.id == streamId }
                                }
                                assertTrue(model.uiState.value.summaries.isEmpty())
                                assertTrue(model.uiState.value.flows.all { it.record.uid == shellUid })
                                assertTrue(model.uiState.value.flows.any { it.record.id == datagram.id && it.record.protocol == "udp" })
                                compose.onNodeWithTag("analytics:list").performScrollToKey("flow:$streamId")
                                compose.onNodeWithTag("flow:$streamId").assertIsDisplayed()
                                    .assertTextContains(retained.name, substring = true)
                                captureActivitySurface(activity, "analytics-by-app-flows")
                                compose.onNodeWithTag("flow:$streamId").performClick()
                                compose.onNodeWithTag("analytics:detail")
                                    .assertTextContains(retained.name, substring = true)
                                    .assertTextContains("com.android.shell", substring = true)
                                    .assertTextContains(LiveRoutingHelper.profileFingerprint(retained.config), substring = true)
                                captureActivitySurface(activity, "analytics-flow-detail")
                                compose.onNodeWithTag("analytics:copy").performClick()
                                activity.onActivity {
                                    val clipboard = it.getSystemService(android.content.ClipboardManager::class.java)
                                    val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
                                    assertTrue(copied.contains(retained.name))
                                    assertTrue(copied.contains("UID $shellUid"))
                                    assertFalse(copied.contains(settings.socksPassword))
                                }
                                compose.onNodeWithText(context.getString(R.string.ca_done)).performClick()
                                compose.onNodeWithTag("flow:$streamId").assertIsDisplayed()
                                compose.onNodeWithTag("analytics:list").performScrollToKey("controls")
                                compose.onNodeWithTag("analytics:filter:Closed").performClick()
                                compose.onNodeWithTag("analytics:search").performTextReplacement("analytics.invalid")
                                val opposite = model.uiState.value.oppositeIdentities.indexOfFirst { it.title == a.name }
                                assertTrue(opposite >= 0)
                                compose.onNodeWithTag("analytics:choice:opposite").performClick()
                                compose.onNodeWithTag("analytics:option:opposite:${opposite + 1}").performClick()
                                compose.waitUntil(10_000) {
                                    val state = model.uiState.value
                                    state.query.filter == ConnectionFilter.Closed && state.query.search == "analytics.invalid" &&
                                        state.flows.any { it.record.id == oldHttp.id } &&
                                        state.flows.all { it.record.state != "active" && it.record.profileId == a.id }
                                }
                                compose.onNodeWithTag("analytics:list").performScrollToKey("flow:${oldHttp.id}")
                                compose.onNodeWithTag("flow:${oldHttp.id}").assertIsDisplayed()
                                captureActivitySurface(activity, "analytics-selected-filters")
                                compose.onNodeWithTag("analytics:back").performClick()
                                compose.waitUntil(10_000) {
                                    val query = model.uiState.value.query
                                    query.selection == null && query.grouping == ConnectionGrouping.App && query.rootFilter == ConnectionFilter.All
                                }
                                compose.onNodeWithTag(shellSummary.key).assertIsDisplayed()
                                compose.onNodeWithTag("analytics:list").performScrollToKey("controls")
                                compose.onNodeWithTag("analytics:group:Config").performClick()
                                compose.onNodeWithTag("analytics:filter:Closed").performClick()
                                compose.waitUntil(10_000) {
                                    val state = model.uiState.value
                                    state.query.grouping == ConnectionGrouping.Config && state.query.rootFilter == ConnectionFilter.Closed &&
                                        state.summaries.any { it.identity.title == a.name }
                                }
                                val capturedConfig = model.uiState.value.summaries.single { it.identity.title == a.name }
                                compose.onNodeWithTag("analytics:list").performScrollToKey(capturedConfig.key)
                                captureActivitySurface(activity, "analytics-by-config-root")
                                compose.onNodeWithTag(capturedConfig.key).performClick()
                                compose.waitUntil(10_000) {
                                    model.uiState.value.flows.any { it.record.id == oldHttp.id } &&
                                        model.uiState.value.flows.all { it.record.profileId == a.id && it.record.state != "active" }
                                }
                                compose.onNodeWithTag("analytics:list").performScrollToKey("flow:${oldHttp.id}")
                                compose.onNodeWithTag("flow:${oldHttp.id}").assertIsDisplayed()
                                    .assertTextContains("com.android.shell", substring = true)
                                compose.onNodeWithTag("flow:${oldHttp.id}").performClick()
                                compose.onNodeWithTag("analytics:detail")
                                    .assertTextContains(a.name, substring = true)
                                    .assertTextContains(oldHttp.profileFingerprint, substring = true)
                                compose.onNodeWithText(context.getString(R.string.ca_done)).performClick()
                                compose.onNodeWithTag("flow:${oldHttp.id}").assertIsDisplayed()
                                captureActivitySurface(activity, "analytics-by-config-flows")
                                compose.onNodeWithTag("analytics:back").performClick()
                                compose.waitUntil(10_000) { model.uiState.value.query.selection == null }
                                compose.onNodeWithTag(capturedConfig.key).assertIsDisplayed()
                                assertEquals(ConnectionFilter.Closed, model.uiState.value.query.rootFilter)
                                compose.onNodeWithTag("analytics:history").performClick()
                                compose.onNodeWithTag("analytics:persistence").performScrollTo().performClick()
                                val disk = File(context.noBackupFilesDir, "connection-history.json")
                                compose.waitUntil(10_000) { settings.connectionHistoryOnDisk && repository.state.value.persistOnDisk && disk.isFile }
                                val checkpoint = disk.readText()
                                val saved = JSONObject(checkpoint).getJSONArray("connections")
                                val savedStream = (0 until saved.length()).map { saved.getJSONObject(it) }.single { it.getString("id") == streamId }
                                assertEquals(retained.id, savedStream.getLong("profileId"))
                                assertEquals(retained.name, savedStream.getString("profileName"))
                                assertFalse("Connection history leaked proxy credentials", checkpoint.contains(settings.socksPassword))
                                captureActivitySurface(activity, "analytics-history")
                                compose.onNodeWithTag("analytics:persistence").performClick()
                                compose.waitUntil(10_000) { !settings.connectionHistoryOnDisk && !repository.state.value.persistOnDisk && !disk.exists() }
                                assertTrue(repository.state.value.connections.any { it.id == streamId })
                                compose.onNodeWithText(context.getString(R.string.ca_done)).performClick()
                                assertArrayEquals(second.payload, transferTunHttp("analytics-after-persistence", first.port, "analytics.invalid"))
                                assertEquals("/vpn-analytics-after-persistence", second.requests.poll(5, TimeUnit.SECONDS)!!.path)
                                compose.onNodeWithTag("analytics:back").performClick()
                                compose.onNodeWithTag("main:connection-analytics").assertIsDisplayed()
                                compose.onNodeWithTag("main:connection-analytics").performClick()
                                compose.waitUntil(10_000) {
                                    val query = model.uiState.value.query
                                    query.selection == null && query.grouping == ConnectionGrouping.Config && query.rootFilter == ConnectionFilter.Closed
                                }
                                compose.onNodeWithTag("analytics:back").performClick()
                            }
                        }
                        stream.awaitFinished()
                        val closed = awaitFlow { it.id == streamId && it.state != "active" && it.liveBytesExact }
                        assertEquals(expectedUpload, closed.upload)
                        assertEquals(expectedDownload, closed.download)
                        assertEquals(retained.id, closed.profileId)
                        assertTrue(closed.endedAt >= closed.startedAt)
                        assertEquals(network, routedNetwork)
                        TProxyService.stop(context)
                        await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
                        withTimeout(15_000) { repository.state.first { it.status == ConnectionCaptureStatus.Stopped } }
                        assertEquals(retained.id, repository.state.value.connections.single { it.id == streamId }.profileId)
                        assertFalse(settings.connectionHistoryOnDisk)
                    }
                }
            }
        }
    }

    @Test
    fun importedDirectDomainPersistsCoreOnlyModeAndRoutesOrdinaryAndLiveTrafficToRealPeers() = runBlocking {
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.android.shell"
        settings.antifilterEnabled = false
        // Android rejects loopback VPN DNS servers; reserved addresses stay blocked by the fixture.
        settings.primaryDns = "192.0.2.53"
        settings.secondaryDns = "192.0.2.54"
        settings.connectionHistoryOnDisk = false
        val coreBeforeImport = """{"domainStrategy":"AsIs","rules":[
            {"port":"53,853","outboundTag":"block"},
            {"domain":["full:proxy.invalid","full:mode-only.invalid"],"outboundTag":"proxy"}
        ]}"""
        db.configDao().update(Config(
            routingMode = Config.Mode.Disable,
            routing = coreBeforeImport,
            dnsMode = Config.Mode.Replace,
            dns = """{"hosts":{"localhost":"127.0.0.1"},"servers":["192.0.2.53"]}""",
            outboundsMode = Config.Mode.Merge,
            outbounds = """[{"tag":"direct","protocol":"freedom","settings":{"domainStrategy":"UseIPv4"}}]""",
        ))
        LoopbackTarget().use { direct ->
            // Distinct real bytes make an accidental proxy redirect incapable of passing Direct.
            direct.payload[0] = 0x44
            target.payload[0] = 0x50
            val candidate = JSONObject(profileConfig())
            val relay = LoopbackConnectProxy(target.port).also { routingRelay = it }
            candidate.getJSONArray("outbounds").put(0, JSONObject(
                """{"tag":"proxy","protocol":"http","settings":{"servers":[{"address":"127.0.0.1","port":${relay.port}}]}}""",
            ))
            candidate.getJSONArray("outbounds").put(JSONObject(
                """{"tag":"direct","protocol":"freedom","settings":{"domainStrategy":"UseIPv4"}}""",
            ))
            candidate.put("routing", JSONObject("""{"domainStrategy":"IPIfNonMatch","rules":[
                {"domain":["full:localhost"],"outboundTag":"direct"},
                {"domain":["full:mode-only.invalid"],"outboundTag":"fixture-blocked"},
                {"domain":["full:proxy.invalid"],"outboundTag":"proxy"},
                {"network":"tcp,udp","outboundTag":"fixture-blocked"}
            ]}"""))
            profile = profile.copy(name = "Selected direct-domain import source", config = candidate.toString())
            db.profileDao().update(profile)
            val untouchedSource = db.profileDao().find(profile.id)

            ActivityScenario.launch(CoreRoutingActivity::class.java).use { activity ->
                lateinit var editor: CoreRoutingEditorViewModel
                activity.onActivity { editor = ViewModelProvider(it)[CoreRoutingEditorViewModel::class.java] }
                compose.waitUntil(10_000) { !editor.isLoading && !editor.isBusy }
                fun importSelectedDirectRule(screenshotName: String) {
                    compose.onNodeWithTag("core-routing:actions").performClick()
                    compose.onNodeWithText(context.getString(R.string.coreImportTitle)).performClick()
                    compose.onNodeWithTag("core-import:profile:${profile.id}").performScrollTo().performClick()
                    compose.waitUntil(10_000) { editor.importPreview?.profile?.id == profile.id && !editor.isBusy }
                    compose.onNodeWithTag("core-import:rule:0").performScrollTo().performClick()
                    captureActivitySurface(activity, screenshotName)
                    compose.onNodeWithTag("core-import:confirm").performClick()
                    compose.waitUntil(10_000) { !editor.importVisible && !editor.isBusy }
                }
                fun saveFromMenu() {
                    val revision = editor.savedRevision
                    compose.onNodeWithTag("core-routing:actions").performClick()
                    compose.onNodeWithText(context.getString(R.string.save)).performClick()
                    compose.waitUntil(10_000) { editor.savedRevision > revision && !editor.isBusy }
                }
                importSelectedDirectRule("core-routing-import-preview")
                val persistedBeforeSave = JSONObject(db.configDao().get()!!.routing)
                val persistedRules = persistedBeforeSave.getJSONArray("rules")
                assertEquals("Import must remain a draft until Save", listOf("block", "proxy"),
                    (0 until persistedRules.length()).map { persistedRules.getJSONObject(it).getString("outboundTag") })
                assertEquals("AsIs", persistedBeforeSave.getString("domainStrategy"))
                assertEquals("53,853", persistedRules.getJSONObject(0).getString("port"))
                assertEquals(listOf("full:proxy.invalid", "full:mode-only.invalid"),
                    persistedRules.getJSONObject(1).getJSONArray("domain").let { domains ->
                        (0 until domains.length()).map(domains::getString)
                    })
                assertEquals(Config.Mode.Disable, db.configDao().get()!!.routingMode)
                assertEquals(untouchedSource, db.profileDao().find(profile.id))
                compose.onNodeWithTag("core-routing:mode:Replace").performClick().assertIsSelected()
                captureActivitySurface(activity, "core-routing-core-only-mode")
                saveFromMenu()
                assertEquals(Config.Mode.Replace, db.configDao().get()!!.routingMode)
                val firstSaved = db.configDao().get()!!.routing
                assertEquals(3, JSONObject(firstSaved).getJSONArray("rules").length())
                assertEquals("AsIs", JSONObject(firstSaved).getString("domainStrategy"))
                assertEquals(untouchedSource, db.profileDao().find(profile.id))

                activity.recreate()
                activity.onActivity { editor = ViewModelProvider(it)[CoreRoutingEditorViewModel::class.java] }
                compose.waitUntil(10_000) { !editor.isLoading && !editor.isBusy }
                compose.onNodeWithTag("core-routing:mode:Replace").assertIsSelected()
                saveFromMenu()
                assertEquals(Config.Mode.Replace, db.configDao().get()!!.routingMode)
                importSelectedDirectRule("core-routing-reimport-preview")
                saveFromMenu()
                val saved = db.configDao().get()!!
                assertEquals(Config.Mode.Replace, saved.routingMode)
                val rules = JSONObject(saved.routing).getJSONArray("rules")
                assertEquals("Re-import duplicated a selected rule", 3, rules.length())
                assertEquals("53,853", rules.getJSONObject(0).getString("port"))
                assertEquals("full:localhost", rules.getJSONObject(2).getJSONArray("domain").getString(0))
                assertEquals(untouchedSource, db.profileDao().find(profile.id))
            }
            // A fresh activity must reload the saved choice, not merely retain a ViewModel field.
            ActivityScenario.launch(CoreRoutingActivity::class.java).use { activity ->
                lateinit var editor: CoreRoutingEditorViewModel
                activity.onActivity { editor = ViewModelProvider(it)[CoreRoutingEditorViewModel::class.java] }
                compose.waitUntil(10_000) { !editor.isLoading }
                compose.onNodeWithTag("core-routing:mode:Replace").assertIsSelected()
                captureActivitySurface(activity, "core-routing-restored-mode")
            }

            val repository = (context.applicationContext as Yaxc).connectionRepository
            suspend fun requireRoute(stage: String, host: String, peer: LoopbackTarget, route: String, throughTun: Boolean = false) {
                val before = repository.state.value.connections.mapTo(HashSet()) { it.id }
                val actual = if (throughTun) transferTunHttp(stage, direct.port, host) else transferSocksHttp(stage, direct.port, host)
                assertArrayEquals("Wrong real response for $stage", peer.payload, actual)
                val request = peer.requests.poll(5, TimeUnit.SECONDS)
                assertNotNull("No HTTP request at the expected controlled peer for $stage", request)
                assertEquals("/vpn-$stage", request!!.path)
                assertNull("Request also reached the wrong peer", (if (peer === direct) target else direct).requests.poll())
                val observed = withTimeout(15_000) {
                    repository.state.first { state ->
                        state.connections.any {
                            it.id !in before && it.domain == host && it.route == route && it.upload > 0 && it.download > 0
                        }
                    }.connections.first { it.id !in before && it.domain == host && it.route == route && it.upload > 0 && it.download > 0 }
                }
                assertEquals("tcp", observed.protocol)
                assertTrue(observed.upload > 0)
                assertTrue(observed.download > 0)
                if (route == "proxy") {
                    assertEquals("$host:${direct.port}", relay.destinations.poll(5, TimeUnit.SECONDS))
                } else {
                    assertNull("Direct traffic unexpectedly used the HTTP proxy hop", relay.destinations.poll())
                }
            }
            start()
            requireRoute("ordinary-imported-direct", "localhost", direct, "direct")
            requireRoute("ordinary-distinct-proxy", "proxy.invalid", target, "proxy")
            // Profile-only would block this domain; an accidental Merge would retain its earlier block.
            requireRoute("ordinary-core-only-discards-profile-rule", "mode-only.invalid", target, "proxy")
            requireRoute("ordinary-tun-proxy", "proxy.invalid", target, "proxy", throughTun = true)
            TProxyService.stop(context)
            await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)

            val group = ProxyGroup(id = "import-live-proxy", name = "Imported routing controlled proxy", profileIds = listOf(profile.id))
            settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(groups = listOf(group), defaultGroupId = group.id))
            settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
            start()
            requireRoute("live-imported-direct", "localhost", direct, "direct")
            requireRoute("live-distinct-proxy", "proxy.invalid", target, "proxy")
            val network = routedNetwork
            val override = AppDomainRule(id = "import-live-override", domains = listOf("full:localhost"), target = group.id)
            settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(listOf(group), listOf(override), group.id))
            TProxyService.applyRouting(context)
            await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
            requireRoute("live-rule-overrides-imported-direct", "localhost", target, "proxy")
            assertEquals("Live rule update replaced the tunnel", network, routedNetwork)
            assertEquals(untouchedSource, db.profileDao().find(profile.id))
            assertEquals(Config.Mode.Replace, db.configDao().get()!!.routingMode)
            assertNull(ConnectionFailureState.failure.value)
            assertTrue(direct.failures.toString(), direct.failures.isEmpty())
            assertTrue(target.failures.toString(), target.failures.isEmpty())
            assertTrue(relay.failures.toString(), relay.failures.isEmpty())
        }
    }

    @Test
    fun ordinaryVpnIgnoresSavedLiveRulesAndSurvivesSubscriptionMemberRemoval() = runBlocking {
        // Permit these sniffed domains in the ordinary profile itself; its IP-only fixture rule
        // otherwise falls through to the fixture's catch-all block after HTTP sniffing.
        val ordinaryConfig = JSONObject(profile.config)
        ordinaryConfig.getJSONObject("routing").getJSONArray("rules").put(0, JSONObject(
            """{"domain":["full:saved-block.invalid","full:saved-redirect.invalid"],"port":"${target.port}","network":"tcp","outboundTag":"proxy"}""",
        ))
        profile = profile.copy(config = ordinaryConfig.toString())
        db.profileDao().update(profile)
        LoopbackTarget().use { redirected ->
            val member = Profile(linkId = source.id, name = "Disposable live member", config = profileConfig(redirected.port))
            member.id = db.profileDao().create(member)
            val group = ProxyGroup(id = "saved-live-group", name = "Saved independent routing",
                profileIds = listOf(member.id))
            val rules = listOf(
                AppDomainRule(id = "saved-block", packages = listOf("com.android.shell"),
                    domains = listOf("full:saved-block.invalid"), target = "block"),
                AppDomainRule(id = "saved-redirect", packages = listOf("com.android.shell"),
                    domains = listOf("full:saved-redirect.invalid"), target = group.id),
            )
            val savedPlan = LiveRoutingHelper.encode(LiveRoutingPlan(listOf(group), rules, group.id))
            settings.liveRoutingPlan = savedPlan
            fun requireOrdinary(stage: String) {
                listOf("saved-block.invalid", "saved-redirect.invalid").forEachIndexed { index, host ->
                    assertArrayEquals(target.payload, transferTunHttp("$stage-$index", target.port, host))
                    assertEquals("/vpn-$stage-$index", target.requests.poll(5, TimeUnit.SECONDS)!!.path)
                }
                assertNull("Saved Live Routing redirect received ordinary VPN traffic", redirected.requests.poll())
                val liveStatus = LiveRoutingHelper.decodeRuntimeStatus(XrayCore.liveRoutingStatus())
                assertTrue("Ordinary VPN native core is not running", liveStatus.running)
                assertTrue(liveStatus.groups.isEmpty())
                assertEquals(profile.id, settings.selectedProfile)
                assertEquals(savedPlan, settings.liveRoutingPlan)
                assertNull(ConnectionFailureState.failure.value)
                assertTrue(TProxyService.isActive())
            }

            start()
            requireOrdinary("saved-rules-ignored")
            val unknownOwner = withTimeout(15_000) {
                (context.applicationContext as Yaxc).connectionRepository.state.first { state ->
                    state.connections.any { it.profileId == profile.id && it.uid == -1 }
                }.connections.first { it.profileId == profile.id && it.uid == -1 }
            }
            assertTrue("No-auth traffic must not be assigned to a guessed application", unknownOwner.apps.isEmpty())
            val applied = settings.xrayConfig().readBytes()
            TProxyService.applyRouting(context)
            awaitUnchangedAcknowledgement(profile.name)
            assertArrayEquals(applied, settings.xrayConfig().readBytes())
            requireOrdinary("ordinary-apply-ignored")

            // The real subscription now contains only the ordinary profile. Its identity remains,
            // while the saved Live Routing member disappears without rewriting the saved plan.
            ReloadSubscriptionTarget(JSONObject(profile.config).put("remarks", profile.name).toString()).use { subscription ->
                source.address = subscription.url
                db.linkDao().update(source)
                assertEquals(1, SubscriptionRefreshHelper(context, settings).refreshLinks(source.id).refreshed)
                subscription.awaitServed()
                awaitUnchangedAcknowledgement(profile.name)
                assertNull(db.profileDao().indexOf(member.id))
                assertEquals(profile.id, db.profileDao().linkProfiles(source.id).single().id)
                assertArrayEquals(applied, settings.xrayConfig().readBytes())
                requireOrdinary("after-member-refresh")
            }

            TProxyService.applyRouting(context)
            awaitUnchangedAcknowledgement(profile.name)
            requireOrdinary("ordinary-apply-with-missing-member")
            TProxyService.restart(context)
            await(TProxyService.START_VPN_SERVICE_ACTION_NAME)
            requireOrdinary("ordinary-restart-with-missing-member")
            TProxyService.stop(context)
            await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            assertFalse(TProxyService.isActive())
            requireSocksListenerReleased()

            settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
            TProxyService.start(context, true)
            val failure = withTimeout(15_000) { ConnectionFailureState.failure.filterNotNull().first() }
            assertTrue(failure.diagnostic, failure.diagnostic.contains("missing profile ${member.id}"))
            assertFalse(TProxyService.isActive())
            requireSocksListenerReleased()
            val liveStatus = LiveRoutingHelper.decodeRuntimeStatus(XrayCore.liveRoutingStatus())
            assertFalse("Rejected Live Routing start acquired runtime", liveStatus.running)
            assertTrue(liveStatus.groups.isEmpty())
            assertArrayEquals(applied, settings.xrayConfig().readBytes())
            assertEquals(savedPlan, settings.liveRoutingPlan)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            assertFalse(events.any {
                it.action == TProxyService.START_VPN_SERVICE_ACTION_NAME ||
                    it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME
            })

            settings.selectedProfile = profile.id
            start()
            requireOrdinary("return-to-ordinary")
            assertArrayEquals(applied, settings.xrayConfig().readBytes())
            assertTrue(redirected.failures.toString(), redirected.failures.isEmpty())
            assertTrue(target.failures.toString(), target.failures.isEmpty())
        }
    }

    @Test
    fun liveAppDomainGroupsSwitchAndBalanceNewFlowsWithoutBreakingEstablishedTunStream() = runBlocking {
        settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.android.shell"
        db.configDao().update(Config(
            routingMode = Config.Mode.Replace,
            routing = """{"rules":[{"port":"53,853","outboundTag":"block"}]}""",
        ))
        settings.socksUsername = "fixture-user"
        settings.socksPassword = "fixture-password"
        LoopbackTarget().use { first ->
            LoopbackTarget().use { second ->
                ChainedStreamTarget(frames = 4).use { stream ->
                    profile = profile.copy(config = profileConfig(stream.port))
                    db.profileDao().update(profile)
                    val otherSource = Link(name = "Synthetic second subscription")
                    otherSource.id = db.linkDao().insertAtEnd(otherSource)
                    try {
                        val a = Profile(linkId = source.id, name = "Synthetic A", config = profileConfig(first.port))
                        a.id = db.profileDao().create(a)
                        val b = Profile(linkId = otherSource.id, name = "Synthetic B", config = profileConfig(second.port))
                        b.id = db.profileDao().create(b)
                        var group = ProxyGroup(id = "live-main", name = "Live mixed sources",
                            profileIds = listOf(a.id, b.id), selectedProfileId = a.id)
                        val other = ProxyGroup(id = "live-other", name = "Other domain", profileIds = listOf(b.id))
                        val streamGroup = ProxyGroup(id = "live-stream", name = "Retained stream",
                            profileIds = listOf(profile.id))
                        val rules = listOf(
                            AppDomainRule(id = "live-rule", packages = listOf("com.android.shell"),
                                domains = listOf("full:live-routing.invalid"), target = group.id),
                            AppDomainRule(id = "other-rule", packages = listOf("com.android.shell"),
                                domains = listOf("full:other-routing.invalid"), target = other.id),
                        )
                        fun save() {
                            settings.liveRoutingPlan = LiveRoutingHelper.encode(
                                LiveRoutingPlan(listOf(streamGroup, group, other), rules, streamGroup.id),
                            )
                        }
                        save()
                        start()
                        val network = routedNetwork
                        ShellTcpFlow(stream.port).use { flow ->
                            fun exchange(stage: String) {
                                val bytes = "$stage 東京\n".toByteArray()
                                flow.assertNextChainedResponse(bytes)
                                assertArrayEquals(bytes, stream.received.poll(5, TimeUnit.SECONDS))
                                assertEquals("Live update replaced TUN network", network, routedNetwork)
                            }
                            exchange("before-live-group-change")
                            assertArrayEquals(first.payload, transferTunHttp("manual-a", first.port, "live-routing.invalid"))
                            assertEquals("/vpn-manual-a", first.requests.poll(5, TimeUnit.SECONDS)!!.path)
                            assertNull(second.requests.poll())
                            assertArrayEquals(second.payload, transferTunHttp("other-domain", first.port, "other-routing.invalid"))
                            assertEquals("/vpn-other-domain", second.requests.poll(5, TimeUnit.SECONDS)!!.path)

                            group = group.copy(selectedProfileId = b.id)
                            save()
                            TProxyService.applyRouting(context)
                            await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
                            assertArrayEquals(second.payload, transferTunHttp("manual-b", first.port, "live-routing.invalid"))
                            assertEquals("/vpn-manual-b", second.requests.poll(5, TimeUnit.SECONDS)!!.path)
                            assertNull(first.requests.poll())
                            exchange("after-manual-group-change")

                            group = group.copy(strategy = ProxyGroupStrategy.RoundRobin)
                            save()
                            TProxyService.applyRouting(context)
                            await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
                            repeat(4) { index ->
                                assertArrayEquals(first.payload, transferTunHttp("balanced-$index", first.port, "live-routing.invalid"))
                            }
                            val firstPaths = mutableListOf<String>()
                            val secondPaths = mutableListOf<String>()
                            while (true) firstPaths += (first.requests.poll()?.path ?: break)
                            while (true) secondPaths += (second.requests.poll()?.path ?: break)
                            assertEquals(2, firstPaths.size)
                            assertEquals(2, secondPaths.size)
                            assertEquals((0..3).map { "/vpn-balanced-$it" }.toSet(), (firstPaths + secondPaths).toSet())
                            exchange("after-live-round-robin")

                            val applied = settings.xrayConfig().readBytes()
                            db.profileDao().delete(b)
                            TProxyService.applyRouting(context)
                            await(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
                            assertNotNull(ConnectionFailureState.failure.value)
                            assertArrayEquals(applied, settings.xrayConfig().readBytes())
                            exchange("after-rejected-missing-member")
                        }
                        stream.awaitFinished()
                        assertEquals(LiveRoutingHelper.CONNECTION_PROFILE_ID, settings.selectedProfile)
                        assertTrue(TProxyService.isActive())
                    } finally {
                        db.linkDao().delete(otherSource)
                    }
                }
            }
        }
    }

    @Test
    fun standaloneDefaultGroupRoutesUnmatchedFlowsAndHotChangesKeepEstablishedTunStream() = runBlocking {
        settings.socksUsername = "fixture-user"
        settings.socksPassword = "fixture-password"
        settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
        // Isolate the shell's fixture traffic from other apps.
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.android.shell"
        db.configDao().update(Config(
            routingMode = Config.Mode.Replace,
            // Android's per-network DNS/DoT validation probes can enter even an include-only VPN.
            // They must not consume the single-accept binary stream listener.
            routing = """{"rules":[{"port":"53,853","outboundTag":"block"},{"domain":["full:core-default.invalid"],"outboundTag":"proxy"}]}""",
        ))
        LoopbackTarget().use { first ->
            LoopbackTarget().use { second ->
                ChainedStreamTarget(frames = 4).use { stream ->
                    val streamProfile = Profile(linkId = source.id, name = "Retained stream", config = profileConfig(stream.port))
                    streamProfile.id = db.profileDao().create(streamProfile)
                    val a = Profile(linkId = source.id, name = "Default A", config = profileConfig(first.port))
                    a.id = db.profileDao().create(a)
                    val b = Profile(linkId = source.id, name = "Default B", config = profileConfig(second.port))
                    b.id = db.profileDao().create(b)
                    val streamGroup = ProxyGroup(id = "stream-group", name = "Stream", profileIds = listOf(streamProfile.id))
                    val aGroup = ProxyGroup(id = "a-group", name = "A", profileIds = listOf(a.id))
                    val bGroup = ProxyGroup(id = "b-group", name = "B", profileIds = listOf(b.id))
                    val balanced = ProxyGroup(id = "balanced-group", name = "Balanced",
                        profileIds = listOf(a.id, b.id), strategy = ProxyGroupStrategy.RoundRobin)
                    val groups = listOf(streamGroup, aGroup, bGroup, balanced)
                    val rules = listOf(
                        AppDomainRule(id = "explicit-rule", packages = listOf("com.android.shell"),
                            domains = listOf("full:explicit-default.invalid"), target = bGroup.id),
                        AppDomainRule(id = "default-rule", domains = listOf("full:default-target.invalid"), target = "proxy"),
                    )
                    fun save(default: String) {
                        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(groups, rules, default))
                    }
                    fun apply(default: String) {
                        save(default)
                        TProxyService.applyRouting(context)
                        await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
                    }
                    fun request(peer: LoopbackTarget, stage: String, host: String = "unmatched.invalid") {
                        assertArrayEquals(peer.payload, transferTunHttp(stage, first.port, host))
                        assertEquals("/vpn-$stage", peer.requests.poll(5, TimeUnit.SECONDS)!!.path)
                    }
                    save(streamGroup.id)
                    start()
                    val network = routedNetwork
                    ShellTcpFlow(stream.port).use { flow ->
                        fun exchange(stage: String) {
                            val bytes = stage.toByteArray()
                            flow.assertNextChainedResponse(bytes)
                            assertArrayEquals(bytes, stream.received.poll(5, TimeUnit.SECONDS))
                            assertEquals("Default group change replaced TUN", network, routedNetwork)
                            assertEquals(LiveRoutingHelper.CONNECTION_PROFILE_ID, settings.selectedProfile)
                        }
                        exchange("standalone-start")
                        apply(aGroup.id)
                        request(first, "unmatched-a")
                        request(second, "explicit-b", "explicit-default.invalid")
                        request(first, "default-target-a", "default-target.invalid")
                        request(first, "core-default-a", "core-default.invalid")
                        exchange("default-a")
                        apply(bGroup.id)
                        request(second, "unmatched-b")
                        exchange("default-b")
                        apply(balanced.id)
                        repeat(4) { index ->
                            assertArrayEquals(first.payload, transferTunHttp("default-balanced-$index", first.port, "unmatched.invalid"))
                        }
                        val firstPaths = List(2) { first.requests.poll(5, TimeUnit.SECONDS)!!.path }
                        val secondPaths = List(2) { second.requests.poll(5, TimeUnit.SECONDS)!!.path }
                        assertEquals((0..3).map { "/vpn-default-balanced-$it" }.toSet(), (firstPaths + secondPaths).toSet())
                        assertNull(first.requests.poll())
                        assertNull(second.requests.poll())
                        exchange("default-round-robin")
                    }
                    stream.awaitFinished()
                }
            }
        }
    }

    @Test
    fun appUidRoutesStandardUdpToUpdatedGroupWithoutRestart() = runBlocking {
        settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.android.shell"
        db.configDao().update(Config(
            routingMode = Config.Mode.Replace,
            routing = """{"rules":[{"port":"53,853","outboundTag":"block"}]}""",
        ))
        settings.socksUsername = "fixture-user"
        settings.socksPassword = "fixture-password"
        settings.socksUdp = true
        UdpEchoPeer(0x41).use { first ->
            UdpEchoPeer(0x42).use { second ->
                fun outbound(port: Int) = """{"outbounds":[{"tag":"proxy","protocol":"freedom","settings":{"redirect":"127.0.0.1:$port","finalRules":[{"action":"allow","ip":["127.0.0.1/32"],"port":"$port"}]}}]}"""
                val a = Profile(linkId = source.id, name = "UDP A", config = outbound(first.port))
                a.id = db.profileDao().create(a)
                val b = Profile(linkId = source.id, name = "UDP B", config = outbound(second.port))
                b.id = db.profileDao().create(b)
                val rule = AppDomainRule(id = "udp-rule", packages = listOf("com.android.shell"), target = "udp-group")
                var group = ProxyGroup(id = "udp-group", name = "UDP group",
                    profileIds = listOf(a.id, b.id), selectedProfileId = a.id)
                settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(listOf(group), listOf(rule), group.id))
                start()
                requireUdpReply(first, "before-live-switch")
                group = group.copy(selectedProfileId = b.id)
                settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(listOf(group), listOf(rule), group.id))
                TProxyService.applyRouting(context)
                await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
                requireUdpReply(second, "after-live-switch")
                assertEquals(LiveRoutingHelper.CONNECTION_PROFILE_ID, settings.selectedProfile)
                TProxyService.stop(context)
                await(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
                requireSocksListenerReleased()
            }
        }
    }

    private fun requireUdpReply(peer: UdpEchoPeer, stage: String) {
        val payload = ByteArray(512) { index -> (index xor stage.hashCode()).toByte() }
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -u -w 5 -W 5 198.18.0.1 29381")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
            DataInputStream(DeadlineInputStream(pipes[0]).apply { beginResponse(10_000) }).use { input ->
                output.write(payload)
                output.flush()
                val actual = try {
                    ByteArray(payload.size + 1).also(input::readFully)
                } catch (error: Exception) {
                    throw AssertionError("UDP stage=$stage peerPackets=${peer.packetsReceived}", error)
                }
                assertArrayEquals(byteArrayOf(peer.marker) + payload, actual)
            }
        }
    }

    private class UdpEchoPeer(value: Int) : Closeable {
        val marker = value.toByte()
        val senders = LinkedBlockingQueue<java.net.SocketAddress>()
        @Volatile var packetsReceived = 0
            private set
        private val socket = DatagramSocket(0, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val executor = Executors.newSingleThreadExecutor()
        private val worker = executor.submit {
            val buffer = ByteArray(513)
            while (!socket.isClosed) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                    packetsReceived++
                    senders.offer(packet.socketAddress)
                    check(packet.length == 512) { "Unexpected UDP fixture size" }
                    val reply = byteArrayOf(marker) + packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                    socket.send(DatagramPacket(reply, reply.size, packet.socketAddress))
                } catch (error: java.net.SocketException) {
                    if (!socket.isClosed) throw error
                }
            }
        }
        override fun close() {
            socket.close()
            worker.get(5, TimeUnit.SECONDS)
            executor.shutdown()
            check(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun start() {
        TProxyService.start(context, true)
        await(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        assertTrue(TProxyService.isActive())
    }

    private fun requireTunTransfer(stage: String) {
        assertArrayEquals(target.payload, transferTunHttp(stage, target.port, "198.18.0.1"))
        val request = target.requests.poll(5, TimeUnit.SECONDS)
        assertNotNull("No exact request reached the controlled VPN peer", request)
        assertEquals("GET", request!!.method)
        assertEquals("/vpn-$stage", request.path)
    }

    private fun transferTunHttp(stage: String, destinationPort: Int, host: String): ByteArray {
        // A shell child owns the original socket; HTTP sniffing supplies the domain independently.
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -w 5 -W 5 198.18.0.1 $destinationPort")
        return ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { request ->
            DataInputStream(DeadlineInputStream(pipes[0]).apply { beginResponse(10_000) }).use { input ->
                request.write("GET /vpn-$stage HTTP/1.1\r\nHost: $host:$destinationPort\r\nConnection: close\r\n\r\n".toByteArray())
                readHttpResponse(input)
            }
        }
    }

    /** Real HTTP CONNECT transport; every upstream socket is pinned to the controlled loopback peer. */
    private class LoopbackConnectProxy(destinationPort: Int) : Closeable {
        private val listener = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val executor = Executors.newCachedThreadPool()
        private val sockets = ConcurrentHashMap.newKeySet<Socket>()
        val destinations = LinkedBlockingQueue<String>()
        val failures = LinkedBlockingQueue<Throwable>()
        val port: Int get() = listener.localPort
        private val acceptor = Thread({
            try {
                while (!listener.isClosed) {
                    val client = listener.accept()
                    sockets += client
                    executor.submit {
                        try {
                            client.use {
                                client.soTimeout = 10_000
                                val input = DataInputStream(client.getInputStream())
                                val headers = buildString {
                                    while (!endsWith("\r\n\r\n")) {
                                        check(length < 16_384) { "CONNECT headers exceed the fixture bound" }
                                        append(input.readUnsignedByte().toChar())
                                    }
                                }
                                val request = headers.substringBefore("\r\n").split(' ')
                                check(request.size == 3 && request[0] == "CONNECT") { "Unexpected HTTP proxy request" }
                                destinations.put(request[1])
                                Socket("127.0.0.1", destinationPort).use { upstream ->
                                    sockets += upstream
                                    try {
                                        check(!listener.isClosed) { "CONNECT proxy is stopping" }
                                        upstream.soTimeout = 10_000
                                        client.getOutputStream().apply {
                                            write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                            flush()
                                        }
                                        val upload = executor.submit {
                                            input.copyTo(upstream.getOutputStream())
                                            upstream.shutdownOutput()
                                        }
                                        upstream.getInputStream().copyTo(client.getOutputStream())
                                        client.shutdownOutput()
                                        upload.get(10, TimeUnit.SECONDS)
                                    } finally {
                                        sockets -= upstream
                                    }
                                }
                            }
                        } catch (error: Exception) {
                            if (!listener.isClosed) failures.offer(error)
                        } finally {
                            sockets -= client
                        }
                    }
                }
            } catch (error: Exception) {
                if (!listener.isClosed) failures.offer(error)
            }
        }, "regression-connect-proxy").apply { start() }

        override fun close() {
            listener.close()
            acceptor.join(5_000)
            sockets.forEach { it.close() }
            executor.shutdownNow()
            check(!acceptor.isAlive) { "CONNECT proxy acceptor did not stop" }
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "CONNECT proxy relay did not stop" }
        }
    }

    private fun transferSocksHttp(stage: String, destinationPort: Int, host: String, authenticate: Boolean = false): ByteArray {
        // The shell owns the facade socket, and CONNECT stays local regardless of the sniffed Host.
        // A Direct route reaches the requested port; Proxy takes a real, separately recorded HTTP hop.
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -w 5 -W 5 127.0.0.1 $socksPort")
        return DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])).use { output ->
            DataInputStream(DeadlineInputStream(pipes[0]).apply { beginResponse(10_000) }).use { input ->
                val method = if (authenticate) 2 else 0
                output.write(byteArrayOf(5, 1, method.toByte()))
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals(method, input.readUnsignedByte())
                if (authenticate) {
                    val username = settings.socksUsername.toByteArray()
                    val password = settings.socksPassword.toByteArray()
                    output.writeByte(1)
                    output.writeByte(username.size)
                    output.write(username)
                    output.writeByte(password.size)
                    output.write(password)
                    output.flush()
                    assertEquals(1, input.readUnsignedByte())
                    assertEquals("SOCKS fixture authentication failed", 0, input.readUnsignedByte())
                }
                output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1))
                output.writeShort(destinationPort)
                output.flush()
                assertEquals(5, input.readUnsignedByte())
                assertEquals("Local SOCKS CONNECT failed", 0, input.readUnsignedByte())
                assertEquals(0, input.readUnsignedByte())
                val addressSize = when (val kind = input.readUnsignedByte()) {
                    1 -> 4
                    3 -> input.readUnsignedByte()
                    4 -> 16
                    else -> error("Unexpected SOCKS address kind $kind")
                }
                input.readFully(ByteArray(addressSize + 2))
                output.write("GET /vpn-$stage HTTP/1.1\r\nHost: $host:$destinationPort\r\nConnection: close\r\n\r\n".toByteArray())
                output.flush()
                readHttpResponse(input)
            }
        }
    }

    private fun readHttpResponse(input: DataInputStream): ByteArray {
        val headers = buildString {
            while (!endsWith("\r\n\r\n")) {
                check(length < 16_384) { "VPN HTTP response headers exceed the fixture bound" }
                append(input.readUnsignedByte().toChar())
            }
        }
        val contentLength = headers.lineSequence()
            .first { it.startsWith("Content-Length:", ignoreCase = true) }
            .substringAfter(':').trim().toInt()
        check(contentLength in 1..16_384)
        return ByteArray(contentLength).also(input::readFully)
    }

    private fun <A : Activity> captureActivitySurface(activity: ActivityScenario<A>, name: String) {
        repeat(2) {
            val committed = java.util.concurrent.CountDownLatch(1)
            activity.onActivity { current ->
                current.window.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                current.window.decorView.invalidate()
            }
            check(committed.await(10, TimeUnit.SECONDS))
        }
        val image = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            File(context.cacheDir, "$name.png").outputStream().use {
                check(image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { image.recycle() }
    }

    private fun profileConfig(destinationPort: Int = target.port): String = """{
        "log":{"loglevel":"none"},
        "outbounds":[
            {"tag":"proxy","protocol":"freedom","settings":{"redirect":"127.0.0.1:$destinationPort","finalRules":[{"action":"allow","ip":["127.0.0.1/32"],"port":"$destinationPort"}]}},
            {"tag":"fixture-blocked","protocol":"blackhole"}
        ],
        "routing":{"domainStrategy":"AsIs","rules":[
            {"ip":["198.18.0.1/32"],"port":"$destinationPort","network":"tcp","outboundTag":"proxy"},
            {"network":"tcp,udp","outboundTag":"fixture-blocked"}
        ]}
    }"""

    private fun awaitUnchangedAcknowledgement(profileName: String) {
        val event = events.poll(15, TimeUnit.SECONDS)
            ?: error("Conditional reload was not acknowledged; failure=${ConnectionFailureState.failure.value}")
        assertEquals("Unchanged reload must not publish a reconnect event", TProxyService.STATUS_VPN_SERVICE_ACTION_NAME, event.action)
        assertTrue(event.getBooleanExtra("isRunning", false))
        assertEquals(profileName, event.getStringExtra("profile"))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertFalse(events.any {
            it.action == TProxyService.START_VPN_SERVICE_ACTION_NAME ||
                it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME ||
                it.action == TProxyService.STOP_VPN_SERVICE_ACTION_NAME
        })
    }

    /** Each write is one exact datagram; the socket remains open across every control operation. */
    private class ShellUdpFlow(port: Int) : Closeable {
        private val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -u -w 120 -W 120 198.18.0.1 $port")
        private val response = DeadlineInputStream(pipes[0])
        private val input = DataInputStream(response)
        private val output = ParcelFileDescriptor.AutoCloseOutputStream(pipes[1])

        fun assertNextResponse(peer: UdpEchoPeer, stage: String): java.net.SocketAddress {
            val payload = ByteArray(512) { index -> (index xor stage.hashCode()).toByte() }
            response.beginResponse(10_000)
            output.write(payload)
            output.flush()
            val actual = try {
                ByteArray(513).also(input::readFully)
            } catch (error: Exception) {
                throw AssertionError("Held UDP association failed at $stage; peerPackets=${peer.packetsReceived}", error)
            }
            assertArrayEquals("$stage: UDP bytes changed", byteArrayOf(peer.marker) + payload, actual)
            return checkNotNull(peer.senders.poll(5, TimeUnit.SECONDS)) { "$stage: UDP peer did not report sender" }
        }

        override fun close() {
            try {
                output.close()
            } finally {
                input.close()
            }
        }
    }

    private fun assertNoLifecycleEvents(stage: String) {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        val pending = mutableListOf<Intent>()
        events.drainTo(pending)
        assertTrue("$stage: unexpected service lifecycle events=${pending.map { it.action }}",
            pending.all { it.action == TProxyService.STATUS_VPN_SERVICE_ACTION_NAME })
    }

    private fun assertVpnNetworkStillPublished(network: Network): String {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.NETWORK_SETTINGS")
        try {
            val capabilities = checkNotNull(manager.getNetworkCapabilities(network)) { "Original VPN Network disappeared" }
            assertTrue(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
            assertEquals(Process.myUid(), capabilities.ownerUid)
            val properties = checkNotNull(manager.getLinkProperties(network)) { "Original VPN routes disappeared" }
            assertTrue(properties.routes.any { it.isDefaultRoute && it.destination.address is Inet4Address })
            return checkNotNull(properties.interfaceName).also { assertTrue(it.startsWith("tun")) }
        } finally {
            automation.dropShellPermissionIdentity()
        }
    }

    private fun assertTunBytesIncrease(stage: String, before: Pair<Long, Long>, after: Pair<Long, Long>) {
        assertTrue("$stage: OS TUN receive bytes did not grow ($before -> $after)", after.first > before.first)
        assertTrue("$stage: OS TUN transmit bytes did not grow ($before -> $after)", after.second > before.second)
    }

    private fun tunByteCounts(network: Network): Pair<Long, Long> {
        val name = assertVpnNetworkStillPublished(network)
        val devices = shell("cat /proc/net/dev").toString(Charsets.UTF_8)
        val line = checkNotNull(devices.lineSequence().firstOrNull { it.substringBefore(':').trim() == name }) {
            "OS TUN counters unavailable for $name: $devices"
        }
        val fields = line.substringAfter(':').trim().split(Regex("\\s+"))
        check(fields.size >= 16) { "Malformed OS TUN counters: $line" }
        return fields[0].toLong() to fields[8].toLong()
    }

    private class ShellTcpFlow(port: Int, idleSeconds: Int = 15) : Closeable {
        // One nc process owns one socket for every exchange. Cumulative replies from
        // a peer that accepts exactly once make a reconnect incapable of satisfying the test.
        private val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -w $idleSeconds -W $idleSeconds 198.18.0.1 $port")
        private val response = DeadlineInputStream(pipes[0])
        private val input = DataInputStream(response)
        private val output = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]))
        private var expected = byteArrayOf()

        fun assertNextChainedResponse(payload: ByteArray) {
            expected += payload
            response.beginResponse(15_000)
            output.writeInt(payload.size)
            output.write(payload)
            output.flush()
            val size = input.readInt()
            check(size in 1..16_384) { "Invalid chained response length: $size" }
            val reply = ByteArray(size).also(input::readFully)
            assertArrayEquals("Established TCP connection lost or payload chain changed", expected, reply)
        }

        override fun close() {
            try {
                output.close()
            } finally {
                input.close()
            }
        }
    }

    private fun await(action: String): Intent {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "Service did not publish $action; failure=${ConnectionFailureState.failure.value?.diagnostic}" }
            val event = events.poll(remaining, TimeUnit.MILLISECONDS)
                ?: error("Service did not publish $action; failure=${ConnectionFailureState.failure.value?.diagnostic}")
            if (event.action == action) {
                if (action == TProxyService.START_VPN_SERVICE_ACTION_NAME) awaitVpnRouting()
                return event
            }
        }
    }

    private fun awaitVpnRouting() {
        // establish()/START acknowledge native ownership, not ConnectivityService's later route
        // publication. The application is excluded from its VPN, so observe other-UID networks.
        // Reject the previous network: a restart must not pass this barrier on stale readiness.
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val networks = LinkedBlockingQueue<Network>()
        val previousNetwork = routedNetwork
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) {
                val capabilities = manager.getNetworkCapabilities(network)
                if (network != previousNetwork &&
                    capabilities?.ownerUid == Process.myUid() &&
                    properties.interfaceName?.startsWith("tun") == true &&
                    properties.routes.any { it.isDefaultRoute && it.destination.address is Inet4Address }) {
                    networks.offer(network)
                }
            }
        }
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.NETWORK_SETTINGS")
        try {
            manager.registerNetworkCallback(NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .setIncludeOtherUidNetworks(true)
                .build(), callback)
        } finally {
            automation.dropShellPermissionIdentity()
        }
        try {
            routedNetwork = checkNotNull(networks.poll(5, TimeUnit.SECONDS)) { "New VPN routes were not published" }
        } finally {
            manager.unregisterNetworkCallback(callback)
        }
    }

    private fun requireSocksListenerReleased() {
        ServerSocket(socksPort, 1, InetAddress.getByName("127.0.0.1")).close()
    }

    private fun shell(command: String): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
}
