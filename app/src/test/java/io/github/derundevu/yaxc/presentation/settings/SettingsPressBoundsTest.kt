package io.github.derundevu.yaxc.presentation.settings

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.root.SettingsHomeScreen
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w360dp-h760dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsPressBoundsTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun settingsRowClickAreaReachesCardHorizontalEdges() {
        val app = RuntimeEnvironment.getApplication()
        val initialForm = SettingsFormState.from(Settings(app))

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.LightSlate) {
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

        compose.onNodeWithTag("settings:tab:advanced").performClick()
        // Tap 2px from the left edge (inside what used to be a dead 12dp card padding).
        compose.onNodeWithTag("settings:dns-preset").performTouchInput {
            click(Offset(2f, centerY))
        }
        compose.onNodeWithTag("settings:dns-preset-list").assertIsDisplayed()
    }

    @Test
    fun settingsHomeCardClickAreaReachesCardEdges() {
        val app = RuntimeEnvironment.getApplication()
        var openedSettings = 0

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.MidnightBlue) {
                SettingsHomeScreen(
                    appVersion = "1.6.3",
                    xrayVersion = "26.11.9.6",
                    tun2socksVersion = "2.19.3",
                    appUpdateState = AppUpdateUiState(),
                    onOpenAssets = {},
                    onOpenLinks = {},
                    onOpenLogs = {},
                    onOpenSettings = { openedSettings++ },
                    onCheckAppUpdate = {},
                    onDownloadAppUpdate = {},
                    onInstallAppUpdate = {},
                    onSelectDestination = {},
                )
            }
        }

        compose.onNodeWithText(app.getString(R.string.preferences)).performTouchInput {
            click(Offset(2f, centerY))
        }
        assertEquals(1, openedSettings)
    }
}
