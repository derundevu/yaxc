package io.github.derundevu.yaxc.presentation.editor

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.blacksquircle.ui.editorkit.model.UndoStack

enum class JsonRoot {
    Object,
    Array,
}

@Stable
class JsonEditorState(
    initialText: String = "",
    val root: JsonRoot = JsonRoot.Object,
) {
    private var textValue by mutableStateOf(initialText)
    private var selectionStartValue by mutableIntStateOf(0)
    private var selectionEndValue by mutableIntStateOf(0)
    private var scrollXValue by mutableIntStateOf(0)
    private var scrollYValue by mutableIntStateOf(0)

    var text: String
        get() = textValue
        set(value) = replaceText(value)

    var selectionStart: Int
        get() = selectionStartValue
        set(value) {
            selectionStartValue = value.coerceIn(0, textValue.length)
        }

    var selectionEnd: Int
        get() = selectionEndValue
        set(value) {
            selectionEndValue = value.coerceIn(0, textValue.length)
        }

    var scrollX: Int
        get() = scrollXValue
        set(value) {
            scrollXValue = value.coerceAtLeast(0)
        }

    var scrollY: Int
        get() = scrollYValue
        set(value) {
            scrollYValue = value.coerceAtLeast(0)
        }

    var fontSizeSp: Float by mutableFloatStateOf(14f)
    var wordWrap: Boolean by mutableStateOf(false)

    internal var undoStack: UndoStack? = null
    internal var redoStack: UndoStack? = null

    /** Keeps the viewport; the attached editor clamps scrolling against its current layout. */
    fun replaceText(value: String) {
        if (value == textValue) return
        textValue = value
        selectionStart = selectionStartValue
        selectionEnd = selectionEndValue
        undoStack = null
        redoStack = null
    }
}
