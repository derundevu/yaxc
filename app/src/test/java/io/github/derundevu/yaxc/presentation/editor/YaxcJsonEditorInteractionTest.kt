package io.github.derundevu.yaxc.presentation.editor

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.YaxcAppTheme
import io.github.derundevu.yaxc.presentation.designsystem.components.YaxcJsonEditor
import io.github.derundevu.yaxc.testing.RegressionApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = RegressionApplication::class, sdk = [34], qualifiers = "en-rUS-w420dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class YaxcJsonEditorInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun findAndReplaceAllAreLiteralCaseInsensitiveAndUndoRedoRestoreTheWholeDocument() {
        val original = """{"value":"A.b a.B axb A.B"}"""
        val state = JsonEditorState(original)
        show(state)
        click(R.string.editorFindReplace)
        compose.onNodeWithText(label(R.string.editorFind)).performTextInput("a.b")
        awaitText("1/3")
        compose.onNodeWithText(label(R.string.editorReplacement)).performTextInput("""$1\literal""")
        click(R.string.editorReplaceAll)
        val replaced = """{"value":"$1\literal $1\literal axb $1\literal"}"""
        awaitDocument(state, replaced)

        click(R.string.editorUndo)
        awaitDocument(state, original)
        click(R.string.editorRedo)
        awaitDocument(state, replaced)
    }

    @Test
    fun nextPreviousWrapAndMatchCaseChangesTheActualSelectedReplacementRange() {
        val state = JsonEditorState("""{"value":"Ab ab AB"}""")
        show(state)
        click(R.string.editorFindReplace)
        compose.onNodeWithText(label(R.string.editorFind)).performTextInput("ab")
        awaitText("1/3")
        click(R.string.editorPrevious)
        compose.runOnIdle { assertEquals(state.text.lastIndexOf("AB"), editor().selectionStart) }
        click(R.string.editorNext)
        compose.runOnIdle { assertEquals(state.text.indexOf("Ab"), editor().selectionStart) }
        click(R.string.editorOptions)
        compose.onNodeWithText(compose.activity.getString(R.string.editorMatchCase, "")).performClick()
        awaitText("1/1")
        compose.onNodeWithText(label(R.string.editorReplacement)).performTextInput("literal")
        click(R.string.editorReplace)

        awaitDocument(state, """{"value":"Ab literal AB"}""")
    }

    @Test
    fun formatInvalidDraftKeepsTextAndUndoHistoryAndMovesCaretToDiagnostic() {
        val initial = """{"value":[1]}"""
        val state = JsonEditorState(initial)
        show(state)
        val invalid = "{\n  \"value\": [1,]\n}"
        compose.runOnIdle { editor().text!!.replace(0, editor().length(), invalid) }
        awaitDocument(state, invalid)
        click(R.string.editorFormat)
        val diagnostic = requireNotNull(JsonEditorTools.validate(invalid, JsonRoot.Object))
        awaitText(compose.activity.getString(R.string.editorError, diagnostic.line, diagnostic.column, diagnostic.message))

        compose.runOnIdle {
            assertEquals(invalid, editor().text.toString())
            assertEquals(diagnostic.offset, editor().selectionStart)
            assertEquals(diagnostic.offset, state.selectionStart)
        }
        click(R.string.editorUndo)
        awaitDocument(state, initial)
    }

    @Test
    fun explicitFormattingIsAnUndoableEditAndRetainsDuplicateKeysAndNumberSpelling() {
        val initial = """{"unknown":1e+007,"unknown":-0,"nested":[true,null]}"""
        val state = JsonEditorState(initial)
        show(state)
        click(R.string.editorFormat)
        val formatted = JsonEditorTools.format(initial, JsonRoot.Object)
        awaitDocument(state, formatted)
        click(R.string.editorUndo)
        awaitDocument(state, initial)
        click(R.string.editorRedo)
        awaitDocument(state, formatted)
    }

    @Test
    fun sectionChangesRetainIndependentTextSelectionAndUndoRedoStacks() {
        val first = JsonEditorState("""{"first":1}""")
        val second = JsonEditorState("[]", JsonRoot.Array)
        var selected by mutableStateOf(first)
        compose.setContent { YaxcAppTheme { YaxcJsonEditor(selected) } }
        compose.runOnIdle {
            editor().text!!.replace(0, editor().length(), """{"first":2}""")
            editor().setSelection(3, 7)
        }
        compose.runOnIdle { selected = second }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("[]", editor().text.toString())
            assertFalse(editor().canUndo())
            editor().text!!.replace(0, editor().length(), "[true]")
            editor().setSelection(2)
        }
        compose.runOnIdle { selected = first }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("""{"first":2}""", editor().text.toString())
            assertEquals(3, editor().selectionStart)
            assertEquals(7, editor().selectionEnd)
        }
        click(R.string.editorUndo)
        awaitDocument(first, """{"first":1}""")
        compose.runOnIdle { selected = second }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals("[true]", editor().text.toString())
            assertEquals(2, editor().selectionStart)
        }
        click(R.string.editorUndo)
        awaitDocument(second, "[]")
        compose.runOnIdle { selected = first }
        compose.waitForIdle()
        click(R.string.editorRedo)
        awaitDocument(first, """{"first":2}""")
    }

    @Test
    fun shortenedExternalDocumentClampsSelectionAndNegativeViewportAndDoesNotRetainOldUndoHistory() {
        val state = JsonEditorState("""{"long":"original document"}""")
        show(state)
        compose.runOnIdle {
            editor().text!!.append(" ")
            editor().setSelection(editor().length() - 1, editor().length())
            state.scrollX = -500
            state.scrollY = -700
            state.replaceText("{}")
        }
        compose.waitForIdle()

        compose.runOnIdle {
            assertEquals("{}", editor().text.toString())
            assertEquals(2, editor().selectionStart)
            assertEquals(2, editor().selectionEnd)
            assertEquals(2, state.selectionStart)
            assertEquals(2, state.selectionEnd)
            assertEquals(0, state.scrollX)
            assertEquals(0, state.scrollY)
            assertTrue(editor().scrollX >= 0)
            assertTrue(editor().scrollY >= 0)
            assertFalse(editor().canUndo())
            assertFalse(editor().canRedo())
        }
    }

    @Test
    fun staleSearchResultsCannotReplaceAnExternallyChangedDocument() {
        val state = JsonEditorState("""{"value":"old old"}""")
        show(state)
        click(R.string.editorFindReplace)
        compose.onNodeWithText(label(R.string.editorFind)).performTextInput("old")
        awaitText("1/2")
        compose.onNodeWithText(label(R.string.editorReplacement)).performTextInput("replacement")
        compose.runOnIdle { state.replaceText("""{"value":"new"}""") }
        awaitText("0/0")
        compose.onNodeWithContentDescription(label(R.string.editorReplace)).assertIsNotEnabled()
        compose.onNodeWithContentDescription(label(R.string.editorReplaceAll)).assertIsNotEnabled()

        compose.runOnIdle {
            assertEquals("""{"value":"new"}""", editor().text.toString())
            assertEquals("""{"value":"new"}""", state.text)
        }
    }

    @Test
    fun restoredViewportIsClampedToActualLayoutAfterDocumentShrinks() {
        val state = JsonEditorState("{\n" + List(100) { "\"line$it\": \"$it\"" }.joinToString(",\n") + "\n}")
        state.scrollX = Int.MAX_VALUE
        state.scrollY = Int.MAX_VALUE
        show(state)
        compose.runOnIdle { state.replaceText("{}") }
        compose.waitForIdle()

        compose.runOnIdle {
            val view = editor()
            val layout = requireNotNull(view.layout)
            val maxX = (layout.width - view.width + view.paddingLeft + view.paddingRight).coerceAtLeast(0)
            assertTrue(view.scrollX in 0..maxX)
            assertEquals(0, view.scrollY)
            assertEquals(view.scrollX, state.scrollX)
            assertEquals(view.scrollY, state.scrollY)
        }
    }

    private fun show(state: JsonEditorState) {
        compose.setContent { YaxcAppTheme { YaxcJsonEditor(state) } }
        compose.waitForIdle()
    }

    private fun click(resource: Int) = compose.onNodeWithContentDescription(label(resource)).performClick()
    private fun label(resource: Int): String = compose.activity.getString(resource)

    private fun awaitText(text: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun awaitDocument(state: JsonEditorState, expected: String) {
        compose.waitUntil(10_000) { state.text == expected }
        compose.runOnIdle { assertEquals(expected, editor().text.toString()) }
    }

    private fun editor(): TextProcessor = requireNotNull(findEditor(compose.activity.window.decorView))

    private fun findEditor(view: View): TextProcessor? {
        if (view is TextProcessor) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findEditor(view.getChildAt(index))?.let { return it }
        }
        return null
    }
}
