package io.github.derundevu.yaxc.presentation.connection

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.testing.RegressionApplication
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
class ConnectionInfoScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun longAddressesAndUrlsRenderWithoutTruncationAndSubscriptionShowsWhenPresent() {
        val longHost = "edge-gateway-01.eu-central.very-long-provider-domain.example.invalid"
        val resolvedIps = "203.0.113.10, 2001:db8:85a3::8a2e:370:7334"
        val supportUrl = "https://support.example.invalid/tickets/very/long/path?tenant=yaxc-mobile&lang=en"
        val webPageUrl = "https://panel.example.invalid/subscriptions/profile-details-overview/123456"
        val pingUrl = "https://connectivitycheck.example.invalid/generate_204?probe=long-query"

        var backClicks = 0
        compose.setContent {
            YaxcTheme(YaxcThemeStyle.LightSlate) {
                ConnectionInfoScreen(
                    state = sampleState(
                        selectedServerLabel = longHost,
                        pingAddress = pingUrl,
                        metadata = SubscriptionMetadata(
                            uploadBytes = 12L * 1024L * 1024L * 1024L,
                            downloadBytes = 30L * 1024L * 1024L * 1024L,
                            totalBytes = 0L,
                            expireAtEpochSeconds = 1_893_456_000L,
                            updateIntervalMinutes = 60,
                            supportUrl = supportUrl,
                            profileWebPageUrl = webPageUrl,
                        ),
                    ),
                    onBack = { backClicks += 1 },
                    resolveServer = { resolvedIps },
                    resolveExitIp = { _, _, _, _ -> "198.51.100.42" },
                )
            }
        }

        val app = RuntimeEnvironment.getApplication()
        compose.onNodeWithText(longHost).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(resolvedIps).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("198.51.100.42").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.connectionInfoSubscriptionSection))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.mainSubscriptionUnlimited))
            .performScrollTo()
            .assertIsDisplayed()
        compose.onNodeWithText(supportUrl).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(webPageUrl).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(pingUrl).performScrollTo().assertIsDisplayed()

        compose.onNodeWithContentDescription(app.getString(R.string.navigateBack)).performClick()
        assertEquals(1, backClicks)
    }

    @Test
    fun omitsSubscriptionSectionWhenMetadataIsAbsentAndTogglesPassword() {
        val app = RuntimeEnvironment.getApplication()
        compose.setContent {
            YaxcTheme(YaxcThemeStyle.MidnightBlue) {
                ConnectionInfoScreen(
                    state = sampleState(metadata = null),
                    onBack = {},
                    resolveServer = { "203.0.113.10" },
                    resolveExitIp = { _, _, _, _ -> "198.51.100.42" },
                )
            }
        }

        compose.onNodeWithTag("connection-info:subscription").assertDoesNotExist()
        assertFalse(
            compose.onAllNodesWithText(app.getString(R.string.connectionInfoSubscriptionSection))
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        val hiddenText = app.getString(R.string.mainConnectionPasswordHidden)
        compose.onNodeWithTag("connection-info:password").performScrollTo()
        compose.onNodeWithText(hiddenText).assertIsDisplayed()
        compose.onNodeWithText("super-secret-pass").assertDoesNotExist()

        compose.onNodeWithTag("connection-info:password-toggle").performClick()
        compose.onNodeWithText("super-secret-pass").assertIsDisplayed()
        compose.onNodeWithText(hiddenText).assertDoesNotExist()

        compose.onNodeWithTag("connection-info:password-toggle").performClick()
        compose.onNodeWithText(hiddenText).assertIsDisplayed()
    }

    @Test
    fun showsLoadingAndErrorStatesForServerResolutionAndExitIp() {
        val app = RuntimeEnvironment.getApplication()
        val serverGate = CompletableDeferred<String>()
        val exitGate = CompletableDeferred<String>()

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.Graphite) {
                ConnectionInfoScreen(
                    state = sampleState(metadata = null),
                    onBack = {},
                    resolveServer = { serverGate.await() },
                    resolveExitIp = { _, _, _, _ -> exitGate.await() },
                )
            }
        }

        compose.onNodeWithTag("connection-info:resolved-server:loading").assertIsDisplayed()
        compose.onNodeWithTag("connection-info:exit-ip:loading").assertIsDisplayed()
        assertEquals(
            2,
            compose.onAllNodesWithText(app.getString(R.string.editorChecking))
                .fetchSemanticsNodes()
                .size,
        )
        assertEquals(
            2,
            compose.onAllNodes(hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate))
                .fetchSemanticsNodes()
                .size,
        )

        serverGate.complete("203.0.113.55")
        exitGate.completeExceptionally(IllegalStateException("socks unreachable"))

        compose.waitUntil(5_000) {
            compose.onAllNodesWithText("203.0.113.55").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithText(app.getString(R.string.mainConnectionResolveFailed))
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }

        compose.onNodeWithTag("connection-info:resolved-server:loading").assertDoesNotExist()
        compose.onNodeWithTag("connection-info:exit-ip:loading").assertDoesNotExist()
        compose.onNodeWithText("203.0.113.55").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.mainConnectionResolveFailed)).assertIsDisplayed()
    }

    @Test
    fun showsNoValueWithoutLoadingWhenInputsAreBlank() {
        val app = RuntimeEnvironment.getApplication()
        val noValue = app.getString(R.string.noValue)

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.Mint) {
                ConnectionInfoScreen(
                    state = ConnectionInfoScreenState(
                        selectedSourceName = "",
                        selectedProfileName = "",
                        selectedSourceMetadata = null,
                        selectedServerLabel = "",
                        socksAddress = "",
                        socksPort = "",
                        socksUsername = "",
                        socksPassword = "",
                        pingAddress = "",
                    ),
                    onBack = {},
                    resolveServer = { error("Should not resolve blank server") },
                    resolveExitIp = { _, _, _, _ -> error("Should not resolve blank socks") },
                )
            }
        }

        compose.onNodeWithTag("connection-info:resolved-server:loading").assertDoesNotExist()
        compose.onNodeWithTag("connection-info:exit-ip:loading").assertDoesNotExist()
        compose.onNodeWithTag("connection-info:password-toggle").assertDoesNotExist()
        compose.onNode(
            hasAnyAncestor(hasTestTag("connection-info:resolved-server")) and hasText(noValue),
        ).assertIsDisplayed()
        compose.onNode(
            hasAnyAncestor(hasTestTag("connection-info:exit-ip")) and hasText(noValue),
        ).assertIsDisplayed()
    }

    private fun sampleState(
        selectedServerLabel: String = "proxy.example.invalid",
        pingAddress: String = "https://www.gstatic.com/generate_204",
        metadata: SubscriptionMetadata? = null,
    ) = ConnectionInfoScreenState(
        selectedSourceName = "Main Provider",
        selectedProfileName = "Helsinki Wire",
        selectedSourceMetadata = metadata,
        selectedServerLabel = selectedServerLabel,
        socksAddress = "127.0.0.1",
        socksPort = "10808",
        socksUsername = "yaxc-user",
        socksPassword = "super-secret-pass",
        pingAddress = pingAddress,
    )
}
