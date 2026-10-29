package io.github.derundevu.yaxc.presentation.editor

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.activity.ConfigsActivity
import io.github.derundevu.yaxc.activity.ProfileActivity
import io.github.derundevu.yaxc.database.Config as DatabaseConfig
import io.github.derundevu.yaxc.database.Link
import io.github.derundevu.yaxc.database.Profile
import io.github.derundevu.yaxc.presentation.configs.ConfigSection
import io.github.derundevu.yaxc.testing.RegressionApplication
import io.github.derundevu.yaxc.testing.RoomTestSupport
import io.github.derundevu.yaxc.testing.waitForAsyncState
import io.github.derundevu.yaxc.viewmodel.ConfigsEditorViewModel
import io.github.derundevu.yaxc.viewmodel.ProfileEditorViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "en-rUS-w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class EditorActivityRecreationTest : RoomTestSupport() {
    override val useAndroidMainDispatcher = true
    @get:Rule val compose = createEmptyComposeRule()

    @Test
    fun profileActivityRecreationKeepsUnsavedDraftOwnershipCaretAndUndoWithoutWritingRoom() {
        val source = runBlocking { app.linkRepository.insertAndGetId(Link(name = "Synthetic manual")) }
        val id = runBlocking { app.profileRepository.create(Profile(name = "Stored", config = "{}", linkId = source)) }
        val before = runBlocking { app.profileRepository.find(id) }
        val controller = Robolectric.buildActivity(ProfileActivity::class.java, ProfileActivity.getIntent(app, id = id)).setup().visible()
        try {
            val draft = ViewModelProvider(controller.get())[ProfileEditorViewModel::class.java]
            compose.waitForAsyncState(10_000) { !draft.isLoading }
            val oldEditor = requireNotNull(findEditor(controller.get().window.decorView))
            val edited = """{"unknown":"unsaved","number":1e+007}"""
            compose.runOnIdle {
                oldEditor.text!!.replace(0, oldEditor.length(), edited)
                oldEditor.setSelection(2, 9)
                draft.name = "Unsaved name"
                draft.document.fontSizeSp = 18f
                draft.document.wordWrap = true
            }

            controller.recreate()
            compose.waitForIdle()

            val recreated = ViewModelProvider(controller.get())[ProfileEditorViewModel::class.java]
            assertSame(draft, recreated)
            val restoredEditor = requireNotNull(findEditor(controller.get().window.decorView))
            assertNotSame(oldEditor, restoredEditor)
            compose.runOnIdle {
                assertEquals(edited, restoredEditor.text.toString())
                assertEquals(2, restoredEditor.selectionStart)
                assertEquals(9, restoredEditor.selectionEnd)
                assertEquals(source, recreated.selectedSourceId)
                assertEquals("Unsaved name", recreated.name)
                assertEquals(18f, recreated.document.fontSizeSp, 0f)
                assertTrue(recreated.document.wordWrap)
                assertTrue(restoredEditor.canUndo())
                restoredEditor.undo()
            }
            compose.waitForAsyncState(10_000) { recreated.document.text == "{}" }
            compose.onNodeWithContentDescription(app.getString(R.string.profileDetails)).performClick()
            compose.onNodeWithText("Unsaved name").assertIsDisplayed()
            assertEquals(before, runBlocking { app.profileRepository.find(id) })
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun configsActivityRecreationKeepsSelectedSectionModeAndIndependentDraftThenSavesToRoom() {
        val controller = Robolectric.buildActivity(ConfigsActivity::class.java, Intent(app, ConfigsActivity::class.java)).setup().visible()
        try {
            val draft = ViewModelProvider(controller.get())[ConfigsEditorViewModel::class.java]
            compose.waitForAsyncState(10_000) { !draft.isLoading }
            val before = runBlocking { database.configDao().get() }
            compose.runOnIdle {
                requireNotNull(findEditor(controller.get().window.decorView)).text!!.replace(0,
                    requireNotNull(findEditor(controller.get().window.decorView)).length(), """{"loglevel":"debug"}""")
            }
            compose.onNodeWithText(app.getString(R.string.configSectionDns)).performClick()
            compose.waitForIdle()
            val dns = """{"servers":["127.0.0.1"],"unknown":true}"""
            compose.runOnIdle {
                val editor = requireNotNull(findEditor(controller.get().window.decorView))
                editor.text!!.replace(0, editor.length(), dns)
                editor.setSelection(4)
            }
            compose.onNodeWithText(app.getString(R.string.configModeDisable)).performClick()
            compose.onNodeWithText(app.getString(R.string.configModeReplace)).performClick()

            controller.recreate()
            compose.waitForIdle()

            val restored = ViewModelProvider(controller.get())[ConfigsEditorViewModel::class.java]
            assertSame(draft, restored)
            assertEquals(ConfigSection.Dns, restored.selectedSection)
            assertEquals(DatabaseConfig.Mode.Replace, restored.modes.getValue(ConfigSection.Dns))
            compose.runOnIdle {
                val editor = requireNotNull(findEditor(controller.get().window.decorView))
                assertEquals(dns, editor.text.toString())
                assertEquals(4, editor.selectionStart)
            }
            assertEquals(before, runBlocking { database.configDao().get() })
            compose.onNodeWithText(app.getString(R.string.configSectionLog)).performClick()
            compose.waitForIdle()
            compose.runOnIdle { assertEquals("""{"loglevel":"debug"}""", requireNotNull(findEditor(controller.get().window.decorView)).text.toString()) }
            compose.onNodeWithContentDescription(app.getString(R.string.save)).performClick()
            compose.waitForAsyncState(10_000) { controller.get().isFinishing }
            val stored = runBlocking { requireNotNull(database.configDao().get()) }
            assertEquals("""{"loglevel":"debug"}""", stored.log)
            assertEquals(dns, stored.dns)
            assertEquals(DatabaseConfig.Mode.Replace, stored.dnsMode)
            assertFalse(restored.isSaving)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun configsInvalidSaveKeepsActivityOpenAndWholeStoredRowUnchangedAndShowsOffendingSection() {
        val controller = Robolectric.buildActivity(ConfigsActivity::class.java, Intent(app, ConfigsActivity::class.java)).setup().visible()
        try {
            val draft = ViewModelProvider(controller.get())[ConfigsEditorViewModel::class.java]
            compose.waitForAsyncState(10_000) { !draft.isLoading }
            val before = runBlocking { database.configDao().get() }
            val broken = "[\n  {\"tag\": \"unfinished\",}\n]"
            compose.runOnIdle {
                draft.documents.getValue(ConfigSection.Log).text = """{"loglevel":"debug"}"""
                draft.documents.getValue(ConfigSection.Outbounds).text = broken
                draft.selectedSection = ConfigSection.Log
                draft.selectMode(DatabaseConfig.Mode.Replace)
            }

            compose.onNodeWithContentDescription(app.getString(R.string.save)).performClick()
            compose.waitForAsyncState(10_000) { draft.selectedSection == ConfigSection.Outbounds && !draft.isSaving }
            val diagnostic = requireNotNull(JsonEditorTools.validate(broken, JsonRoot.Array))
            val message = app.getString(R.string.editorError, diagnostic.line, diagnostic.column, diagnostic.message)
            compose.mainClock.advanceTimeBy(500)
            compose.waitForAsyncState(10_000) {
                compose.onAllNodesWithText(message, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
            }

            assertFalse(controller.get().isFinishing)
            assertEquals(before, runBlocking { database.configDao().get() })
            compose.runOnIdle {
                assertEquals(broken, requireNotNull(findEditor(controller.get().window.decorView)).text.toString())
            }
            compose.onNodeWithText(message).assertIsDisplayed()
        } finally {
            controller.pause().stop().destroy()
        }
    }

    private fun findEditor(view: View): TextProcessor? {
        if (view is TextProcessor) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findEditor(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
