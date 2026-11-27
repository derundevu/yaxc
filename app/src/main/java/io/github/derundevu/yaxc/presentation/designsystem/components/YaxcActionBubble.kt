package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftFill
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftOnSurface
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftStroke

@Composable
fun YaxcActionBubble(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    containerSize: Dp = 40.dp,
    iconSize: Dp = 20.dp,
    loading: Boolean = false,
    contentDescription: String? = null,
    emphasizePress: Boolean = false,
) {
    val shape = MaterialTheme.shapes.large
    val colors = MaterialTheme.colorScheme
    val interactions = if (emphasizePress) remember { MutableInteractionSource() } else null
    val pressed = interactions?.collectIsPressedAsState()?.value ?: false
    val clickable = if (interactions == null) {
        Modifier.yaxcClickable(shape = shape, onClick = onClick)
    } else {
        Modifier.yaxcTvFocus(shape).clip(shape).clickable(
            interactionSource = interactions,
            indication = LocalIndication.current,
            onClick = onClick,
        )
    }
    Surface(
        modifier = modifier.then(clickable),
        color = if (pressed) colors.primary else yaxcSoftFill(darkAlpha = 0.08f, lightAlpha = 0.84f),
        contentColor = colors.onSurface,
        shape = shape,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(
            width = 1.dp,
            color = if (pressed) colors.primary else yaxcSoftStroke(darkAlpha = 0.12f, lightAlpha = 0.92f),
        ),
    ) {
        Box(
            modifier = Modifier.size(containerSize),
            contentAlignment = Alignment.Center,
        ) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(iconSize),
                    strokeWidth = 2.dp,
                    color = yaxcSoftOnSurface(darkAlpha = 0.82f, lightAlpha = 0.8f),
                )
            } else {
                Icon(
                    imageVector = icon,
                    contentDescription = contentDescription,
                    tint = if (pressed) colors.onPrimary else yaxcSoftOnSurface(darkAlpha = 0.76f, lightAlpha = 0.74f),
                    modifier = Modifier.size(iconSize),
                )
            }
        }
    }
}
