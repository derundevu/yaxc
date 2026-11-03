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
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import androidx.annotation.Keep
import androidx.core.app.NotificationCompat
import androidx.room.withTransaction
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Settings.AppsRoutingMode
import io.github.derundevu.yaxc.Yaxc
import io.github.derundevu.yaxc.activity.MainActivity
import io.github.derundevu.yaxc.database.YaxcDatabase
import io.github.derundevu.yaxc.dto.XrayConfig
import io.github.derundevu.yaxc.helper.ConfigHelper
import io.github.derundevu.yaxc.helper.LiveRoutingHelper
import io.github.derundevu.yaxc.helper.ConnectionFailure
import io.github.derundevu.yaxc.helper.ConnectionFailureState
import io.github.derundevu.yaxc.helper.FileHelper
import io.github.derundevu.yaxc.helper.TransparentProxyHelper
import io.github.derundevu.yaxc.helper.XrayValidationHelper
import io.github.derundevu.yaxc.service.RuntimeOptions.Mode as RuntimeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import org.json.JSONObject
import java.net.InetSocketAddress
import kotlin.coroutines.CoroutineContext
import kotlin.reflect.cast

@SuppressLint("VpnServicePolicy")
class TProxyService : VpnService() {

    companion object {
        private object NativeLibrary {
            init {
                System.loadLibrary("hev-socks5-tunnel")
            }

            fun ensureLoaded() = Unit
        }

        const val PKG_NAME = BuildConfig.APPLICATION_ID
        const val STATUS_VPN_SERVICE_ACTION_NAME = "$PKG_NAME.VpnStatus"
        const val STOP_VPN_SERVICE_ACTION_NAME = "$PKG_NAME.VpnStop"
        const val STOP_FAILED_SERVICE_ACTION_NAME = "$PKG_NAME.VpnStopFailed"
        const val START_VPN_SERVICE_ACTION_NAME = "$PKG_NAME.VpnStart"
        const val RESTART_VPN_SERVICE_ACTION_NAME = "$PKG_NAME.VpnRestart"
        const val NEW_CONFIG_SERVICE_ACTION_NAME = "$PKG_NAME.NewConfig"
        const val APPLY_ROUTING_SERVICE_ACTION_NAME = "$PKG_NAME.ApplyRouting"
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
        fun applyRouting(context: Context) = startCommand(context, APPLY_ROUTING_SERVICE_ACTION_NAME)

        fun start(context: Context, check: Boolean) {
            if (check && prepare(context) != null) {
                Log.e(
                    "TProxyService",
                    "Can't start: VpnService#prepare(): needs user permission"
                )
                ConnectionFailureState.report(
                    ConnectionFailure(context.getString(R.string.connectionPermissionRequired))
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
    private val database by lazy { YaxcDatabase.ref(applicationContext) }
    internal var commandDispatcher: CoroutineDispatcher = Dispatchers.IO
    private val scope by lazy { CoroutineScope(SupervisorJob() + commandDispatcher) }
    private val runtimeLock = Any()
    private var commandJob: Job? = null
    private var stopJob: Job? = null
    @Volatile
    private var configJob: Job? = null
    // Android service lifetime is owned by the main thread, independently of native teardown.
    private var latestStartId = 0
    private var startGeneration = 0L
    private var stopPending = false
    private var serviceDestroyed = false

    private var isRunning: Boolean = false
    private var tunDevice: ParcelFileDescriptor? = null
    private var cellularCallback: ConnectivityManager.NetworkCallback? = null
    private val tunOwnerPolicyCache = linkedMapOf<Int, Boolean>()
    private var activeMode: RuntimeMode? = null
    private var activeConfig: RuntimeSnapshot? = null
    @Volatile
    private var activeProfileName: String? = null
    @Volatile
    private var runtimeOptions: RuntimeOptions? = null
    @Volatile
    private var tunIdentityPackages: Map<String, Int> = emptyMap()

    private data class RuntimeSnapshot(
        val text: String,
        val options: RuntimeOptions,
        val restartKey: String,
        val identityPackages: Map<String, Int>,
    )
    private data class ConfigCandidate(val name: String, val snapshot: RuntimeSnapshot)

    /** Only the platform/native boundary is replaceable; command ownership stays in the service. */
    internal interface RuntimeBoundary {
        suspend fun validate(config: String): String
        fun startCore(config: XrayConfig, transparent: Boolean)
        fun stopCore(transparent: Boolean)
        fun applyRouting(config: String): String
        fun establish(builder: Builder): ParcelFileDescriptor?
        fun startTunnel(configPath: String, fd: Int): Boolean
        fun stopTunnel(): Boolean
        fun tunnelRunning(): Boolean
        fun transparentRunning(): Boolean
        fun enableTransparent()
        fun disableTransparent()
    }

    internal var runtime: RuntimeBoundary = object : RuntimeBoundary {
        override suspend fun validate(config: String) = XrayValidationHelper.validate(this@TProxyService, config)
        override fun startCore(config: XrayConfig, transparent: Boolean) {
            if (transparent) {
                transparentProxyHelper.startService(checkNotNull(runtimeOptions?.transparent))
            } else {
                val diagnostic = XrayCore.start(config.dir, config.file)
                check(diagnostic.isEmpty()) { diagnostic }
            }
        }
        override fun stopCore(transparent: Boolean) {
            if (transparent) transparentProxyHelper.stopService() else XrayCore.stop()
        }
        override fun applyRouting(config: String) = XrayCore.applyRouting(config)
        override fun establish(builder: Builder) = builder.establish()
        override fun startTunnel(configPath: String, fd: Int): Boolean {
            NativeLibrary.ensureLoaded()
            return TProxyStartService(configPath, fd)
        }
        override fun stopTunnel(): Boolean {
            NativeLibrary.ensureLoaded()
            return TProxyStopService()
        }
        override fun tunnelRunning(): Boolean {
            NativeLibrary.ensureLoaded()
            return TProxyIsRunning()
        }
        override fun transparentRunning() = transparentProxyHelper.isRunning()
        override fun enableTransparent() {
            transparentProxyHelper.enableProxy()
            transparentProxyHelper.monitorNetwork()
        }
        override fun disableTransparent() = transparentProxyHelper.disableProxy()
    }

    private external fun TProxyStartService(configPath: String, fd: Int): Boolean
    private external fun TProxyStopService(): Boolean
    private external fun TProxyIsRunning(): Boolean
    private external fun TProxyGetStats(): LongArray

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        val action = intent?.action
        if (action == START_VPN_SERVICE_ACTION_NAME || action == RESTART_VPN_SERVICE_ACTION_NAME) {
            startGeneration++
            stopPending = false
            startForeground(
                VPN_SERVICE_NOTIFICATION_ID,
                createNotification(activeProfileName ?: getString(R.string.connectionConnecting)),
            )
        }
        val generation = startGeneration
        when (action) {
            START_VPN_SERVICE_ACTION_NAME, RESTART_VPN_SERVICE_ACTION_NAME,
            NEW_CONFIG_SERVICE_ACTION_NAME, APPLY_ROUTING_SERVICE_ACTION_NAME -> {
                val previous = commandJob
                val previousStop = stopJob
                configJob?.cancel()
                ConnectionFailureState.clear()
                configJob = scope.launch {
                    try {
                        // Cancelling an intermediate config waiter must not let START overtake STOP.
                        if (previousStop !== previous) previousStop?.join()
                        previous?.join()
                        currentCoroutineContext().ensureActive()
                        configure(checkNotNull(action), generation)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Exception) {
                        currentCoroutineContext().ensureActive()
                        Log.e("TProxyService", "Connection operation failed", error)
                        val failure = ConnectionFailure.fromDiagnostic(
                            error.message?.takeIf { it.isNotBlank() } ?: getString(R.string.invalidProfile),
                            applicationContext.filesDir,
                        )
                        currentCoroutineContext().ensureActive()
                        ConnectionFailureState.report(failure)
                        if (!getIsRunning()) {
                            stopVpn(generation, notifyStop = false)
                        }
                        broadcastStatus()
                    } finally {
                        withContext(NonCancellable + Dispatchers.Main.immediate) {
                            finishPendingStop()
                        }
                    }
                }
                commandJob = configJob
            }
            STOP_VPN_SERVICE_ACTION_NAME -> enqueueStop()
            STATUS_VPN_SERVICE_ACTION_NAME -> scope.launch {
                broadcastStatus()
                withContext(Dispatchers.Main.immediate) { finishPendingStop() }
            }
            NETWORK_UPDATE_SERVICE_ACTION_NAME -> scope.launch {
                transparentProxyHelper.networkUpdate()
                withContext(Dispatchers.Main.immediate) { finishPendingStop() }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        enqueueStop()
    }

    override fun onDestroy() {
        serviceDestroyed = true
        stopPending = false
        scope.cancel()
        stopVpnInternal()
        cellularCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        cellularCallback = null
        serviceRunning = getIsRunning()
        synchronized(tunOwnerPolicyCache) { tunOwnerPolicyCache.clear() }
        super.onDestroy()
    }


    private fun getIsRunning(): Boolean = synchronized(runtimeLock) {
        when (activeMode) {
            RuntimeMode.TRANSPARENT -> isRunning && runtime.transparentRunning()
            RuntimeMode.TUN -> isRunning && runtime.tunnelRunning()
            RuntimeMode.CORE -> isRunning
            null -> false
        }
    }

    private suspend fun configure(action: String, generation: Long) {
        if (action == START_VPN_SERVICE_ACTION_NAME && getIsRunning()) return
        if (isConditionalUpdate(action) && !getIsRunning()) {
            broadcastStatus()
            return
        }
        var validated: RuntimeSnapshot? = null
        var diagnostic = ""
        while (true) {
            var pending: RuntimeSnapshot? = null
            // Keep the selected connection, group members and global config in one committed database state.
            // Validation runs outside the transaction and runtime lock; STOP never waits for it.
            val complete = database.withTransaction {
                val selected = settings.selectedProfile
                val standalone = selected == LiveRoutingHelper.CONNECTION_PROFILE_ID
                val profile = if (selected > 0L && database.profileDao().indexOf(selected) != null) {
                    profileRepository.find(selected)
                } else null
                val candidate = if (standalone || profile != null) {
                    val options = RuntimeOptions.capture(settings, applicationContext.packageName)
                    val plan = LiveRoutingHelper.decode(settings.liveRoutingPlan)
                    check(options.mode != RuntimeMode.TRANSPARENT || (!standalone && plan.groups.isEmpty() && plan.rules.isEmpty())) {
                        "Live proxy groups are unavailable in root mode. Use VPN or SOCKS mode."
                    }
                    check(plan.rules.none { rule -> rule.enabled && rule.packages.isNotEmpty() } ||
                        (options.mode == RuntimeMode.TUN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)) {
                        "Application rules require VPN mode on Android 10 or newer."
                    }
                    val profiles = plan.groups.flatMap { group -> group.profileIds }.distinct()
                        .mapNotNull { id ->
                            if (database.profileDao().indexOf(id) == null) null
                            else id to profileRepository.find(id)
                        }.toMap()
                    val packageUids = if (options.mode == RuntimeMode.TUN && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        packageManager.getInstalledApplications(0).associate { app -> app.packageName to app.uid }
                    } else emptyMap()
                    // Independent routing owns global runtime settings, never a member's
                    // listeners, DNS, policy or routing. Only outbound graphs come from members.
                    val base = ConfigHelper(settings, configRepository.get(), profile?.config ?: "{}").toString()
                    val text = if (options.mode == RuntimeMode.TRANSPARENT) base else LiveRoutingHelper.compile(
                        base, plan, profiles, packageUids, settings.socksUsername.trim(), settings.socksPassword.trim(),
                        standalone = standalone,
                        pingAddress = settings.pingAddress.trim(),
                    )
                    ConfigCandidate(
                        if (standalone) getString(R.string.liveRouting) else checkNotNull(profile).name,
                        RuntimeSnapshot(text, options, restartKey(base), packageUids),
                    )
                } else null
                if (candidate == null) {
                    currentCoroutineContext().ensureActive()
                    check(action != APPLY_ROUTING_SERVICE_ACTION_NAME) {
                        "The selected default profile is missing. Live routing cannot replace the running session."
                    }
                    val hadRuntime = synchronized(runtimeLock) { activeMode != null }
                    currentCoroutineContext().ensureActive()
                    ConnectionFailureState.report(ConnectionFailure(getString(R.string.connectionMissingProfile)))
                    check(stopVpn(generation, notifyStop = hadRuntime)) {
                        "Failed to stop the previous VPN"
                    }
                    broadcastStatus()
                    return@withTransaction true
                }
                val context = currentCoroutineContext()
                synchronized(runtimeLock) {
                    context.ensureActive()
                    if (isConditionalUpdate(action) && !getIsRunning()) {
                        broadcastStatus()
                        return@synchronized true
                    }
                    if (isConditionalUpdate(action) && candidate.snapshot == activeConfig) {
                        activeProfileName = candidate.name
                        notificationManager.notify(VPN_SERVICE_NOTIFICATION_ID, createNotification(candidate.name))
                        broadcastStatus()
                        return@synchronized true
                    }
                    if (action == APPLY_ROUTING_SERVICE_ACTION_NAME) {
                        check(canApplyRouting(candidate.snapshot)) {
                            "Other connection settings changed. Reconnect explicitly before applying live routing."
                        }
                    }
                    // Re-read after asynchronous validation. A newer committed edit must not be
                    // replaced by a stale candidate even when it did not send another command.
                    if (candidate.snapshot != validated) {
                        pending = candidate.snapshot
                        return@synchronized false
                    }
                    check(diagnostic.isEmpty()) { diagnostic }
                    applyCandidate(action, candidate, context)
                    true
                }
            }
            if (complete) {
                return
            }
            val snapshot = checkNotNull(pending)
            diagnostic = runtime.validate(snapshot.text)
            currentCoroutineContext().ensureActive()
            validated = snapshot
        }
    }

    private fun writeConfig(snapshot: RuntimeSnapshot): XrayConfig {
        val file = settings.xrayConfig()
        FileHelper.createOrUpdate(file, snapshot.text)
        return XrayConfig(applicationContext.filesDir.absolutePath, file.absolutePath)
    }

    private fun isConditionalUpdate(action: String) =
        action == NEW_CONFIG_SERVICE_ACTION_NAME || action == APPLY_ROUTING_SERVICE_ACTION_NAME

    private fun canApplyRouting(snapshot: RuntimeSnapshot): Boolean {
        val active = activeConfig ?: return false
        return activeMode != RuntimeMode.TRANSPARENT && getIsRunning() &&
            active.options == snapshot.options && active.restartKey == snapshot.restartKey
    }

    /** Rules, balancers and outbound graphs are live; listeners, DNS and routing resolution are not. */
    private fun restartKey(text: String): String {
        val root = JSONObject(text)
        root.remove("outbounds")
        root.remove("_yaxcLiveHealth")
        root.optJSONObject("routing")?.let { routing ->
            routing.remove("rules")
            routing.remove("balancers")
        }
        return root.toString()
    }

    private fun applyCandidate(action: String, candidate: ConfigCandidate, context: CoroutineContext) {
        val snapshot = candidate.snapshot
        if (isConditionalUpdate(action) && canApplyRouting(snapshot)) {
            val diagnostic = runtime.applyRouting(snapshot.text)
            check(diagnostic.isEmpty()) { diagnostic }
            // Publish only after the native routing snapshot commits. Never restart on rejection.
            activeConfig = snapshot
            tunIdentityPackages = snapshot.identityPackages
            activeProfileName = candidate.name
            writeConfig(snapshot)
            ConnectionFailureState.clear()
            notificationManager.notify(VPN_SERVICE_NOTIFICATION_ID, createNotification(candidate.name))
            broadcastStart(NEW_CONFIG_SERVICE_ACTION_NAME, candidate.name)
            return
        }
        if (
            isConditionalUpdate(action) && getIsRunning() &&
            activeConfig?.options == snapshot.options
        ) {
            try {
                val transparent = activeMode == RuntimeMode.TRANSPARENT
                runtime.stopCore(transparent)
                runtime.startCore(writeConfig(snapshot), transparent)
                context.ensureActive()
                activeConfig = snapshot
                tunIdentityPackages = snapshot.identityPackages
                activeProfileName = candidate.name
                notificationManager.notify(VPN_SERVICE_NOTIFICATION_ID, createNotification(candidate.name))
                broadcastStart(NEW_CONFIG_SERVICE_ACTION_NAME, candidate.name)
            } catch (error: Exception) {
                stopVpnInternal()
                throw error
            }
            return
        }
        if (activeMode != null) {
            check(stopVpnInternal()) {
                "Failed to stop the previous VPN"
            }
        }
        context.ensureActive()
        val config = writeConfig(snapshot)
        activeMode = snapshot.options.mode
        runtimeOptions = snapshot.options
        tunIdentityPackages = snapshot.identityPackages
        try {
            runtime.startCore(config, activeMode == RuntimeMode.TRANSPARENT)
            context.ensureActive()
            startVPN(candidate, context, action)
        } catch (error: Exception) {
            stopVpnInternal()
            throw error
        }
    }


    private fun startVPN(candidate: ConfigCandidate, context: CoroutineContext, action: String) {
        if (activeMode == RuntimeMode.TRANSPARENT) {
            runtime.enableTransparent()
        } else if (activeMode == RuntimeMode.TUN) {
            val options = checkNotNull(candidate.snapshot.options.tun)
            synchronized(tunOwnerPolicyCache) { tunOwnerPolicyCache.clear() }
            val result = runCatching {
                /** Create Tun */
                val tun = Builder()
                val tunName = options.name.ifBlank { getString(R.string.appName) }

                /** Basic tun config */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tun.setMetered(false)
                tun.setMtu(options.mtu)
                tun.setSession(tunName)

                /** IPv4 */
                tun.addAddress(options.address, options.prefix)
                tun.addDnsServer(options.primaryDns)
                tun.addDnsServer(options.secondaryDns)

                /** IPv6 */
                options.ipv6?.let { ipv6 ->
                    tun.addAddress(ipv6.address, ipv6.prefix)
                    tun.addDnsServer(ipv6.primaryDns)
                    tun.addDnsServer(ipv6.secondaryDns)
                    tun.addRoute("::", 0)
                }

                /** Bypass LAN (IPv4) */
                if (options.bypassLan) {
                    options.routes.forEach {
                        val address = it.split('/')
                        tun.addRoute(address[0], address[1].toInt())
                    }
                } else {
                    tun.addRoute("0.0.0.0", 0)
                }

                /** Apps Routing */
                applyAppsRouting(tun, options)

                /** Build tun device */
                tunDevice = runtime.establish(tun)

                /** Check tun device */
                if (tunDevice == null) {
                    error("tun#establish failed")
                }

                /** Create, Update tun2socks config */
                val tun2socksConfig = arrayListOf(
                    "tunnel:",
                    "  name: $tunName",
                    "  mtu: ${options.mtu}",
                    "socks5:",
                    "  address: ${options.socksAddress}",
                    "  port: ${options.socksPort}",
                )
                if (options.socksUsername.isNotEmpty() && options.socksPassword.isNotEmpty()) {
                    tun2socksConfig.add("  username: ${options.socksUsername}")
                    tun2socksConfig.add("  password: ${options.socksPassword}")
                }
                tun2socksConfig.add(if (options.socksUdp) "  udp: udp" else "  udp: tcp")
                tun2socksConfig.add("")
                FileHelper.createOrUpdate(
                    settings.tun2socksConfig(),
                    tun2socksConfig.joinToString("\n")
                )

                /** Start tun2socks */
                check(runtime.startTunnel(settings.tun2socksConfig().absolutePath, tunDevice!!.fd)) {
                    "Failed to start tun2socks worker"
                }
            }
            result.exceptionOrNull()?.let { error ->
                Log.e(
                    "TProxyService",
                    "Failed to start tun2socks VPN",
                    error
                )
                throw error
            }
        }
        context.ensureActive()

        /** Service Notification */
        val name = candidate.name
        activeProfileName = name
        notificationManager.notify(VPN_SERVICE_NOTIFICATION_ID, createNotification(name))

        /** Listen for cellular changes */
        if (cellularCallback == null) {
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
                .build()
            cellularCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    val transparent = synchronized(runtimeLock) {
                        activeMode == RuntimeMode.TRANSPARENT && isRunning
                    }
                    if (transparent) this@TProxyService.transparentProxyHelper.networkUpdate()
                }
            }
            connectivityManager.registerNetworkCallback(request, cellularCallback!!)
        }

        /** Broadcast start event */
        isRunning = true
        serviceRunning = true
        activeConfig = candidate.snapshot
        ConnectionFailureState.clear()
        broadcastStart(
            if (action == NEW_CONFIG_SERVICE_ACTION_NAME) action else START_VPN_SERVICE_ACTION_NAME,
            name,
        )
    }

    private fun enqueueStop() {
        val previousStop = stopJob
        val generation = startGeneration
        configJob?.cancel()
        configJob = null
        // The isolated constructor cannot be interrupted; it must not delay active teardown.
        stopJob = scope.launch {
            previousStop?.join()
            stopVpn(generation, notifyStop = true)
        }
        commandJob = stopJob
    }

    private suspend fun stopVpn(generation: Long, notifyStop: Boolean): Boolean {
        if (!stopVpnInternal()) {
            if (notifyStop) {
                sendBroadcast(Intent(STOP_FAILED_SERVICE_ACTION_NAME).setPackage(BuildConfig.APPLICATION_ID))
            }
            return false
        }
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            if (serviceDestroyed) return@withContext
            if (generation == startGeneration) {
                stopPending = true
                finishPendingStop()
            }
            // A replacement runtime waits for this job, so STOP is acknowledged before its START.
            if (notifyStop) broadcastStop()
        }
        return true
    }

    private fun finishPendingStop() {
        if (serviceDestroyed || !stopPending) return
        // AMS may already know a newer start that onStartCommand has not received yet.
        if (stopSelfResult(latestStartId)) {
            stopPending = false
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun stopVpnInternal(): Boolean = synchronized(runtimeLock) {
        synchronized(tunOwnerPolicyCache) { tunOwnerPolicyCache.clear() }
        val mode = activeMode
        if (mode == RuntimeMode.TRANSPARENT) {
            runtime.disableTransparent()
        } else if (tunDevice != null) {
            if (!runtime.stopTunnel()) {
                Log.e("TProxyService", "Failed to join tun2socks worker")
                return false
            }
            runCatching { tunDevice?.close() }
            tunDevice = null
        }
        isRunning = false
        activeMode = null
        activeConfig = null
        activeProfileName = null
        runtimeOptions = null
        tunIdentityPackages = emptyMap()
        serviceRunning = false
        if (mode != null) runtime.stopCore(mode == RuntimeMode.TRANSPARENT)
        return true
    }

    private fun applyAppsRouting(tun: Builder, options: RuntimeOptions.Tun) {
        when (options.appsRoutingMode) {
            AppsRoutingMode.Disabled -> {
                tun.addDisallowedApplication(applicationContext.packageName)
                return
            }

            AppsRoutingMode.Exclude -> {
                tun.addDisallowedApplication(applicationContext.packageName)
            }

            AppsRoutingMode.Include -> Unit
        }

        options.appsRouting.forEach { packageName ->
            val result = runCatching {
                when (options.appsRoutingMode) {
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
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    @Keep
    private fun tunConnectionOwnerUid(
        protocol: Int,
        localAddress: String,
        localPort: Int,
        remoteAddress: String,
        remotePort: Int,
    ): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return Process.INVALID_UID
        val options = runtimeOptions?.tun ?: return Process.INVALID_UID
        if (options.socksUsername.isEmpty() || options.socksPassword.isEmpty()) return Process.INVALID_UID
        val uid = runCatching {
            connectivityManager.getConnectionOwnerUid(
                protocol,
                InetSocketAddress(InetAddress.getByName(localAddress), localPort),
                InetSocketAddress(InetAddress.getByName(remoteAddress), remotePort),
            )
        }.getOrDefault(Process.INVALID_UID)
        // UIDs can be reused after uninstall. Never lend a removed app's identity to its replacement.
        val expected = tunIdentityPackages
        val stillInstalled = runCatching {
            packageManager.getPackagesForUid(uid)?.any { expected[it] == uid } == true
        }.getOrDefault(false)
        return if (stillInstalled) uid else Process.INVALID_UID
    }

    @Keep
    private fun shouldAllowTunConnection(
        protocol: Int,
        localAddress: String,
        localPort: Int,
        remoteAddress: String,
        remotePort: Int,
    ): Boolean {
        val options = runtimeOptions?.tun
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || options?.ownerDefense != true) {
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

        val allowedPackages = options.appsRouting

        val isAllowed = when (options.appsRoutingMode) {
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
            it.putExtra("profile", synchronized(runtimeLock) { activeProfileName })
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


}
