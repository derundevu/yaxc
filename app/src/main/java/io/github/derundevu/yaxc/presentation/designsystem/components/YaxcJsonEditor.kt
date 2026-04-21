package io.github.derundevu.yaxc.presentation.designsystem.components

import android.content.Context
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
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

@Composable
fun YaxcJsonEditorSurface(
    onEditorReady: (TextProcessor) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(18.dp)

    Box(
        modifier = modifier
            .clip(shape)
            .background(
                color = MaterialTheme.colorScheme.surfaceContainerLow.copy(alpha = 0.94f),
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = YaxcTheme.extendedColors.cardBorder.copy(alpha = 0.72f),
                shape = shape,
            ),
    ) {
        YaxcJsonEditor(
            context = context,
            onEditorReady = onEditorReady,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp),
        )
    }
}
