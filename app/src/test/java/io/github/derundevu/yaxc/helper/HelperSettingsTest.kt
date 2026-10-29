package io.github.derundevu.yaxc.helper

import android.app.Application
import android.content.Context
import io.github.derundevu.yaxc.Settings
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.robolectric.RuntimeEnvironment

abstract class HelperSettingsTest {
    protected lateinit var settings: Settings
    private lateinit var application: Application

    @Before
    fun createIsolatedSettings() {
        application = RuntimeEnvironment.getApplication()
        // Seed the initialization flags before constructing real Settings: no random credentials/HWID.
        application.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .clear()
            .putBoolean("socksCredentialsInitialized", true)
            .putBoolean("xHwidInitialized", true)
            .putString("socksUsername", "test-user")
            .putString("socksPassword", "test-password")
            .putString("xHwid", "synthetic-test-hwid")
            .commit()
        settings = Settings(application).apply {
            socksAddress = "127.0.0.1"
            socksPort = "19080"
            primaryDns = "192.0.2.53"
            secondaryDns = "198.51.100.53"
            primaryDnsV6 = "2001:db8::53"
            secondaryDnsV6 = "2001:db8::54"
            enableIpV6 = false
            transparentProxy = false
            tproxyHotspot = false
            tproxyTethering = false
            antifilterEnabled = false
        }
        settings.antifilterFile().delete()
    }

    @After
    fun clearIsolatedSettings() {
        settings.antifilterFile().delete()
        application.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }
}

internal fun JSONArray.stringValues(): List<String> = (0 until length()).map(::getString)

internal fun JSONArray.objectValues(): List<JSONObject> = (0 until length()).map(::getJSONObject)

internal fun assertJsonEquals(expected: Any, actual: Any, path: String = "$") {
    when (expected) {
        is JSONObject -> {
            require(actual is JSONObject) { "$path must be an object, got $actual" }
            val keys = expected.keys().asSequence().toSet()
            assertEquals("$path members", keys, actual.keys().asSequence().toSet())
            keys.forEach { key -> assertJsonEquals(expected.get(key), actual.get(key), "$path.$key") }
        }
        is JSONArray -> {
            require(actual is JSONArray) { "$path must be an array, got $actual" }
            assertEquals("$path length", expected.length(), actual.length())
            for (index in 0 until expected.length()) {
                assertJsonEquals(expected.get(index), actual.get(index), "$path[$index]")
            }
        }
        else -> assertEquals(path, expected, actual)
    }
}
