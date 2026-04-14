package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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

@Composable
fun YaxcLiquidSurface(
    backdrop: LayerBackdrop,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(32.dp),
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
    surfaceTint: Color = Color.White.copy(alpha = 0.28f),
    blurRadius: androidx.compose.ui.unit.Dp = 16.dp,
    lensRadius: androidx.compose.ui.unit.Dp = 22.dp,
    lensDistortion: androidx.compose.ui.unit.Dp = 44.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .drawBackdrop(
                backdrop = backdrop,
                shape = { shape },
                effects = {
                    vibrancy()
                    blur(blurRadius.toPx())
                    lens(lensRadius.toPx(), lensDistortion.toPx())
                },
                onDrawSurface = { drawRect(surfaceTint) },
            )
            .padding(contentPadding),
        content = content,
    )
}
