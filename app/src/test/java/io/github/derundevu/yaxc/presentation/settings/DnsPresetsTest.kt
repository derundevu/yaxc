package io.github.derundevu.yaxc.presentation.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.helper.ConfigHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class DnsPresetsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun allPresetsContainValidAddressesAndEncryptedResolvers() {
        assertTrue(popularDnsPresets.size >= 5)
        assertEquals(popularDnsPresets.size, popularDnsPresets.map { it.id }.distinct().size)

        popularDnsPresets.forEach { preset ->
            assertTrue("${preset.id} primaryDns", InetAddress.getByName(preset.primaryDns) is Inet4Address)
            assertTrue("${preset.id} secondaryDns", InetAddress.getByName(preset.secondaryDns) is Inet4Address)
            assertTrue("${preset.id} primaryDnsV6", InetAddress.getByName(preset.primaryDnsV6) is Inet6Address)
            assertTrue("${preset.id} secondaryDnsV6", InetAddress.getByName(preset.secondaryDnsV6) is Inet6Address)
            if (preset.encryptedDns.isNotEmpty()) {
                assertNotNull(
                    "${preset.id} encryptedDns must pass ConfigHelper.encryptedDnsResolver",
                    ConfigHelper.encryptedDnsResolver(preset.encryptedDns),
                )
            }
        }
    }

    @Test
    fun selectingDnsPresetUpdatesAllFieldsAndManualEditsReportCustom() {
        val app = RuntimeEnvironment.getApplication()
        var formState by mutableStateOf(SettingsFormState.from(Settings(app)))

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.LightSlate) {
                SettingsScreen(
                    formState = formState,
                    generatedXHwid = "generated-hwid",
                    tunRoutes = emptyList(),
                    onFormStateChange = { formState = it },
                    onTunRoutesSave = {},
                    onBack = {},
                    onSave = {},
                    onSystemVpnSettings = {},
                    onOpenCoreDns = {},
                )
            }
        }

        compose.onNodeWithTag("settings:tab:advanced").performClick()
        compose.onNode(
            hasTestTag("settings:dns-preset") and hasText(app.getString(R.string.dnsPresetCloudflare)),
        ).assertIsDisplayed().performClick()

        compose.onNodeWithTag("settings:dns-preset:cloudflare").assertIsSelected()
        compose.onNodeWithTag("settings:dns-preset-list")
            .performScrollToNode(hasTestTag("settings:dns-preset:adguard"))
        compose.onNodeWithTag("settings:dns-preset:adguard").performClick()

        assertEquals("94.140.14.14", formState.primaryDns)
        assertEquals("94.140.15.15", formState.secondaryDns)
        assertEquals("2a10:50c0::ad1:ff", formState.primaryDnsV6)
        assertEquals("2a10:50c0::ad2:ff", formState.secondaryDnsV6)
        assertEquals("https://94.140.14.14/dns-query", formState.encryptedDns)
        compose.onNodeWithText(app.getString(R.string.dnsPresetAdGuard)).assertIsDisplayed()

        // Selecting a plain-only preset clears encryptedDns rather than keeping a stale resolver.
        compose.onNodeWithTag("settings:dns-preset").performClick()
        compose.onNodeWithTag("settings:dns-preset-list")
            .performScrollToNode(hasTestTag("settings:dns-preset:yandex"))
        compose.onNodeWithTag("settings:dns-preset:yandex").performClick()

        assertEquals("77.88.8.8", formState.primaryDns)
        assertEquals("77.88.8.1", formState.secondaryDns)
        assertEquals("2a02:6b8::feed:0ff", formState.primaryDnsV6)
        assertEquals("2a02:6b8:0:1::feed:0ff", formState.secondaryDnsV6)
        assertEquals("", formState.encryptedDns)

        formState = formState.copy(primaryDns = "9.9.9.10")
        assertNull(findMatchingDnsPreset(formState))
        compose.onNode(
            hasTestTag("settings:dns-preset") and hasText(app.getString(R.string.settingsDnsPresetCustom)),
        ).assertIsDisplayed()
    }
}
