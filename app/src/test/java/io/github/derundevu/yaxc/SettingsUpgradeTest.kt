package io.github.derundevu.yaxc

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsUpgradeTest {
    private lateinit var application: Application
    private lateinit var preferences: SharedPreferences

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        preferences = application.getSharedPreferences("settings", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        preferences.edit().clear().commit()
    }

    @Test
    fun legacyRoutingBooleanKeepsItsMeaningAndPackageSelectionAcrossReopen() {
        for ((legacy, expected) in listOf(true to Settings.AppsRoutingMode.Exclude, false to Settings.AppsRoutingMode.Include)) {
            preferences.edit().clear()
                .putBoolean("appsRoutingMode", legacy)
                .putString("excludedApps", "synthetic.first\nsynthetic.second")
                .commit()

            val upgraded = Settings(application)
            assertEquals(expected, upgraded.appsRoutingMode)
            assertEquals("synthetic.first\nsynthetic.second", upgraded.appsRouting)
            val reopened = Settings(application)
            assertEquals(expected, reopened.appsRoutingMode)
            assertEquals(upgraded.appsRouting, reopened.appsRouting)
        }
    }

    @Test
    fun explicitNewRoutingModeIncludingDisabledAlwaysWinsOverLegacyBoolean() {
        for (legacy in listOf(true, false)) {
            for (mode in Settings.AppsRoutingMode.entries) {
                preferences.edit().clear()
                    .putBoolean("appsRoutingMode", legacy)
                    .putString("appsRoutingModeV2", mode.value)
                    .putString("excludedApps", "synthetic.chosen")
                    .commit()
                assertEquals(mode, Settings(application).appsRoutingMode)
                assertEquals("synthetic.chosen", Settings(application).appsRouting)
            }
        }

        preferences.edit().remove("appsRoutingModeV2").putBoolean("appsRoutingMode", false).commit()
        Settings(application).appsRoutingMode = Settings.AppsRoutingMode.Disabled
        assertEquals(Settings.AppsRoutingMode.Disabled, Settings(application).appsRoutingMode)
        assertEquals("disabled", preferences.getString("appsRoutingModeV2", null))
    }

    @Test
    fun previousGeoDefaultsMigrateIndependentlyAndPersistWithoutLosingCustomBackups() {
        preferences.edit()
            .putString("geoIpAddress", "  https://github.com/v2fly/geoip/releases/latest/download/geoip.dat/  ")
            .putString("geoSiteAddress", " https://github.com/v2fly/domain-list-community/releases/latest/download/dlc.dat/ ")
            .putString("customGeoIpAddress", "http://127.0.0.1:18881/custom-ip.dat")
            .putString("customGeoSiteAddress", "http://127.0.0.1:18881/custom-site.dat")
            .commit()

        val upgraded = Settings(application)
        assertEquals(Settings.GeoResourcesProvider.RunetFreedom, upgraded.currentGeoResourcesProvider())
        assertEquals(Settings.DEFAULT_GEO_IP_ADDRESS, preferences.getString("geoIpAddress", null))
        assertEquals(Settings.DEFAULT_GEO_SITE_ADDRESS, preferences.getString("geoSiteAddress", null))
        assertEquals(upgraded.geoIpAddress, Settings(application).geoIpAddress)
        upgraded.setGeoResourcesProvider(Settings.GeoResourcesProvider.Custom)
        val reopened = Settings(application)
        assertEquals("http://127.0.0.1:18881/custom-ip.dat", reopened.geoIpAddress)
        assertEquals("http://127.0.0.1:18881/custom-site.dat", reopened.geoSiteAddress)
        assertEquals(Settings.GeoResourcesProvider.Custom, reopened.currentGeoResourcesProvider())
    }

    @Test
    fun mixedOldGeoDefaultAndUserChoiceMigrateOnlyTheOldDefault() {
        val customSite = "http://127.0.0.1:18881/site.dat/"
        preferences.edit()
            .putString("geoIpAddress", "https://github.com/v2fly/geoip/releases/latest/download/geoip.dat")
            .putString("geoSiteAddress", customSite)
            .commit()

        val upgraded = Settings(application)
        assertEquals(Settings.DEFAULT_GEO_IP_ADDRESS, upgraded.geoIpAddress)
        assertEquals(customSite, upgraded.geoSiteAddress)
        assertEquals(Settings.GeoResourcesProvider.Custom, upgraded.currentGeoResourcesProvider())
        assertEquals(customSite, preferences.getString("geoSiteAddress", null))
        assertEquals(customSite, Settings(application).geoSiteAddress)
    }

    @Test
    fun legacyPingAndUserAgentDefaultsMigrateButExplicitUserChoicesSurvive() {
        preferences.edit()
            .putString("pingAddress", "  https://www.google.com/  ")
            .putString("userAgent", "  ${BuildConfig.APPLICATION_ID}/${BuildConfig.VERSION_NAME}  ")
            .commit()
        val upgraded = Settings(application)
        val migratedAddress = upgraded.pingAddress
        val migratedAgent = upgraded.userAgent
        assertEquals("https://connectivitycheck.gstatic.com/generate_204", migratedAddress)
        assertEquals("yaxc/${BuildConfig.VERSION_NAME}", migratedAgent)
        assertEquals(migratedAddress, preferences.getString("pingAddress", null))
        assertEquals(migratedAgent, preferences.getString("userAgent", null))
        assertEquals(migratedAddress, Settings(application).pingAddress)
        assertEquals(migratedAgent, Settings(application).userAgent)

        preferences.edit()
            .putString("pingAddress", "http://127.0.0.1:18881/probe/")
            .putString("pingType", " TCP ")
            .putString("userAgent", "synthetic-client/7")
            .commit()
        val chosen = Settings(application)
        assertEquals("http://127.0.0.1:18881/probe/", chosen.pingAddress)
        assertEquals(Settings.PingType.Tcp, chosen.pingType)
        assertEquals("synthetic-client/7", chosen.userAgent)
        assertEquals(chosen.pingAddress, Settings(application).pingAddress)
        assertEquals(chosen.userAgent, Settings(application).userAgent)
        assertEquals(" TCP ", preferences.getString("pingType", null))
    }

    @Test
    fun blankLegacyPingAndAgentValuesDoNotEraseLaterChoicesOnReopen() {
        preferences.edit().putString("pingAddress", "  ").putString("userAgent", "  ").commit()
        val upgraded = Settings(application)
        assertEquals("https://connectivitycheck.gstatic.com/generate_204", upgraded.pingAddress)
        assertEquals("yaxc/${BuildConfig.VERSION_NAME}", upgraded.userAgent)
        upgraded.pingAddress = "http://127.0.0.1:18881/chosen"
        upgraded.pingType = Settings.PingType.Head
        upgraded.userAgent = "synthetic-chosen/1"
        val reopened = Settings(application)
        assertEquals("http://127.0.0.1:18881/chosen", reopened.pingAddress)
        assertEquals(Settings.PingType.Head, reopened.pingType)
        assertEquals("synthetic-chosen/1", reopened.userAgent)
    }

    @Test
    fun generatedCredentialsAndHardwareIdentityArePersistedOnceAndNotRewrittenOnReopen() {
        val first = Settings(application)
        val username = first.socksUsername
        val password = first.socksPassword
        val generatedHwid = first.generatedXHwid
        assertTrue(username.matches(Regex("yaxc_[A-Z0-9]{6}")))
        assertTrue(password.length == 8 && password.all { it in Settings.PASSWORD_ALPHABET })
        assertTrue(generatedHwid.matches(Regex("[0-9a-f]{32}")))
        assertEquals(generatedHwid, first.xHwid)
        assertTrue(preferences.getBoolean("socksCredentialsInitialized", false))
        assertTrue(preferences.getBoolean("xHwidInitialized", false))
        val persisted = preferences.all.toMap()
        val changes = mutableListOf<String?>()
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key -> changes.add(key) }
        preferences.registerOnSharedPreferenceChangeListener(listener)
        try {
            repeat(3) {
                val reopened = Settings(application)
                assertEquals(username, reopened.socksUsername)
                assertEquals(password, reopened.socksPassword)
                assertEquals(generatedHwid, reopened.generatedXHwid)
                assertEquals(generatedHwid, reopened.xHwid)
            }
            assertEquals(persisted, preferences.all)
            assertTrue(changes.isEmpty())
        } finally {
            preferences.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    @Test
    fun legacyPartialCredentialsAndCustomHardwareIdentityArePreservedDuringInitialization() {
        preferences.edit()
            .putString("socksUsername", "  synthetic-owner  ")
            .putString("socksPassword", "")
            .putString("xHwid", " synthetic-device ")
            .putString("generatedXHwid", "0123456789abcdef0123456789abcdef")
            .commit()
        val upgraded = Settings(application)
        val generatedPassword = upgraded.socksPassword
        assertEquals("synthetic-owner", upgraded.socksUsername)
        assertTrue(generatedPassword.length == 8 && generatedPassword.all { it in Settings.PASSWORD_ALPHABET })
        assertEquals("synthetic-device", upgraded.xHwid)
        assertEquals("0123456789abcdef0123456789abcdef", upgraded.generatedXHwid)
        val reopened = Settings(application)
        assertEquals("synthetic-owner", reopened.socksUsername)
        assertEquals(generatedPassword, reopened.socksPassword)
        assertEquals("synthetic-device", reopened.xHwid)

        reopened.socksUsername = ""
        reopened.socksPassword = ""
        reopened.xHwid = "  "
        val explicitlyCleared = Settings(application)
        assertEquals("", explicitlyCleared.socksUsername)
        assertEquals("", explicitlyCleared.socksPassword)
        assertEquals(upgraded.generatedXHwid, explicitlyCleared.xHwid)
    }

    @Test
    fun existingPasswordIsNotRegeneratedWhenOnlyLegacyUsernameIsMissing() {
        preferences.edit().putString("socksPassword", " synthetic saved password ").commit()
        val upgraded = Settings(application)
        val generatedUsername = upgraded.socksUsername
        assertTrue(generatedUsername.matches(Regex("yaxc_[A-Z0-9]{6}")))
        assertEquals(" synthetic saved password ", upgraded.socksPassword)
        val reopened = Settings(application)
        assertEquals(generatedUsername, reopened.socksUsername)
        assertEquals(" synthetic saved password ", reopened.socksPassword)
    }

    @Test
    fun refreshIntervalsClampLegacyAndNewValuesAtTheSchedulerMinimumWithoutCappingValidChoices() {
        val minimum = Settings.MIN_REFRESH_LINKS_INTERVAL_MINUTES
        for (stored in listOf(Int.MIN_VALUE, -1, 0, minimum - 1, minimum, minimum + 1, Int.MAX_VALUE)) {
            preferences.edit().putInt("refreshLinksInterval", stored).commit()
            assertEquals(stored.coerceAtLeast(minimum), Settings(application).refreshLinksIntervalMinutes)
        }
        val settings = Settings(application)
        for (chosen in listOf(Int.MIN_VALUE, minimum - 1, minimum, 90, Int.MAX_VALUE)) {
            settings.refreshLinksIntervalMinutes = chosen
            assertEquals(chosen.coerceAtLeast(minimum), preferences.getInt("refreshLinksInterval", -1))
            assertEquals(chosen.coerceAtLeast(minimum), Settings(application).refreshLinksIntervalMinutes)
        }
    }
}
