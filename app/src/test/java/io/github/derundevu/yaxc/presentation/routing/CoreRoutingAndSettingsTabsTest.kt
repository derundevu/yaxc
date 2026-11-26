package io.github.derundevu.yaxc.presentation.routing

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.database.Config
import io.github.derundevu.yaxc.helper.CoreRoutingEditorMode
import io.github.derundevu.yaxc.helper.CoreRoutingRule
import io.github.derundevu.yaxc.helper.CoreRoutingTransport
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.presentation.settings.SettingsFormState
import io.github.derundevu.yaxc.presentation.settings.SettingsScreen
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config as RobolectricConfig
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@RobolectricConfig(application = RegressionApplication::class, sdk = [34], qualifiers = "w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CoreRoutingAndSettingsTabsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun coreRoutingUnifiedTabSelectorsUpdateModesStrategyTransportAndOutbound() {
        val app = RuntimeEnvironment.getApplication()
        var routingMode by mutableStateOf(Config.Mode.Merge)
        var editorMode by mutableStateOf(CoreRoutingEditorMode.Visual)
        var strategy by mutableStateOf("IPIfNonMatch")
        var rule by mutableStateOf(
            CoreRoutingRule(
                id = "rule-1",
                name = "Direct LAN",
                domainsText = "geosite:private",
                network = CoreRoutingTransport.Any,
                outboundTag = "proxy",
                enabled = true,
            ),
        )
        val editor = JsonEditorState()

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.LightSlate) {
                CoreRoutingScreen(
                    editorMode = editorMode,
                    domainStrategy = strategy,
                    rules = listOf(rule),
                    unsupportedRuleCount = 0,
                    isLoading = false,
                    isBusy = false,
                    savedRevision = 0,
                    editorState = editor,
                    antifilterEnabled = false,
                    antifilterInstalled = false,
                    antifilterRouteCount = 0,
                    onBack = {},
                    onSave = {},
                    onAntifilterEnabledChange = {},
                    onImportFromClipboard = {},
                    onExportJson = {},
                    onEditorModeChange = { editorMode = it },
                    onDomainStrategyChange = { strategy = it },
                    onRuleChange = { rule = it },
                    onAddRule = {},
                    onMoveRule = { _, _ -> },
                    onDeleteRule = {},
                    routingMode = routingMode,
                    onRoutingModeChange = { routingMode = it },
                    onImportFromProfile = {},
                )
            }
        }

        compose.onNodeWithTag("core-routing:mode:Merge").assertIsSelected()
        compose.onNodeWithTag("core-routing:mode:Replace").performClick().assertIsSelected()
        assertEquals(Config.Mode.Replace, routingMode)

        compose.onNodeWithText("AsIs").performClick()
        assertEquals("AsIs", strategy)

        compose.onNodeWithTag("core-routing:rule-header:rule-1").performTouchInput {
            click(Offset(width * 0.15f, height / 2f))
        }
        compose.onNodeWithText(app.getString(R.string.routingTransportTcp)).performScrollTo().performClick()
        assertEquals(CoreRoutingTransport.Tcp, rule.network)

        compose.onNodeWithText(app.getString(R.string.routingOutboundDirect)).performScrollTo().performClick()
        assertEquals("direct", rule.outboundTag)

        compose.onNodeWithText(app.getString(R.string.routingModeJson)).performClick()
        assertEquals(CoreRoutingEditorMode.Json, editorMode)
    }

    @Test
    fun settingsTabSelectorSwitchesBetweenBasicAndAdvanced() {
        val app = RuntimeEnvironment.getApplication()
        val initialForm = SettingsFormState.from(Settings(app))

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.MidnightBlue) {
                SettingsScreen(
                    formState = initialForm,
                    generatedXHwid = "generated-hwid",
                    tunRoutes = emptyList(),
                    onFormStateChange = {},
                    onTunRoutesSave = {},
                    onBack = {},
                    onSave = {},
                    onSystemVpnSettings = {},
                    onOpenCoreDns = {},
                )
            }
        }

        compose.onNodeWithTag("settings:tab:basic").assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.settingsSectionConnection)).assertIsDisplayed()

        compose.onNodeWithTag("settings:tab:advanced").performClick().assertIsSelected()
        compose.onNodeWithText(app.getString(R.string.settingsSectionDns)).assertIsDisplayed()
    }
}
