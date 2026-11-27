package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcIsLightTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftStroke
import kotlin.math.ceil

/** An offscreen outline whose inner border can use the panel's existing clip. */
internal interface YaxcVectorBorderShape : Shape

@Composable
fun YaxcGlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    contentPadding: PaddingValues? = null,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    accentAlpha: Float = 0.10f,
    borderColor: Color = Color.Unspecified,
    shadowElevation: Dp = 12.dp,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedShape = shape ?: YaxcTheme.shapes.extraLarge
    val resolvedContentPadding = contentPadding ?: YaxcTheme.paddings.card
    val isLightTheme = yaxcIsLightTheme()
    val resolvedBorderColor = if (borderColor == Color.Unspecified) {
        yaxcSoftStroke(darkAlpha = 0.12f, lightAlpha = 0.72f)
    } else {
        borderColor
    }
    val vectorBorder = resolvedShape is YaxcVectorBorderShape
    // The vector surface clips all gradient layers once; avoid rebuilding its moving
    // outline on every color layer as the list scrolls.
    val gradientShape = if (vectorBorder) RectangleShape else resolvedShape
    val effectiveAccentAlpha = if (isLightTheme) accentAlpha * 0.24f else accentAlpha * 0.7f
    val baseGradient = if (isLightTheme) {
        listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.99f),
            MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
        )
    } else {
        listOf(
            MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
            MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.90f),
            MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.86f),
        )
    }
    val radialHighlight = if (isLightTheme) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.012f)
    } else {
        Color.White.copy(alpha = 0.05f)
    }
    val topHighlight = if (isLightTheme) {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.008f)
    } else {
        Color.White.copy(alpha = 0.02f)
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = resolvedShape,
        color = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        shadowElevation = shadowElevation,
        border = if (vectorBorder) null else BorderStroke(1.dp, resolvedBorderColor),
    ) {
        Box(
            modifier = (if (vectorBorder) Modifier.vectorInnerBorder(resolvedShape, resolvedBorderColor) else Modifier)
                .background(
                    brush = Brush.verticalGradient(baseGradient),
                    shape = gradientShape,
                )
        ) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                accentColor.copy(alpha = effectiveAccentAlpha),
                                Color.Transparent,
                            ),
                            radius = 820f,
                        ),
                        shape = gradientShape,
                    )
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                radialHighlight,
                                Color.Transparent,
                            ),
                            radius = 900f,
                        ),
                        shape = gradientShape,
                    )
            )
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(
                        brush = Brush.verticalGradient(
                            listOf(
                                topHighlight,
                                Color.Transparent,
                            )
                        ),
                        shape = gradientShape,
                    )
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (onClick != null) {
                            Modifier
                                .yaxcTvFocus(resolvedShape)
                                .clip(resolvedShape)
                                .clickable(enabled = enabled, onClick = onClick)
                        } else {
                            Modifier
                        },
                    )
                    .padding(resolvedContentPadding),
                content = content,
            )
        }
    }
}

private fun Modifier.vectorInnerBorder(shape: Shape, color: Color): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val width = ceil(1.dp.toPx()).coerceAtMost(ceil(size.minDimension / 2))
    val style = if (width * 2 > size.minDimension) Fill else Stroke(width * 2)
    onDrawWithContent {
        drawContent()
        if (width > 0f) drawOutline(outline, color, style = style)
    }
}
