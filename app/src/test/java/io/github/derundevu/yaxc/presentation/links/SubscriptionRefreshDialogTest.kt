package io.github.derundevu.yaxc.presentation.links

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.derundevu.yaxc.helper.SubscriptionRefreshHelper
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "en-rUS-w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SubscriptionRefreshDialogTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun unchangedPartialImportListsSkippedNamesAndProtocolsInsteadOfClaimingNothingNew() {
        val open = mutableStateOf(true)
        val result = SubscriptionRefreshHelper.RefreshResult(1, 1, 0, warnings = listOf(
            SubscriptionRefreshHelper.SourceWarning(7, "Synthetic source", listOf(
                SubscriptionRefreshHelper.SkippedProfile("Unsupported node", "anytls"),
            )),
        ))
        compose.setContent {
            YaxcTheme {
                if (open.value) SubscriptionRefreshDialog(result, onClose = { open.value = false })
            }
        }
        compose.onNodeWithText("Nothing new").assertDoesNotExist()
        compose.onNodeWithText("Supported profiles were processed, but some entries were skipped.").assertIsDisplayed()
        compose.onNodeWithText("Synthetic source").assertIsDisplayed()
        compose.onNodeWithText("Unsupported node · anytls").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.onNodeWithText("Unsupported node · anytls").assertDoesNotExist()
    }
}
