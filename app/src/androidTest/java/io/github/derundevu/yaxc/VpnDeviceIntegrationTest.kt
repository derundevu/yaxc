package io.github.derundevu.yaxc

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
import androidx.core.content.ContextCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.testing.ChainedStreamTarget
import io.github.derundevu.yaxc.testing.DeadlineInputStream
import io.github.derundevu.yaxc.testing.LoopbackTarget
import io.github.derundevu.yaxc.testing.ReloadSubscriptionTarget
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Explicit opt-in AND emulator-only: must never take over a physical device's VPN. */
@RunWith(AndroidJUnit4::class)
class VpnDeviceIntegrationTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var db: YaxcDatabase
    private lateinit var source: Link
    private lateinit var profile: Profile
    private lateinit var scenario: ActivityScenario<ComponentActivity>
    private lateinit var target: LoopbackTarget
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
                        SubscriptionRefreshHelper.RefreshResult(1, 1, 0),
                        SubscriptionRefreshHelper(context, settings).refreshLinks(source.id),
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
                            SubscriptionRefreshHelper.RefreshResult(1, 1, 0),
                            SubscriptionRefreshHelper(context, settings).refreshLinks(otherSource.id),
                        )
                        subscription.awaitServed()
                        awaitUnchangedAcknowledgement(renamed)
                        val stored = db.profileDao().linkProfiles(otherSource.id).single()
                        assertEquals(other.id, stored.id)
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
                SubscriptionRefreshHelper.RefreshResult(1, 1, 0),
                SubscriptionRefreshHelper(context, settings).refreshLinks(source.id),
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
    fun liveAppDomainGroupsSwitchAndBalanceNewFlowsWithoutBreakingEstablishedTunStream() = runBlocking {
        settings.socksUsername = "fixture-user"
        settings.socksPassword = "fixture-password"
        settings.liveRoutingPlan = "{}"
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
                        val rules = listOf(
                            AppDomainRule(id = "live-rule", packages = listOf("com.android.shell"),
                                domains = listOf("full:live-routing.invalid"), target = group.id),
                            AppDomainRule(id = "other-rule", packages = listOf("com.android.shell"),
                                domains = listOf("full:other-routing.invalid"), target = other.id),
                        )
                        fun save() {
                            settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(listOf(group, other), rules))
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
                        assertEquals(profile.id, settings.selectedProfile)
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
                settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(listOf(group), listOf(rule)))
                start()
                requireUdpReply(first, "before-live-switch")
                group = group.copy(selectedProfileId = b.id)
                settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(listOf(group), listOf(rule)))
                TProxyService.applyRouting(context)
                await(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
                requireUdpReply(second, "after-live-switch")
                assertEquals(profile.id, settings.selectedProfile)
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
                ByteArray(contentLength).also(input::readFully)
            }
        }
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

    private class ShellTcpFlow(port: Int) : Closeable {
        // This one nc process owns one socket for all four exchanges. Cumulative replies from
        // a peer that accepts exactly once make a reconnect incapable of satisfying the test.
        private val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -w 15 -W 15 198.18.0.1 $port")
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
            check(remaining > 0) { "Service did not publish $action; failure=${ConnectionFailureState.failure.value}" }
            val event = events.poll(remaining, TimeUnit.MILLISECONDS)
                ?: error("Service did not publish $action; failure=${ConnectionFailureState.failure.value}")
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
