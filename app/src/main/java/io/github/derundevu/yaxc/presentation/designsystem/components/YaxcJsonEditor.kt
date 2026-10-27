package io.github.derundevu.yaxc.presentation.designsystem.components

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.NoCopySpan
import android.text.Spannable
import android.text.Spanned
import android.text.SpanWatcher
import android.text.TextWatcher
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Redo
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Code
import androidx.compose.material.icons.outlined.DoneAll
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.blacksquircle.ui.editorkit.model.SyntaxHighlightSpan
import com.blacksquircle.ui.editorkit.plugin.autoindent.autoIndentation
import com.blacksquircle.ui.editorkit.plugin.base.PluginSupplier
import com.blacksquircle.ui.editorkit.plugin.delimiters.highlightDelimiters
import com.blacksquircle.ui.editorkit.plugin.linenumbers.LineNumbersPlugin
import com.blacksquircle.ui.editorkit.widget.TextProcessor
import com.blacksquircle.ui.editorkit.widget.internal.UndoRedoEditText
import com.blacksquircle.ui.language.json.JsonLanguage
import io.github.derundevu.yaxc.R
import io.github.derundevu.yaxc.presentation.designsystem.yaxcIsLightTheme
import io.github.derundevu.yaxc.presentation.editor.JsonDiagnostic
import io.github.derundevu.yaxc.presentation.editor.JsonEditorState
import io.github.derundevu.yaxc.presentation.editor.JsonEditorTools
import io.github.derundevu.yaxc.presentation.editor.JsonSyntaxException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun YaxcJsonEditor(state: JsonEditorState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val editor = remember(context, state) { createEditor(context, state) }
    var canUndo by remember(editor) { mutableStateOf(editor.canUndo()) }
    var canRedo by remember(editor) { mutableStateOf(editor.canRedo()) }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var replacement by rememberSaveable { mutableStateOf("") }
    var matchCase by rememberSaveable { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var matches by remember(state) { mutableStateOf<List<IntRange>>(emptyList()) }
    var matchedText by remember(state) { mutableStateOf<String?>(null) }
    var matchIndex by remember(state) { mutableStateOf(-1) }
    var diagnostic by remember(state) { mutableStateOf<JsonDiagnostic?>(null) }
    var checkedText by remember(state) { mutableStateOf<String?>(null) }
    var busy by remember(state) { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val light = yaxcIsLightTheme()
    val palette = remember(editor, colors, light) {
        editor.colorScheme.copy(
            textColor = colors.onSurface.toArgb(),
            cursorColor = colors.primary.toArgb(),
            backgroundColor = colors.surface.toArgb(),
            gutterColor = colors.surface.toArgb(),
            gutterDividerColor = colors.outlineVariant.toArgb(),
            gutterCurrentLineNumberColor = colors.primary.toArgb(),
            gutterTextColor = colors.onSurfaceVariant.copy(alpha = 0.65f).toArgb(),
            selectedLineColor = colors.primary.copy(alpha = 0.06f).toArgb(),
            selectionColor = colors.primary.copy(alpha = 0.28f).toArgb(),
            delimiterBackgroundColor = colors.primary.copy(alpha = 0.20f).toArgb(),
            numberColor = (if (light) Color(0xFF7950A3) else Color(0xFFC4A7E7)).toArgb(),
            operatorColor = colors.onSurfaceVariant.toArgb(),
            keywordColor = (if (light) Color(0xFF9B472B) else Color(0xFFF6B887)).toArgb(),
            langConstColor = (if (light) Color(0xFF9B472B) else Color(0xFFF6B887)).toArgb(),
            stringColor = (if (light) Color(0xFF236A4D) else Color(0xFF9ED3AE)).toArgb(),
        )
    }

    DisposableEffect(editor) {
        editor.onUndoRedoChangedListener = object : UndoRedoEditText.OnUndoRedoChangedListener {
            override fun onUndoRedoChanged() {
                canUndo = editor.canUndo()
                canRedo = editor.canRedo()
            }
        }
        onDispose {
            state.undoStack = editor.undoStack
            state.redoStack = editor.redoStack
            editor.document = null
            editor.onUndoRedoChangedListener = null
        }
    }

    LaunchedEffect(state, state.text) {
        checkedText = null
        diagnostic = null
        val snapshot = state.text
        delay(350)
        val result = withContext(Dispatchers.Default) { JsonEditorTools.validate(snapshot, state.root) }
        if (state.text === snapshot) {
            diagnostic = result
            checkedText = snapshot
        }
    }

    LaunchedEffect(state, state.text, query, matchCase, searchOpen) {
        matchedText = null
        if (!searchOpen || query.isEmpty()) {
            matches = emptyList()
            matchIndex = -1
            return@LaunchedEffect
        }
        val snapshot = state.text
        val needle = query
        delay(120)
        val found = withContext(Dispatchers.Default) {
            buildList {
                var from = 0
                while (from <= snapshot.length - needle.length) {
                    ensureActive()
                    val start = snapshot.indexOf(needle, from, ignoreCase = !matchCase)
                    if (start < 0) break
                    add(start until start + needle.length)
                    from = start + needle.length
                }
            }
        }
        matches = found
        matchedText = snapshot
        matchIndex = found.indexOfFirst { it.first >= state.selectionStart }.let {
            if (it < 0 && found.isNotEmpty()) 0 else it
        }
        if (!editor.hasFocus() && matchIndex in found.indices) {
            val match = found[matchIndex]
            editor.setSelection(match.first, match.last + 1)
            editor.bringPointIntoView(match.last + 1)
        }
    }

    fun selectMatch(index: Int) {
        if (matchedText !== state.text || matches.isEmpty()) return
        matchIndex = (index + matches.size) % matches.size
        val match = matches[matchIndex]
        editor.setSelection(match.first, match.last + 1)
        editor.bringPointIntoView(match.last + 1)
    }

    fun formatJson() {
        if (busy) return
        scope.launch {
            busy = true
            val snapshot = state.text
            try {
                val result = withContext(Dispatchers.Default) {
                    JsonEditorTools.format(snapshot, state.root)
                }
                if (state.text === snapshot) {
                    if (result != snapshot) {
                        val caret = editor.selectionStart.coerceAtLeast(0).coerceAtMost(result.length)
                        editor.text?.replace(0, editor.length(), result)
                        editor.setSelection(caret)
                    }
                    diagnostic = null
                    checkedText = state.text
                }
            } catch (error: JsonSyntaxException) {
                if (state.text === snapshot) {
                    diagnostic = error.diagnostic
                    checkedText = snapshot
                    editor.setSelection(error.diagnostic.offset.coerceIn(0, editor.length()))
                    editor.bringPointIntoView(editor.selectionStart)
                }
            } finally {
                busy = false
            }
        }
    }

    Surface(modifier = modifier.fillMaxSize(), color = colors.surface, contentColor = colors.onSurface) {
        Column(modifier = Modifier.fillMaxSize().imePadding().navigationBarsPadding()) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                IconButton(onClick = { editor.undo() }, enabled = canUndo) {
                    Icon(Icons.AutoMirrored.Outlined.Undo, stringResource(R.string.editorUndo))
                }
                IconButton(onClick = { editor.redo() }, enabled = canRedo) {
                    Icon(Icons.AutoMirrored.Outlined.Redo, stringResource(R.string.editorRedo))
                }
                IconButton(onClick = { searchOpen = !searchOpen }) {
                    Icon(Icons.Outlined.Search, stringResource(R.string.editorFindReplace))
                }
                IconButton(onClick = { formatJson() }, enabled = !busy) {
                    Icon(Icons.Outlined.Code, stringResource(R.string.editorFormat))
                }
                Column {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Outlined.MoreVert, stringResource(R.string.editorOptions))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editorWrap, if (state.wordWrap) "✓" else "")) },
                            onClick = { state.wordWrap = !state.wordWrap; menuOpen = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editorSmallerText)) },
                            enabled = state.fontSizeSp > 10f,
                            onClick = { state.fontSizeSp = (state.fontSizeSp - 1f).coerceAtLeast(10f) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.editorLargerText)) },
                            enabled = state.fontSizeSp < 24f,
                            onClick = { state.fontSizeSp = (state.fontSizeSp + 1f).coerceAtMost(24f) },
                        )
                        if (searchOpen) DropdownMenuItem(
                            text = { Text(stringResource(R.string.editorMatchCase, if (matchCase) "✓" else "")) },
                            onClick = { matchCase = !matchCase; menuOpen = false },
                        )
                    }
                }
            }
            if (searchOpen) {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(R.string.editorFind)) },
                        suffix = { Text("${if (matches.isEmpty()) 0 else matchIndex + 1}/${matches.size}") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { selectMatch(matchIndex - 1) }, enabled = matches.isNotEmpty()) {
                        Icon(Icons.Outlined.KeyboardArrowUp, stringResource(R.string.editorPrevious))
                    }
                    IconButton(onClick = { selectMatch(matchIndex + 1) }, enabled = matches.isNotEmpty()) {
                        Icon(Icons.Outlined.KeyboardArrowDown, stringResource(R.string.editorNext))
                    }
                    IconButton(onClick = { searchOpen = false }) {
                        Icon(Icons.Outlined.Close, stringResource(R.string.editorCloseSearch))
                    }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                    OutlinedTextField(
                        value = replacement,
                        onValueChange = { replacement = it },
                        label = { Text(stringResource(R.string.editorReplacement)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    val currentResults = matchedText === state.text && matchIndex in matches.indices
                    IconButton(onClick = {
                        val match = matches[matchIndex]
                        editor.text?.replace(match.first, match.last + 1, replacement)
                        editor.setSelection((match.first + replacement.length).coerceAtMost(editor.length()))
                    }, enabled = currentResults) {
                        Icon(Icons.Outlined.SwapHoriz, stringResource(R.string.editorReplace))
                    }
                    IconButton(onClick = {
                        val snapshot = state.text
                        val ranges = matches
                        val value = replacement
                        scope.launch {
                            val result = withContext(Dispatchers.Default) {
                                StringBuilder(snapshot).apply {
                                    for (match in ranges.asReversed()) {
                                        ensureActive()
                                        replace(match.first, match.last + 1, value)
                                    }
                                }.toString()
                            }
                            if (state.text === snapshot) editor.text?.replace(0, editor.length(), result)
                        }
                    }, enabled = currentResults) {
                        Icon(Icons.Outlined.DoneAll, stringResource(R.string.editorReplaceAll))
                    }
                }
            }
            HorizontalDivider()
            key(editor) {
                AndroidView(factory = { editor },
                modifier = Modifier.fillMaxWidth().weight(1f).clipToBounds(),
                update = { view ->
                    if (view.colorScheme !== palette) {
                        view.colorScheme = palette
                        // EditorKit updates chrome colors, but syntax spans need a language refresh.
                        view.language = view.language
                    }
                    if (view.configuredFontSize != state.fontSizeSp) {
                        view.updateFontSize(state.fontSizeSp)
                    }
                    if (view.isHorizontallyScrollableCompat() == state.wordWrap) {
                        view.setHorizontallyScrolling(!state.wordWrap)
                    }
                    if (view.syncedText !== state.text) view.loadDocument(state)
                },)
            }
            HorizontalDivider()
            val caret = state.selectionStart.coerceIn(0, state.text.length)
            val line = editor.structure.getLineForIndex(caret)
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp)) {
                Text(
                    stringResource(R.string.editorPosition, line + 1, caret - editor.structure.getIndexForStartOfLine(line) + 1),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                )
                val error = diagnostic
                Text(
                    text = when {
                        checkedText !== state.text -> stringResource(R.string.editorChecking)
                        error == null -> stringResource(R.string.editorValid)
                        else -> stringResource(R.string.editorError, error.line, error.column, error.message)
                    },
                    modifier = Modifier.weight(1f).padding(start = 12.dp).clickable(enabled = error != null) {
                        error?.let {
                            editor.requestFocus()
                            editor.setSelection(it.offset.coerceIn(0, editor.length()))
                            editor.bringPointIntoView(editor.selectionStart)
                        }
                    },
                    color = if (error != null) colors.error else colors.onSurfaceVariant,
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (busy) CircularProgressIndicator(modifier = Modifier.sizeIn(maxWidth = 16.dp, maxHeight = 16.dp))
            }
            Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                val indentLabel = stringResource(R.string.editorIndent)
                val outdentLabel = stringResource(R.string.editorOutdent)
                TextButton(
                    onClick = { indentSelection(editor, false) },
                    modifier = Modifier.width(40.dp).semantics { contentDescription = indentLabel },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("→|", maxLines = 1) }
                TextButton(
                    onClick = { indentSelection(editor, true) },
                    modifier = Modifier.width(40.dp).semantics { contentDescription = outdentLabel },
                    contentPadding = PaddingValues(0.dp),
                ) { Text("|←", maxLines = 1) }
                for (symbol in listOf("{", "}", "[", "]", "\"", ":", ",")) {
                    TextButton(onClick = {
                        editor.requestFocus()
                        val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
                        val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
                        editor.text?.replace(start, end, symbol)
                    }, modifier = Modifier.width(40.dp), contentPadding = PaddingValues(0.dp)) { Text(symbol) }
                }
            }
        }
    }
}

private class JsonTextProcessor(context: Context) : TextProcessor(context) {
    var document: JsonEditorState? = null
    var syncedText: String? = null
    var synchronizing = false
    var configuredFontSize = 0f
    val gutterPlugin = LineNumbersPlugin().apply {
        lineNumbers = true
        highlightCurrentLine = true
    }

    fun updateFontSize(sp: Float) {
        setTextSize(sp)
        // EditorKit skips plugin notifications after TextView invalidates its layout.
        gutterPlugin.setTextSize(sp)
        configuredFontSize = sp
    }

    // EditorKit's syntax spans lack UpdateAppearance, so Android otherwise caches old glyph colors.
    private val syntaxRedrawWatcher = object : SpanWatcher, NoCopySpan {
        private fun changed(span: Any?) {
            if (span is SyntaxHighlightSpan && !isDirty) invalidate()
        }

        override fun onSpanAdded(text: Spannable?, what: Any?, start: Int, end: Int) = changed(what)
        override fun onSpanRemoved(text: Spannable?, what: Any?, start: Int, end: Int) = changed(what)
        override fun onSpanChanged(
            text: Spannable?, what: Any?, oldStart: Int, oldEnd: Int, newStart: Int, newEnd: Int,
        ) = changed(what)
    }

    override fun onSelectionChanged(selStart: Int, selEnd: Int) {
        super.onSelectionChanged(selStart, selEnd)
        if (!synchronizing) document?.let {
            it.selectionStart = selStart.coerceAtLeast(0)
            it.selectionEnd = selEnd.coerceAtLeast(0)
        }
    }

    override fun onScrollChanged(horiz: Int, vert: Int, oldHoriz: Int, oldVert: Int) {
        super.onScrollChanged(horiz, vert, oldHoriz, oldVert)
        if (!synchronizing) document?.let { it.scrollX = horiz; it.scrollY = vert }
    }

    fun loadDocument(state: JsonEditorState) {
        val start = state.selectionStart.coerceIn(0, state.text.length)
        val end = state.selectionEnd.coerceIn(0, state.text.length)
        val x = state.scrollX
        val y = state.scrollY
        synchronizing = true
        setTextContent(state.text)
        text?.setSpan(syntaxRedrawWatcher, 0, length(), Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        state.undoStack?.let { undoStack = it }
        state.redoStack?.let { redoStack = it }
        setSelection(start, end)
        syncedText = state.text
        synchronizing = false
        post { if (document === state) scrollTo(x, y) }
    }
}

private fun createEditor(context: Context, state: JsonEditorState): JsonTextProcessor {
    return JsonTextProcessor(context).apply {
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        isSaveEnabled = false
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_ACTION_NONE
        typeface = Typeface.MONOSPACE
        // Plugins read the initial text paint on attachment.
        setTextSize(state.fontSizeSp)
        configuredFontSize = state.fontSizeSp
        language = JsonLanguage()
        tabWidth = 4
        useSpacesInsteadOfTabs = true
        plugins(PluginSupplier.create {
            highlightDelimiters()
            autoIndentation { autoIndentLines = true; autoCloseBrackets = true; autoCloseQuotes = true }
        })
        installPlugin(gutterPlugin)
        val padding = (8 * resources.displayMetrics.density).toInt()
        setPadding(padding, padding, padding, padding)
        setLineSpacing(0f, 1.12f)
        isVerticalScrollBarEnabled = true
        isHorizontalScrollBarEnabled = true
        overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
        setHorizontallyScrolling(!state.wordWrap)
        loadDocument(state)
        document = state
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(text: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(text: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(text: Editable?) {
                if (!synchronizing && document === state) {
                    val value = text?.toString().orEmpty()
                    state.text = value
                    syncedText = state.text
                    state.selectionStart = selectionStart.coerceAtLeast(0)
                    state.selectionEnd = selectionEnd.coerceAtLeast(0)
                }
            }
        })
    }
}

private fun indentSelection(editor: JsonTextProcessor, outdent: Boolean) {
    editor.requestFocus()
    val text = editor.document?.text ?: return
    val start = minOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(0)
    val end = maxOf(editor.selectionStart, editor.selectionEnd).coerceAtLeast(start)
    if (!outdent && start == end) {
        val indent = editor.tab()
        editor.text?.replace(start, end, indent)
        editor.setSelection(start + indent.length)
        return
    }
    val firstLine = if (start == 0) 0 else text.lastIndexOf('\n', start - 1) + 1
    val blockEnd = if (start == end) text.indexOf('\n', end).let { if (it < 0) text.length else it } else end
    val block = text.substring(firstLine, blockEnd)
    val result = StringBuilder(block.length)
    var offset = 0
    var firstDelta = 0
    do {
        val lineEnd = block.indexOf('\n', offset).let { if (it < 0) block.length else it }
        var removed = 0
        if (outdent) {
            if (offset < lineEnd && block[offset] == '\t') removed = 1
            else while (removed < 4 && offset + removed < lineEnd && block[offset + removed] == ' ') removed++
        } else result.append("    ")
        if (offset == 0) firstDelta = if (outdent) -removed else 4
        result.append(block, offset + removed, lineEnd)
        if (lineEnd == block.length) break
        result.append('\n')
        offset = lineEnd + 1
    } while (offset < block.length)
    val value = result.toString()
    editor.text?.replace(firstLine, blockEnd, value)
    val newStart = (start + firstDelta).coerceAtLeast(firstLine)
    val newEnd = (end + value.length - block.length).coerceIn(firstLine, editor.length())
    editor.setSelection(newStart.coerceAtMost(editor.length()), newEnd.coerceAtLeast(newStart))
}
