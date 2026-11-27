package io.github.derundevu.yaxc.presentation.designsystem

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcCard
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcGlassPanel
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcScaffold
import io.github.derundevu.yaxc.presentation.main.MainScreenTestContent
import io.github.derundevu.yaxc.presentation.main.MainUiState
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w1024dp-h700dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MintThemeContentColorTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun darkThemesAndContainersProvideLightContentColors() {
        val darkThemes = listOf(YaxcThemeStyle.Mint, YaxcThemeStyle.MidnightBlue, YaxcThemeStyle.Graphite)
        val recorded = mutableMapOf<YaxcThemeStyle, List<Color>>()
        val expected = mutableMapOf<YaxcThemeStyle, Pair<Color, Color>>()

        compose.setContent {
            darkThemes.forEach { style ->
                YaxcTheme(style = style) {
                    val onBg = MaterialTheme.colorScheme.onBackground
                    val onSurface = MaterialTheme.colorScheme.onSurface
                    expected[style] = onBg to onSurface
                    val rootColor = LocalContentColor.current
                    YaxcScaffold {
                        val scaffoldColor = LocalContentColor.current
                        YaxcCard {
                            val cardColor = LocalContentColor.current
                            Text("card")
                            YaxcCard(onClick = {}) {
                                val clickableCardColor = LocalContentColor.current
                                YaxcGlassPanel {
                                    val glassColor = LocalContentColor.current
                                    recorded[style] = listOf(
                                        rootColor,
                                        scaffoldColor,
                                        cardColor,
                                        clickableCardColor,
                                        glassColor,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        compose.waitForIdle()

        darkThemes.forEach { style ->
            val (onBg, onSurface) = expected.getValue(style)
            val colors = recorded.getValue(style)
            assertEquals("Root LocalContentColor for $style", onBg, colors[0])
            assertEquals("YaxcScaffold LocalContentColor for $style", onBg, colors[1])
            assertEquals("YaxcCard LocalContentColor for $style", onSurface, colors[2])
            assertEquals("Clickable YaxcCard LocalContentColor for $style", onSurface, colors[3])
            assertEquals("YaxcGlassPanel LocalContentColor for $style", onSurface, colors[4])
            colors.forEach { color ->
                assertNotEquals("Content color must not fall back to Color.Black on $style", Color.Black, color)
                assertTrue("Content color luminance must be light on $style", color.luminance() > 0.7f)
            }
        }
    }

    @Test
    fun adaptiveRoutingAndSettingsTabsDisplayOnMintTheme() {
        val app = RuntimeEnvironment.getApplication()
        val routingLabel = app.getString(R.string.rootTabRouting)
        val settingsLabel = app.getString(R.string.rootTabSettings)

        compose.setContent {
            YaxcTheme(style = YaxcThemeStyle.Mint) {
                MainScreenTestContent(state = MainUiState(), onAction = {})
            }
        }

        compose.onNodeWithTag("main:rail:routing").performClick()
        compose.waitForIdle()
        compose.onNode(isHeading() and hasText(routingLabel)).assertIsDisplayed()

        compose.onNodeWithTag("main:rail:settings").performClick()
        compose.waitForIdle()
        compose.onNode(isHeading() and hasText(settingsLabel)).assertIsDisplayed()
    }
}
