package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftFill
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftStroke

data class YaxcTabOption<T>(
    val value: T,
    val label: String,
    val testTag: String? = null,
)

@Composable
fun <T> YaxcTabRow(
    options: List<YaxcTabOption<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    nested: Boolean = false,
) {
    val selectedIndex = options.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val shape = MaterialTheme.shapes.large
    val horizontalTabPadding = if (options.size >= 4) 4.dp else 8.dp
    val containerColor = if (nested) {
        yaxcSoftFill(darkAlpha = 0.06f, lightAlpha = 0.55f)
    } else {
        MaterialTheme.colorScheme.surface.copy(alpha = 0.94f)
    }
    val borderColor = if (nested) {
        yaxcSoftStroke(darkAlpha = 0.12f, lightAlpha = 0.72f)
    } else {
        YaxcTheme.extendedColors.cardBorder
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = containerColor,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shape = shape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, borderColor),
    ) {
        TabRow(
            selectedTabIndex = selectedIndex,
            modifier = Modifier.clip(shape),
            containerColor = Color.Transparent,
        ) {
            options.forEach { option ->
                val isSelected = option.value == selected
                Tab(
                    selected = isSelected,
                    onClick = { onSelect(option.value) },
                    enabled = enabled,
                    modifier = (option.testTag?.let { Modifier.testTag(it) } ?: Modifier).yaxcTvFocus(),
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = YaxcTheme.extendedColors.textMuted,
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(horizontal = horizontalTabPadding, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = option.label,
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}
