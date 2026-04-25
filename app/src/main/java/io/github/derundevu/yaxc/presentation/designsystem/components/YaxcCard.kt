package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme

@Composable
fun YaxcCard(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedContentPadding = contentPadding ?: YaxcTheme.paddings.card

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, YaxcTheme.extendedColors.cardBorder),
    ) {
        Column(
            modifier = Modifier.padding(resolvedContentPadding),
            content = content,
        )
    }
}
