package io.github.derundevu.yaxc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
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
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.helper.XrayValidationHelper
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.testing.ChainedStreamTarget
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

    private fun start() {
        TProxyService.start(context, true)
        await(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        assertTrue(TProxyService.isActive())
    }

    private fun requireTunTransfer(stage: String) {
        // UiAutomation's shell child is a different UID from the excluded VPN application.
        // 198.18.0.1 is a synthetic benchmark destination; only the test core redirects it to our peer.
        val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -w 5 -W 5 198.18.0.1 ${target.port}")
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use {
            it.write("GET /vpn-$stage HTTP/1.1\r\nHost: 198.18.0.1:${target.port}\r\nConnection: close\r\n\r\n".toByteArray())
        }
        val response = ParcelFileDescriptor.AutoCloseInputStream(pipes[0]).use { it.readBytes() }
        val separator = "\r\n\r\n".toByteArray()
        val bodyAt = response.indices.firstOrNull { offset ->
            offset + separator.size <= response.size && separator.indices.all { response[offset + it] == separator[it] }
        } ?: error("VPN did not return an HTTP response to the separate UID")
        assertArrayEquals(target.payload, response.copyOfRange(bodyAt + separator.size, response.size))
        val request = target.requests.poll(5, TimeUnit.SECONDS)
        assertNotNull("No exact request reached the controlled VPN peer", request)
        assertEquals("GET", request!!.method)
        assertEquals("/vpn-$stage", request.path)
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
        private val input = DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(pipes[0]))
        private val output = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]))
        private val executor = Executors.newSingleThreadExecutor()
        private var expected = byteArrayOf()

        fun assertNextChainedResponse(payload: ByteArray) {
            expected += payload
            val reply = executor.submit<ByteArray> {
                output.writeInt(payload.size)
                output.write(payload)
                output.flush()
                val size = input.readInt()
                check(size in 1..16_384) { "Invalid chained response length: $size" }
                ByteArray(size).also(input::readFully)
            }.get(15, TimeUnit.SECONDS)
            assertArrayEquals("Established TCP connection lost or payload chain changed", expected, reply)
        }

        override fun close() {
            output.close()
            input.close()
            executor.shutdownNow()
            check(executor.awaitTermination(5, TimeUnit.SECONDS)) { "Shell stream reader did not stop" }
        }
    }

    private fun await(action: String): Intent {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "Service did not publish $action; failure=${ConnectionFailureState.failure.value}" }
            val event = events.poll(remaining, TimeUnit.MILLISECONDS)
                ?: error("Service did not publish $action; failure=${ConnectionFailureState.failure.value}")
            if (event.action == action) return event
        }
    }

    private fun requireSocksListenerReleased() {
        ServerSocket(socksPort, 1, InetAddress.getByName("127.0.0.1")).close()
    }

    private fun shell(command: String): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)).use { it.readBytes() }
}
