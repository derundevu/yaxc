package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import io.github.derundevu.yaxc.presentation.designsystem.YaxcTheme
import io.github.derundevu.yaxc.presentation.designsystem.yaxcSoftFill

@Composable
fun YaxcLiquidSurface(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape? = null,
    contentPadding: PaddingValues? = null,
    surfaceTint: Color = Color.Unspecified,
    blurRadius: androidx.compose.ui.unit.Dp = 16.dp,
    lensRadius: androidx.compose.ui.unit.Dp = 22.dp,
    lensDistortion: androidx.compose.ui.unit.Dp = 44.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val resolvedShape = shape ?: YaxcTheme.shapes.extraLarge
    val resolvedContentPadding = contentPadding ?: YaxcTheme.paddings.card
    val resolvedSurfaceTint = if (surfaceTint == Color.Unspecified) {
        yaxcSoftFill(darkAlpha = 0.28f, lightAlpha = 0.82f)
    } else {
        surfaceTint
    }

    Column(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { resolvedShape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(lensRadius.toPx(), lensDistortion.toPx())
                },
                onDrawSurface = { drawRect(resolvedSurfaceTint) },
            )
            .padding(resolvedContentPadding),
        content = content,
    )
}
