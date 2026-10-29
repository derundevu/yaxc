package io.github.derundevu.yaxc.presentation.assets

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.Settings
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertTrue
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
class AssetsScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun geositeRecoveryFocusRevealsNamedAssetWithoutStartingAnyMutation() {
        val actions = mutableListOf<String>()
        compose.setContent { Screen("geosite.dat", actions, YaxcThemeStyle.Graphite) }
        compose.onNodeWithText("Synthetic GeoSite").assertIsDisplayed()
        compose.onNodeWithTag("asset:geosite.dat").assertIsDisplayed()
        assertTrue("Focusing recovery must not download, import, delete, or change provider", actions.isEmpty())
    }

    @Test
    fun geoipRecoveryFocusRevealsNamedAssetWithoutStartingAnyMutation() {
        val actions = mutableListOf<String>()
        compose.setContent { Screen("geoip.dat", actions, YaxcThemeStyle.LightSlate) }
        compose.onNodeWithText("Synthetic GeoIP").assertIsDisplayed()
        compose.onNodeWithTag("asset:geoip.dat").assertIsDisplayed()
        assertTrue(actions.isEmpty())
    }

    @Test
    fun recreationPreservesManualScrollInsteadOfReplayingAlreadyHandledFocus() {
        val actions = mutableListOf<String>()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { Screen("geosite.dat", actions, YaxcThemeStyle.MidnightBlue) }
        compose.onNodeWithText("Synthetic GeoSite").assertIsDisplayed()
        val providerTitle = RuntimeEnvironment.getApplication()
            .getString(R.string.assetsGeoProviderTitle)
        compose.onNodeWithText(providerTitle).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Synthetic GeoSite").assertIsNotDisplayed()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText(providerTitle).assertIsDisplayed()
        compose.onNodeWithText("Synthetic GeoSite").assertIsNotDisplayed()
        assertTrue(actions.isEmpty())
    }

    @androidx.compose.runtime.Composable
    private fun Screen(filename: String, actions: MutableList<String>, theme: YaxcThemeStyle) {
        val density = LocalDensity.current
        CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
            YaxcTheme(theme) {
                AssetsScreen(
                    geoIpState = AssetCardState(title = "Synthetic GeoIP", value = "Not installed"),
                    geoSiteState = AssetCardState(title = "Synthetic GeoSite", value = "Not installed"),
                    antifilterState = AssetCardState(title = "Synthetic antifilter", value = "Not installed"),
                    xrayCoreState = AssetCardState(title = "Synthetic core", value = "Bundled"),
                    selectedGeoProvider = Settings.GeoResourcesProvider.Custom,
                    customGeoIpUrl = "http://127.0.0.1:19080/geoip.dat",
                    customGeoSiteUrl = "http://127.0.0.1:19080/geosite.dat",
                    focusedAssetFilename = filename,
                    onBack = { actions += "back" },
                    onGeoProviderSelected = { actions += "provider" },
                    onCustomGeoIpUrlChange = { actions += "ip-url" },
                    onCustomGeoSiteUrlChange = { actions += "site-url" },
                    onApplyCustomGeoUrls = { actions += "apply-urls" },
                    onGeoIpDownload = { actions += "ip-download" },
                    onGeoIpPick = { actions += "ip-pick" },
                    onGeoIpDelete = { actions += "ip-delete" },
                    onGeoSiteDownload = { actions += "site-download" },
                    onGeoSitePick = { actions += "site-pick" },
                    onGeoSiteDelete = { actions += "site-delete" },
                    onAntifilterDownload = { actions += "antifilter-download" },
                    onAntifilterPick = { actions += "antifilter-pick" },
                    onAntifilterDelete = { actions += "antifilter-delete" },
                    onXrayCorePick = { actions += "core-pick" },
                    onXrayCoreDelete = { actions += "core-delete" },
                )
            }
        }
    }
}
