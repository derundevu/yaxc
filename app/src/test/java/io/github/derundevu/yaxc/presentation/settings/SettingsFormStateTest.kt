package io.github.derundevu.yaxc.presentation.settings

import android.app.Application
import android.content.Context
import androidx.compose.runtime.saveable.SaverScope
import io.github.derundevu.yaxc.Settings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class SettingsFormStateTest {
    private lateinit var application: Application
    private val saverScope = object : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    @Before
    fun setUp() {
        application = RuntimeEnvironment.getApplication()
        application.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @After
    fun tearDown() {
        application.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun killSwitchDefaultsOffAndPersistsWithoutChangingModeOrExclusions() {
        val settings = Settings(application)
        assertFalse(settings.killSwitch)
        assertFalse(SettingsFormState.from(settings).killSwitch)
        settings.tun2socks = false
        settings.transparentProxy = true
        settings.appsRoutingMode = Settings.AppsRoutingMode.Include
        settings.appsRouting = "synthetic.chosen"
        settings.bypassLan = true
        settings.tunRoutes = setOf("198.18.0.0/15")

        settings.killSwitch = true

        val reopened = Settings(application)
        val form = SettingsFormState.from(reopened)
        assertTrue(form.killSwitch)
        assertFalse(form.tun2socks)
        assertTrue(form.transparentProxy)
        assertEquals(Settings.AppsRoutingMode.Include, reopened.appsRoutingMode)
        assertEquals("synthetic.chosen", reopened.appsRouting)
        assertTrue(reopened.bypassLan)
        assertEquals(setOf("198.18.0.0/15"), reopened.tunRoutes)
        reopened.killSwitch = false
        assertFalse(Settings(application).killSwitch)
    }

    @Test
    fun saverRoundTripsBothKillSwitchValuesAndLeavesExistingFieldsIntact() {
        val original = SettingsFormState.from(Settings(application)).copy(
            xHwid = "synthetic-device",
            liveRoutingHealthInterval = "25",
            bypassLan = false,
        )
        for (enabled in listOf(false, true)) {
            val form = original.copy(killSwitch = enabled)
            val saved = with(SettingsFormState.Saver) { saverScope.save(form) }!!
            assertEquals(form, SettingsFormState.Saver.restore(saved))
        }
    }

    @Test
    fun savedFormsBeforeKillSwitchRestoreWithProtectionOff() {
        val form = SettingsFormState.from(Settings(application)).copy(killSwitch = true)
        val saved = with(SettingsFormState.Saver) { saverScope.save(form) }!! as List<*>

        // One trailing field was added after killSwitch, so pre-killSwitch saves are two slots shorter.
        assertEquals(true, saved[saved.size - 2])
        assertEquals(
            form.copy(killSwitch = false, encryptedDns = ""),
            SettingsFormState.Saver.restore(saved.dropLast(2)),
        )
    }
}
