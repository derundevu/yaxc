package io.github.derundevu.yaxc.activity

import XrayCore.XrayCore
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToKey
import androidx.lifecycle.ViewModelProvider
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.helper.AppUpdateManager
import io.github.derundevu.yaxc.helper.ConnectionFailure
import io.github.derundevu.yaxc.helper.ConnectionFailureState
import io.github.derundevu.yaxc.helper.GeodataFailure
import io.github.derundevu.yaxc.helper.GeodataProblem
import io.github.derundevu.yaxc.presentation.main.MainAction
import io.github.derundevu.yaxc.service.TProxyService
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import io.github.derundevu.yaxc.testing.waitForAsyncState
import io.github.derundevu.yaxc.viewmodel.MainViewModel
import kotlin.coroutines.Continuation
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(
    application = RegressionApplication::class,
    sdk = [34],
    qualifiers = "w420dp-h900dp-mdpi",
    shadows = [MainActivityVersionShadow::class, MainActivityUpdateShadow::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainActivityRegressionTest : RoomTestSupport() {
    override val useAndroidMainDispatcher = true
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var controller: ActivityController<MainActivity>
    private val activity get() = controller.get()
    private val model get() = ViewModelProvider(activity)[MainViewModel::class.java]

    @Before
    fun prepareActivityData() {
        ConnectionFailureState.clear()
        setRuntimeActive(false)
        settings.refreshLinksOnOpen = false
        settings.tun2socks = false
        settings.selectedLink = 71
        settings.selectedProfile = 711
        runBlocking {
            app.linkRepository.insert(Link(id = 71, name = "Synthetic source"))
            app.profileRepository.create(profile(711, 0, "First profile"))
            app.profileRepository.create(profile(722, 1, "Second profile"))
        }
    }

    @After
    fun closeActivity() {
        if (::controller.isInitialized) controller.pause().stop().destroy()
        setRuntimeActive(false)
        ConnectionFailureState.clear()
    }

    @Test
    fun selectingAnotherProfileWhileActivePersistsItsIdAndCommandsReloadWithoutStoppingRuntime() {
        setRuntimeActive(true)
        launch()
        broadcastRuntime(true)
        drainServiceCommands()

        compose.onNodeWithTag("main:profiles").performScrollToKey("profile:722")
        compose.onNodeWithTag("profile:722").performClick()
        await { model.uiState.value.selectedProfileId == 722L }
        val reload = drainServiceCommands().single()
        assertEquals(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME, reload.action)
        assertEquals(TProxyService::class.java.name, reload.component?.className)
        assertEquals(722L, settings.selectedProfile)
        assertTrue(TProxyService.isActive())
        assertTrue(model.uiState.value.isRunning)
        assertEquals("Second profile", model.uiState.value.selectedProfileName)

        compose.onNodeWithTag("profile:722").performClick()
        compose.waitForIdle()
        assertTrue("Reselecting the already selected profile must not issue another command", drainServiceCommands().isEmpty())

        setRuntimeActive(false)
        broadcastRuntime(false)
        compose.onNodeWithTag("main:profiles").performScrollToKey("profile:711")
        compose.onNodeWithTag("profile:711").performClick()
        await { model.uiState.value.selectedProfileId == 711L }
        assertEquals(711L, settings.selectedProfile)
        assertFalse(model.uiState.value.isRunning)
        assertTrue("Stopped profile selection only changes the persisted selection", drainServiceCommands().isEmpty())
    }

    @Test
    fun resourcesReturnRetryStartsStoppedRuntimeEvenWhenActivityStillHasOldRunningStatus() {
        setRuntimeActive(true)
        launch()
        broadcastRuntime(true)
        val failure = reportMissingFailure()
        drainServiceCommands()
        compose.onNodeWithText(app.getString(R.string.connectionResources)).performScrollTo().performClick()
        compose.waitForIdle()

        val resourcesIntent = shadowOf(activity).nextStartedActivity
        assertEquals(AssetsActivity::class.java.name, resourcesIntent.component?.className)
        assertEquals("geosite.dat", resourcesIntent.getStringExtra(AssetsActivity.EXTRA_FOCUS_ASSET))
        assertSame(failure, ConnectionFailureState.failure.value)
        assertTrue(drainServiceCommands().isEmpty())

        // MainActivity is not listening while Resources is foreground. Returning sends a status
        // request, but Retry can be pressed before the reply updates its retained ViewModel.
        controller.pause().stop()
        setRuntimeActive(false)
        controller.start().resume().visible()
        assertTrue(model.uiState.value.isRunning)
        drainServiceCommands()
        compose.onNodeWithText(app.getString(R.string.connectionRetry)).performClick()
        await { ConnectionFailureState.failure.value == null }
        assertEquals(TProxyService.START_VPN_SERVICE_ACTION_NAME, drainServiceCommands().single().action)
        assertEquals(711L, settings.selectedProfile)
    }

    @Test
    fun retryReloadsActuallyActiveRuntimeBeforeFirstStatusReplyRatherThanStartingItAgain() {
        setRuntimeActive(true)
        launch()
        assertFalse(model.uiState.value.isRunning)
        reportMissingFailure()
        drainServiceCommands()
        compose.onNodeWithText(app.getString(R.string.connectionRetry)).performClick()
        await { ConnectionFailureState.failure.value == null }
        assertEquals(TProxyService.NEW_CONFIG_SERVICE_ACTION_NAME, drainServiceCommands().single().action)
        assertTrue(TProxyService.isActive())
    }

    @Test
    fun activityRecreationKeepsFailureExpandedDiagnosticAndStableSelectionButStaleDismissCannotEraseNewFailure() {
        setRuntimeActive(true)
        launch()
        broadcastRuntime(true)
        val older = reportMissingFailure()
        compose.onNodeWithText(app.getString(R.string.connectionDiagnostic)).performScrollTo().performClick()
        compose.onNodeWithText(older.diagnostic).performScrollTo().assertIsDisplayed()
        setRuntimeActive(false)
        app.sendBroadcast(Intent(TProxyService.STOP_VPN_SERVICE_ACTION_NAME).setPackage(app.packageName))
        await { !model.uiState.value.isRunning }
        assertSame(older, ConnectionFailureState.failure.value)

        controller.recreate()
        await { model.uiState.value.connectionFailure === older && model.uiState.value.selectedProfileId == 711L }
        compose.onNodeWithText(older.diagnostic).performScrollTo().assertIsDisplayed()
        assertSame(older, ConnectionFailureState.failure.value)
        assertEquals(71L, model.uiState.value.selectedTabId)
        assertEquals(711L, settings.selectedProfile)

        val newer = ConnectionFailure(older.diagnostic, older.geodata)
        ConnectionFailureState.report(newer)
        await { model.uiState.value.connectionFailure === newer }
        compose.onNodeWithText(newer.diagnostic).assertDoesNotExist()
        model.onAction(MainAction.DismissConnectionFailure(older))
        compose.waitForIdle()
        assertSame(newer, ConnectionFailureState.failure.value)
        compose.onNodeWithText(app.getString(R.string.connectionDismiss)).assertIsDisplayed().performClick()
        await { ConnectionFailureState.failure.value == null && model.uiState.value.connectionFailure == null }
        assertNull(model.uiState.value.connectionFailure)
    }

    private fun launch() {
        controller = Robolectric.buildActivity(MainActivity::class.java).setup().visible()
        await { model.uiState.value.selectedProfileName == "First profile" && model.uiState.value.tabs.size == 1 }
    }

    private fun reportMissingFailure(): ConnectionFailure {
        val failure = ConnectionFailure(
            "synthetic geosite.dat missing diagnostic",
            GeodataFailure("geosite.dat", GeodataProblem.Missing),
        )
        ConnectionFailureState.report(failure)
        await { model.uiState.value.connectionFailure === failure }
        compose.onNodeWithText(app.getString(R.string.connectionFailedTitle)).assertIsDisplayed()
        return failure
    }

    private fun broadcastRuntime(active: Boolean) {
        app.sendBroadcast(
            Intent(TProxyService.STATUS_VPN_SERVICE_ACTION_NAME)
                .setPackage(app.packageName)
                .putExtra("isRunning", active)
        )
        await { model.uiState.value.isRunning == active }
    }

    private fun await(predicate: () -> Boolean) {
        compose.waitForAsyncState(10_000, predicate)
    }

    private fun drainServiceCommands(): List<Intent> = buildList {
        while (true) add(shadowOf(app).nextStartedService ?: break)
    }

    private fun setRuntimeActive(active: Boolean) {
        TProxyService::class.java.getDeclaredField("serviceRunning").apply { isAccessible = true }
            .setBoolean(null, active)
    }

    private fun profile(id: Long, index: Int, name: String) = Profile(
        id = id,
        linkId = 71,
        index = index,
        name = name,
        config = """{"outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":19081}]}}]}""",
    )
}

/** Only isolates the unavailable native version label; runtime commands remain real Android intents. */
@Implements(value = XrayCore::class, isInAndroidSdk = false)
class MainActivityVersionShadow {
    companion object {
        @JvmStatic @Implementation fun __staticInitializer__() = Unit
        @JvmStatic @Implementation fun version(): String = "synthetic-regression-core"
    }
}

/** Keeps an unrelated automatic release check completely offline, including on recreation. */
@Implements(value = AppUpdateManager::class, isInAndroidSdk = false)
class MainActivityUpdateShadow {
    @Implementation
    fun refresh(continuation: Continuation<Unit>): Any = Unit
}
