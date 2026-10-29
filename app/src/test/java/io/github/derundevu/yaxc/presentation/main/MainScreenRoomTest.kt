package io.github.derundevu.yaxc.presentation.main

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToKey
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.withTransaction
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.helper.ConnectionFailureState
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import io.github.derundevu.yaxc.testing.waitForAsyncState
import io.github.derundevu.yaxc.viewmodel.MainViewModel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MainScreenRoomTest : RoomTestSupport() {
    override val useAndroidMainDispatcher = true
    @get:Rule val compose = createComposeRule()
    private val store = ViewModelStore()
    private lateinit var model: MainViewModel
    private val modelGeneration = mutableIntStateOf(0)

    @After
    fun releaseViewModel() {
        store.clear()
        ConnectionFailureState.clear()
    }

    @Test
    fun thousandRowGroupScrollsToIndividualProfileAndSelectionSurvivesDatabaseEdits() {
        val lastId = 11_499L
        runBlocking {
            database.withTransaction {
                app.linkRepository.insert(Link(id = 101, position = 0, name = "Large source"))
                app.linkRepository.insert(Link(id = 202, position = 1, name = "Other source"))
                repeat(1_500) { index ->
                    database.profileDao().insert(profile(10_000L + index, 101, index, "Profile $index"))
                }
                database.profileDao().insert(profile(20_000, 202, 1_500, "Other profile"))
            }
        }
        settings.selectedLink = 101
        showMain()
        await { it.profilesCount == 1_500 }

        // The distant row must not be present until its own lazy key is requested. A whole-group
        // item would expose all 1,500 rows and would not implement ScrollToKey for this profile.
        compose.onNodeWithTag("profile:$lastId").assertDoesNotExist()
        compose.onNodeWithTag("main:profiles").performScrollToKey("profile:$lastId")
        compose.onNodeWithTag("profile:$lastId").assertIsDisplayed().performClick()
        await { it.selectedProfileId == lastId }
        compose.onNodeWithTag("profile:$lastId").assertIsSelected()
        assertEquals(lastId, settings.selectedProfile)

        runBlocking {
            database.withTransaction {
                val selected = app.profileRepository.find(lastId)
                app.profileRepository.update(selected.copy(name = "Renamed stable profile"))
                app.profileRepository.moveUp(0, selected.index, lastId)
                app.profileRepository.remove(app.profileRepository.find(10_000))
            }
        }
        await { state ->
            state.profilesCount == 1_499 && state.selectedProfileName == "Renamed stable profile"
        }
        compose.onNodeWithTag("main:profiles").performScrollToKey("profile:$lastId")
        compose.onNodeWithTag("profile:$lastId").assertIsSelected().assertIsDisplayed()
        assertEquals(101L, model.uiState.value.selectedSourceId)
        assertEquals(lastId, settings.selectedProfile)
        compose.onNodeWithTag("main:profiles").performScrollToKey("profile:10001")
        compose.onNodeWithTag("profile:10001").performClick()
        await { it.selectedProfileId == 10_001L }
        compose.onNodeWithTag("main:profiles").performScrollToKey("profile:$lastId")
        compose.onNodeWithTag("profile:$lastId").performClick()
        await { it.selectedProfileId == lastId }
        assertEquals(lastId, settings.selectedProfile)

        compose.onNodeWithTag("main:profiles").performScrollToKey("source:101:header")
        compose.onNodeWithTag("source:101").performClick()
        await { it.selectedTabId == 0L }
        compose.onNodeWithTag("profile:$lastId").assertDoesNotExist()
        compose.onNodeWithTag("main:profiles").performScrollToKey("source:202:header")
        compose.onNodeWithTag("source:202").performClick()
        await { it.selectedTabId == 202L && it.profilesCount == 1 }
        compose.onNodeWithTag("main:profiles").performScrollToKey("profile:20000")
        compose.onNodeWithTag("profile:20000").assertIsDisplayed().performClick()
        await { it.selectedProfileId == 20_000L && it.selectedSourceId == 202L }
        compose.onNodeWithTag("profile:20000").assertIsSelected()
    }

    @Test
    fun draggingSourceCommitsStableIdsAndLaterDatabaseOrderDoesNotChangeItsProfileIdentity() {
        runBlocking {
            listOf(31L, 82L, 203L).forEachIndexed { index, id ->
                app.linkRepository.insert(Link(id = id, position = index, name = "Source $id"))
                app.profileRepository.create(profile(id * 10, id, index, "Node $id"))
            }
        }
        showMain()
        await { it.tabs.size == 3 }
        val first = compose.onNodeWithTag("source:31:drag", useUnmergedTree = true)
        val third = compose.onNodeWithTag("source:203:drag", useUnmergedTree = true)
        val distance = third.fetchSemanticsNode().boundsInRoot.center.y -
            first.fetchSemanticsNode().boundsInRoot.center.y
        first.performTouchInput {
            down(center)
            advanceEventTime(700)
            moveBy(Offset(0f, distance + 32f * app.resources.displayMetrics.density), delayMillis = 300)
            up()
        }
        await { it.tabs.map(Link::id) == listOf(82L, 203L, 31L) }
        assertEquals(listOf(82L, 203L, 31L), runBlocking { app.linkRepository.activeLinks() }.map(Link::id))

        compose.onNodeWithTag("source:31").performClick()
        await { it.selectedTabId == 31L && it.filteredProfiles.singleOrNull()?.profile?.id == 310L }
        runBlocking {
            app.linkRepository.reorder(listOf(203, 31, 82))
            app.linkRepository.update(Link(id = 31, position = 1, name = "Renamed source"))
        }
        await { it.tabs.map(Link::id) == listOf(203L, 31L, 82L) }
        compose.onNodeWithTag("main:profiles").performScrollToKey("profile:310")
        compose.onNodeWithTag("profile:310").performClick()
        await { it.selectedProfileId == 310L && it.selectedSourceName == "Renamed source" }
        assertEquals(31L, settings.selectedLink)
        assertEquals(310L, settings.selectedProfile)
    }

    @Test
    fun deletingLastSourceClearsBothPersistedSelectionsAndRecreationCannotResurrectThem() {
        runBlocking {
            app.linkRepository.insert(Link(id = 901, name = "Only source"))
            app.profileRepository.create(profile(902, 901, 0, "Only profile"))
        }
        settings.selectedLink = 901
        settings.selectedProfile = 902
        showMain()
        await { it.selectedProfileName == "Only profile" && it.selectedTabId == 901L }

        runBlocking { app.linkRepository.delete(Link(id = 901)) }
        await { it.tabs.isEmpty() && it.filteredProfiles.isEmpty() && it.selectedProfileId == 0L }
        assertEquals(0L, settings.selectedLink)
        assertEquals(0L, settings.selectedProfile)
        compose.onNodeWithTag("profile:902").assertDoesNotExist()

        compose.runOnIdle {
            store.clear()
            model = newModel()
            modelGeneration.intValue++
        }
        await { it.tabs.isEmpty() && it.selectedTabId == 0L && it.selectedProfileId == 0L }
        assertEquals("", model.uiState.value.selectedProfileName)
    }

    @Test
    fun actualEmptyInitialDatabaseClearsObsoletePersistedSelection() {
        settings.selectedLink = 811
        settings.selectedProfile = 812
        showMain()
        compose.waitForAsyncState(10_000) { settings.selectedLink == 0L && settings.selectedProfile == 0L }
        assertEquals(0L, model.uiState.value.selectedTabId)
        assertEquals(0L, model.uiState.value.selectedProfileId)
    }

    private fun showMain() {
        ConnectionFailureState.clear()
        model = newModel()
        compose.setContent {
            modelGeneration.intValue
            val state by model.uiState.collectAsState()
            YaxcTheme { MainScreenTestContent(state, model::onAction) }
        }
    }

    private fun newModel() = ViewModelProvider(
        store,
        ViewModelProvider.AndroidViewModelFactory.getInstance(app),
    )[MainViewModel::class.java]

    private fun await(predicate: (MainUiState) -> Boolean) {
        compose.waitForAsyncState(10_000) { predicate(model.uiState.value) }
    }

    private fun profile(id: Long, sourceId: Long, index: Int, name: String) = Profile(
        id = id,
        linkId = sourceId,
        index = index,
        name = name,
        config = """{"outbounds":[{"protocol":"socks","settings":{"servers":[{"address":"127.0.0.1","port":19081}]},"streamSettings":{"network":"tcp"}}]}""",
    )
}
