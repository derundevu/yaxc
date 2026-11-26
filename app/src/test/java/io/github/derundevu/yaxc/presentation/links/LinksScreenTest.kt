package io.github.derundevu.yaxc.presentation.links

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.YaxcThemeStyle
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
class LinksScreenTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun rendersEmptyStateWhenNoLinksPresent() {
        val app = RuntimeEnvironment.getApplication()
        compose.setContent {
            YaxcTheme(YaxcThemeStyle.LightSlate) {
                LinksScreen(
                    links = emptyList(),
                    onBack = {},
                    onRefresh = {},
                    onNewLink = {},
                    onEditLink = {},
                    onDeleteLink = {},
                )
            }
        }

        compose.onNodeWithTag("links:empty").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.noLinksYet)).assertIsDisplayed()
    }

    @Test
    fun rendersGroupMetadataAndCompactEditDeleteActionsWork() {
        val app = RuntimeEnvironment.getApplication()
        val subscription = Link(
            id = 1L,
            name = "Provider Alpha",
            address = "https://sub.example.invalid/api/v1/client/subscribe?token=secret",
            type = Link.Type.Subscription,
            isActive = true,
            lastRefreshedAt = 1_764_150_000_000L,
            refreshIntervalMinutes = 120,
        )
        val manual = Link(
            id = 2L,
            name = "Local JSON",
            address = "",
            type = Link.Type.Json,
            isActive = true,
        )
        val edited = mutableListOf<Long>()
        val deleted = mutableListOf<Long>()

        compose.setContent {
            YaxcTheme(YaxcThemeStyle.MidnightBlue) {
                LinksScreen(
                    links = listOf(subscription, manual),
                    onBack = {},
                    onRefresh = {},
                    onNewLink = {},
                    onEditLink = { edited += it.id },
                    onDeleteLink = { deleted += it.id },
                )
            }
        }

        compose.onNodeWithText("Provider Alpha").assertIsDisplayed()
        compose.onNodeWithText("sub.example.invalid").assertIsDisplayed()
        compose.onNodeWithText("Local JSON").assertIsDisplayed()
        compose.onNodeWithText(app.getString(R.string.linkTypeJson)).assertIsDisplayed()

        val editBounds = compose.onNodeWithTag("links:edit:1").fetchSemanticsNode().boundsInRoot
        val deleteBounds = compose.onNodeWithTag("links:delete:1").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "Edit and delete bubbles should stay compactly grouped",
            deleteBounds.left - editBounds.right in 0f..24f,
        )

        compose.onNodeWithTag("links:edit:1").performClick()
        compose.onNodeWithTag("links:delete:1").performClick()
        compose.onNodeWithTag("links:item:2").performClick()

        assertEquals(listOf(1L, 2L), edited)
        assertEquals(listOf(1L), deleted)
    }
}
