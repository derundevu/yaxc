package io.github.derundevu.yaxc.presentation.root

import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.AppUpdateUiState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppVersionsPanelTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun downloadProgressKeepsRealByteRatioDuringChecksAndReplacesItWithInstallOnCompletion() {
        val state = mutableStateOf(AppUpdateUiState(
            isChecking = true, isDownloading = true, pendingVersion = "999.1",
            downloadedBytes = 5_000L, totalBytes = 20_000L,
        ))
        compose.setContent { YaxcTheme { AppUpdatePanel(state.value, {}, {}) } }
        assertEquals(0.25f, progress().current, 0f)
        compose.runOnIdle { state.value = state.value.copy(downloadedBytes = 30_000L) }
        assertEquals(1f, progress().current, 0f)
        compose.runOnIdle {
            state.value = state.value.copy(isChecking = false, isDownloading = false, isReadyToInstall = true)
        }
        compose.onNodeWithTag("updates:progress").assertDoesNotExist()
        compose.onNodeWithTag("updates:install").assertExists()
        compose.onNodeWithTag("updates:download").assertDoesNotExist()
    }

    @Test
    fun unknownSizeUsesIndeterminateProgressWithoutInventingAPercentage() {
        val state = mutableStateOf(AppUpdateUiState(
            isChecking = false, isDownloading = true, downloadedBytes = 5_000L,
        ))
        compose.setContent { YaxcTheme { AppUpdatePanel(state.value, {}, {}) } }
        assertEquals(ProgressBarRangeInfo.Indeterminate, progress())
        compose.onNodeWithTag("updates:percent").assertDoesNotExist()
        compose.runOnIdle { state.value = state.value.copy(totalBytes = 10_000L) }
        assertEquals(0.5f, progress().current, 0f)
        compose.onNodeWithTag("updates:percent").assertExists()
    }

    @Test
    fun sourceCodeButtonOpensGitHubRepositoryUrl() {
        val app = RuntimeEnvironment.getApplication()
        compose.setContent {
            YaxcTheme {
                AppVersionsPanel(
                    appVersion = "1.6.3",
                    xrayVersion = "26.11.9.6",
                    tun2socksVersion = "2.19.3",
                    updateState = AppUpdateUiState(),
                    onCheckUpdate = {},
                    onDownloadUpdate = {},
                    onInstallUpdate = {},
                )
            }
        }

        compose.onNodeWithTag("settings:source-code").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.appSourceCode)).assertIsDisplayed()
        compose.onNodeWithTag("settings:source-code").performClick()

        val started = shadowOf(app).nextStartedActivity
        assertNotNull(started)
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals(APP_REPOSITORY_URL, started.dataString)
    }

    private fun progress(): ProgressBarRangeInfo = compose.onNodeWithTag("updates:progress")
        .fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo]
}
