package io.github.derundevu.yaxc.presentation.designsystem.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import io.github.derundevu.yaxc.presentation.designsystem.YaxcShapeDefaults

@Composable
fun Modifier.yaxcClickable(
    shape: Shape = YaxcShapeDefaults.medium,
    onClick: () -> Unit,
): Modifier = yaxcTvFocus(shape).clip(shape).clickable(onClick = onClick)

@Composable
internal fun isYaxcTv(): Boolean =
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION

/** Place before an existing focus target; this only observes focus and does not add a target. */
@Composable
internal fun Modifier.yaxcTvFocus(shape: Shape = YaxcShapeDefaults.medium): Modifier {
    if (!isYaxcTv()) return this

    var focused by remember { mutableStateOf(false) }
    return onFocusChanged { focused = it.isFocused }
        .then(
            if (focused) {
                Modifier
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f), shape)
                    .border(3.dp, MaterialTheme.colorScheme.primary, shape)
            } else {
                Modifier
            },
        )
}
