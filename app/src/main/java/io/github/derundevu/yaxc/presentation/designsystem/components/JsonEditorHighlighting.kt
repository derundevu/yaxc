package io.github.derundevu.yaxc.presentation.designsystem.components

import android.text.NoCopySpan
import android.text.Spanned
import android.text.TextPaint
import android.text.style.CharacterStyle
import android.text.style.UpdateAppearance
import android.util.SparseArray
import android.widget.TextView
import com.blacksquircle.ui.editorkit.model.ColorScheme
import com.blacksquircle.ui.language.json.lexer.JsonLexer
import com.blacksquircle.ui.language.json.lexer.JsonToken
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.StringReader

internal class JsonEditorHighlighting(private val view: TextView) {
    internal enum class Style { String, Number, Constant, Operator, Comment }
    internal data class Token(val start: Int, val end: Int, val style: Style)
    internal class Result(val source: String, val tokens: List<Token>)

    private class SyntaxColor(private val color: Int) : CharacterStyle(), UpdateAppearance, NoCopySpan {
        override fun updateDrawState(paint: TextPaint) {
            paint.color = color
        }
    }

    private var palette: ColorScheme? = null
    private var tokens: List<Token> = emptyList()
    private var source: String? = null
    private var buffer: CharSequence? = null
    private val spans = SparseArray<SyntaxColor>()
    private var first = 0
    private var end = 0
    private var safeStart = 0
    private var safeEnd = 0

    fun setPalette(colors: ColorScheme) {
        palette = colors
        clearSpans()
        refresh()
    }

    fun setTokens(result: Result) {
        if (source === result.source) return
        clearSpans()
        source = result.source
        tokens = result.tokens
        refresh()
    }

    fun textChanged() {
        // The editable will move existing spans with the edit. Don't attach old token offsets.
        source = null
    }

    fun documentReplaced() {
        clearSpans()
        source = null
        tokens = emptyList()
    }

    fun refresh() {
        val colors = palette ?: return
        val current = view.editableText ?: return
        val layout = view.layout ?: return
        if (tokens.isEmpty() || current.length != source?.length) return
        if (buffer !== current) {
            clearSpans()
            buffer = current
        }
        val top = layout.getLineForVertical(view.scrollY)
        val bottom = layout.getLineForVertical(view.scrollY + view.height)
        val visibleStart = layout.getLineStart(top)
        val visibleEnd = layout.getLineEnd(bottom)
        if (visibleStart >= safeStart && visibleEnd <= safeEnd) return

        // Keep a runway on both sides. Pixel and horizontal scrolling inside it do not touch spans.
        val rows = bottom - top + 1
        val margin = rows.coerceAtLeast(8)
        val lastLine = layout.lineCount - 1
        val nextFirst = firstEndingAfter(layout.getLineStart((top - margin).coerceAtLeast(0)))
        val nextEnd = firstStartingAt(layout.getLineEnd((bottom + margin).coerceAtMost(lastLine)))
        safeStart = layout.getLineStart((top - margin / 2).coerceAtLeast(0))
        safeEnd = layout.getLineEnd((bottom + margin / 2).coerceAtMost(lastLine))

        for (index in first until minOf(end, nextFirst)) detach(index)
        for (index in maxOf(first, nextEnd) until end) detach(index)
        for (index in nextFirst until minOf(nextEnd, first)) attach(index, colors)
        for (index in maxOf(nextFirst, end) until nextEnd) attach(index, colors)
        first = nextFirst
        end = nextEnd
    }

    private fun detach(index: Int) {
        view.editableText.removeSpan(spans[index])
        spans.remove(index)
    }

    private fun attach(index: Int, colors: ColorScheme) {
        val token = tokens[index]
        val color = when (token.style) {
            Style.String -> colors.stringColor
            Style.Number -> colors.numberColor
            Style.Constant -> colors.langConstColor
            Style.Operator -> colors.operatorColor
            Style.Comment -> colors.commentColor
        }
        val span = SyntaxColor(color)
        view.editableText.setSpan(span, token.start, token.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        spans.put(index, span)
    }

    private fun clearSpans() {
        val current = view.editableText
        for (index in 0 until spans.size()) current?.removeSpan(spans.valueAt(index))
        spans.clear()
        buffer = null
        first = 0
        end = 0
        safeStart = 0
        safeEnd = 0
    }

    private fun firstEndingAfter(offset: Int): Int {
        var left = 0
        var right = tokens.size
        while (left < right) {
            val mid = (left + right) ushr 1
            if (tokens[mid].end <= offset) left = mid + 1 else right = mid
        }
        return left
    }

    private fun firstStartingAt(offset: Int): Int {
        var left = 0
        var right = tokens.size
        while (left < right) {
            val mid = (left + right) ushr 1
            if (tokens[mid].start < offset) left = mid + 1 else right = mid
        }
        return left
    }

    companion object {
        suspend fun tokenize(source: String): Result {
            val context = currentCoroutineContext()
            val lexer = JsonLexer(StringReader(source))
            return Result(source, buildList {
                var scanned = 0
                while (true) {
                    if (scanned++ % 256 == 0) context.ensureActive()
                    val style = when (lexer.advance()) {
                        JsonToken.EOF -> break
                        JsonToken.NUMBER -> Style.Number
                        JsonToken.TRUE, JsonToken.FALSE, JsonToken.NULL -> Style.Constant
                        JsonToken.LBRACE, JsonToken.RBRACE, JsonToken.LBRACK, JsonToken.RBRACK,
                        JsonToken.COMMA, JsonToken.COLON -> Style.Operator
                        JsonToken.DOUBLE_QUOTED_STRING, JsonToken.SINGLE_QUOTED_STRING -> Style.String
                        JsonToken.BLOCK_COMMENT, JsonToken.LINE_COMMENT -> Style.Comment
                        else -> continue
                    }
                    add(Token(lexer.tokenStart, lexer.tokenEnd, style))
                }
            })
        }
    }
}
