package io.github.derundevu.yaxc.presentation.designsystem.components

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.blacksquircle.ui.editorkit.widget.TextProcessor

@Composable
fun YaxcJsonEditor(
    context: Context,
    onEditorReady: (TextProcessor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val editor = remember(context) {
        TextProcessor(context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    DisposableEffect(editor) {
        onEditorReady(editor)
        onDispose { }
    }

    AndroidView(
        factory = { editor },
        modifier = modifier.fillMaxSize(),
        update = { },
    )
}
