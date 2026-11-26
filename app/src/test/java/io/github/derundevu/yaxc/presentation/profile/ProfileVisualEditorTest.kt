package io.github.derundevu.yaxc.presentation.profile

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
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
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "en-rUS-w412dp-h892dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProfileVisualEditorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun profileScreenSupportsModeSwitchProtocolPickerKnownValuesAndCustomValue() {
        val app = RuntimeEnvironment.getApplication()
        var mode by mutableStateOf(ProfileEditorMode.Visual)
        var name by mutableStateOf("My VLESS")
        var visualConfig by mutableStateOf(ProfileVisualConfig())
        val editorState = JsonEditorState("{}")

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.MidnightBlue) {
                ProfileScreen(
                    title = app.getString(R.string.editProfile),
                    editorMode = mode,
                    name = name,
                    selectedSourceId = 1L,
                    availableSources = listOf(ProfileSourceOption(1L, "Manual")),
                    isLoading = false,
                    isSaving = false,
                    editorState = editorState,
                    visualConfig = visualConfig,
                    onBack = {},
                    onSave = {},
                    onEditorModeChange = { mode = it },
                    onNameChange = { name = it },
                    onSourceChange = {},
                    onVisualConfigChange = { transform -> visualConfig = transform(visualConfig) },
                )
            }
        }

        fun scrollToTag(tag: String) {
            compose.onNodeWithTag("profile-editor:visual-content").performScrollToNode(hasTestTag(tag))
        }

        compose.onNodeWithTag("profile-editor:mode:Visual").assertIsSelected()
        scrollToTag("profile-visual:address")
        compose.onNodeWithTag("profile-visual:address").performTextReplacement("proxy.example.org")
        assertEquals("proxy.example.org", visualConfig.activeOutbound.address)

        scrollToTag("profile-visual:protocol")
        compose.onNodeWithTag("profile-visual:protocol").performClick()
        compose.onNodeWithText("Shadowsocks").performClick()
        assertEquals("shadowsocks", visualConfig.activeOutbound.protocol)

        scrollToTag("profile-visual:ss-method")
        compose.onNodeWithTag("profile-visual:ss-method").performClick()
        compose.onNodeWithText("2022-blake3-aes-256-gcm").performClick()
        assertEquals("2022-blake3-aes-256-gcm", visualConfig.activeOutbound.ssMethod)

        scrollToTag("profile-visual:protocol")
        compose.onNodeWithTag("profile-visual:protocol").performClick()
        compose.onNodeWithText("VLESS").performClick()
        assertEquals("vless", visualConfig.activeOutbound.protocol)

        scrollToTag("profile-visual:flow")
        compose.onNodeWithTag("profile-visual:flow").performClick()
        compose.onNodeWithText("xtls-rprx-vision").performClick()
        assertEquals("xtls-rprx-vision", visualConfig.activeOutbound.vlessFlow)

        scrollToTag("profile-visual:security:reality")
        compose.onNodeWithTag("profile-visual:security:reality").performClick()
        assertEquals("reality", visualConfig.activeOutbound.security)

        scrollToTag("profile-visual:fingerprint")
        compose.onNodeWithTag("profile-visual:fingerprint").performClick()
        compose.onNodeWithText("chrome").performClick()
        assertEquals("chrome", visualConfig.activeOutbound.fingerprint)

        compose.onNodeWithTag("profile-editor:mode:Json").performClick()
        assertEquals(ProfileEditorMode.Json, mode)
    }
}
