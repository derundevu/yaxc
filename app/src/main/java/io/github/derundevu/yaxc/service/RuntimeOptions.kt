package io.github.derundevu.yaxc.service

import android.os.Build
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.Settings.AppsRoutingMode
import java.util.Collections

internal data class RuntimeOptions(
    val mode: Mode,
    val tun: Tun? = null,
    val transparent: Transparent? = null,
    val killSwitch: Boolean = false,
) {
    enum class Mode { CORE, TUN, TRANSPARENT }

    data class Tun(
        val name: String,
        val mtu: Int,
        val address: String,
        val prefix: Int,
        val primaryDns: String,
        val secondaryDns: String,
        val ipv6: Ipv6?,
        val bypassLan: Boolean,
        val routes: Set<String>,
        val appsRoutingMode: AppsRoutingMode,
        val appsRouting: Set<String>,
        val ownerDefense: Boolean,
        val socksAddress: String,
        val socksPort: Int,
        val socksUsername: String,
        val socksPassword: String,
        val socksUdp: Boolean,
    ) {
        /** SOCKS endpoint/credentials and owner checks do not change Android's interface. */
        fun sameInterface(other: Tun): Boolean =
            name == other.name && mtu == other.mtu && address == other.address && prefix == other.prefix &&
                primaryDns == other.primaryDns && secondaryDns == other.secondaryDns && ipv6 == other.ipv6 &&
                bypassLan == other.bypassLan && routes == other.routes &&
                appsRoutingMode == other.appsRoutingMode && appsRouting == other.appsRouting
    }

    data class Ipv6(
        val address: String,
        val prefix: Int,
        val primaryDns: String,
        val secondaryDns: String,
    )

    data class Transparent(
        val address: String,
        val port: Int,
        val ipv6: Boolean,
        val appsRoutingMode: AppsRoutingMode,
        val appsRouting: Set<String>,
        val hotspotInterface: String,
        val hotspot: Boolean,
        val tetheringInterface: String,
        val tethering: Boolean,
        val autoConnect: Boolean,
        val bypassWiFi: Set<String>,
    )

    companion object {
        fun capture(settings: Settings, packageName: String): RuntimeOptions {
            check(!settings.killSwitch || (!settings.transparentProxy && settings.tun2socks)) {
                "Kill switch requires VPN mode. Disable Kill switch before changing modes."
            }
            return captureMode(settings, packageName)
        }

        private fun captureMode(settings: Settings, packageName: String): RuntimeOptions = when {
            settings.transparentProxy -> {
                val appsMode = settings.appsRoutingMode
                val autoConnect = settings.tproxyAutoConnect
                RuntimeOptions(
                    mode = Mode.TRANSPARENT,
                    transparent = Transparent(
                        address = settings.tproxyAddress,
                        port = settings.tproxyPort,
                        ipv6 = settings.enableIpV6,
                        appsRoutingMode = appsMode,
                        appsRouting = apps(settings, appsMode),
                        hotspotInterface = settings.hotspotInterface,
                        hotspot = settings.tproxyHotspot,
                        tetheringInterface = settings.tetheringInterface,
                        tethering = settings.tproxyTethering,
                        autoConnect = autoConnect,
                        bypassWiFi = if (autoConnect) immutableSet(settings.tproxyBypassWiFi) else emptySet(),
                    ),
                )
            }
            settings.tun2socks -> {
                val appsMode = settings.appsRoutingMode
                val bypassLan = settings.bypassLan
                val username = settings.socksUsername
                val password = settings.socksPassword
                val authenticated = username.isNotBlank() && password.isNotBlank()
                RuntimeOptions(
                    mode = Mode.TUN,
                    killSwitch = settings.killSwitch,
                    tun = Tun(
                        name = settings.tunName.trim(),
                        mtu = settings.tunMtu,
                        address = settings.tunAddress,
                        prefix = settings.tunPrefix,
                        primaryDns = settings.primaryDns,
                        secondaryDns = settings.secondaryDns,
                        ipv6 = if (settings.enableIpV6) {
                            Ipv6(
                                address = settings.tunAddressV6,
                                prefix = settings.tunPrefixV6,
                                primaryDns = settings.primaryDnsV6,
                                secondaryDns = settings.secondaryDnsV6,
                            )
                        } else null,
                        bypassLan = bypassLan,
                        routes = if (bypassLan) immutableSet(settings.tunRoutes) else emptySet(),
                        appsRoutingMode = appsMode,
                        appsRouting = apps(settings, appsMode, packageName),
                        ownerDefense = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && settings.tunOwnerDefense,
                        socksAddress = settings.socksAddress,
                        socksPort = settings.socksPort.toInt(),
                        socksUsername = if (authenticated) username else "",
                        socksPassword = if (authenticated) password else "",
                        socksUdp = settings.socksUdp,
                    ),
                )
            }
            else -> RuntimeOptions(mode = Mode.CORE)
        }

        private fun apps(
            settings: Settings,
            mode: AppsRoutingMode,
            excludedPackage: String? = null,
        ): Set<String> {
            if (mode == AppsRoutingMode.Disabled) return emptySet()
            return immutableSet(
                settings.appsRouting.lineSequence()
                    .map(String::trim)
                    .filter { it.isNotEmpty() && it != excludedPackage }
                    .toSet()
            )
        }

        private fun immutableSet(values: Set<String>): Set<String> =
            Collections.unmodifiableSet(LinkedHashSet(values))
    }
}
