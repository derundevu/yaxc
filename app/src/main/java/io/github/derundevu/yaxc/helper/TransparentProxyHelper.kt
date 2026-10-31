package io.github.derundevu.yaxc.helper

import android.content.Context
import android.os.Build
import com.topjohnwu.superuser.Shell
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Settings.AppsRoutingMode
import io.github.derundevu.yaxc.service.RuntimeOptions
import io.github.derundevu.yaxc.service.TProxyService
import java.io.FileOutputStream

class TransparentProxyHelper(
    private val context: Context,
    private val settings: Settings,
) {

    private val networkStateHelper by lazy { NetworkStateHelper() }

    fun isRunning(): Boolean = settings.xrayCorePid().exists()

    internal fun startService(options: RuntimeOptions.Transparent) {
        makeConfig(options)
        val result = Shell.cmd("${cmd()} service start").exec()
        check(result.isSuccess) {
            result.err.joinToString("\n").ifBlank { "Failed to start the root Xray service" }
        }
    }

    fun stopService() {
        Shell.cmd("${cmd()} service stop").exec()
    }

    fun enableProxy() {
        Shell.cmd("${cmd()} proxy enable").exec()
    }

    fun disableProxy() {
        Shell.cmd("${cmd()} proxy disable").exec()
    }

    fun refreshProxy() {
        Shell.cmd("${cmd()} proxy refresh").exec()
    }

    fun kill() {
        if (settings.networkMonitorPid().exists()) {
            val path = settings.networkMonitorPid().absolutePath
            Shell.cmd("kill $(cat $path) && rm $path").exec()
        }
        if (settings.xrayCorePid().exists()) {
            disableProxy()
            stopService()
        }
    }

    fun monitorNetwork() {
        val script = settings.networkMonitorScript()
        val pid = settings.networkMonitorPid()
        if (!settings.tproxyAutoConnect || !settings.transparentProxy || pid.exists()) return
        networkStateHelper.monitor(script, pid)
    }

    fun networkState(): NetworkStateHelper.NetworkState {
        return networkStateHelper.getState()
    }

    fun bypassWiFi(state: NetworkStateHelper.NetworkState): Boolean {
        val tproxyBypassWiFi = settings.tproxyBypassWiFi
        val ssid = state.wifi ?: return false
        return ssid in tproxyBypassWiFi
    }

    internal enum class NetworkAction { NONE, STOP, START, REFRESH }

    internal fun networkAction(
        state: NetworkStateHelper.NetworkState,
        running: Boolean,
    ): NetworkAction = when {
        !settings.transparentProxy || !settings.tproxyAutoConnect -> NetworkAction.NONE
        !networkStateHelper.isOnline(state) || bypassWiFi(state) -> NetworkAction.STOP
        !running -> NetworkAction.START
        else -> NetworkAction.REFRESH
    }

    fun networkUpdate(value: NetworkStateHelper.NetworkState? = null) {
        if (!settings.transparentProxy || !settings.tproxyAutoConnect) return
        when (networkAction(value ?: networkState(), isRunning())) {
            NetworkAction.NONE -> Unit
            NetworkAction.STOP -> TProxyService.stop(context)
            NetworkAction.START -> TProxyService.start(context, false)
            NetworkAction.REFRESH -> refreshProxy()
        }
    }

    fun install() {
        val xrayHelper = settings.xrayHelperFile()
        val appVersionCode = BuildConfig.VERSION_CODE
        val xrayHelperVersionCode = settings.xrayHelperVersionCode
        if (xrayHelper.exists() && xrayHelperVersionCode == appVersionCode) return
        if (xrayHelper.exists()) xrayHelper.delete()
        val assetPath = xrayHelperAssetPath(
            Build.SUPPORTED_ABIS,
            context.assets.list("xrayhelpers").orEmpty(),
        )
        context.assets.open(assetPath).use { input ->
            FileOutputStream(xrayHelper).use { output ->
                input.copyTo(output)
            }
        }
        Shell.cmd("chown root:root ${xrayHelper.absolutePath}").exec()
        Shell.cmd("chmod +x ${xrayHelper.absolutePath}").exec()
        settings.xrayHelperVersionCode = appVersionCode
    }

    private fun cmd(): String {
        return arrayListOf(
            settings.xrayHelperFile().absolutePath,
            "-c",
            settings.xrayHelperConfig().absolutePath,
        ).joinToString(" ")
    }

    private fun makeConfig(options: RuntimeOptions.Transparent) {
        val yml = arrayListOf(
            "xrayHelper:",
            "  coreType: xray",
            "  corePath: ${settings.xrayCoreFile().absolutePath}",
            "  coreConfig: ${settings.xrayConfig().absolutePath}",
            "  dataDir: ${settings.baseDir().absolutePath}",
            "  runDir: ${settings.baseDir().absolutePath}",
            "proxy:",
            "  method: tproxy",
            "  tproxyPort: ${options.port}",
            "  enableIPv6: ${options.ipv6}",
            "  mode: ${options.appsRoutingMode.xrayHelperMode()}",
        )

        if (options.appsRouting.isNotEmpty()) {
            yml.add("  pkgList:")
            options.appsRouting.forEach { yml.add("    - $it") }
        }

        val includedInterfaces = arrayListOf<String>()
        val excludedInterfaces = arrayListOf<String>()
        if (options.hotspot) includedInterfaces.add(options.hotspotInterface)
        else excludedInterfaces.add(options.hotspotInterface)
        if (options.tethering) includedInterfaces.add(options.tetheringInterface)
        else excludedInterfaces.add(options.tetheringInterface)
        if (includedInterfaces.isNotEmpty()) {
            yml.add("  apList:")
            includedInterfaces.forEach { yml.add("    - $it") }
        }
        if (excludedInterfaces.isNotEmpty()) {
            yml.add("  ignoreList:")
            excludedInterfaces.forEach { yml.add("    - $it") }
        }

        yml.add("")
        FileHelper.createOrUpdate(
            settings.xrayHelperConfig(),
            yml.joinToString("\n")
        )
    }

    private fun AppsRoutingMode.xrayHelperMode(): String {
        return when (this) {
            AppsRoutingMode.Disabled,
            AppsRoutingMode.Exclude,
            -> "blacklist"
            AppsRoutingMode.Include -> "whitelist"
        }
    }
}

internal fun xrayHelperAssetPath(supportedAbis: Array<out String>, packagedAbis: Array<out String>): String {
    val abi = supportedAbis.firstOrNull { it in packagedAbis }
        ?: error("No compatible XrayHelper for this device")
    return "xrayhelpers/$abi"
}
