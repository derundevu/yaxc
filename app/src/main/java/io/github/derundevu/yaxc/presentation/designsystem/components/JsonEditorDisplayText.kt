package io.github.derundevu.yaxc.presentation.designsystem.components

import android.graphics.Rect
import android.text.Editable
import android.text.GetChars
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.method.TransformationMethod
import android.view.View

/** Keep native layout, spans and offsets, but give Canvas only the requested text run. */
internal object JsonEditorDisplayText : TransformationMethod {
    val editableFactory: Editable.Factory = object : Editable.Factory() {
        override fun newEditable(source: CharSequence): Editable = EditorBuffer(source)
    }

    fun snapshotAfterEdit(text: Editable?): String =
        if (text is EditorBuffer) text.snapshotAfterEdit() else text?.toString().orEmpty()

    override fun getTransformation(source: CharSequence, view: View): CharSequence =
        if (source is Spanned) DisplayText(source) else source

    override fun onFocusChanged(
        view: View, sourceText: CharSequence, focused: Boolean, direction: Int, previouslyFocusedRect: Rect?,
    ) = Unit

    // A vendor Canvas can still ask for toString() for each run. Reuse the immutable text while
    // characters are unchanged. Selection and styling only change spans, not the snapshot.
    private class EditorBuffer(source: CharSequence) : SpannableStringBuilder(source) {
        private var snapshot = source as? String
        private var replaceDepth = 0

        override fun replace(start: Int, end: Int, text: CharSequence, from: Int, to: Int): SpannableStringBuilder {
            snapshot = null
            replaceDepth++
            try {
                return super.replace(start, end, text, from, to)
            } finally {
                replaceDepth--
            }
        }

        override fun toString(): String {
            // TextWatchers may run both before and after the underlying mutation.
            if (replaceDepth != 0) return super.toString()
            return snapshot ?: super.toString().also { snapshot = it }
        }

        fun snapshotAfterEdit(): String = super.toString().also { snapshot = it }
    }

    // Drawing a SpannableStringBuilder directly exposes its entire backing char array to Canvas.
    // Some Android renderers copy that array into a string for every styled run. This live facade
    // does not expose GraphicsOperations, so Canvas copies just the run context through GetChars.
    private class DisplayText(private val source: Spanned) : Spanned by source, GetChars {
        override fun getChars(start: Int, end: Int, dest: CharArray, destoff: Int) {
            TextUtils.getChars(source, start, end, dest, destoff)
        }

        override fun toString(): String = source.toString()
    }
}
