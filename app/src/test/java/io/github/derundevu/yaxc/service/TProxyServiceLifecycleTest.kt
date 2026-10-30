package io.github.derundevu.yaxc.service

import android.content.Intent
import android.net.VpnService
import android.os.Looper
import android.os.ParcelFileDescriptor
import io.github.derundevu.yaxc.database.Config as StoredConfig
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.dto.XrayConfig
import io.github.derundevu.yaxc.helper.ConnectionFailure
import io.github.derundevu.yaxc.helper.ConnectionFailureState
import io.github.derundevu.yaxc.helper.SubscriptionHttpServer
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import java.io.File
import io.github.derundevu.yaxc.testing.VpnBuilderShadow
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], shadows = [VpnBuilderShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class TProxyServiceLifecycleTest : RoomTestSupport() {
    private val scheduler = TestCoroutineScheduler()
    private lateinit var controller: ServiceController<TProxyService>
    private lateinit var service: TProxyService
    private lateinit var boundary: OwnedRuntime
    private lateinit var profile: Profile
    private var destroyed = false
    private var startId = 0

    @Before
    fun createService() {
        ConnectionFailureState.clear()
        settings.tun2socks = true
        profile = Profile(id = 42, name = "Synthetic local profile", config = profileConfig("first"))
        runBlocking { database.profileDao().insert(profile) }
        settings.selectedProfile = profile.id
        settings.xrayConfig().delete()
        controller = Robolectric.buildService(TProxyService::class.java).create()
        service = controller.get()
        boundary = OwnedRuntime()
        service.commandDispatcher = StandardTestDispatcher(scheduler)
        service.runtime = boundary
    }

    @After
    fun releaseService() {
        boundary.validationGate?.complete("")
        boundary.allowStop = true
        if (!destroyed) controller.destroy()
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
        boundary.close()
        settings.xrayConfig().delete()
        settings.tun2socksConfig().delete()
        ConnectionFailureState.clear()
    }

    @Test
    fun blockedStartThenStopCannotPublishLateValidationOrResurrectRuntime() {
        val gate = CompletableDeferred<String>()
        boundary.validationGate = gate
        settings.xrayConfig().writeText("previous config remains untouched")
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 1 }
        command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        scheduler.runCurrent()
        assertFalse(TProxyService.isActive())
        assertFalse(boundary.coreRunning)

        gate.complete("late rejected validation")
        pumpUntil { shadowOf(service).isStoppedBySelf }
        assertFalse(TProxyService.isActive())
        assertEquals(0, boundary.coreStarts)
        assertEquals(0, boundary.tunnelStarts)
        assertTrue(boundary.devices.isEmpty())
        assertNull(ConnectionFailureState.failure.value)
        assertEquals("previous config remains untouched", settings.xrayConfig().readText())
        assertFalse(broadcasts().any { it.action == TProxyService.START_VPN_SERVICE_ACTION_NAME })
        assertFalse(lastStatus())
        assertTrue(shadowOf(service).isForegroundStopped)
    }

    @Test
    fun unchangedReloadAcknowledgesStatusWithoutReplacingOwnedRuntimeOrRewritingConfig() {
        startAndAwait()
        val device = boundary.devices.single()
        val stored = settings.xrayConfig().readBytes()
        assertTrue(settings.xrayConfig().setLastModified(1_600_000_000_000L))
        val timestamp = settings.xrayConfig().lastModified()
        val before = broadcasts().size
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { broadcasts().drop(before).any { it.action in reloadAcknowledgements } }

        val acknowledged = broadcasts().drop(before)
        assertEquals(listOf(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME), acknowledged.map { it.action })
        assertTrue(acknowledged.single().getBooleanExtra("isRunning", false))
        assertEquals(profile.name, acknowledged.single().getStringExtra("profile"))
        assertEquals(1, boundary.coreStarts)
        assertEquals(0, boundary.coreStops)
        assertEquals(1, boundary.tunnelStarts)
        assertEquals(0, boundary.tunnelStops)
        assertSame(device, boundary.devices.single())
        device.assertTraffic("unchanged established tunnel")
        assertArrayEquals(stored, settings.xrayConfig().readBytes())
        assertEquals(timestamp, settings.xrayConfig().lastModified())
        assertEquals("19080", settings.socksPort)
        assertEquals(19080, JSONObject(boundary.appliedConfig!!).getJSONArray("inbounds").getJSONObject(0).getInt("port"))
        assertTrue(settings.tun2socksConfig().readText().contains("port: 19080"))
    }

    @Test
    fun renamedProfileAndUnselectedProfileMutationKeepTheSameRunningSession() {
        startAndAwait()
        val device = boundary.devices.single()
        val stored = settings.xrayConfig().readBytes()
        assertTrue(settings.xrayConfig().setLastModified(1_600_000_000_000L))
        val timestamp = settings.xrayConfig().lastModified()
        profile.name = "Renamed without reconnecting"
        runBlocking { database.profileDao().update(profile) }
        assertReloadAcknowledgement(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        assertEquals(profile.name, broadcasts().last().getStringExtra("profile"))

        val other = Profile(id = 43, name = "Unselected", config = profileConfig("unselected"))
        runBlocking {
            database.profileDao().insert(other)
            database.profileDao().update(other.copy(name = "Refreshed unselected", config = profileConfig("refreshed")))
        }
        assertReloadAcknowledgement(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        assertEquals(profile.name, broadcasts().last().getStringExtra("profile"))
        assertEquals(1, boundary.coreStarts)
        assertEquals(0, boundary.coreStops)
        assertEquals(1, boundary.tunnelStarts)
        assertEquals(0, boundary.tunnelStops)
        assertSame(device, boundary.devices.single())
        device.assertTraffic("metadata and unselected profile do not reconnect")
        assertArrayEquals(stored, settings.xrayConfig().readBytes())
        assertEquals(timestamp, settings.xrayConfig().lastModified())
    }

    @Test
    fun activeAndGlobalConfigChangesReloadCoreExactlyOnceAndKeepTheTunnel() {
        startAndAwait()
        val device = boundary.devices.single()
        updateProfileConfig("active-change")
        assertReloadAcknowledgement(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        assertEquals("active-change", JSONObject(boundary.appliedConfig!!).getString("regressionMarker"))
        assertEquals(2, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertReloadAcknowledgement(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        assertEquals(2, boundary.coreStarts)

        runBlocking {
            val global = database.configDao().get()!!
            database.configDao().update(global.copy(dns = """{"queryStrategy":"UseIPv4"}""", dnsMode = StoredConfig.Mode.Replace))
        }
        assertReloadAcknowledgement(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        assertEquals("UseIPv4", JSONObject(boundary.appliedConfig!!).getJSONObject("dns").getString("queryStrategy"))
        assertEquals(3, boundary.coreStarts)
        assertEquals(2, boundary.coreStops)
        assertReloadAcknowledgement(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        assertEquals(3, boundary.coreStarts)
        assertEquals(1, boundary.tunnelStarts)
        assertEquals(0, boundary.tunnelStops)
        assertSame(device, boundary.devices.single())
        device.assertTraffic("core reload keeps tunnel ownership")
        assertEquals(boundary.appliedConfig, settings.xrayConfig().readText())
    }

    @Test
    fun changedPlatformTunOptionValidatesBeforeReplacingTunnelAndAppliesExactlyOnce() {
        startAndAwait()
        val old = boundary.devices.single()
        val applied = settings.xrayConfig().readBytes()
        settings.tunMtu = 1500
        boundary.diagnostic = "reject changed TUN options"
        assertReloadAcknowledgement(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        assertNotNull(ConnectionFailureState.failure.value)
        assertSame(old, boundary.devices.single())
        assertEquals(1, boundary.coreStarts)
        assertEquals(0, boundary.coreStops)
        assertEquals(0, boundary.tunnelStops)
        old.assertTraffic("invalid platform option candidate preserves owner")
        assertArrayEquals(applied, settings.xrayConfig().readBytes())

        boundary.diagnostic = ""
        assertReloadAcknowledgement(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        old.assertClosed()
        assertEquals(2, boundary.devices.size)
        assertEquals(2, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertEquals(2, boundary.tunnelStarts)
        assertEquals(1, boundary.tunnelStops)
        assertTrue(settings.tun2socksConfig().readText().contains("mtu: 1500"))
        boundary.devices.last().assertTraffic("replacement honors changed TUN options")
        assertReloadAcknowledgement(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        assertEquals(2, boundary.coreStarts)
        assertEquals(2, boundary.devices.size)
    }

    @Test
    fun clearingSelectedProfileOnReloadStopsOwnedRuntimeInsteadOfKeepingStaleCore() {
        startAndAwait()
        settings.selectedProfile = 0L
        assertMissingSelectionStopsRuntime()
    }

    @Test
    fun deletingSelectedProfileOnReloadStopsOwnedRuntimeInsteadOfKeepingStaleCore() {
        startAndAwait()
        runBlocking { database.profileDao().delete(profile) }
        assertMissingSelectionStopsRuntime()
    }

    @Test
    fun preallocatedEphemeralSocksPortStaysStableAcrossConditionalComparisons() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        settings.socksPort = port.toString()
        startAndAwait()
        val applied = settings.xrayConfig().readBytes()
        repeat(3) {
            assertReloadAcknowledgement(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
            assertEquals(port.toString(), settings.socksPort)
            assertEquals(port, JSONObject(boundary.appliedConfig!!).getJSONArray("inbounds").getJSONObject(0).getInt("port"))
            assertTrue(settings.tun2socksConfig().readText().contains("port: $port"))
            assertArrayEquals(applied, settings.xrayConfig().readBytes())
        }
        assertEquals(1, boundary.coreStarts)
        assertEquals(1, boundary.tunnelStarts)
        assertEquals(0, boundary.coreStops)
        assertEquals(0, boundary.tunnelStops)
    }

    @Test
    fun invalidReloadAndRestartKeepTheSameLiveTunnelAndAppliedConfig() {
        startAndAwait()
        val device = boundary.devices.single()
        val applied = boundary.appliedConfig
        val stored = settings.xrayConfig().readText()
        val starts = boundary.coreStarts
        updateProfileConfig("invalid-candidate")
        for (action in listOf(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME, TProxyService.RESTART_VPN_SERVICE_ACTION_NAME)) {
            boundary.diagnostic = "synthetic invalid candidate"
            command(action)
            pumpUntil { ConnectionFailureState.failure.value != null }
            assertTrue(TProxyService.isActive())
            assertTrue(boundary.coreRunning)
            assertEquals(applied, boundary.appliedConfig)
            assertEquals(stored, settings.xrayConfig().readText())
            assertEquals(starts, boundary.coreStarts)
            assertEquals(0, boundary.tunnelStops)
            assertSame(device, boundary.devices.single())
            device.assertTraffic("candidate rejected: $action")
            assertTrue(lastStatus())
        }
    }

    @Test
    fun duplicateStartsDoNotAcquireAnotherDescriptorAndStopsAreIdempotent() {
        startAndAwait()
        repeat(4) { command(TProxyService.START_VPN_SERVICE_ACTION_NAME) }
        scheduler.runCurrent()
        assertEquals(1, boundary.coreStarts)
        assertEquals(1, boundary.tunnelStarts)
        assertEquals(1, boundary.devices.size)
        boundary.devices.single().assertTraffic("still one tunnel")
        repeat(3) { command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME) }
        pumpUntil { !TProxyService.isActive() }
        scheduler.runCurrent()
        assertEquals(1, boundary.tunnelStops)
        assertEquals(1, boundary.coreStops)
        boundary.devices.single().assertClosed()
        assertFalse(boundary.coreRunning)
        assertFalse(lastStatus())
    }

    @Test
    fun queuedStopMustRunBeforeFollowingStartAcquiresANewTunnel() {
        startAndAwait()
        val first = boundary.devices.single()
        command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { boundary.devices.size == 2 && TProxyService.isActive() }
        first.assertClosed()
        boundary.devices.last().assertTraffic("second lifecycle")
        assertEquals(2, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertEquals(1, boundary.tunnelStops)
        assertTrue(boundary.coreRunning)
    }

    @Test
    fun newerRestartCancelsBlockedReloadWithoutPublishingItsFailure() {
        startAndAwait()
        val old = boundary.devices.single()
        updateProfileConfig("obsolete-blocked-candidate")
        val gate = CompletableDeferred<String>()
        boundary.validationGate = gate
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 2 }
        profile.config = profileConfig("newest")
        runBlocking { database.profileDao().update(profile) }
        command(TProxyService.RESTART_VPN_SERVICE_ACTION_NAME)
        boundary.validationGate = null
        gate.complete("obsolete error")
        pumpUntil { boundary.devices.size == 2 && TProxyService.isActive() }
        old.assertClosed()
        boundary.devices.last().assertTraffic("newest config")
        assertEquals("newest", JSONObject(boundary.appliedConfig!!).getString("regressionMarker"))
        assertNull(ConnectionFailureState.failure.value)
        assertFalse(broadcasts().any { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME })
    }

    @Test
    fun nativeStartFailureJoinsWorkerClosesTunStopsCoreAndPublishesInactiveFailure() {
        boundary.allowStart = false
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { ConnectionFailureState.failure.value != null && shadowOf(service).isStoppedBySelf }
        assertFalse(TProxyService.isActive())
        assertFalse(boundary.coreRunning)
        assertFalse(boundary.workerRunning)
        assertEquals(1, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertEquals(1, boundary.tunnelStarts)
        assertEquals(1, boundary.tunnelStops)
        boundary.devices.single().assertClosed()
        assertFalse(lastStatus())
        assertFalse(broadcasts().any { it.action == TProxyService.START_VPN_SERVICE_ACTION_NAME })
    }

    @Test
    fun stopArrivingDuringNativeStartCannotPublishACompletedStart() {
        boundary.onTunnelStart = { command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME) }
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { shadowOf(service).isStoppedBySelf }
        assertFalse(TProxyService.isActive())
        assertFalse(boundary.coreRunning)
        assertFalse(boundary.workerRunning)
        boundary.devices.single().assertClosed()
        assertEquals(1, boundary.tunnelStops)
        assertNull(ConnectionFailureState.failure.value)
        assertFalse(broadcasts().any { it.action == TProxyService.START_VPN_SERVICE_ACTION_NAME })
        assertFalse(lastStatus())
    }

    @Test
    fun failedEstablishReleasesCoreWithoutCallingNativeOrClaimingRunning() {
        boundary.denyEstablish = true
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { ConnectionFailureState.failure.value != null }
        assertFalse(TProxyService.isActive())
        assertFalse(boundary.coreRunning)
        assertTrue(boundary.devices.isEmpty())
        assertEquals(0, boundary.tunnelStarts)
        assertEquals(0, boundary.tunnelStops)
        assertEquals(1, boundary.coreStops)
        assertFalse(lastStatus())
    }

    @Test
    fun nativeStopRefusalKeepsLiveDescriptorCoreStatusAndNotificationUntilRetry() {
        startAndAwait()
        val device = boundary.devices.single()
        boundary.allowStop = false
        command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        pumpUntil { boundary.tunnelStops == 1 }
        assertTrue(TProxyService.isActive())
        assertTrue(boundary.coreRunning)
        assertTrue(boundary.workerRunning)
        device.assertTraffic("worker still owns fd")
        assertEquals(0, boundary.coreStops)
        assertFalse(shadowOf(service).isForegroundStopped)
        assertFalse(broadcasts().any { it.action == TProxyService.STOP_VPN_SERVICE_ACTION_NAME })
        assertTrue(lastStatus())
        boundary.allowStop = true
        command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        pumpUntil { !TProxyService.isActive() }
        device.assertClosed()
        assertEquals(2, boundary.tunnelStops)
        assertEquals(1, boundary.coreStops)
    }

    @Test
    fun failedStartWithUnjoinedWorkerCannotReplaceItsStillOwnedDescriptor() {
        boundary.allowStart = false
        boundary.allowStop = false
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { ConnectionFailureState.failure.value != null }
        val device = boundary.devices.single()
        assertTrue(boundary.coreRunning)
        assertFalse(TProxyService.isActive())
        device.assertTraffic("retained partial-start ownership")
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { ConnectionFailureState.failure.value != null }
        assertEquals(1, boundary.coreStarts)
        assertEquals(1, boundary.tunnelStarts)
        assertEquals(1, boundary.devices.size)
        device.assertTraffic("retry cannot overwrite unjoined fd")
        boundary.allowStop = true
        command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        pumpUntil { !boundary.coreRunning }
        device.assertClosed()
        assertFalse(TProxyService.isActive())
    }

    @Test
    fun restartCannotAcquireReplacementWhenPreviousWorkerRefusesToStop() {
        startAndAwait()
        val device = boundary.devices.single()
        boundary.allowStop = false
        command(TProxyService.RESTART_VPN_SERVICE_ACTION_NAME)
        pumpUntil { ConnectionFailureState.failure.value != null }
        assertTrue(TProxyService.isActive())
        assertEquals(1, boundary.devices.size)
        assertEquals(1, boundary.coreStarts)
        device.assertTraffic("restart refused")
        assertTrue(lastStatus())
    }

    @Test
    fun editedTransparentPreferenceCannotRedirectTunTeardown() {
        startAndAwait()
        val device = boundary.devices.single()
        settings.transparentProxy = true
        settings.tun2socks = false
        service.onRevoke()
        device.assertClosed()
        assertFalse(boundary.coreRunning)
        assertEquals(listOf(false), boundary.stoppedCoreModes)
        assertEquals(0, boundary.transparentDisables)
        assertFalse(TProxyService.isActive())
    }

    @Test
    fun editedTunPreferenceCannotRedirectTransparentTeardown() {
        settings.transparentProxy = true
        startAndAwait()
        assertTrue(boundary.transparentEnabled)
        assertTrue(boundary.devices.isEmpty())
        settings.transparentProxy = false
        settings.tun2socks = true
        service.onRevoke()
        assertFalse(boundary.transparentEnabled)
        assertFalse(boundary.coreRunning)
        assertEquals(listOf(true), boundary.stoppedCoreModes)
        assertEquals(1, boundary.transparentDisables)
        assertEquals(0, boundary.tunnelStops)
        assertFalse(TProxyService.isActive())
    }

    @Test
    fun successfulModeSwitchStopsOldTunBeforeStartingTransparentRuntime() {
        startAndAwait()
        val device = boundary.devices.single()
        settings.transparentProxy = true
        command(TProxyService.RESTART_VPN_SERVICE_ACTION_NAME)
        pumpUntil { boundary.transparentEnabled && TProxyService.isActive() }
        device.assertClosed()
        assertEquals(listOf(false), boundary.stoppedCoreModes)
        assertEquals(listOf(false, true), boundary.startedCoreModes)
        assertEquals(1, boundary.tunnelStops)
        assertEquals(1, boundary.devices.size)
    }

    @Test
    fun destroyReleasesOwnedRuntimeButDoesNotDismissConnectionFailure() {
        startAndAwait()
        val device = boundary.devices.single()
        val failure = ConnectionFailure("synthetic persistent diagnostic")
        ConnectionFailureState.report(failure)
        settings.transparentProxy = true
        controller.destroy()
        destroyed = true
        device.assertClosed()
        assertFalse(boundary.coreRunning)
        assertFalse(TProxyService.isActive())
        assertSame(failure, ConnectionFailureState.failure.value)
        assertEquals(listOf(false), boundary.stoppedCoreModes)
    }

    @Test
    fun destroyDuringValidationCannotPublishALateFailureOrCreateRuntime() {
        val gate = CompletableDeferred<String>()
        boundary.validationGate = gate
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 1 }
        val failure = ConnectionFailure("failure survives owner destruction")
        ConnectionFailureState.report(failure)
        controller.destroy()
        destroyed = true
        gate.complete("obsolete diagnostic")
        scheduler.runCurrent()
        assertEquals(0, boundary.coreStarts)
        assertTrue(boundary.devices.isEmpty())
        assertFalse(TProxyService.isActive())
        assertSame(failure, ConnectionFailureState.failure.value)
        assertFalse(broadcasts().any { it.action == TProxyService.START_VPN_SERVICE_ACTION_NAME })
    }

    @Test
    fun revokeDuringValidationPreventsLateStartAndLeavesExistingFailureUntouched() {
        val gate = CompletableDeferred<String>()
        boundary.validationGate = gate
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 1 }
        val failure = ConnectionFailure("unrelated failure must survive cancellation")
        ConnectionFailureState.report(failure)
        service.onRevoke()
        gate.complete("")
        scheduler.runCurrent()
        assertFalse(boundary.coreRunning)
        assertEquals(0, boundary.coreStarts)
        assertFalse(TProxyService.isActive())
        assertSame(failure, ConnectionFailureState.failure.value)
        assertTrue(boundary.devices.isEmpty())
    }

    @Test
    fun coreOnlyRuntimeDoesNotLoadTunNativeEvenWhenStoppedAfterPreferenceChange() {
        settings.tun2socks = false
        startAndAwait()
        assertTrue(boundary.coreRunning)
        assertTrue(boundary.devices.isEmpty())
        settings.tun2socks = true
        assertTrue(lastStatus())
        command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        pumpUntil { !TProxyService.isActive() }
        assertEquals(0, boundary.tunnelStops)
        assertEquals(0, boundary.tunnelStarts)
        assertEquals(1, boundary.coreStops)
    }

    @Test
    fun missingSelectedProfileReportsFailureWithoutNativeOrDescriptorAcquisition() {
        settings.selectedProfile = 0
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { ConnectionFailureState.failure.value != null }
        assertFalse(TProxyService.isActive())
        assertEquals(0, boundary.validations)
        assertEquals(0, boundary.coreStarts)
        assertTrue(boundary.devices.isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun notificationStopActionDispatchesRealServiceStopCommand() {
        startAndAwait()
        val notification = shadowOf(service).lastForegroundNotification
        assertNotNull(notification)
        while (shadowOf(app).nextStartedService != null) {
            // Isolate this notification action from preceding tile updates.
        }
        notification.actions.single().actionIntent.send()
        val dispatched = shadowOf(app).nextStartedService
        assertEquals(TProxyService.STOP_VPN_SERVICE_ACTION_NAME, dispatched.action)
        assertEquals(TProxyService::class.java.name, dispatched.component!!.className)
        service.onStartCommand(dispatched, 0, ++startId)
        pumpUntil { !TProxyService.isActive() }
        boundary.devices.single().assertClosed()
        assertFalse(boundary.coreRunning)
    }

    @Test
    fun stopReleasesActiveRuntimeBeforeUninterruptibleReloadValidationCompletes() {
        startAndAwait()
        val device = boundary.devices.single()
        val stored = settings.xrayConfig().readBytes()
        updateProfileConfig("blocked-before-stop")
        val gate = CompletableDeferred<String>()
        boundary.validationGate = gate
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 2 }
        command(TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        scheduler.runCurrent()
        assertFalse("STOP must not wait for a different process's native constructor", TProxyService.isActive())
        assertFalse(boundary.coreRunning)
        device.assertClosed()
        assertFalse(gate.isCompleted)
        gate.complete("")
        scheduler.runCurrent()
        assertEquals(1, boundary.coreStarts)
        assertNull(ConnectionFailureState.failure.value)
        assertArrayEquals(stored, settings.xrayConfig().readBytes())
        assertFalse(broadcasts().any { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME })
    }

    @Test
    fun overlappingConditionalReloadsApplyOnlyLatestCandidateOnce() {
        startAndAwait()
        val device = boundary.devices.single()
        val gate = CompletableDeferred<String>()
        updateProfileConfig("obsolete")
        boundary.validationGate = gate
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 2 }
        device.assertTraffic("old core remains usable while validating")
        updateProfileConfig("latest")
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        boundary.validationGate = null
        gate.complete("obsolete validation error")
        pumpUntil { broadcasts().any { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME } }
        assertEquals("latest", JSONObject(boundary.appliedConfig!!).getString("regressionMarker"))
        assertEquals(2, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertEquals(1, broadcasts().count { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME })
        assertEquals(1, boundary.tunnelStarts)
        assertEquals(0, boundary.tunnelStops)
        assertSame(device, boundary.devices.single())
        device.assertTraffic("latest conditional reload converged")
        assertNull(ConnectionFailureState.failure.value)
    }

    @Test
    fun databaseChangeDuringBlockedConditionalReloadConvergesWithoutAnotherCommand() {
        startAndAwait()
        val device = boundary.devices.single()
        val gate = CompletableDeferred<String>()
        updateProfileConfig("validated-but-obsolete")
        boundary.validationGate = gate
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 2 }
        updateProfileConfig("latest-without-another-command")
        boundary.validationGate = null
        gate.complete("")
        pumpUntil { broadcasts().any { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME } }
        assertEquals("latest-without-another-command", JSONObject(boundary.appliedConfig!!).getString("regressionMarker"))
        assertEquals(boundary.appliedConfig, settings.xrayConfig().readText())
        assertEquals(2, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertEquals(1, broadcasts().count { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME })
        assertSame(device, boundary.devices.single())
        assertEquals(0, boundary.tunnelStops)
        device.assertTraffic("latest database candidate without a second command")
        assertNull(ConnectionFailureState.failure.value)
    }

    @Test
    fun settingsChangedDuringBlockedValidationApplyLatestTunOptionsOnlyOnce() {
        startAndAwait()
        val old = boundary.devices.single()
        val gate = CompletableDeferred<String>()
        updateProfileConfig("changed-core-and-later-tun-options")
        boundary.validationGate = gate
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 2 }
        settings.tunMtu = 1600
        boundary.validationGate = null
        gate.complete("")
        pumpUntil { broadcasts().any { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME } }
        old.assertClosed()
        assertEquals(2, boundary.devices.size)
        assertEquals(2, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertEquals(2, boundary.tunnelStarts)
        assertEquals(1, boundary.tunnelStops)
        assertTrue(settings.tun2socksConfig().readText().contains("mtu: 1600"))
        boundary.devices.last().assertTraffic("latest TUN options after validation rendezvous")
        assertEquals(1, broadcasts().count { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME })
        assertNull(ConnectionFailureState.failure.value)
    }

    @Test
    fun selectionDeletedDuringBlockedChangedValidationCannotPublishOrKeepStaleRuntime() {
        startAndAwait()
        val device = boundary.devices.single()
        val stored = settings.xrayConfig().readBytes()
        val gate = CompletableDeferred<String>()
        updateProfileConfig("removed-before-validation-completes")
        boundary.validationGate = gate
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { boundary.validations == 2 }
        runBlocking { database.profileDao().delete(profile) }
        gate.complete("")
        pumpUntil { !TProxyService.isActive() }
        device.assertClosed()
        assertFalse(boundary.coreRunning)
        assertEquals(1, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertArrayEquals(stored, settings.xrayConfig().readBytes())
        assertFalse(broadcasts().any { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME })
        assertTrue(broadcasts().any { it.action == TProxyService.STOP_VPN_SERVICE_ACTION_NAME })
    }

    @Test
    fun cancellingRefreshBatchAfterFirstCommitReconcilesCommittedActiveConfigExactlyOnce() {
        SubscriptionHttpServer().use { server ->
            val first = Link(name = "Committed source", address = server.url)
            val second = Link(name = "Cancelled source", address = server.url)
            val third = Link(name = "Never requested source", address = server.url)
            val unselected = Profile(
                name = "Previously unselected",
                config = profileConfig("committed-before-cancel"),
                index = 0,
            )
            runBlocking {
                first.id = database.linkDao().insertAtEnd(first)
                second.id = database.linkDao().insertAtEnd(second)
                third.id = database.linkDao().insertAtEnd(third)
                profile.linkId = first.id
                profile.index = 1
                database.profileDao().update(profile)
                unselected.linkId = first.id
                unselected.id = database.profileDao().insert(unselected)
            }
            startAndAwait()
            val device = boundary.devices.single()
            while (shadowOf(app).nextStartedService != null) {
                // Observe only the reconciliation dispatched by this actual HTTP refresh.
            }
            // Reverse the two source entries. IDs remain positional, so the selected row's
            // effective configuration changes and must be applied rather than skipped by ID.
            val movedToSelected = JSONObject(unselected.config).put("remarks", unselected.name)
            val movedFromSelected = JSONObject(profile.config).put("remarks", profile.name)
            server.enqueue("[$movedFromSelected,$movedToSelected]")
            val held = server.enqueue(JSONObject(profileConfig("must-not-commit")).put("remarks", "Cancelled").toString(), held = true)
            val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val pending = refreshScope.async { SubscriptionRefreshHelper(app, settings).refreshLinks() }
            try {
                pumpUntil { held.request.isDone }
                held.awaitRequest()
                assertEquals(
                    "committed-before-cancel",
                    runBlocking { JSONObject(database.profileDao().find(profile.id).config).getString("regressionMarker") },
                )
                assertEquals(profile.id, settings.selectedProfile)
                assertEquals(
                    "first",
                    runBlocking { JSONObject(database.profileDao().find(unselected.id).config).getString("regressionMarker") },
                )
                pending.cancel()
                held.release()
                pumpUntil { pending.isCompleted }
                assertTrue(pending.isCancelled)
                assertEquals(2, server.requestCount.get())
                assertTrue(runBlocking { database.profileDao().linkProfiles(second.id).isEmpty() })
                assertEquals(0L, runBlocking { database.linkDao().find(second.id)!!.lastRefreshedAt })
                assertEquals(0L, runBlocking { database.linkDao().find(third.id)!!.lastRefreshedAt })
                val reconciliations = buildList {
                    while (true) {
                        val dispatched = shadowOf(app).nextStartedService ?: break
                        if (dispatched.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME) add(dispatched)
                    }
                }
                assertEquals("A partially committed batch must reconcile exactly once", 1, reconciliations.size)
                service.onStartCommand(reconciliations.single(), 0, ++startId)
                pumpUntil { broadcasts().any { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME } }
                assertEquals("committed-before-cancel", JSONObject(boundary.appliedConfig!!).getString("regressionMarker"))
                assertEquals(2, boundary.coreStarts)
                assertEquals(1, boundary.coreStops)
                assertEquals(1, boundary.tunnelStarts)
                assertEquals(0, boundary.tunnelStops)
                assertSame(device, boundary.devices.single())
                device.assertTraffic("committed refresh survives later source cancellation")
                assertNull(ConnectionFailureState.failure.value)
            } finally {
                pending.cancel()
                held.release()
                refreshScope.cancel()
                pumpUntil { pending.isCompleted }
            }
        }
    }

    private fun updateProfileConfig(marker: String) {
        profile.config = profileConfig(marker)
        runBlocking { database.profileDao().update(profile) }
    }

    private fun assertReloadAcknowledgement(action: String) {
        val before = broadcasts().size
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { broadcasts().drop(before).any { it.action in reloadAcknowledgements } }
        assertEquals(listOf(action), broadcasts().drop(before).map { it.action })
    }

    private fun assertMissingSelectionStopsRuntime() {
        val device = boundary.devices.single()
        val stored = settings.xrayConfig().readBytes()
        val before = broadcasts().size
        command(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME)
        pumpUntil { broadcasts().drop(before).any { it.action == TProxyService.STOP_VPN_SERVICE_ACTION_NAME } }
        assertFalse(TProxyService.isActive())
        assertFalse(boundary.coreRunning)
        device.assertClosed()
        assertEquals(1, boundary.coreStarts)
        assertEquals(1, boundary.coreStops)
        assertEquals(1, boundary.tunnelStops)
        assertArrayEquals(stored, settings.xrayConfig().readBytes())
        assertFalse(broadcasts().drop(before).any { it.action == TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME })
        assertFalse(lastStatus())
    }

    private fun startAndAwait() {
        command(TProxyService.START_VPN_SERVICE_ACTION_NAME)
        pumpUntil { TProxyService.isActive() }
    }

    private fun command(action: String) {
        service.onStartCommand(Intent(app, TProxyService::class.java).setAction(action), 0, ++startId)
    }

    private fun lastStatus(): Boolean {
        val previous = broadcasts().count { it.action == TProxyService.STATUS_VPN_SERVICE_ACTION_NAME }
        command(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
        pumpUntil { broadcasts().count { it.action == TProxyService.STATUS_VPN_SERVICE_ACTION_NAME } > previous }
        return broadcasts().last { it.action == TProxyService.STATUS_VPN_SERVICE_ACTION_NAME }
            .getBooleanExtra("isRunning", false)
    }

    private fun broadcasts(): List<Intent> = shadowOf(app).broadcastIntents

    private fun pumpUntil(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            scheduler.runCurrent()
            shadowOf(Looper.getMainLooper()).idle()
            check(System.nanoTime() < deadline) {
                "Service did not reach ownership state; failure=${ConnectionFailureState.failure.value?.diagnostic}; " +
                    "core=${boundary.coreRunning}, worker=${boundary.workerRunning}"
            }
            Thread.sleep(1)
        }
        scheduler.runCurrent()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private class Device : AutoCloseable {
        private val pipe = ParcelFileDescriptor.createPipe()
        val tun: ParcelFileDescriptor = pipe[0]
        private val input = FileInputStream(tun.fileDescriptor)
        private val output = FileOutputStream(pipe[1].fileDescriptor)

        fun assertTraffic(value: String) {
            val bytes = value.toByteArray()
            output.write(bytes)
            output.flush()
            val actual = ByteArray(bytes.size)
            var offset = 0
            while (offset < actual.size) {
                val count = input.read(actual, offset, actual.size - offset)
                check(count > 0) { "Owned tunnel stopped transporting bytes" }
                offset += count
            }
            assertArrayEquals(bytes, actual)
            assertTrue(tun.fd >= 0)
        }

        fun assertClosed() {
            assertThrows(IllegalStateException::class.java) { tun.fd }
        }

        override fun close() {
            runCatching { input.close() }
            runCatching { output.close() }
            pipe.forEach { runCatching { it.close() } }
        }
    }

    private class OwnedRuntime : TProxyService.RuntimeBoundary, AutoCloseable {
        val devices = mutableListOf<Device>()
        val startedCoreModes = mutableListOf<Boolean>()
        val stoppedCoreModes = mutableListOf<Boolean>()
        var validationGate: CompletableDeferred<String>? = null
        var diagnostic = ""
        var validations = 0
        var onTunnelStart: (() -> Unit)? = null
        var coreRunning = false
        var workerRunning = false
        var transparentEnabled = false
        var appliedConfig: String? = null
        var coreStarts = 0
        var coreStops = 0
        var tunnelStarts = 0
        var tunnelStops = 0
        var transparentDisables = 0
        var allowStart = true
        var allowStop = true
        var denyEstablish = false

        override suspend fun validate(config: String): String {
            JSONObject(config)
            validations++
            val gate = validationGate
            return if (gate == null) diagnostic else withContext(NonCancellable) { gate.await() }
        }

        override fun startCore(config: XrayConfig, transparent: Boolean) {
            check(!coreRunning) { "Overlapping core ownership" }
            appliedConfig = File(config.file).readText()
            JSONObject(appliedConfig!!)
            coreRunning = true
            coreStarts++
            startedCoreModes += transparent
        }

        override fun stopCore(transparent: Boolean) {
            check(coreRunning) { "Core stopped without ownership" }
            assertEquals(startedCoreModes.last(), transparent)
            coreRunning = false
            coreStops++
            stoppedCoreModes += transparent
        }

        override fun establish(builder: VpnService.Builder): ParcelFileDescriptor? {
            if (denyEstablish) return null
            check(devices.none { runCatching { it.tun.fd }.isSuccess }) { "Overlapping tunnel ownership" }
            return Device().also { devices += it }.tun
        }

        override fun startTunnel(configPath: String, fd: Int): Boolean {
            check(coreRunning)
            assertEquals(devices.last().tun.fd, fd)
            assertTrue(File(configPath).readText().contains("address: 127.0.0.1"))
            tunnelStarts++
            workerRunning = allowStart
            onTunnelStart?.invoke()
            return allowStart
        }

        override fun stopTunnel(): Boolean {
            // A real stop joins the worker before the service may close its descriptor.
            assertTrue(devices.last().tun.fd >= 0)
            tunnelStops++
            if (allowStop) workerRunning = false
            return allowStop
        }

        override fun tunnelRunning() = workerRunning
        override fun transparentRunning() = coreRunning && transparentEnabled
        override fun enableTransparent() {
            check(coreRunning)
            transparentEnabled = true
        }
        override fun disableTransparent() {
            transparentEnabled = false
            transparentDisables++
        }
        override fun close() = devices.forEach { it.close() }
    }

    private companion object {
        val reloadAcknowledgements = setOf(
            TProxyService.STATUS_VPN_SERVICE_ACTION_NAME,
            TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME,
            TProxyService.START_VPN_SERVICE_ACTION_NAME,
            TProxyService.STOP_VPN_SERVICE_ACTION_NAME,
        )

        fun profileConfig(marker: String) = """{"regressionMarker":"$marker","outbounds":[{"protocol":"freedom","tag":"direct"}]}"""
    }
}
