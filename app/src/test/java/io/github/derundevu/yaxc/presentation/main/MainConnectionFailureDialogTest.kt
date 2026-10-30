package io.github.derundevu.yaxc.presentation.main

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.helper.ConnectionFailure
import io.github.derundevu.yaxc.helper.GeodataFailure
import io.github.derundevu.yaxc.helper.GeodataProblem
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w360dp-h480dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainConnectionFailureDialogTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = RuntimeEnvironment.getApplication()
    private val style = mutableStateOf(YaxcThemeStyle.MidnightBlue)

    @Test
    fun standardGeodataExplainsRecoveryAndResourcesNeverDismissesTheFailure() {
        val diagnostic = "common/geodata: failed to open geosite.dat > no such file or directory"
        val failure = ConnectionFailure.fromDiagnostic(diagnostic, app.filesDir)
        val actions = mutableListOf<MainAction>()
        show(failure, actions::add)

        themes.forEach { theme ->
            compose.runOnIdle { style.value = theme }
            compose.onNodeWithText(app.getString(R.string.connectionGeodataMissing, "geosite.dat"))
                .performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(diagnostic).assertDoesNotExist()
            compose.onNodeWithText(app.getString(R.string.connectionResources))
                .performScrollTo().assertIsDisplayed().performClick()
            assertSame(failure, (actions.last() as MainAction.OpenConnectionResources).failure)
            compose.onNodeWithText(app.getString(R.string.connectionFailedTitle)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.connectionRetry)).assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.connectionDismiss)).assertIsDisplayed()

            compose.onNodeWithText(app.getString(R.string.connectionDiagnostic))
                .performScrollTo().performClick()
            compose.onNodeWithText(diagnostic).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(app.getString(R.string.connectionDiagnostic))
                .performScrollTo().performClick()
            compose.onNodeWithText(diagnostic).assertDoesNotExist()
        }
        assertEquals(themes.size, actions.size)
    }

    @Test
    fun customDatabaseAndGenericFailureShowDiagnosticWithoutMisleadingStandardDownloadAction() {
        val failure = mutableStateOf(
            ConnectionFailure.fromDiagnostic(
                "common/geodata: failed to open custom-database.dat > no such file or directory",
                app.filesDir,
            )
        )
        compose.setContent {
            LargeFontTheme {
                MainScreenTestContent(MainUiState(connectionFailure = failure.value), {})
            }
        }
        val diagnostics = listOf(
            failure.value,
            ConnectionFailure("synthetic outbound initialization error: loopback endpoint refused"),
        )
        themes.forEach { theme ->
            diagnostics.forEach { current ->
                compose.runOnIdle {
                    style.value = theme
                    failure.value = current
                }
                compose.onNodeWithText(current.diagnostic).performScrollTo().assertIsDisplayed()
                compose.onNodeWithText(app.getString(R.string.connectionResources)).assertDoesNotExist()
                compose.onNodeWithText(app.getString(R.string.connectionDiagnostic)).assertDoesNotExist()
                compose.onNodeWithText(app.getString(R.string.connectionRetry)).assertIsDisplayed()
                compose.onNodeWithText(app.getString(R.string.connectionDismiss)).assertIsDisplayed()
            }
        }
    }

    @Test
    fun expandedDiagnosticAndRecoveryActionsSurviveSavedStateRestoration() {
        val failure = ConnectionFailure(
            "synthetic geosite diagnostic retained across recreation",
            GeodataFailure("geosite.dat", GeodataProblem.MissingGroup, "SYNTHETIC-GROUP"),
        )
        val actions = mutableListOf<MainAction>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            LargeFontTheme {
                MainScreenTestContent(MainUiState(connectionFailure = failure), actions::add)
            }
        }
        compose.onNodeWithText(failure.diagnostic).assertDoesNotExist()
        compose.onNodeWithText(app.getString(R.string.connectionDiagnostic)).performScrollTo().performClick()
        compose.onNodeWithText(failure.diagnostic).performScrollTo().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText(failure.diagnostic).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.connectionResources)).performScrollTo().performClick()
        assertSame(failure, (actions.single() as MainAction.OpenConnectionResources).failure)
        compose.onNodeWithText(app.getString(R.string.connectionRetry)).assertIsDisplayed().performClick()
        assertSame(failure, (actions.last() as MainAction.RetryConnectionFailure).failure)
    }

    private fun show(failure: ConnectionFailure, onAction: (MainAction) -> Unit) {
        compose.setContent {
            LargeFontTheme { MainScreenTestContent(MainUiState(connectionFailure = failure), onAction) }
        }
    }

    @androidx.compose.runtime.Composable
    private fun LargeFontTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
            YaxcTheme(style = style.value, content = content)
        }
    }

    private companion object {
        val themes = listOf(
            YaxcThemeStyle.MidnightBlue,
            YaxcThemeStyle.Graphite,
            YaxcThemeStyle.Mint,
            YaxcThemeStyle.LightSlate,
        )
    }
}
