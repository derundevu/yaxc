package io.github.derundevu.yaxc.service

import XrayCore.XrayCore
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Settings.AppsRoutingMode
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.activity.MainActivity
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.dto.XrayConfig
import io.github.derundevu.yaxc.helper.ConfigHelper
import io.github.derundevu.yaxc.helper.FileHelper
import io.github.derundevu.yaxc.helper.TransparentProxyHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlin.reflect.cast

@SuppressLint("VpnServicePolicy")
class TProxyService : VpnService() {

    companion object {
        init {
            System.loadLibrary("hev-socks5-tunnel")
        }

        const val PKG_NAME = BuildConfig.APPLICATION_ID
        const val STATUS_VPN_SERVICE_ACTION_NAME = "$PKG_NAME.VpnStatus"
        const val STOP_VPN_SERVICE_ACTION_NAME = "$PKG_NAME.VpnStop"
        const val START_VPN_SERVICE_ACTION_NAME = "$PKG_NAME.VpnStart"
        const val RESTART_VPN_SERVICE_ACTION_NAME = "$PKG_NAME.VpnRestart"
        const val NEW_CONFIG_SERVICE_ACTION_NAME = "$PKG_NAME.NewConfig"
        const val NETWORK_UPDATE_SERVICE_ACTION_NAME = "$PKG_NAME.NetworkUpdate"
        private const val VPN_SERVICE_NOTIFICATION_ID = 1
        private const val OPEN_MAIN_ACTIVITY_ACTION_ID = 2
        private const val STOP_VPN_SERVICE_ACTION_ID = 3
        @Volatile
        private var serviceRunning: Boolean = false

        fun isActive(): Boolean = serviceRunning

        fun status(context: Context) = startCommand(context, STATUS_VPN_SERVICE_ACTION_NAME)
        fun stop(context: Context) = startCommand(context, STOP_VPN_SERVICE_ACTION_NAME)
        fun restart(context: Context) = startCommand(context, RESTART_VPN_SERVICE_ACTION_NAME)
        fun newConfig(context: Context) = startCommand(context, NEW_CONFIG_SERVICE_ACTION_NAME)

        fun start(context: Context, check: Boolean) {
            if (check && prepare(context) != null) {
                Log.e(
                    "TProxyService",
                    "Can't start: VpnService#prepare(): needs user permission"
                )
                return
            }
            startCommand(context, START_VPN_SERVICE_ACTION_NAME, true)
        }

        private fun startCommand(context: Context, name: String, foreground: Boolean = false) {
            Intent(context, TProxyService::class.java).also {
                it.action = name
                if (foreground) {
                    context.startForegroundService(it)
                } else {
                    context.startService(it)
                }
            }
        }
    }

    private val notificationManager by lazy { getSystemService(NotificationManager::class.java) }
    private val connectivityManager by lazy { getSystemService(ConnectivityManager::class.java) }
    private val settings by lazy { Settings(applicationContext) }
    private val transparentProxyHelper by lazy { TransparentProxyHelper(this, settings) }
    private val configRepository by lazy { Yaxc::class.cast(application).configRepository }
    private val profileRepository by lazy { Yaxc::class.cast(application).profileRepository }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private var isRunning: Boolean = false
    private var tunDevice: ParcelFileDescriptor? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private var toast: Toast? = null
    private val tunOwnerPolicyCache = linkedMapOf<Int, Boolean>()
    private var activeTransparentProxy: Boolean = false

    private external fun TProxyStartService(configPath: String, fd: Int)
    private external fun TProxyStopService()
    private external fun TProxyGetStats(): LongArray

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            when (intent?.action) {
                START_VPN_SERVICE_ACTION_NAME -> start(getProfile(), globalConfigs())
                RESTART_VPN_SERVICE_ACTION_NAME -> restart(getProfile(), globalConfigs())
                NEW_CONFIG_SERVICE_ACTION_NAME -> newConfig(getProfile(), globalConfigs())
                STOP_VPN_SERVICE_ACTION_NAME -> stopVPN()
                STATUS_VPN_SERVICE_ACTION_NAME -> broadcastStatus()
                NETWORK_UPDATE_SERVICE_ACTION_NAME -> transparentProxyHelper.networkUpdate()
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVPN()
    }

    override fun onDestroy() {
        scope.cancel()
        cellularCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        cellularCallback = null
        serviceRunning = false
        toast = null
        synchronized(tunOwnerPolicyCache) { tunOwnerPolicyCache.clear() }
        super.onDestroy()
    }

    private fun configName(profile: Profile?): String = profile?.name ?: settings.tunName

    private fun getIsRunning(): Boolean {
        return if (activeTransparentProxy) {
            transparentProxyHelper.isRunning()
        } else {
            isRunning
        }
    }

    private suspend fun getProfile(): Profile? {
        return if (settings.selectedProfile == 0L) {
            null
        } else {
            profileRepository.find(settings.selectedProfile)
        }
    }

    private suspend fun globalConfigs(): Config {
        return configRepository.get()
    }

    private fun getConfig(profile: Profile, globalConfigs: Config): XrayConfig? {
        val dir: File = applicationContext.filesDir
        val config: File = settings.xrayConfig()
        val configHelper = runCatching { ConfigHelper(settings, globalConfigs, profile.config) }
        val error: String = if (configHelper.isSuccess) {
            FileHelper.createOrUpdate(config, configHelper.getOrNull().toString())
            XrayCore.test(dir.absolutePath, config.absolutePath)
        } else {
            configHelper.exceptionOrNull()?.message ?: getString(R.string.invalidProfile)
        }
        if (error.isNotEmpty()) {
            Log.e(
                "TProxyService",
                "Runtime config validation failed: $error; configPath=${config.absolutePath}; " +
                    "dns4=${settings.primaryDns},${settings.secondaryDns}; " +
                    "dns6=${settings.primaryDnsV6},${settings.secondaryDnsV6}; ipv6=${settings.enableIpV6}"
            )
            showToast(userFacingConfigError(error))
            return null
        }
        return XrayConfig(dir.absolutePath, config.absolutePath)
    }

    private fun userFacingConfigError(error: String): String {
        val normalized = error.lowercase()
        return when {
            "geoip.dat" in normalized || "geosite.dat" in normalized ->
                getString(R.string.installAssetsFirst)

            else -> error
        }
    }

    private fun start(profile: Profile?, globalConfigs: Config) {
        if (profile == null) return
        getConfig(profile, globalConfigs)?.let {
            startXray(it)
            startVPN(profile)
        }
    }

    private fun restart(profile: Profile?, globalConfigs: Config) {
        if (!getIsRunning()) {
            start(profile, globalConfigs)
            return
        }
        if (profile == null) {
            stopVPN()
            return
        }
        stopVpnInternal(notifyStop = false, stopService = false)
        start(profile, globalConfigs)
    }

    private fun newConfig(profile: Profile?, globalConfigs: Config) {
        if (!getIsRunning() || profile == null) return
        stopXray()
        getConfig(profile, globalConfigs).also {
            if (it == null) stopVPN() else startXray(it)
        }?.let {
            val name = configName(profile)
            val notification = createNotification(name)
            showToast(name)
            broadcastStart(NEW_CONFIG_SERVICE_ACTION_NAME, name)
            notificationManager.notify(VPN_SERVICE_NOTIFICATION_ID, notification)
        }
    }

    private fun startXray(config: XrayConfig) {
        if (settings.transparentProxy) transparentProxyHelper.startService()
        else XrayCore.start(config.dir, config.file)
    }

    private fun stopXray() {
        if (settings.transparentProxy) transparentProxyHelper.stopService()
        else XrayCore.stop()
    }

    private fun startVPN(profile: Profile?) {
        activeTransparentProxy = settings.transparentProxy
        if (settings.transparentProxy) {
            transparentProxyHelper.enableProxy()
            transparentProxyHelper.monitorNetwork()
        } else if (settings.tun2socks) {
            synchronized(tunOwnerPolicyCache) { tunOwnerPolicyCache.clear() }
            val result = runCatching {
                /** Create Tun */
                val tun = Builder()
                val tunName = settings.tunName.trim().ifBlank { getString(R.string.appName) }

                /** Basic tun config */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tun.setMetered(false)
                tun.setMtu(settings.tunMtu)
                tun.setSession(tunName)

                /** IPv4 */
                tun.addAddress(settings.tunAddress, settings.tunPrefix)
                tun.addDnsServer(settings.primaryDns)
                tun.addDnsServer(settings.secondaryDns)

                /** IPv6 */
                if (settings.enableIpV6) {
                    tun.addAddress(settings.tunAddressV6, settings.tunPrefixV6)
                    tun.addDnsServer(settings.primaryDnsV6)
                    tun.addDnsServer(settings.secondaryDnsV6)
                    tun.addRoute("::", 0)
                }

                /** Bypass LAN (IPv4) */
                if (settings.bypassLan) {
                    settings.tunRoutes.forEach {
                        val address = it.split('/')
                        tun.addRoute(address[0], address[1].toInt())
                    }
                } else {
                    tun.addRoute("0.0.0.0", 0)
                }

                /** Apps Routing */
                applyAppsRouting(tun)

                /** Build tun device */
                tunDevice = tun.establish()

                /** Check tun device */
                if (tunDevice == null) {
                    error("tun#establish failed")
                }

                /** Create, Update tun2socks config */
                val tun2socksConfig = arrayListOf(
                    "tunnel:",
                    "  name: $tunName",
                    "  mtu: ${settings.tunMtu}",
                    "socks5:",
                    "  address: ${settings.socksAddress}",
                    "  port: ${settings.socksPort}",
                )
                if (
                    settings.socksUsername.trim().isNotEmpty() &&
                    settings.socksPassword.trim().isNotEmpty()
                ) {
                    tun2socksConfig.add("  username: ${settings.socksUsername}")
                    tun2socksConfig.add("  password: ${settings.socksPassword}")
                }
                tun2socksConfig.add(if (settings.socksUdp) "  udp: udp" else "  udp: tcp")
                tun2socksConfig.add("")
                FileHelper.createOrUpdate(
                    settings.tun2socksConfig(),
                    tun2socksConfig.joinToString("\n")
                )

                /** Start tun2socks */
                TProxyStartService(settings.tun2socksConfig().absolutePath, tunDevice!!.fd)
            }
            result.exceptionOrNull()?.let { error ->
                Log.e(
                    "TProxyService",
                    "Failed to start tun2socks VPN; dns4=${settings.primaryDns},${settings.secondaryDns}; " +
                        "dns6=${settings.primaryDnsV6},${settings.secondaryDnsV6}; ipv6=${settings.enableIpV6}",
                    error
                )
                showToast(error.message ?: "Failed to start VPN")
                return
            }
        }

        /** Service Notification */
        val name = configName(profile)
        startForeground(VPN_SERVICE_NOTIFICATION_ID, createNotification(name))

        /** Listen for cellular changes */
        if (cellularCallback == null) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cellularCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    this@TProxyService.transparentProxyHelper.networkUpdate()
                }
            }
            connectivityManager.registerNetworkCallback(request, cellularCallback!!)
        }

        /** Broadcast start event */
        showToast("Start VPN")
        isRunning = true
        serviceRunning = true
        broadcastStart(START_VPN_SERVICE_ACTION_NAME, name)
    }

    private fun stopVPN() {
        stopVpnInternal(notifyStop = true, stopService = true)
    }

    private fun stopVpnInternal(
        notifyStop: Boolean,
        stopService: Boolean,
    ) {
        synchronized(tunOwnerPolicyCache) { tunOwnerPolicyCache.clear() }
        if (activeTransparentProxy) {
            transparentProxyHelper.disableProxy()
        } else {
            TProxyStopService()
            runCatching { tunDevice?.close() }
            tunDevice = null
        }
        isRunning = false
        activeTransparentProxy = false
        serviceRunning = false
        stopXray()
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (notifyStop) {
            showToast("Stop VPN")
            broadcastStop()
        }
        if (stopService) {
            stopSelf()
        }
    }

    private fun applyAppsRouting(tun: Builder) {
        val selectedPackages = settings.appsRouting
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it != applicationContext.packageName }
            .toSet()

        when (settings.appsRoutingMode) {
            AppsRoutingMode.Disabled -> {
                tun.addDisallowedApplication(applicationContext.packageName)
                return
            }

            AppsRoutingMode.Exclude -> {
                tun.addDisallowedApplication(applicationContext.packageName)
            }

            AppsRoutingMode.Include -> Unit
        }

        selectedPackages.forEach { packageName ->
            val result = runCatching {
                when (settings.appsRoutingMode) {
                    AppsRoutingMode.Disabled -> Unit
                    AppsRoutingMode.Exclude -> tun.addDisallowedApplication(packageName)
                    AppsRoutingMode.Include -> tun.addAllowedApplication(packageName)
                }
            }
            result.exceptionOrNull()?.let { error ->
                Log.w("TProxyService", "Skip apps routing package: $packageName", error)
            }
        }
    }

    @Keep
    private fun shouldUseTunConnectionOwnerCheck(): Boolean {
        return settings.tunOwnerDefense && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    @Keep
    private fun shouldAllowTunConnection(
        protocol: Int,
        localAddress: String,
        localPort: Int,
        remoteAddress: String,
        remotePort: Int,
    ): Boolean {
        if (!settings.tunOwnerDefense || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return true
        }

        val local = runCatching {
            InetSocketAddress(InetAddress.getByName(localAddress), localPort)
        }.getOrElse {
            Log.w(
                "TProxyService",
                "tun0 defense failed to parse local address: $localAddress:$localPort",
                it,
            )
            return false
        }
        val remote = runCatching {
            InetSocketAddress(InetAddress.getByName(remoteAddress), remotePort)
        }.getOrElse {
            Log.w(
                "TProxyService",
                "tun0 defense failed to parse remote address: $remoteAddress:$remotePort",
                it,
            )
            return false
        }

        val ownerUid = runCatching {
            connectivityManager.getConnectionOwnerUid(protocol, local, remote)
        }.getOrElse {
            Log.w(
                "TProxyService",
                "tun0 defense failed to resolve owner uid for $localAddress:$localPort -> $remoteAddress:$remotePort",
                it,
            )
            return false
        }

        if (ownerUid == Process.INVALID_UID) {
            val isDnsBootstrapFlow = localPort == 53 ||
                localPort == 853 ||
                remotePort == 53 ||
                remotePort == 853
            if (isDnsBootstrapFlow) {
                return true
            }

            Log.w(
                "TProxyService",
                "tun0 defense blocked unresolved uid for $localAddress:$localPort -> $remoteAddress:$remotePort"
            )
            return false
        }

        if (ownerUid == Process.myUid() || ownerUid < Process.FIRST_APPLICATION_UID) {
            return true
        }

        synchronized(tunOwnerPolicyCache) {
            tunOwnerPolicyCache[ownerUid]?.let { return it }
        }

        val packages = packageManager.getPackagesForUid(ownerUid)
            ?.asSequence()
            ?.map(String::trim)
            ?.filter(String::isNotEmpty)
            ?.toSet()
            .orEmpty()

        val allowedPackages = settings.appsRouting
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .filter { it != applicationContext.packageName }
            .toSet()

        val isAllowed = when (settings.appsRoutingMode) {
            AppsRoutingMode.Disabled -> true
            AppsRoutingMode.Exclude -> packages.none {
                it == applicationContext.packageName || it in allowedPackages
            }
            AppsRoutingMode.Include -> packages.any { it in allowedPackages }
        }

        synchronized(tunOwnerPolicyCache) {
            tunOwnerPolicyCache[ownerUid] = isAllowed
        }

        if (!isAllowed) {
            Log.w(
                "TProxyService",
                "tun0 defense blocked uid=$ownerUid packages=$packages for $localAddress:$localPort -> $remoteAddress:$remotePort"
            )
        }

        return isAllowed
    }

    private fun broadcastStart(action: String, configName: String) {
        Intent(action).also {
            it.`package` = BuildConfig.APPLICATION_ID
            it.putExtra("profile", configName)
            sendBroadcast(it)
        }
    }

    private fun broadcastStop() {
        Intent(STOP_VPN_SERVICE_ACTION_NAME).also {
            it.`package` = BuildConfig.APPLICATION_ID
            sendBroadcast(it)
        }
    }

    private fun broadcastStatus() {
        Intent(STATUS_VPN_SERVICE_ACTION_NAME).also {
            it.`package` = BuildConfig.APPLICATION_ID
            it.putExtra("isRunning", getIsRunning())
            sendBroadcast(it)
        }
    }

    private fun createNotification(name: String): Notification {
        val pendingActivity = PendingIntent.getActivity(
            applicationContext,
            OPEN_MAIN_ACTIVITY_ACTION_ID,
            Intent(applicationContext, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        val pendingStop = PendingIntent.getService(
            applicationContext,
            STOP_VPN_SERVICE_ACTION_ID,
            Intent(applicationContext, TProxyService::class.java).also {
                it.action = STOP_VPN_SERVICE_ACTION_NAME
            },
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat
            .Builder(applicationContext, createNotificationChannel())
            .setSmallIcon(R.drawable.baseline_vpn_lock)
            .setContentTitle(name)
            .setContentIntent(pendingActivity)
            .addAction(0, getString(R.string.vpnStop), pendingStop)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel(): String {
        val id = "yaxcVpnServiceNotification"
        val name = "yaxc VPN Service"
        val channel = NotificationChannel(id, name, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)
        return id
    }

    private fun showToast(message: String) {
        Handler(Looper.getMainLooper()).post {
            toast?.cancel()
            toast = Toast.makeText(applicationContext, message, Toast.LENGTH_SHORT).also {
                it.show()
            }
        }
    }

}
