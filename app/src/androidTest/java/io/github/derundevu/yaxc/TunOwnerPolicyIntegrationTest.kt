package io.github.derundevu.yaxc

import android.content.Context
import android.content.ContextWrapper
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.activity.ComponentActivity
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.derundevu.yaxc.service.RuntimeOptions
import io.github.derundevu.yaxc.service.TProxyService
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real Android socket-owner lookup and production admission policy, not kernel VPN exclusion. */
@RunWith(AndroidJUnit4::class)
class TunOwnerPolicyIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var context: Context
    private lateinit var settings: Settings
    private lateinit var previous: Map<String, *>
    private lateinit var peerPackage: String
    private var peerUid = Process.INVALID_UID
    private var ownerTunnel: ParcelFileDescriptor? = null
    private var scenario: ActivityScenario<ComponentActivity>? = null
    private val loopback = InetAddress.getByName("127.0.0.1")

    @Before
    fun prepareIsolatedOwnerLookup() {
        check(InstrumentationRegistry.getArguments().getString("allowVpn") == "true")
        context = instrumentation.targetContext
        check(context.packageName == "io.github.derundevu.yaxc.regression")
        check(Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish")
        check(Build.VERSION.SDK_INT >= 31)
        check(!TProxyService.isActive())
        peerPackage = instrumentation.context.packageName
        peerUid = context.packageManager.getApplicationInfo(peerPackage, 0).uid
        check(peerUid >= Process.FIRST_APPLICATION_UID && peerUid != Process.myUid())
        previous = context.getSharedPreferences("settings", Context.MODE_PRIVATE).all
        settings = Settings(context)
        settings.transparentProxy = false
        settings.tun2socks = true
        settings.enableIpV6 = false
        settings.appsRoutingMode = Settings.AppsRoutingMode.Exclude
        settings.socksAddress = "127.0.0.1"
        settings.socksPort = "1080"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "cmd appops set ${context.packageName} ACTIVATE_VPN allow",
        )).use { it.readBytes() }
        scenario = ActivityScenario.launch(ComponentActivity::class.java)
        check(VpnService.prepare(context) == null)
        establishOwnerLookupVpn()
    }

    @After
    fun restoreIsolatedPreferences() {
        ownerTunnel?.close()
        scenario?.close()
        if (!::previous.isInitialized) return
        val editor = context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear()
        previous.forEach { (key, value) ->
            when (value) {
                is String -> editor.putString(key, value)
                is Long -> editor.putLong(key, value)
                is Int -> editor.putInt(key, value)
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
            }
        }
        assertTrue(editor.commit())
    }

    @Test
    fun realOtherApplicationTcpOwnerIsDeniedWithAndWithoutLiveRoutingCredentials() {
        ServerSocket(0, 1, loopback).use { server ->
            server.soTimeout = 5_000
            client("toybox nc -w 10 -W 10 127.0.0.1 ${server.localPort}").use {
                server.accept().use { accepted ->
                    verifyOwnerPolicy(OsConstants.IPPROTO_TCP,
                        accepted.remoteSocketAddress as InetSocketAddress,
                        accepted.localSocketAddress as InetSocketAddress)
                }
            }
        }
    }

    @Test
    fun realOtherApplicationUdpOwnerIsDeniedWithAndWithoutLiveRoutingCredentials() {
        DatagramSocket(0, loopback).use { server ->
            server.soTimeout = 5_000
            client("toybox nc -u -w 10 -W 10 127.0.0.1 ${server.localPort}").use { client ->
                client.output.write(byteArrayOf(0x41, 0x42, 0x43))
                client.output.flush()
                val packet = DatagramPacket(ByteArray(16), 16)
                server.receive(packet)
                assertEquals(3, packet.length)
                verifyOwnerPolicy(OsConstants.IPPROTO_UDP,
                    packet.socketAddress as InetSocketAddress,
                    InetSocketAddress(loopback, server.localPort))
            }
        }
    }

    @Test
    fun unresolvedNonDnsOwnerIsDeniedButExistingDnsBootstrapExceptionIsUnchanged() {
        // A listening socket is still owned. Close the reservation before querying an absent flow.
        val port = ServerSocket(0, 1, loopback).use { it.localPort }
        val service = policyService(defense = true, excluded = true, authenticated = true)
        val source = InetSocketAddress(loopback, port)
        val remote = InetSocketAddress(loopback, 19081)
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertEquals(Process.INVALID_UID, manager.getConnectionOwnerUid(OsConstants.IPPROTO_TCP, source, remote))
        assertFalse(allow(service, OsConstants.IPPROTO_TCP, source, remote))
        assertEquals(Process.INVALID_UID, identity(service, OsConstants.IPPROTO_TCP, source, remote))
        assertTrue(allow(service, OsConstants.IPPROTO_TCP, source, InetSocketAddress(loopback, 853)))
    }

    private fun verifyOwnerPolicy(protocol: Int, local: InetSocketAddress, remote: InetSocketAddress) {
        // The shell UID is exempt from admission policy. run-as owns this real socket as the
        // separately installed test APK's regular application UID, verified before any rejection.
        val manager = context.getSystemService(ConnectivityManager::class.java)
        assertEquals(peerUid, manager.getConnectionOwnerUid(protocol, local, remote))
        for (authenticated in listOf(false, true)) {
            val allowed = policyService(defense = true, excluded = false, authenticated = authenticated)
            assertTrue(allow(allowed, protocol, local, remote))
            val denied = policyService(defense = true, excluded = true, authenticated = authenticated)
            assertFalse(allow(denied, protocol, local, remote))
            assertEquals(if (authenticated) peerUid else Process.INVALID_UID, identity(allowed, protocol, local, remote))
            if (authenticated) {
                TProxyService::class.java.getDeclaredField("tunIdentityPackages").apply { isAccessible = true }
                    .set(allowed, mapOf(peerUid to setOf(peerPackage, "missing.synthetic.shared.member")))
                assertEquals("Uncertain analytics must not change UID-based routing", peerUid,
                    identity(allowed, protocol, local, remote))
            }
            val disabled = policyService(defense = false, excluded = true, authenticated = authenticated)
            assertTrue(allow(disabled, protocol, local, remote))
        }
    }

    private fun policyService(defense: Boolean, excluded: Boolean, authenticated: Boolean): TProxyService {
        settings.tunOwnerDefense = defense
        settings.appsRouting = if (excluded) peerPackage else ""
        settings.socksUsername = if (authenticated) "fixture-user" else ""
        settings.socksPassword = if (authenticated) "fixture-password" else ""
        val service = attachedService()
        TProxyService::class.java.getDeclaredField("runtimeOptions").apply { isAccessible = true }
            .set(service, RuntimeOptions.capture(settings, context.packageName))
        TProxyService::class.java.getDeclaredField("tunIdentityPackages").apply { isAccessible = true }
            .set(service, mapOf(peerUid to setOf(peerPackage)))
        return service
    }

    private fun attachedService(): TProxyService = TProxyService().also { service ->
        ContextWrapper::class.java.getDeclaredMethod("attachBaseContext", Context::class.java).apply {
            isAccessible = true
        }.invoke(service, context)
    }

    private fun establishOwnerLookupVpn() {
        // Android only reveals a regular app's socket UID to the VPN applying to that UID.
        // No default route/DNS is installed: these loopback controls must not traverse the
        // kernel's application exclusion filter or depend on native proxy availability.
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val previousNetworks = manager.allNetworks.toSet()
        val ready = LinkedBlockingQueue<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network !in previousNetworks && capabilities.ownerUid == Process.myUid()) ready.offer(network)
            }
        }
        manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), callback)
        try {
            ownerTunnel = checkNotNull(attachedService().Builder()
                .setSession("Isolated owner policy")
                .addAddress("198.18.0.2", 32)
                .establish())
            checkNotNull(ready.poll(5, TimeUnit.SECONDS)) { "Owner lookup VPN was not published" }
        } finally {
            manager.unregisterNetworkCallback(callback)
        }
    }

    private fun allow(service: TProxyService, protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Boolean =
        invoke(service, "shouldAllowTunConnection", protocol, local, remote) as Boolean

    private fun identity(service: TProxyService, protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Int =
        invoke(service, "tunConnectionOwnerUid", protocol, local, remote) as Int

    private fun invoke(service: TProxyService, name: String, protocol: Int, local: InetSocketAddress, remote: InetSocketAddress): Any =
        TProxyService::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType, String::class.java,
            Int::class.javaPrimitiveType, String::class.java, Int::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(service, protocol, local.address!!.hostAddress, local.port, remote.address!!.hostAddress, remote.port)

    private fun client(command: String): Client {
        val pipes = instrumentation.uiAutomation.executeShellCommandRw("run-as $peerPackage $command")
        return Client(ParcelFileDescriptor.AutoCloseOutputStream(pipes[1]), ParcelFileDescriptor.AutoCloseInputStream(pipes[0]))
    }

    private class Client(val output: java.io.OutputStream, private val input: java.io.InputStream) : Closeable {
        override fun close() {
            try { output.close() } finally { input.close() }
        }
    }
}
