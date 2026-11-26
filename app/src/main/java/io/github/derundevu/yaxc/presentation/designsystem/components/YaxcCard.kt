package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

@Composable
fun YaxcCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    contentPadding: PaddingValues? = null,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedContentPadding = contentPadding ?: YaxcTheme.paddings.card
    val cardColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)
    val cardBorder = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder)
    val body: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(resolvedContentPadding),
            content = content,
        )
    }

    if (onClick != null) {
        Surface(
            onClick = onClick,
            modifier = modifier.fillMaxWidth(),
            enabled = enabled,
            shape = shape,
            color = cardColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = cardBorder,
            content = body,
        )
    } else {
        Surface(
            modifier = modifier.fillMaxWidth(),
            shape = shape,
            color = cardColor,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = cardBorder,
            content = body,
        )
    }
}
