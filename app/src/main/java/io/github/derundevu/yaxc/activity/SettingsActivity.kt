package io.github.derundevu.yaxc.activity

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.settings.SettingsFormState
import io.github.derundevu.yaxc.presentation.settings.SettingsScreen
import io.github.derundevu.yaxc.service.TProxyService
import kotlinx.coroutines.launch
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class SettingsActivity : AppCompatActivity() {

    private val settings by lazy { Settings(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }

        setContent {
            var formState by rememberSaveable(stateSaver = SettingsFormState.Saver) {
                mutableStateOf(SettingsFormState.from(settings))
            }
            var tunRoutes by rememberSaveable {
                mutableStateOf(settings.tunRoutes.toList())
            }

            YaxcAppTheme {
                SettingsScreen(
                    formState = formState,
                    generatedXHwid = settings.generatedXHwid,
                    tunRoutes = tunRoutes,
                    onFormStateChange = { formState = it },
                    onTunRoutesSave = { routes ->
                        tunRoutes = routes.sorted()
                        settings.tunRoutes = routes.toSet()
                    },
                    onBack = ::finish,
                    onSave = { applySettings(formState) },
                )
            }
        }
    }

    private fun applySettings(formState: SettingsFormState) {
        if (formState.transparentProxy && !settings.xrayCoreFile().exists()) {
            Toast.makeText(
                applicationContext,
                getString(R.string.installAssetsFirst),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }

        val pingTimeout = formState.pingTimeout.toIntOrNull()
            ?: return showInvalidNumber(R.string.pingTimeout)
        val refreshLinksInterval = formState.refreshLinksInterval.toIntOrNull()
            ?: return showInvalidNumber(R.string.refreshLinksInterval)
        val tunMtu = formState.tunMtu.toIntOrNull()
            ?: return showInvalidNumber(R.string.tunMtu)
        val tunPrefix = formState.tunPrefix.toIntOrNull()
            ?: return showInvalidNumber(R.string.tunPrefix)
        val tunPrefixV6 = formState.tunPrefixV6.toIntOrNull()
            ?: return showInvalidNumber(R.string.tunPrefixV6)
        val tproxyPort = formState.tproxyPort.toIntOrNull()
            ?: return showInvalidNumber(R.string.tproxyPort)

        val oldSocksAddress = settings.socksAddress
        val oldSocksPort = settings.socksPort
        val oldSocksUsername = settings.socksUsername
        val oldSocksPassword = settings.socksPassword
        val oldPrimaryDns = settings.primaryDns
        val oldSecondaryDns = settings.secondaryDns
        val oldBypassLan = settings.bypassLan
        val oldEnableIpV6 = settings.enableIpV6
        val oldPrimaryDnsV6 = settings.primaryDnsV6
        val oldSecondaryDnsV6 = settings.secondaryDnsV6
        val oldSocksUdp = settings.socksUdp
        val oldTun2socks = settings.tun2socks
        val oldTunOwnerDefense = settings.tunOwnerDefense
        val oldTunName = settings.tunName
        val oldTunMtu = settings.tunMtu
        val oldTunAddress = settings.tunAddress
        val oldTunPrefix = settings.tunPrefix
        val oldTunAddressV6 = settings.tunAddressV6
        val oldTunPrefixV6 = settings.tunPrefixV6
        val oldHotspotInterface = settings.hotspotInterface
        val oldTetheringInterface = settings.tetheringInterface
        val oldTproxyAddress = settings.tproxyAddress
        val oldTproxyPort = settings.tproxyPort
        val oldTproxyBypassWiFi = settings.tproxyBypassWiFi
        val oldTproxyAutoConnect = settings.tproxyAutoConnect
        val oldTproxyHotspot = settings.tproxyHotspot
        val oldTproxyTethering = settings.tproxyTethering
        val oldTransparentProxy = settings.transparentProxy
        val oldLanguageTag = settings.languageTag
        val oldThemeStyle = settings.themeStyle

        val newSocksAddress = formState.socksAddress.trim()
        val newSocksPort = formState.socksPort.trim()
        val newSocksUsername = formState.socksUsername.trim()
        val newSocksPassword = formState.socksPassword
        val newUserAgent = formState.userAgent.trim()
        val newXHwid = formState.xHwid.trim().ifBlank { settings.generatedXHwid }
        val newPingAddress = formState.pingAddress.trim()
        val newPingType = Settings.PingType.fromValue(formState.pingType)
        val newPrimaryDns = formState.primaryDns.trim()
        val newSecondaryDns = formState.secondaryDns.trim()
        val newPrimaryDnsV6 = formState.primaryDnsV6.trim()
        val newSecondaryDnsV6 = formState.secondaryDnsV6.trim()
        val newTunName = formState.tunName.trim()
        val newTunAddress = formState.tunAddress.trim()
        val newTunAddressV6 = formState.tunAddressV6.trim()
        val newHotspotInterface = formState.hotspotInterface.trim()
        val newTetheringInterface = formState.tetheringInterface.trim()
        val newTproxyAddress = formState.tproxyAddress.trim()
        val newLanguageTag = formState.languageTag.trim().ifBlank { "system" }
        val newThemeStyle = YaxcThemeStyle.fromValue(formState.themeStyle)
        val newTproxyBypassWiFi = formState.tproxyBypassWiFi
            .split(",")
            .map(String::trim)
            .filter(String::isNotBlank)
            .toSet()

        if (!isValidIpv4Address(newPrimaryDns)) {
            return showInvalidIpAddress(R.string.primaryDns)
        }
        if (!isValidIpv4Address(newSecondaryDns)) {
            return showInvalidIpAddress(R.string.secondaryDns)
        }
        if (!isValidIpv6Address(newPrimaryDnsV6)) {
            return showInvalidIpAddress(R.string.primaryDnsV6)
        }
        if (!isValidIpv6Address(newSecondaryDnsV6)) {
            return showInvalidIpAddress(R.string.secondaryDnsV6)
        }

        val vpnSettingsChanged = oldSocksAddress != newSocksAddress ||
                oldSocksPort != newSocksPort ||
                oldSocksUsername != newSocksUsername ||
                oldSocksPassword != newSocksPassword ||
                oldPrimaryDns != newPrimaryDns ||
                oldSecondaryDns != newSecondaryDns ||
                oldBypassLan != formState.bypassLan ||
                oldEnableIpV6 != formState.enableIpV6 ||
                oldPrimaryDnsV6 != newPrimaryDnsV6 ||
                oldSecondaryDnsV6 != newSecondaryDnsV6 ||
                oldSocksUdp != formState.socksUdp ||
                oldTun2socks != formState.tun2socks ||
                oldTunOwnerDefense != formState.tunOwnerDefense ||
                oldTunName != newTunName ||
                oldTunMtu != tunMtu ||
                oldTunAddress != newTunAddress ||
                oldTunPrefix != tunPrefix ||
                oldTunAddressV6 != newTunAddressV6 ||
                oldTunPrefixV6 != tunPrefixV6 ||
                oldHotspotInterface != newHotspotInterface ||
                oldTetheringInterface != newTetheringInterface ||
                oldTproxyAddress != newTproxyAddress ||
                oldTproxyPort != tproxyPort ||
                oldTproxyBypassWiFi != newTproxyBypassWiFi ||
                oldTproxyAutoConnect != formState.tproxyAutoConnect ||
                oldTproxyHotspot != formState.tproxyHotspot ||
                oldTproxyTethering != formState.tproxyTethering ||
                oldTransparentProxy != formState.transparentProxy

        val restartService = vpnSettingsChanged && TProxyService.isActive()

        lifecycleScope.launch {
            settings.socksAddress = newSocksAddress
            settings.socksPort = newSocksPort
            settings.socksUsername = newSocksUsername
            settings.socksPassword = newSocksPassword
            settings.userAgent = newUserAgent
            settings.xHwid = newXHwid
            settings.pingAddress = newPingAddress
            settings.pingType = newPingType
            settings.pingTimeout = pingTimeout
            settings.refreshLinksInterval = refreshLinksInterval
            settings.bypassLan = formState.bypassLan
            settings.enableIpV6 = formState.enableIpV6
            settings.socksUdp = formState.socksUdp
            settings.tun2socks = formState.tun2socks
            settings.tunOwnerDefense = formState.tunOwnerDefense
            settings.bootAutoStart = formState.bootAutoStart
            settings.refreshLinksOnOpen = formState.refreshLinksOnOpen
            settings.primaryDns = newPrimaryDns
            settings.secondaryDns = newSecondaryDns
            settings.primaryDnsV6 = newPrimaryDnsV6
            settings.secondaryDnsV6 = newSecondaryDnsV6
            settings.tunName = newTunName
            settings.tunMtu = tunMtu
            settings.tunAddress = newTunAddress
            settings.tunPrefix = tunPrefix
            settings.tunAddressV6 = newTunAddressV6
            settings.tunPrefixV6 = tunPrefixV6
            settings.hotspotInterface = newHotspotInterface
            settings.tetheringInterface = newTetheringInterface
            settings.tproxyAddress = newTproxyAddress
            settings.tproxyPort = tproxyPort
            settings.tproxyBypassWiFi = newTproxyBypassWiFi
            settings.tproxyAutoConnect = formState.tproxyAutoConnect
            settings.tproxyHotspot = formState.tproxyHotspot
            settings.tproxyTethering = formState.tproxyTethering
            settings.transparentProxy = formState.transparentProxy
            settings.languageTag = newLanguageTag
            settings.themeStyle = newThemeStyle

            if (oldLanguageTag != newLanguageTag) {
                AppCompatDelegate.setApplicationLocales(settings.appLocales())
            }

            val themeChanged = oldThemeStyle != newThemeStyle

            if (restartService) {
                TProxyService.restart(this@SettingsActivity)
            }

            if (themeChanged) {
                recreate()
            } else {
                finish()
            }
        }
    }

    private fun showInvalidNumber(labelRes: Int) {
        Toast.makeText(
            applicationContext,
            getString(R.string.invalidNumberValue, getString(labelRes)),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun showInvalidIpAddress(labelRes: Int) {
        Toast.makeText(
            applicationContext,
            getString(R.string.invalidIpAddressValue, getString(labelRes)),
            Toast.LENGTH_SHORT,
        ).show()
    }

    private fun isValidIpv4Address(value: String): Boolean {
        if (value.isBlank()) return false
        val parts = value.split('.')
        if (parts.size != 4) return false
        if (parts.any { it.isBlank() || (it.length > 1 && it.startsWith('0')) }) return false
        if (parts.any { part -> part.toIntOrNull()?.let { it in 0..255 } != true }) return false
        return parseAddress(value) is Inet4Address
    }

    private fun isValidIpv6Address(value: String): Boolean {
        if (value.isBlank() || ':' !in value) return false
        return parseAddress(value) is Inet6Address
    }

    private fun parseAddress(value: String): InetAddress? {
        return runCatching { InetAddress.getByName(value) }.getOrNull()
    }
}
