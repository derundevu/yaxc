package io.github.derundevu.yaxc.activity

import android.os.Bundle

import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.ViewModelProvider
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.dto.SubscriptionMetadata
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import io.github.derundevu.yaxc.testing.waitForAsyncState
import io.github.derundevu.yaxc.viewmodel.LinksManagerViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "en-rUS-w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LinkFormActivityTest : RoomTestSupport() {
    override val useAndroidMainDispatcher = true
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun unsavedIntervalSurvivesRecreationThenSavesOfflineWithoutChangingProfilesOrIdentity() {
        val source = storedSource(30)
        val beforeProfiles = runBlocking { database.profileDao().linkProfiles(source.id) }
        val controller = open(source)
        try {
            interval().assertTextContains("30")
            interval().performScrollTo().performTextReplacement("90")
            controller.recreate()
            compose.waitForIdle()
            interval().assertTextContains("90")
            assertEquals(source, runBlocking { database.linkDao().find(source.id) })

            save(controller)
            assertEquals(source.copy(refreshIntervalMinutes = 90),
                runBlocking { database.linkDao().find(source.id) })
            assertEquals(beforeProfiles, runBlocking { database.profileDao().linkProfiles(source.id) })
            assertEquals(listOf(source.id), runBlocking { database.linkDao().orderedIds() })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun savedInstanceStateRestoresUnsavedIntervalWithAFreshActivityAndViewModel() {
        val source = storedSource(30)
        var controller: ActivityController<LinksManagerActivity>? = open(source)
        try {
            interval().performScrollTo().performTextReplacement("120")
            val saved = Bundle()
            controller!!.pause().saveInstanceState(saved).stop().destroy()
            controller = null
            controller = Robolectric.buildActivity(LinksManagerActivity::class.java,
                LinksManagerActivity.openLink(app, source))
                .create(saved).start().restoreInstanceState(saved).resume().visible()
            compose.waitForIdle()
            interval().assertTextContains("120")
            assertFalse(controller.get().isFinishing)
            assertEquals(source, runBlocking { database.linkDao().find(source.id) })
            save(controller)
            assertEquals(source.copy(refreshIntervalMinutes = 120),
                runBlocking { database.linkDao().find(source.id) })
        } finally {
            controller?.pause()?.stop()?.destroy()
        }
    }

    @Test
    fun clearingOverrideSavesGlobalFallbackOfflineAndLeavesRefreshMetadataUntouched() {
        val source = storedSource(45)
        val controller = open(source)
        try {
            interval().performScrollTo().performTextReplacement("")
            save(controller)
            assertEquals(source.copy(refreshIntervalMinutes = null),
                runBlocking { database.linkDao().find(source.id) })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun malformedOverflowAndBelowMinimumIntervalsCannotSaveButFifteenMinutesCan() {
        val source = storedSource(60)
        val controller = open(source)
        try {
            val state = ViewModelProvider(controller.get())[LinksManagerViewModel::class.java]
            for (value in listOf("abc", "2147483648", "9999999999999999999999", "15.5", "-1", "0", "14")) {
                interval().performScrollTo().performTextReplacement(value)
                compose.onNodeWithText(app.getString(R.string.updateSource)).performScrollTo().performClick()
                compose.waitForIdle()
                assertFalse("value=$value", state.busy)
                assertFalse("value=$value", controller.get().isFinishing)
                assertEquals("value=$value", source, runBlocking { database.linkDao().find(source.id) })
                interval().assertTextContains(value)
            }
            interval().performScrollTo().performTextReplacement("15")
            save(controller)
            assertEquals(15, runBlocking { database.linkDao().find(source.id) }!!.refreshIntervalMinutes)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun convertingToManualHidesAndClearsSubscriptionOnlyInterval() {
        val source = storedSource(30)
        val controller = open(source)
        try {
            compose.onNodeWithText(app.getString(R.string.sourceKindManual)).performScrollTo().performClick()
            interval().assertDoesNotExist()
            save(controller)
            val manual = runBlocking { database.linkDao().find(source.id) }!!
            assertEquals("", manual.address)
            assertNull(manual.refreshIntervalMinutes)
            assertNull(manual.subscriptionMetadata)
            assertEquals(source.id, manual.id)
            assertEquals(1, runBlocking { database.profileDao().linkProfiles(source.id).size })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun interval() = compose.onNodeWithTag("source:refresh-interval")

    private fun open(source: Link): ActivityController<LinksManagerActivity> =
        Robolectric.buildActivity(LinksManagerActivity::class.java,
            LinksManagerActivity.openLink(app, source)).setup().visible().also {
            compose.waitForIdle()
        }

    private fun save(controller: ActivityController<LinksManagerActivity>) {
        compose.onNodeWithText(app.getString(R.string.updateSource)).performScrollTo().performClick()
        compose.waitForAsyncState(10_000) { controller.get().isFinishing }
    }

    private fun storedSource(interval: Int): Link = runBlocking {
        val source = Link(
            name = "Stored subscription",
            address = "https://127.0.0.1:1/offline-subscription",
            type = Link.Type.Subscription,
            userAgent = "fixture-agent",
            xHwid = "fixture-hwid",
            customHeaders = "X-Fixture: kept",
            subscriptionMetadata = SubscriptionMetadata(updateIntervalMinutes = 240).toJsonString(),
            lastRefreshedAt = 123_456L,
            refreshIntervalMinutes = interval,
        )
        source.id = database.linkDao().insertAtEnd(source)
        database.profileDao().create(Profile(linkId = source.id, name = "Retained profile",
            config = """{"outbounds":[{"protocol":"freedom"}]}"""))
        source
    }
}
