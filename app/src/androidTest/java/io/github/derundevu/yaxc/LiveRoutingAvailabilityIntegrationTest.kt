package io.github.derundevu.yaxc

import XrayCore.XrayCore
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
import android.system.ErrnoException
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
import io.github.derundevu.yaxc.helper.AppDomainRule
import io.github.derundevu.yaxc.helper.ConnectionFailureState
import io.github.derundevu.yaxc.helper.LiveRoutingGroupStatus
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.LiveRoutingMemberStatus
import io.github.derundevu.yaxc.helper.LiveRoutingPlan
import io.github.derundevu.yaxc.helper.ProxyGroup
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.testing.DeadlineInputStream
import io.github.derundevu.yaxc.testing.LiveAvailabilityPeer
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Real production-cadence availability tests, explicitly opted into on a disposable emulator.
 * Only shell-owned sockets enter TUN; the regression application and its synthetic peers do not.
 * Uses the production timeout/thresholds and supported user-configurable intervals, never test-only native options.
 */
@RunWith(AndroidJUnit4::class)
class LiveRoutingAvailabilityIntegrationTest {
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var db: YaxcDatabase
    private lateinit var source: Link
    private lateinit var ordinaryProfile: Profile
    private lateinit var ordinary: LiveAvailabilityPeer
    private lateinit var fallback: LiveAvailabilityPeer
    private lateinit var direct: LiveAvailabilityPeer
    private lateinit var previousPreferences: Map<String, *>
    private var previousConfig: Config? = null
    private var configCaptured = false
    private var configChanged = false
    private var previousVpnAppOp: String? = null
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private var networkObserver: TunNetworks? = null
    private var ownsSession = false
    private var registered = false
    private val peers = mutableListOf<LiveAvailabilityPeer>()
    private val files = mutableListOf<SavedFile>()
    private val events = LinkedBlockingQueue<Intent>()
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent) { events.offer(Intent(intent)) }
    }

    @Before
    fun prepareIsolatedAvailabilityVpn() = runBlocking {
        check(InstrumentationRegistry.getArguments().getString("allowVpn") == "true") {
            "Availability VPN tests require allowVpn=true on a dedicated emulator"
        }
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish") {
            "Availability VPN tests refuse physical devices"
        }
        check(Build.VERSION.SDK_INT >= 31) { "Shell-owned bidirectional traffic requires API 31+" }
        context = InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName == "io.github.derundevu.yaxc.regression")
        check(!TProxyService.isActive()) { "Refusing to replace an existing regression session" }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        withNetworkPermission {
            check(manager.allNetworks.none {
                manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
            }) { "Refusing to replace any existing emulator VPN" }
        }
        previousPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all
        settings = Settings(context)
        files += SavedFile.capture(settings.xrayConfig())
        files += SavedFile.capture(settings.tun2socksConfig())
        settings.liveRoutingPlan = "{}"
        settings.liveRoutingHealthIntervalSeconds = Settings.DEFAULT_LIVE_ROUTING_HEALTH_INTERVAL_SECONDS
        settings.transparentProxy = false
        settings.tun2socks = true
        settings.tproxyHotspot = false
        settings.tproxyTethering = false
        settings.antifilterEnabled = false
        settings.enableIpV6 = false
        settings.bypassLan = false
        // Include only the shell UID, never the app that hosts our local HTTP peers.
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "com.android.shell"
        settings.tunOwnerDefense = true
        settings.socksAddress = "127.0.0.1"
        settings.socksPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }.toString()
        settings.socksUsername = "availability-fixture-user"
        settings.socksPassword = "availability-fixture-password"
        settings.refreshLinksOnOpen = false
        ordinary = peer("ordinary-connection")
        fallback = peer("default-outbound")
        direct = peer("direct-outbound")
        // A probe that accidentally bypasses its member reaches a real sentinel, not the Internet.
        settings.pingAddress = "http://127.0.0.1:${fallback.port}/availability-probe"
        db = YaxcDatabase.ref(context)
        previousConfig = db.configDao().get()
        configCaptured = true
        val fixtureConfig = Config(
            outboundsMode = Config.Mode.Replace,
            outbounds = "[${outbound("fallback-sentinel", fallback)},${outbound("proxy", ordinary)}," +
                "${outbound("ordinary-sentinel", ordinary)},${outbound("direct", direct)}," +
                """{"tag":"block","protocol":"blackhole"}]""",
            routingMode = Config.Mode.Replace,
            routing = """{"domainStrategy":"AsIs","rules":[
                {"port":"53,853","outboundTag":"block"},
                {"domain":["full:core-health.invalid"],"outboundTag":"proxy"},
                {"domain":["full:ordinary-control.invalid"],"outboundTag":"ordinary-sentinel"},
                {"domain":["full:fallback-control.invalid"],"outboundTag":"fallback-sentinel"},
                {"domain":["full:direct-control.invalid"],"outboundTag":"direct"}
            ]}""",
        )
        configChanged = true
        if (previousConfig == null) db.configDao().insert(fixtureConfig) else db.configDao().update(fixtureConfig)
        source = Link(name = "isolated-live-availability-${UUID.randomUUID()}")
        source.id = db.linkDao().insertAtEnd(source)
        ordinaryProfile = createProfile("Ordinary connection", ordinary)
        settings.selectedProfile = ordinaryProfile.id
        ConnectionFailureState.clear()
        ContextCompat.registerReceiver(context, receiver, IntentFilter().apply {
            addAction(TProxyService.START_VPN_SERVICE_ACTION_NAME)
            addAction(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
            addAction(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            addAction(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        val appOp = shell("cmd appops get ${context.packageName} ACTIVATE_VPN").toString(Charsets.UTF_8)
        previousVpnAppOp = Regex("ACTIVATE_VPN: (allow|ignore|deny|default|foreground)")
            .find(appOp)?.groupValues?.get(1) ?: "default"
        shell("cmd appops set ${context.packageName} ACTIVATE_VPN allow")
        assertNull("Isolated emulator VPN consent was not granted", VpnService.prepare(context))
        networkObserver = TunNetworks(context)
    }

    @After
    fun restoreOnlyFixtureState() = runBlocking {
        // The opt-in/device/package/active-VPN guards precede every mutation.
        if (!::previousPreferences.isInitialized) return@runBlocking
        var failure: Throwable? = null
        suspend fun cleanup(action: suspend () -> Unit) {
            try { action() } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        cleanup {
            if (ownsSession) {
                TProxyService.stop(context)
                if (TProxyService.isActive()) awaitService(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            }
        }
        cleanup { networkObserver?.close() }
        cleanup { if (registered) context.unregisterReceiver(receiver) }
        cleanup { scenario?.close() }
        // This one source was inserted by this fixture; its own profiles cascade. Never clear a DAO.
        cleanup { if (::source.isInitialized && source.id != 0L) db.linkDao().delete(source) }
        cleanup {
            if (configCaptured && configChanged) {
                previousConfig?.let { db.configDao().update(it) }
                    ?: db.openHelper.writableDatabase.execSQL("DELETE FROM configs WHERE id = ?", arrayOf(1))
            }
        }
        peers.asReversed().forEach { owned -> cleanup { owned.close() } }
        files.asReversed().forEach { saved -> cleanup { saved.restore() } }
        cleanup {
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
            assertTrue("Could not restore regression preferences", editor.commit())
        }
        cleanup { previousVpnAppOp?.let { shell("cmd appops set ${context.packageName} ACTIVATE_VPN $it") } }
        cleanup { ConnectionFailureState.clear() }
        if (failure != null) throw failure!!
    }

    @Test
    fun independentDefaultAndRuleTargetsFailOverRecoverAndFailClosedWithoutReplacingTun() = runBlocking {
        val primaryPeer = peer("primary", 503)
        val backupPeer = peer("backup")
        val primary = createProfile("Preferred primary", primaryPeer)
        val backup = createProfile("Ordered backup", backupPeer)
        val group = healthGroup(primary, backup)
        settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
        savePlan(group, independent = true)
        start()
        val network = networkObserver!!.awaitFirst()
        awaitHealth { it.activeProfileId == backup.id && member(it, backup).state == "available" }
        requireTrapControls("startup-controls")
        requireAllIndependentTargets(backupPeer, "primary-down-startup")
        ShellHeldFlow(primaryPeer.port, EXPLICIT_HOST, backupPeer).use { flow ->
            fun exchange(stage: String) {
                flow.exchange(stage)
                networkObserver!!.assertUnchanged(network)
            }
            exchange("startup-backup-stream")
            val firstFailure = awaitProbe(primaryPeer, 0, 503)
            val secondFailure = awaitProbe(primaryPeer, 0, 503)
            assertProductionInterval(firstFailure, secondFailure)
            awaitObserved(primary, secondFailure) { it.state == "unavailable" }
            assertEquals(backup.id, health().activeProfileId)

            val recovery = primaryPeer.configureProbes(204)
            var previous: LiveAvailabilityPeer.ProbeResponse? = null
            repeat(3) { index ->
                val observation = awaitProbe(primaryPeer, recovery, 204)
                previous?.let { assertProductionInterval(it, observation) }
                previous = observation
                awaitObserved(primary, observation) {
                    it.state == if (index == 2) "available" else "unavailable"
                }
                val expected = if (index == 2) primaryPeer else backupPeer
                assertEquals(if (index == 2) primary.id else backup.id, health().activeProfileId)
                requireAllIndependentTargets(expected, "recovery-success-${index + 1}")
                exchange("held-after-recovery-success-${index + 1}")
            }

            val failing = primaryPeer.configureProbes(503)
            repeat(2) { index ->
                val observation = awaitProbe(primaryPeer, failing, 503)
                awaitObserved(primary, observation) { it.state == if (index == 0) "available" else "unavailable" }
                val expected = if (index == 0) primaryPeer else backupPeer
                assertEquals(if (index == 0) primary.id else backup.id, health().activeProfileId)
                requireAllIndependentTargets(expected, "runtime-failure-${index + 1}")
                exchange("held-after-primary-failure-${index + 1}")
            }

            val backupFailing = backupPeer.configureProbes(503)
            repeat(2) { index ->
                val observation = awaitProbe(backupPeer, backupFailing, 503)
                awaitObserved(backup, observation) { it.state == if (index == 0) "available" else "unavailable" }
                if (index == 0) requireAllIndependentTargets(backupPeer, "backup-first-failure")
                exchange("held-after-backup-failure-${index + 1}")
            }
            awaitHealth { it.state == "unavailable" && it.eligibleProfileIds.isEmpty() && it.activeProfileId == null }
            INDEPENDENT_HOSTS.forEachIndexed { index, host -> requireBlocked("all-down-$index", host) }
            requireNoApplicationRequests()
            // Normal/direct recipients remain reachable by their explicit control rules, so a
            // globally broken VPN or an unreachable sentinel cannot make fail-closed assertions pass.
            requireTrapControls("all-down-controls")
            exchange("held-backup-stream-even-while-all-probes-fail")
            assertEquals(LiveRoutingHelper.CONNECTION_PROFILE_ID, settings.selectedProfile)
        }
        assertPeerHealth()
    }

    @Test
    fun sameProfileEndpointReplacementChecksAgainAndNeverFallsBackToOrdinaryConnection() = runBlocking {
        val originalPeer = peer("original-primary")
        val backupPeer = peer("replacement-backup")
        val replacementPeer = peer("replacement-primary")
        val primary = createProfile("Same logical primary", originalPeer)
        val backup = createProfile("Replacement backup", backupPeer)
        val group = healthGroup(primary, backup)
        savePlan(group, independent = false)
        start()
        val network = networkObserver!!.awaitFirst()
        awaitHealth { it.activeProfileId == primary.id && it.eligibleProfileIds.toSet() == setOf(primary.id, backup.id) }
        requireTrapControls("regular-controls")
        requireRecipient(originalPeer, "original-healthy", EXPLICIT_HOST)
        ShellHeldFlow(originalPeer.port, EXPLICIT_HOST, originalPeer).use { flow ->
            flow.exchange("before-endpoint-replacement")
            val heldGeneration = replacementPeer.configureProbes(204, hold = true)
            val updated = primary.copy(config = profileConfig(replacementPeer))
            db.profileDao().update(updated)
            TProxyService.applyRouting(context)
            awaitService(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
            val fingerprint = LiveRoutingHelper.profileFingerprint(updated.config)
            awaitHealth {
                val replaced = member(it, primary)
                replaced.fingerprint == fingerprint && replaced.state == "checking" &&
                    replaced.lastCheckedAt == 0L && it.activeProfileId == backup.id &&
                    it.eligibleProfileIds == listOf(backup.id)
            }
            val heldProbe = awaitProbeRequest(replacementPeer, heldGeneration)
            requireRecipient(backupPeer, "replacement-is-checking", EXPLICIT_HOST)
            flow.exchange("old-established-primary-during-new-endpoint-check")
            networkObserver!!.assertUnchanged(network)

            // Withhold the real HTTP reply; one native five-second timeout must not inherit
            // the previous endpoint's healthy state, nor make the first-failure member eligible.
            val timedOut = awaitHealth(timeoutMillis = 12_000) {
                val replaced = member(it, primary)
                replaced.fingerprint == fingerprint && replaced.lastCheckedAt >= heldProbe.startedAt &&
                    replaced.state == "checking" && it.activeProfileId == backup.id
            }
            val timeoutDuration = member(timedOut, primary).lastCheckedAt - heldProbe.startedAt
            assertTrue("Expected production 5s timeout, observed ${timeoutDuration}ms", timeoutDuration in 4_000L..9_000L)
            requireRecipient(backupPeer, "replacement-after-real-timeout", EXPLICIT_HOST)
            flow.exchange("old-stream-after-new-endpoint-timeout")

            val restoredGeneration = replacementPeer.configureProbes(204)
            val firstSuccess = awaitProbe(replacementPeer, restoredGeneration, 204)
            awaitObserved(primary, firstSuccess) { it.fingerprint == fingerprint && it.state == "available" }
            assertEquals(primary.id, health().activeProfileId)
            requireRecipient(replacementPeer, "replacement-first-valid-success", EXPLICIT_HOST)
            flow.exchange("old-stream-after-new-endpoint-becomes-healthy")
            networkObserver!!.assertUnchanged(network)

            replacementPeer.configureProbes(503)
            backupPeer.configureProbes(503)
            awaitHealth(timeoutMillis = 35_000) {
                it.state == "unavailable" && it.eligibleProfileIds.isEmpty() &&
                    member(it, primary).state == "unavailable" && member(it, backup).state == "unavailable"
            }
            requireBlocked("regular-all-down-one", EXPLICIT_HOST)
            requireBlocked("regular-all-down-two", EXPLICIT_HOST)
            requireNoApplicationRequests()
            requireTrapControls("regular-all-down-controls")
            flow.exchange("retained-old-primary-stream-after-all-down")
            networkObserver!!.assertUnchanged(network)
            assertEquals("Regular selected connection was changed", ordinaryProfile.id, settings.selectedProfile)
        }
        assertPeerHealth()
    }

    @Test
    fun leastPingAndChangedIntervalMoveNewFlowsWithoutReplacingEstablishedTun() = runBlocking {
        val firstPeer = peer("least-ping-first")
        val secondPeer = peer("least-ping-second")
        firstPeer.configureProbes(204, delayMillis = 700)
        val initialGeneration = secondPeer.configureProbes(204, delayMillis = 20)
        val first = createProfile("First priority but slower", firstPeer)
        val second = createProfile("Second priority but faster", secondPeer)
        val group = healthGroup(first, second).copy(
            strategy = io.github.derundevu.yaxc.helper.ProxyGroupStrategy.LeastPing,
        )
        settings.liveRoutingHealthIntervalSeconds = 5
        settings.selectedProfile = LiveRoutingHelper.CONNECTION_PROFILE_ID
        savePlan(group, independent = true)
        start()
        val network = networkObserver!!.awaitFirst()
        awaitHealth {
            it.activeProfileId == second.id && member(it, first).state == "available" &&
                member(it, second).state == "available"
        }
        requireAllIndependentTargets(secondPeer, "least-ping-initial")
        val initial = awaitProbe(secondPeer, initialGeneration, 204)
        val repeated = awaitProbe(secondPeer, initialGeneration, 204)
        assertTrue("Configured five-second cadence was not used", repeated.elapsedAt - initial.elapsedAt in 4_000L..7_500L)
        ShellHeldFlow(firstPeer.port, EXPLICIT_HOST, secondPeer).use { flow ->
            flow.exchange("least-ping-held-before-change")
            val firstGeneration = firstPeer.configureProbes(204, delayMillis = 20)
            val secondGeneration = secondPeer.configureProbes(204, delayMillis = 700)
            settings.liveRoutingHealthIntervalSeconds = 8
            TProxyService.applyRouting(context)
            awaitService(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
            awaitProbe(firstPeer, firstGeneration, 204)
            val changed = awaitProbe(secondPeer, secondGeneration, 204)
            awaitHealth { it.activeProfileId == first.id }
            requireAllIndependentTargets(firstPeer, "least-ping-after-delay-change")
            flow.exchange("least-ping-held-after-change")
            val next = awaitProbe(secondPeer, secondGeneration, 204)
            assertTrue("Hot-applied eight-second cadence was not used", next.elapsedAt - changed.elapsedAt in 7_000L..11_000L)
            networkObserver!!.assertUnchanged(network)
            flow.exchange("least-ping-held-after-interval")
        }
        assertPeerHealth()
    }

    private fun peer(name: String, status: Int = 204) = LiveAvailabilityPeer(name, status).also { peers += it }

    private suspend fun createProfile(name: String, peer: LiveAvailabilityPeer): Profile =
        Profile(linkId = source.id, name = name, config = profileConfig(peer)).also { it.id = db.profileDao().create(it) }

    private fun healthGroup(primary: Profile, backup: Profile) = ProxyGroup(
        id = GROUP_ID, name = "Production availability fixture", profileIds = listOf(primary.id, backup.id),
        selectedProfileId = primary.id, checkAvailability = true,
    )

    private fun savePlan(group: ProxyGroup, independent: Boolean) {
        val rules = listOf(
            AppDomainRule(id = "explicit-health", packages = listOf("com.android.shell"),
                domains = listOf("full:$EXPLICIT_HOST"), target = group.id),
            AppDomainRule(id = "default-route-health", domains = listOf("full:default-route-health.invalid"), target = "proxy"),
        )
        settings.liveRoutingPlan = LiveRoutingHelper.encode(LiveRoutingPlan(
            groups = listOf(group), rules = rules, defaultGroupId = if (independent) group.id else null,
        ))
    }

    private fun start() {
        ownsSession = true
        TProxyService.start(context, true)
        awaitService(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        assertTrue(TProxyService.isActive())
    }

    private fun health(): LiveRoutingGroupStatus {
        val status = LiveRoutingHelper.decodeRuntimeStatus(XrayCore.liveRoutingStatus())
        check(status.running) { "Native availability controller stopped; failure=${ConnectionFailureState.failure.value}" }
        return status.groups.single { it.id == GROUP_ID }
    }

    private fun member(group: LiveRoutingGroupStatus, profile: Profile): LiveRoutingMemberStatus =
        group.members.single { it.profileId == profile.id }

    private fun awaitHealth(timeoutMillis: Long = 17_000, predicate: (LiveRoutingGroupStatus) -> Boolean): LiveRoutingGroupStatus {
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        var last: LiveRoutingGroupStatus
        do {
            last = health()
            if (predicate(last)) return last
            SystemClock.sleep(50)
        } while (SystemClock.elapsedRealtime() < deadline)
        error("Availability did not reach expected state: $last; failure=${ConnectionFailureState.failure.value}")
    }

    private fun awaitObserved(profile: Profile, probe: LiveAvailabilityPeer.ProbeResponse,
                              predicate: (LiveRoutingMemberStatus) -> Boolean): LiveRoutingGroupStatus =
        awaitHealth(timeoutMillis = 3_000) {
            val observed = member(it, profile)
            observed.lastCheckedAt >= probe.sentAt && predicate(observed)
        }

    private fun awaitProbe(peer: LiveAvailabilityPeer, generation: Long, status: Int): LiveAvailabilityPeer.ProbeResponse {
        val deadline = SystemClock.elapsedRealtime() + 17_000
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "No real member probe for generation=$generation status=$status; failures=${peer.failures}" }
            val observation = peer.probeResponses.poll(remaining, TimeUnit.MILLISECONDS)
                ?: error("No member HTTP probe response; failures=${peer.failures}")
            if (observation.generation == generation) {
                assertEquals(status, observation.status)
                return observation
            }
        }
    }

    private fun awaitProbeRequest(peer: LiveAvailabilityPeer, generation: Long): LiveAvailabilityPeer.ProbeRequest {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "Replacement endpoint never received its own probe" }
            val request = peer.probeRequests.poll(remaining, TimeUnit.MILLISECONDS)
                ?: error("Replacement endpoint never received its own probe")
            if (request.generation == generation) return request
        }
    }

    private fun assertProductionInterval(first: LiveAvailabilityPeer.ProbeResponse, second: LiveAvailabilityPeer.ProbeResponse) {
        assertTrue("Member probes ran faster than production 10s cadence", second.elapsedAt - first.elapsedAt >= 9_000)
    }

    private fun requireAllIndependentTargets(peer: LiveAvailabilityPeer, stage: String) {
        INDEPENDENT_HOSTS.forEachIndexed { index, host -> requireRecipient(peer, "$stage-$index", host) }
        requireNoApplicationRequests()
    }

    private fun requireTrapControls(stage: String) {
        requireRecipient(ordinary, "$stage-ordinary", "ordinary-control.invalid")
        requireRecipient(fallback, "$stage-fallback", "fallback-control.invalid")
        requireRecipient(direct, "$stage-direct", "direct-control.invalid")
        requireNoApplicationRequests()
    }

    private fun requireRecipient(peer: LiveAvailabilityPeer, stage: String, host: String) {
        assertArrayEquals("Wrong real recipient for $stage", peer.payload, transferTunHttp(stage, host))
        val received = peer.requests.poll(3, TimeUnit.SECONDS)
        assertNotNull("No exact request reached expected member for $stage", received)
        assertEquals("GET", received!!.method)
        assertEquals("/vpn-$stage", received.path)
        assertEquals("$host:$DESTINATION_PORT", received.host)
    }

    private fun requireNoApplicationRequests() {
        peers.forEach { peer -> assertNull("Unexpected ordinary/default/direct/member recipient", peer.requests.poll()) }
    }

    private fun transferTunHttp(stage: String, host: String): ByteArray {
        val pipes = shellSocket()
        return ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
            DataInputStream(DeadlineInputStream(pipes[0]).apply { beginResponse(8_000) }).use { input ->
                output.write(httpRequest("/vpn-$stage", host))
                output.flush()
                val headers = readHeaders(input)
                check(headers.startsWith("HTTP/1.1 200 ")) { "Unexpected VPN HTTP response: $headers" }
                val size = headers.lineSequence().first { it.startsWith("Content-Length:", ignoreCase = true) }
                    .substringAfter(':').trim().toInt()
                check(size in 1..16_384)
                ByteArray(size).also(input::readFully)
            }
        }
    }

    private fun requireBlocked(stage: String, host: String) {
        val pipes = shellSocket()
        ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]).use { output ->
            DeadlineInputStream(pipes[0]).apply { beginResponse(5_000) }.use { input ->
                val firstByte = try {
                    output.write(httpRequest("/vpn-$stage", host))
                    output.flush()
                    input.read()
                } catch (_: SocketTimeoutException) {
                    -1
                } catch (error: IOException) {
                    val errno = generateSequence<Throwable>(error) { it.cause }
                        .filterIsInstance<ErrnoException>().firstOrNull()?.errno
                    // A refused/reset shell connection can close nc's input pipe before its
                    // request is written. Do not mistake that genuine rejection for a response.
                    if (errno !in setOf(OsConstants.EPIPE, OsConstants.ECONNRESET, OsConstants.ECONNREFUSED)) throw error
                    -1
                }
                assertEquals("All-down new flow received data instead of being blocked: $stage", -1, firstByte)
            }
        }
        requireNoApplicationRequests()
    }

    private fun assertPeerHealth() {
        requireNoApplicationRequests()
        peers.forEach { assertTrue("Synthetic peer failure: ${it.failures}", it.failures.isEmpty()) }
        listOf(ordinary, fallback, direct).forEach {
            assertTrue("Health probe bypassed a member and reached a sentinel", it.probeRequests.isEmpty())
        }
        assertTrue(TProxyService.isActive())
        assertNull(ConnectionFailureState.failure.value)
    }

    private fun awaitService(action: String) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            check(remaining > 0) { "No service acknowledgement $action; failure=${ConnectionFailureState.failure.value}" }
            val event = events.poll(remaining, TimeUnit.MILLISECONDS)
                ?: error("No service acknowledgement $action; failure=${ConnectionFailureState.failure.value}")
            if (event.action == action) return
        }
    }

    /** One HTTP-sniffed socket, then cumulative binary frames: reconnects cannot satisfy it. */
    private class ShellHeldFlow(port: Int, host: String, private val peer: LiveAvailabilityPeer) : Closeable {
        private val pipes = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -w 240 -W 240 198.18.0.1 $port")
        private val deadline = DeadlineInputStream(pipes[0])
        private val input = DataInputStream(deadline)
        private val output = DataOutputStream(ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]))
        private var chain = byteArrayOf()

        init {
            deadline.beginResponse(8_000)
            output.write("GET /framed HTTP/1.1\r\nHost: $host:$port\r\nConnection: keep-alive\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
            check(readHeaders(input).startsWith("HTTP/1.1 200 "))
            assertEquals(1, peer.streamConnections.get())
        }

        fun exchange(stage: String) {
            val frame = byteArrayOf(0, 1, 0x7f, 0xff.toByte()) + "$stage 東京\n".toByteArray()
            chain += frame
            deadline.beginResponse(8_000)
            output.writeInt(frame.size)
            output.write(frame)
            output.flush()
            val size = input.readInt()
            check(size in 1..16_384)
            assertArrayEquals("Held framed TCP changed recipient or lost its chain", peer.payload + chain, ByteArray(size).also(input::readFully))
            assertArrayEquals(frame, peer.frames.poll(3, TimeUnit.SECONDS))
            assertEquals("Held stream reconnected", 1, peer.streamConnections.get())
        }

        override fun close() {
            try { output.close() } finally { input.close() }
        }
    }

    /** Keep observing throughout the test; comparing a start-time variable alone misses restarts. */
    private class TunNetworks(context: Context) : Closeable {
        private val manager = context.getSystemService(ConnectivityManager::class.java)
        private val seen = ConcurrentHashMap.newKeySet<Network>()
        private val lost = ConcurrentHashMap.newKeySet<Network>()
        private val ready = LinkedBlockingQueue<Network>()
        private val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onLinkPropertiesChanged(network: Network, properties: LinkProperties) = observe(network, properties)
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                manager.getLinkProperties(network)?.let { observe(network, it) }
            }
            override fun onLost(network: Network) { if (network in seen) lost += network }
        }

        init {
            withNetworkPermission {
                manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities()
                    .addTransportType(NetworkCapabilities.TRANSPORT_VPN).setIncludeOtherUidNetworks(true).build(), callback)
            }
        }

        private fun observe(network: Network, properties: LinkProperties) {
            if (manager.getNetworkCapabilities(network)?.ownerUid == Process.myUid() &&
                properties.interfaceName?.startsWith("tun") == true &&
                properties.routes.any { it.isDefaultRoute && it.destination.address is Inet4Address } && seen.add(network)) {
                ready.offer(network)
            }
        }

        fun awaitFirst(): Network = checkNotNull(ready.poll(8, TimeUnit.SECONDS)) { "Owned TUN default route was not published" }

        fun assertUnchanged(original: Network) {
            assertTrue("Original TUN network was lost", lost.isEmpty())
            assertEquals("Health changes replaced the TUN network", setOf(original), seen.toSet())
            assertNotNull("Original TUN network disappeared", manager.getLinkProperties(original))
        }

        override fun close() = manager.unregisterNetworkCallback(callback)
    }

    private data class SavedFile(val file: File, val bytes: ByteArray?, val modified: Long) {
        fun restore() {
            if (bytes == null) {
                check(!file.exists() || file.delete()) { "Could not delete fixture runtime file" }
            } else {
                file.writeBytes(bytes)
                check(file.setLastModified(modified)) { "Could not restore runtime file timestamp" }
            }
        }
        companion object {
            fun capture(file: File) = SavedFile(file, if (file.exists()) file.readBytes() else null, file.lastModified())
        }
    }

    companion object {
        private const val GROUP_ID = "availability-health"
        private const val EXPLICIT_HOST = "explicit-health.invalid"
        private const val DESTINATION_PORT = 29387
        private val INDEPENDENT_HOSTS = listOf(
            "unmatched-health.invalid", EXPLICIT_HOST, "default-route-health.invalid", "core-health.invalid",
        )

        private fun outbound(tag: String, peer: LiveAvailabilityPeer): String = """{
            "tag":"$tag","protocol":"freedom","settings":{"redirect":"127.0.0.1:${peer.port}",
            "finalRules":[{"action":"allow","ip":["127.0.0.1/32"],"port":"${peer.port}"}]}
        }"""

        private fun profileConfig(peer: LiveAvailabilityPeer): String = """{"outbounds":[${outbound("proxy", peer)}]}"""

        private fun shellSocket(): Array<ParcelFileDescriptor> = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommandRw("toybox nc -w 3 -W 3 198.18.0.1 $DESTINATION_PORT")

        private fun httpRequest(path: String, host: String) =
            "GET $path HTTP/1.1\r\nHost: $host:$DESTINATION_PORT\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII)

        private fun readHeaders(input: InputStream): String = buildString {
            while (!endsWith("\r\n\r\n")) {
                check(length < 16_384) { "VPN response headers exceed fixture bound" }
                val next = input.read()
                check(next >= 0) { "EOF before VPN HTTP response headers" }
                append(next.toChar())
            }
        }

        private fun shell(command: String): ByteArray = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command),
        ).use { it.readBytes() }

        private fun <T> withNetworkPermission(action: () -> T): T {
            val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
            automation.adoptShellPermissionIdentity("android.permission.NETWORK_SETTINGS")
            return try { action() } finally { automation.dropShellPermissionIdentity() }
        }
    }
}
