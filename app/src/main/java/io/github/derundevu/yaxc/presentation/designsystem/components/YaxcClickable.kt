package io.github.derundevu.yaxc.presentation.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import io.github.derundevu.yaxc.presentation.designsystem.YaxcShapeDefaults

fun Modifier.yaxcClickable(
    shape: Shape = YaxcShapeDefaults.medium,
    onClick: () -> Unit,
): Modifier = clip(shape).clickable(onClick = onClick)
