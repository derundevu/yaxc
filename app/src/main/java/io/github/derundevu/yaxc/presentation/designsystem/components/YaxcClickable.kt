package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

fun Modifier.yaxcClickable(
    shape: Shape = RoundedCornerShape(20.dp),
    onClick: () -> Unit,
): Modifier = clip(shape).clickable(onClick = onClick)
