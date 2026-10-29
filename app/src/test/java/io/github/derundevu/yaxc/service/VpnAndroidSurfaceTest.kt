package io.github.derundevu.yaxc.service

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.ConnectionFailure
import io.github.derundevu.yaxc.helper.ConnectionFailureState
import io.github.derundevu.yaxc.helper.NetworkStateHelper
import io.github.derundevu.yaxc.helper.TransparentProxyHelper
import io.github.derundevu.yaxc.helper.TransparentProxyHelper.NetworkAction
import io.github.derundevu.yaxc.receiver.BootReceiver
import io.github.derundevu.yaxc.receiver.VpnActionReceiver
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import java.time.Duration
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock
import org.robolectric.shadows.ShadowService
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowVpnService

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], shadows = [ListeningTileShadow::class])
@LooperMode(LooperMode.Mode.PAUSED)
class VpnAndroidSurfaceTest : RoomTestSupport() {
    private var workManagerInitialized = false

    @Before
    fun isolateCommandSurfaces() {
        app.getSharedPreferences("vpn_tile", Context.MODE_PRIVATE).edit().clear().commit()
        ConnectionFailureState.clear()
        ShadowVpnService.setPrepareResult(null)
        settings.xrayCorePid().delete()
        settings.networkMonitorPid().delete()
    }

    @After
    fun releaseCommandSurfaces() {
        if (workManagerInitialized) WorkManagerTestInitHelper.closeWorkDatabase()
        settings.xrayCorePid().delete()
        settings.networkMonitorPid().delete()
        app.getSharedPreferences("vpn_tile", Context.MODE_PRIVATE).edit().clear().commit()
        ShadowVpnService.setPrepareResult(null)
        ConnectionFailureState.clear()
    }

    @Test
    fun deniedVpnPermissionReportsFailureWithoutDispatchingStart() {
        ShadowVpnService.setPrepareResult(Intent("synthetic.permission.REQUEST"))
        TProxyService.start(app, true)
        assertNull(shadowOf(app).nextStartedService)
        val failure = ConnectionFailureState.failure.value
        assertNotNull(failure)
        assertTrue(failure!!.diagnostic.isNotBlank())
        assertNull(failure.geodata)
        assertFalse(TProxyService.isActive())
    }

    @Test
    fun permittedStartAndEveryControlCommandTargetOnlyTheVpnService() {
        TProxyService.start(app, true)
        TProxyService.status(app)
        TProxyService.newConfig(app)
        TProxyService.restart(app)
        TProxyService.stop(app)
        val expected = listOf(
            TProxyService.START_VPN_SERVICE_ACTION_NAME,
            TProxyService.STATUS_VPN_SERVICE_ACTION_NAME,
            TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME,
            TProxyService.RESTART_VPN_SERVICE_ACTION_NAME,
            TProxyService.STOP_VPN_SERVICE_ACTION_NAME,
        )
        expected.forEach { action -> assertCommand(nextCommand(), action) }
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun uncheckedProxyStartDoesNotRequestVpnPermissionOrErasePriorFailure() {
        ShadowVpnService.setPrepareResult(Intent("synthetic.permission.REQUEST"))
        val previous = ConnectionFailure("existing failure is owned by service lifecycle")
        ConnectionFailureState.report(previous)
        TProxyService.start(app, false)
        assertCommand(nextCommand(), TProxyService.START_VPN_SERVICE_ACTION_NAME)
        assertSame(previous, ConnectionFailureState.failure.value)
    }

    @Test
    fun receiverRoutesOnlyLifecycleEventsAndPreservesProfileIdentityForTile() {
        val receiver = VpnActionReceiver()
        receiver.onReceive(app, Intent(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME))
        receiver.onReceive(app, Intent("synthetic.UNRELATED"))
        receiver.onReceive(app, null)
        receiver.onReceive(null, Intent(TProxyService.STOP_VPN_SERVICE_ACTION_NAME))
        assertNull(shadowOf(app).nextStartedService)
        for (action in listOf(
            TProxyService.START_VPN_SERVICE_ACTION_NAME,
            TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME,
            TProxyService.STOP_VPN_SERVICE_ACTION_NAME,
        )) {
            receiver.onReceive(app, Intent(action).putExtra("profile", "Synthetic profile identity"))
            val intent = nextCommand()
            assertEquals(VpnTileService::class.java.name, intent.component!!.className)
            assertEquals(action, intent.getStringExtra("action"))
            assertEquals("Synthetic profile identity", intent.getStringExtra("label"))
        }
    }

    @Test
    fun blankReceiverProfileUsesAnActualTileLabelInsteadOfLosingStopUpdate() {
        VpnActionReceiver().onReceive(
            app,
            Intent(TProxyService.STOP_VPN_SERVICE_ACTION_NAME).putExtra("profile", "  "),
        )
        val intent = nextCommand()
        assertEquals(app.getString(R.string.appName), intent.getStringExtra("label"))
        val controller = Robolectric.buildService(VpnTileService::class.java).create()
        try {
            controller.get().qsTile!!.state = Tile.STATE_ACTIVE
            controller.get().onStartCommand(intent, 0, 1)
            assertEquals(Tile.STATE_INACTIVE, controller.get().qsTile!!.state)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun tileLifecycleReflectsStartReloadStopAndRestoresAfterStickyNullIntent() {
        val first = Robolectric.buildService(VpnTileService::class.java).create()
        try {
            val tile = first.get().qsTile!!
            first.get().onStartCommand(tileEvent(TProxyService.START_VPN_SERVICE_ACTION_NAME, "First"), 0, 1)
            assertEquals(Tile.STATE_ACTIVE, tile.state)
            assertEquals("First", tile.label.toString())
            assertNotNull(tile.icon)
            first.get().onStartCommand(tileEvent(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME, "Reloaded"), 0, 2)
            assertEquals(Tile.STATE_ACTIVE, tile.state)
            assertEquals("Reloaded", tile.label.toString())
            first.get().onStartCommand(null, 0, 3)
            first.get().onStartCommand(tileEvent("synthetic.UNRELATED", "Wrong identity"), 0, 4)
            assertEquals(Tile.STATE_ACTIVE, tile.state)
            assertEquals("Reloaded", tile.label.toString())
        } finally {
            first.destroy()
        }
        val second = Robolectric.buildService(VpnTileService::class.java).create()
        try {
            second.get().onStartListening()
            assertEquals(Tile.STATE_ACTIVE, second.get().qsTile!!.state)
            assertEquals("Reloaded", second.get().qsTile!!.label.toString())
            second.get().onStartCommand(tileEvent(TProxyService.STOP_VPN_SERVICE_ACTION_NAME, "Reloaded"), 0, 5)
            assertEquals(Tile.STATE_INACTIVE, second.get().qsTile!!.state)
        } finally {
            second.destroy()
        }
    }

    @Test
    fun tileClickHonorsVpnPermissionButProxyModesBypassItAndActiveTileStops() {
        val controller = Robolectric.buildService(VpnTileService::class.java).create()
        try {
            val service = controller.get()
            val tile = service.qsTile!!
            tile.state = Tile.STATE_INACTIVE
            settings.tun2socks = true
            settings.transparentProxy = false
            ShadowVpnService.setPrepareResult(Intent("synthetic.permission.REQUEST"))
            service.onClick()
            assertNull(shadowOf(app).nextStartedService)
            assertNotNull(ConnectionFailureState.failure.value)
            settings.transparentProxy = true
            service.onClick()
            assertCommand(nextCommand(), TProxyService.START_VPN_SERVICE_ACTION_NAME)
            settings.transparentProxy = false
            settings.tun2socks = false
            service.onClick()
            assertCommand(nextCommand(), TProxyService.START_VPN_SERVICE_ACTION_NAME)
            tile.state = Tile.STATE_ACTIVE
            service.onClick()
            assertCommand(nextCommand(), TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
            tile.state = Tile.STATE_UNAVAILABLE
            service.onClick()
            assertNull(shadowOf(app).nextStartedService)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun bootDisabledClearsStalePidFilesAndStopsInsteadOfStarting() {
        initializeWorkManager()
        settings.xrayCorePid().writeText("424242")
        settings.networkMonitorPid().writeText("434343")
        settings.bootAutoStart = false
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertFalse(settings.xrayCorePid().exists())
        assertFalse(settings.networkMonitorPid().exists())
        assertCommand(nextCommand(), TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
    }

    @Test
    fun bootVpnStartRequiresPermissionWhileCoreOnlyBootStartDoesNot() {
        initializeWorkManager()
        settings.bootAutoStart = true
        settings.tun2socks = true
        ShadowVpnService.setPrepareResult(Intent("synthetic.permission.REQUEST"))
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertNull(shadowOf(app).nextStartedService)
        assertNotNull(ConnectionFailureState.failure.value)
        settings.tun2socks = false
        BootReceiver().onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertCommand(nextCommand(), TProxyService.START_VPN_SERVICE_ACTION_NAME)
    }

    @Test
    fun unrelatedOrLateBootSignalsDoNotDeleteRuntimePidFilesOrIssueCommands() {
        settings.xrayCorePid().writeText("424242")
        val receiver = BootReceiver()
        receiver.onReceive(app, Intent(Intent.ACTION_PACKAGE_REPLACED))
        receiver.onReceive(app, null)
        receiver.onReceive(null, Intent(Intent.ACTION_BOOT_COMPLETED))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(3))
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        assertEquals("424242", settings.xrayCorePid().readText())
        assertNull(shadowOf(app).nextStartedService)
    }

    @Test
    fun transparentNetworkDecisionTableAccountsForModeOnlineBypassAndRuntimeOwnership() {
        settings.transparentProxy = true
        settings.tproxyAutoConnect = true
        settings.tproxyBypassWiFi = setOf("Trusted synthetic WiFi", "")
        val helper = TransparentProxyHelper(app, settings)
        val cases = listOf(
            NetworkCase(null, false, false, NetworkAction.STOP),
            NetworkCase(null, false, true, NetworkAction.STOP),
            NetworkCase(null, true, false, NetworkAction.START),
            NetworkCase(null, true, true, NetworkAction.REFRESH),
            NetworkCase("Trusted synthetic WiFi", false, false, NetworkAction.STOP),
            NetworkCase("Trusted synthetic WiFi", true, true, NetworkAction.STOP),
            NetworkCase("Other synthetic WiFi", false, false, NetworkAction.START),
            NetworkCase("Other synthetic WiFi", false, true, NetworkAction.REFRESH),
        )
        cases.forEach { case ->
            assertEquals(case.toString(), case.action, helper.networkAction(case.state, case.running))
        }
        assertFalse(helper.bypassWiFi(NetworkStateHelper.NetworkState(null, true)))
        assertFalse(helper.bypassWiFi(NetworkStateHelper.NetworkState("Trusted", false)))
        settings.tproxyAutoConnect = false
        cases.forEach { assertEquals(NetworkAction.NONE, helper.networkAction(it.state, it.running)) }
        settings.tproxyAutoConnect = true
        settings.transparentProxy = false
        cases.forEach { assertEquals(NetworkAction.NONE, helper.networkAction(it.state, it.running)) }
    }

    @Test
    fun transparentNetworkUpdateDispatchesStopStartOrNothingWithoutRootCommands() {
        settings.transparentProxy = true
        settings.tproxyAutoConnect = true
        val helper = TransparentProxyHelper(app, settings)
        helper.networkUpdate(NetworkStateHelper.NetworkState(null, false))
        assertCommand(nextCommand(), TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        ShadowVpnService.setPrepareResult(Intent("synthetic.permission.REQUEST"))
        helper.networkUpdate(NetworkStateHelper.NetworkState(null, true))
        assertCommand(nextCommand(), TProxyService.START_VPN_SERVICE_ACTION_NAME)
        settings.tproxyBypassWiFi = setOf("Bypass synthetic WiFi")
        helper.networkUpdate(NetworkStateHelper.NetworkState("Bypass synthetic WiFi", true))
        assertCommand(nextCommand(), TProxyService.STOP_VPN_SERVICE_ACTION_NAME)
        settings.transparentProxy = false
        helper.networkUpdate(NetworkStateHelper.NetworkState(null, false))
        assertNull(shadowOf(app).nextStartedService)
        settings.transparentProxy = true
        settings.tproxyAutoConnect = false
        helper.networkUpdate(NetworkStateHelper.NetworkState(null, true))
        assertNull(shadowOf(app).nextStartedService)
    }

    private fun initializeWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(
            app,
            Configuration.Builder().setExecutor(SynchronousExecutor()).build(),
        )
        workManagerInitialized = true
    }

    private fun nextCommand(): Intent = checkNotNull(shadowOf(app).nextStartedService)

    private fun assertCommand(intent: Intent, action: String) {
        assertEquals(action, intent.action)
        assertEquals(TProxyService::class.java.name, intent.component!!.className)
        assertEquals(app.packageName, intent.component!!.packageName)
    }

    private fun tileEvent(action: String, label: String) = Intent(app, VpnTileService::class.java)
        .putExtra("action", action).putExtra("label", label)

    private data class NetworkCase(
        val wifi: String?,
        val mobile: Boolean,
        val running: Boolean,
        val action: NetworkAction,
    ) {
        val state get() = NetworkStateHelper.NetworkState(wifi, mobile)
    }
}

/** Android's status-bar binder is unavailable on the host; tile state itself remains real. */
@Implements(TileService::class)
class ListeningTileShadow : ShadowService() {
    private val tile = Shadow.newInstanceOf(Tile::class.java)

    @Implementation
    fun getQsTile(): Tile = tile

    companion object {
        @JvmStatic
        @Implementation
        fun requestListeningState(context: Context, component: ComponentName) = Unit
    }
}
