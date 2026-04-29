package io.github.derundevu.yaxc

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.core.os.LocaleListCompat
import io.github.derundevu.yaxc.BuildConfig
import io.github.derundevu.yaxc.helper.AntifilterHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import java.io.File
import java.security.SecureRandom

class Settings(private val context: Context) {

    enum class PingType(val value: String) {
        Head("head"),
        Get("get"),
        Tcp("tcp");

        companion object {
            fun fromValue(value: String?): PingType {
                return entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: Get
            }
        }
    }

    enum class GeoResourcesProvider(
        val value: String,
        val geoIpAddress: String,
        val geoSiteAddress: String,
    ) {
        RunetFreedom(
            value = "runetfreedom",
            geoIpAddress = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geoip.dat",
            geoSiteAddress = "https://github.com/runetfreedom/russia-v2ray-rules-dat/releases/latest/download/geosite.dat",
        ),
        LoyalSoldier(
            value = "loyalsoldier",
            geoIpAddress = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat",
            geoSiteAddress = "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat",
        ),
        Custom(
            value = "custom",
            geoIpAddress = "",
            geoSiteAddress = "",
        );

        companion object {
            fun fromUrls(
                geoIpAddress: String,
                geoSiteAddress: String,
            ): GeoResourcesProvider? {
                val normalizedGeoIp = geoIpAddress.trim().removeSuffix("/")
                val normalizedGeoSite = geoSiteAddress.trim().removeSuffix("/")
                return entries.firstOrNull { provider ->
                    provider != Custom &&
                    provider.geoIpAddress == normalizedGeoIp &&
                        provider.geoSiteAddress == normalizedGeoSite
                }
            }
        }
    }

    enum class AppsRoutingMode(val value: String) {
        Disabled("disabled"),
        Exclude("exclude"),
        Include("include");

        companion object {
            fun fromValue(value: String?): AppsRoutingMode {
                return entries.firstOrNull { it.value == value?.trim()?.lowercase() } ?: Exclude
            }
        }
    }

    companion object {
        private const val LEGACY_APPS_ROUTING_MODE_KEY = "appsRoutingMode"
        private const val APPS_ROUTING_MODE_KEY = "appsRoutingModeV2"
        private const val LEGACY_DEFAULT_USER_AGENT = "${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}"
        private const val DEFAULT_USER_AGENT = "yaxc/${BuildConfig.VERSION_NAME}"
        private const val PREVIOUS_DEFAULT_PING_ADDRESS = "https://www.google.com"
        private const val DEFAULT_PING_ADDRESS =
            "https://connectivitycheck.gstatic.com/generate_204"
        private const val DEFAULT_PING_TYPE = "get"
        private const val SYSTEM_LANGUAGE_TAG = "system"
        private const val PREVIOUS_DEFAULT_GEO_IP_ADDRESS =
            "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat"
        private const val PREVIOUS_DEFAULT_GEO_SITE_ADDRESS =
            "https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat"

        val DEFAULT_GEO_PROVIDER = GeoResourcesProvider.RunetFreedom
        val DEFAULT_GEO_IP_ADDRESS = GeoResourcesProvider.RunetFreedom.geoIpAddress
        val DEFAULT_GEO_SITE_ADDRESS = GeoResourcesProvider.RunetFreedom.geoSiteAddress
        const val DEFAULT_ANTIFILTER_ADDRESS = AntifilterHelper.DEFAULT_URL
        val USERNAME_ALPHABET: CharArray = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray()
        val PASSWORD_ALPHABET: CharArray =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789".toCharArray()
    }

    private val sharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val random = SecureRandom()

    init {
        initializeSocksCredentialsIfNeeded()
        initializeXHwidIfNeeded()
    }

    /** Active Link ID */
    var selectedLink: Long
        get() = sharedPreferences.getLong("selectedLink", 0L)
        set(value) = sharedPreferences.edit { putLong("selectedLink", value) }

    /** Active Profile ID */
    var selectedProfile: Long
        get() = sharedPreferences.getLong("selectedProfile", 0L)
        set(value) = sharedPreferences.edit { putLong("selectedProfile", value) }

    /** The time of last refresh */
    var lastRefreshLinks: Long
        get() = sharedPreferences.getLong("lastRefreshLinks", 0L)
        set(value) = sharedPreferences.edit { putLong("lastRefreshLinks", value) }

    /** Pending app update download */
    var appUpdateDownloadId: Long
        get() = sharedPreferences.getLong("appUpdateDownloadId", 0L)
        set(value) = sharedPreferences.edit { putLong("appUpdateDownloadId", value) }
    var appUpdatePendingVersion: String
        get() = sharedPreferences.getString("appUpdatePendingVersion", "")!!
        set(value) = sharedPreferences.edit { putString("appUpdatePendingVersion", value) }

    /** XrayHelper Version Code */
    var xrayHelperVersionCode: Int
        get() = sharedPreferences.getInt("xrayHelperVersionCode", 0)
        set(value) = sharedPreferences.edit { putInt("xrayHelperVersionCode", value) }

    /** Apps Routing */
    var appsRoutingMode: AppsRoutingMode
        get() {
            val storedMode = sharedPreferences.getString(APPS_ROUTING_MODE_KEY, null)
            if (storedMode != null) return AppsRoutingMode.fromValue(storedMode)

            return if (sharedPreferences.getBoolean(LEGACY_APPS_ROUTING_MODE_KEY, true)) {
                AppsRoutingMode.Exclude
            } else {
                AppsRoutingMode.Include
            }
        }
        set(value) = sharedPreferences.edit { putString(APPS_ROUTING_MODE_KEY, value.value) }
    var appsRouting: String
        get() = sharedPreferences.getString("excludedApps", "")!!
        set(value) = sharedPreferences.edit { putString("excludedApps", value) }
    var coreRoutingUiRules: String
        get() = sharedPreferences.getString("coreRoutingUiRules", "[]")!!
        set(value) = sharedPreferences.edit { putString("coreRoutingUiRules", value) }
    var coreRoutingDefaultsVersion: Int
        get() = sharedPreferences.getInt("coreRoutingDefaultsVersion", 0)
        set(value) = sharedPreferences.edit { putInt("coreRoutingDefaultsVersion", value) }

    /** Tun Routes */
    var tunRoutes: Set<String>
        get() = sharedPreferences.getStringSet(
            "tunRoutes",
            context.resources.getStringArray(R.array.publicIpAddresses).toSet()
        )!!
        set(value) = sharedPreferences.edit { putStringSet("tunRoutes", value) }

    /** Basic */
    var socksAddress: String
        get() = sharedPreferences.getString("socksAddress", "127.0.0.1")!!
        set(value) = sharedPreferences.edit { putString("socksAddress", value) }
    var socksPort: String
        get() = sharedPreferences.getString("socksPort", "10808")!!
        set(value) = sharedPreferences.edit { putString("socksPort", value) }
    var socksUsername: String
        get() = sharedPreferences.getString("socksUsername", "")!!
        set(value) = sharedPreferences.edit { putString("socksUsername", value) }
    var socksPassword: String
        get() = sharedPreferences.getString("socksPassword", "")!!
        set(value) = sharedPreferences.edit { putString("socksPassword", value) }
    var geoIpAddress: String
        get() {
            val stored = sharedPreferences.getString("geoIpAddress", DEFAULT_GEO_IP_ADDRESS)!!
            return if (stored.trim().removeSuffix("/") == PREVIOUS_DEFAULT_GEO_IP_ADDRESS) {
                sharedPreferences.edit { putString("geoIpAddress", DEFAULT_GEO_IP_ADDRESS) }
                DEFAULT_GEO_IP_ADDRESS
            } else stored
        }
        set(value) = sharedPreferences.edit { putString("geoIpAddress", value) }
    var customGeoIpAddress: String
        get() = sharedPreferences.getString("customGeoIpAddress", DEFAULT_GEO_IP_ADDRESS)!!
        set(value) = sharedPreferences.edit { putString("customGeoIpAddress", value) }
    var geoSiteAddress: String
        get() {
            val stored = sharedPreferences.getString("geoSiteAddress", DEFAULT_GEO_SITE_ADDRESS)!!
            return if (stored.trim().removeSuffix("/") == PREVIOUS_DEFAULT_GEO_SITE_ADDRESS) {
                sharedPreferences.edit { putString("geoSiteAddress", DEFAULT_GEO_SITE_ADDRESS) }
                DEFAULT_GEO_SITE_ADDRESS
            } else stored
        }
        set(value) = sharedPreferences.edit { putString("geoSiteAddress", value) }
    var customGeoSiteAddress: String
        get() = sharedPreferences.getString("customGeoSiteAddress", DEFAULT_GEO_SITE_ADDRESS)!!
        set(value) = sharedPreferences.edit { putString("customGeoSiteAddress", value) }
    var installedGeoIpSourceLabel: String
        get() = sharedPreferences.getString("installedGeoIpSourceLabel", "")!!
        set(value) = sharedPreferences.edit { putString("installedGeoIpSourceLabel", value) }
    var installedGeoIpSourceUrl: String
        get() = sharedPreferences.getString("installedGeoIpSourceUrl", "")!!
        set(value) = sharedPreferences.edit { putString("installedGeoIpSourceUrl", value) }
    var installedGeoSiteSourceLabel: String
        get() = sharedPreferences.getString("installedGeoSiteSourceLabel", "")!!
        set(value) = sharedPreferences.edit { putString("installedGeoSiteSourceLabel", value) }
    var installedGeoSiteSourceUrl: String
        get() = sharedPreferences.getString("installedGeoSiteSourceUrl", "")!!
        set(value) = sharedPreferences.edit { putString("installedGeoSiteSourceUrl", value) }
    var antifilterAddress: String
        get() = sharedPreferences.getString("antifilterAddress", DEFAULT_ANTIFILTER_ADDRESS)!!
        set(value) = sharedPreferences.edit { putString("antifilterAddress", value) }
    var antifilterEnabled: Boolean
        get() = sharedPreferences.getBoolean("antifilterEnabled", false)
        set(value) = sharedPreferences.edit { putBoolean("antifilterEnabled", value) }
    var installedAntifilterSourceLabel: String
        get() = sharedPreferences.getString("installedAntifilterSourceLabel", "")!!
        set(value) = sharedPreferences.edit { putString("installedAntifilterSourceLabel", value) }
    var installedAntifilterSourceUrl: String
        get() = sharedPreferences.getString("installedAntifilterSourceUrl", "")!!
        set(value) = sharedPreferences.edit { putString("installedAntifilterSourceUrl", value) }
    var installedAntifilterRouteCount: Int
        get() = sharedPreferences.getInt("installedAntifilterRouteCount", 0)
        set(value) = sharedPreferences.edit { putInt("installedAntifilterRouteCount", value) }
    var pingAddress: String
        get() {
            val storedValue = sharedPreferences.getString("pingAddress", null)?.trim()
            val normalizedValue = storedValue?.removeSuffix("/")
            return when {
                storedValue.isNullOrEmpty() -> DEFAULT_PING_ADDRESS
                normalizedValue == PREVIOUS_DEFAULT_PING_ADDRESS -> {
                    sharedPreferences.edit { putString("pingAddress", DEFAULT_PING_ADDRESS) }
                    DEFAULT_PING_ADDRESS
                }
                else -> storedValue
            }
        }
        set(value) = sharedPreferences.edit { putString("pingAddress", value) }
    var pingType: PingType
        get() = PingType.fromValue(
            sharedPreferences.getString("pingType", DEFAULT_PING_TYPE)
        )
        set(value) = sharedPreferences.edit { putString("pingType", value.value) }
    var userAgent: String
        get() {
            val storedValue = sharedPreferences.getString("userAgent", null)?.trim()
            return when {
                storedValue.isNullOrEmpty() -> DEFAULT_USER_AGENT
                storedValue == LEGACY_DEFAULT_USER_AGENT -> {
                    sharedPreferences.edit { putString("userAgent", DEFAULT_USER_AGENT) }
                    DEFAULT_USER_AGENT
                }

                else -> storedValue
            }
        }
        set(value) = sharedPreferences.edit { putString("userAgent", value) }
    val generatedXHwid: String
        get() {
            val storedValue = sharedPreferences.getString("generatedXHwid", null)?.trim()
            if (!storedValue.isNullOrEmpty()) return storedValue

            val generatedValue = randomHexString(16)
            sharedPreferences.edit { putString("generatedXHwid", generatedValue) }
            return generatedValue
        }
    var xHwid: String
        get() {
            val storedValue = sharedPreferences.getString("xHwid", null)?.trim()
            return storedValue?.takeIf { it.isNotEmpty() } ?: generatedXHwid
        }
        set(value) = sharedPreferences.edit {
            putString("xHwid", value.trim().ifEmpty { generatedXHwid })
        }
    var pingTimeout: Int
        get() = sharedPreferences.getInt("pingTimeout", 5)
        set(value) = sharedPreferences.edit { putInt("pingTimeout", value) }
    var refreshLinksInterval: Int
        get() = sharedPreferences.getInt("refreshLinksInterval", 60)
        set(value) = sharedPreferences.edit { putInt("refreshLinksInterval", value) }
    var bypassLan: Boolean
        get() = sharedPreferences.getBoolean("bypassLan", true)
        set(value) = sharedPreferences.edit { putBoolean("bypassLan", value) }
    var enableIpV6: Boolean
        get() = sharedPreferences.getBoolean("enableIpV6", true)
        set(value) = sharedPreferences.edit { putBoolean("enableIpV6", value) }
    var socksUdp: Boolean
        get() = sharedPreferences.getBoolean("socksUdp", true)
        set(value) = sharedPreferences.edit { putBoolean("socksUdp", value) }
    var tun2socks: Boolean
        get() = sharedPreferences.getBoolean("tun2socks", true)
        set(value) = sharedPreferences.edit { putBoolean("tun2socks", value) }
    var tunOwnerDefense: Boolean
        get() = sharedPreferences.getBoolean("tunOwnerDefense", false)
        set(value) = sharedPreferences.edit { putBoolean("tunOwnerDefense", value) }
    var bootAutoStart: Boolean
        get() = sharedPreferences.getBoolean("bootAutoStart", false)
        set(value) = sharedPreferences.edit { putBoolean("bootAutoStart", value) }
    var refreshLinksOnOpen: Boolean
        get() = sharedPreferences.getBoolean("refreshLinksOnOpen", false)
        set(value) = sharedPreferences.edit { putBoolean("refreshLinksOnOpen", value) }

    /** Advanced */
    var primaryDns: String
        get() = sharedPreferences.getString("primaryDns", "1.1.1.1")!!
        set(value) = sharedPreferences.edit { putString("primaryDns", value) }
    var secondaryDns: String
        get() = sharedPreferences.getString("secondaryDns", "1.0.0.1")!!
        set(value) = sharedPreferences.edit { putString("secondaryDns", value) }
    var primaryDnsV6: String
        get() = sharedPreferences.getString("primaryDnsV6", "2606:4700:4700::1111")!!
        set(value) = sharedPreferences.edit { putString("primaryDnsV6", value) }
    var secondaryDnsV6: String
        get() = sharedPreferences.getString("secondaryDnsV6", "2606:4700:4700::1001")!!
        set(value) = sharedPreferences.edit { putString("secondaryDnsV6", value) }
    var tunName: String
        get() = sharedPreferences.getString("tunName", "tun0")!!
        set(value) = sharedPreferences.edit { putString("tunName", value) }
    var tunMtu: Int
        get() = sharedPreferences.getInt("tunMtu", 8500)
        set(value) = sharedPreferences.edit { putInt("tunMtu", value) }
    var tunAddress: String
        get() = sharedPreferences.getString("tunAddress", "10.10.10.10")!!
        set(value) = sharedPreferences.edit { putString("tunAddress", value) }
    var tunPrefix: Int
        get() = sharedPreferences.getInt("tunPrefix", 32)
        set(value) = sharedPreferences.edit { putInt("tunPrefix", value) }
    var tunAddressV6: String
        get() = sharedPreferences.getString("tunAddressV6", "fc00::1")!!
        set(value) = sharedPreferences.edit { putString("tunAddressV6", value) }
    var tunPrefixV6: Int
        get() = sharedPreferences.getInt("tunPrefixV6", 128)
        set(value) = sharedPreferences.edit { putInt("tunPrefixV6", value) }
    var hotspotInterface
        get() = sharedPreferences.getString("hotspotInterface", "wlan2")!!
        set(value) = sharedPreferences.edit { putString("hotspotInterface", value) }
    var tetheringInterface
        get() = sharedPreferences.getString("tetheringInterface", "rndis0")!!
        set(value) = sharedPreferences.edit { putString("tetheringInterface", value) }
    var tproxyAddress: String
        get() = sharedPreferences.getString("tproxyAddress", "127.0.0.1")!!
        set(value) = sharedPreferences.edit { putString("tproxyAddress", value) }
    var tproxyPort: Int
        get() = sharedPreferences.getInt("tproxyPort", 10888)
        set(value) = sharedPreferences.edit { putInt("tproxyPort", value) }
    var tproxyBypassWiFi: Set<String>
        get() = sharedPreferences.getStringSet("tproxyBypassWiFi", mutableSetOf<String>())!!
        set(value) = sharedPreferences.edit { putStringSet("tproxyBypassWiFi", value) }
    var tproxyAutoConnect: Boolean
        get() = sharedPreferences.getBoolean("tproxyAutoConnect", false)
        set(value) = sharedPreferences.edit { putBoolean("tproxyAutoConnect", value) }
    var tproxyHotspot: Boolean
        get() = sharedPreferences.getBoolean("tproxyHotspot", false)
        set(value) = sharedPreferences.edit { putBoolean("tproxyHotspot", value) }
    var tproxyTethering: Boolean
        get() = sharedPreferences.getBoolean("tproxyTethering", false)
        set(value) = sharedPreferences.edit { putBoolean("tproxyTethering", value) }
    var transparentProxy: Boolean
        get() = sharedPreferences.getBoolean("transparentProxy", false)
        set(value) = sharedPreferences.edit { putBoolean("transparentProxy", value) }
    var languageTag: String
        get() = sharedPreferences.getString("languageTag", SYSTEM_LANGUAGE_TAG)!!
        set(value) = sharedPreferences.edit { putString("languageTag", value) }
    var themeStyle: YaxcThemeStyle
        get() = YaxcThemeStyle.fromValue(
            sharedPreferences.getString("themeStyle", YaxcThemeStyle.System.value)
        )
        set(value) = sharedPreferences.edit { putString("themeStyle", value.value) }

    fun baseDir(): File = context.filesDir
    fun xrayCoreFile(): File = File(baseDir(), "xray")
    fun xrayHelperFile(): File = File(baseDir(), "xrayhelper")
    fun testConfig(): File = File(baseDir(), "test.json")
    fun xrayConfig(): File = File(baseDir(), "config.json")
    fun tun2socksConfig(): File = File(baseDir(), "tun2socks.yml")
    fun xrayHelperConfig(): File = File(baseDir(), "config.yml")
    fun antifilterFile(): File = File(baseDir(), "antifilter.lst")
    fun xrayCorePid(): File = File(baseDir(), "core.pid")
    fun networkMonitorPid(): File = File(baseDir(), "monitor.pid")
    fun networkMonitorScript(): File = File(baseDir(), "monitor.sh")
    fun xrayCoreLogs(): File = File(baseDir(), "error.log")
    fun getString(resId: Int, vararg args: Any): String = context.getString(resId, *args)

    fun currentGeoResourcesProvider(): GeoResourcesProvider {
        return GeoResourcesProvider.fromUrls(geoIpAddress, geoSiteAddress)
            ?: GeoResourcesProvider.Custom
    }

    fun setGeoResourcesProvider(provider: GeoResourcesProvider) {
        when (provider) {
            GeoResourcesProvider.Custom -> {
                geoIpAddress = customGeoIpAddress
                geoSiteAddress = customGeoSiteAddress
            }

            else -> {
                geoIpAddress = provider.geoIpAddress
                geoSiteAddress = provider.geoSiteAddress
            }
        }
    }

    fun appLocales(): LocaleListCompat {
        return if (languageTag == SYSTEM_LANGUAGE_TAG) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(languageTag)
        }
    }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        sharedPreferences.unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun updateCustomGeoResources(
        geoIpAddress: String,
        geoSiteAddress: String,
    ) {
        customGeoIpAddress = geoIpAddress
        customGeoSiteAddress = geoSiteAddress
        this.geoIpAddress = geoIpAddress
        this.geoSiteAddress = geoSiteAddress
    }

    private fun initializeSocksCredentialsIfNeeded() {
        if (sharedPreferences.getBoolean("socksCredentialsInitialized", false)) return

        val storedUsername = sharedPreferences.getString("socksUsername", "")?.trim().orEmpty()
        val storedPassword = sharedPreferences.getString("socksPassword", "").orEmpty()
        val username = storedUsername.ifBlank { "yaxc_${randomString(6, USERNAME_ALPHABET)}" }
        val password = storedPassword.ifBlank { randomString(8, PASSWORD_ALPHABET) }

        sharedPreferences.edit {
            putString("socksUsername", username)
            putString("socksPassword", password)
            putBoolean("socksCredentialsInitialized", true)
        }
    }

    private fun initializeXHwidIfNeeded() {
        if (sharedPreferences.getBoolean("xHwidInitialized", false)) return

        val generatedValue = generatedXHwid
        val currentValue = sharedPreferences.getString("xHwid", null)?.trim().orEmpty()

        sharedPreferences.edit {
            putString("xHwid", currentValue.ifBlank { generatedValue })
            putBoolean("xHwidInitialized", true)
        }
    }

    private fun randomString(length: Int, alphabet: CharArray): String {
        return buildString(length) {
            repeat(length) {
                append(alphabet[random.nextInt(alphabet.size)])
            }
        }
    }

    private fun randomHexString(byteCount: Int): String {
        val bytes = ByteArray(byteCount)
        random.nextBytes(bytes)
        return buildString(byteCount * 2) {
            bytes.forEach { value ->
                append(((value.toInt() ushr 4) and 0x0F).toString(16))
                append((value.toInt() and 0x0F).toString(16))
            }
        }
    }
}
