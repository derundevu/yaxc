package io.github.derundevu.yaxc.presentation.routing

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import io.github.derundevu.yaxc.presentation.designsystem.components.isYaxcTv
import io.github.derundevu.yaxc.presentation.designsystem.components.yaxcTvFocus

/** A tall adjacent editor can push the focused row out of the lazy viewport after a swap. */
internal inline fun moveRoutingItem(
    tvListState: LazyListState?,
    key: String,
    indexDelta: Int,
    move: () -> Unit,
) {
    val anchor = tvListState?.layoutInfo?.visibleItemsInfo?.firstOrNull { it.key == key }
    move()
    if (anchor != null) tvListState.requestScrollToItem(anchor.index + indexDelta, -anchor.offset)
}

@Composable
internal fun RoutingMoveButtons(
    index: Int,
    total: Int,
    enabled: Boolean,
    moveUpLabel: String,
    moveDownLabel: String,
    onMove: (Int) -> Unit,
) {
    val canMoveUp = enabled && index > 0
    val canMoveDown = enabled && index in 0 until total - 1
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    val colors = IconButtonDefaults.iconButtonColors(
        contentColor = foreground,
        disabledContentColor = foreground.copy(alpha = 0.38f),
    )
    val trackFocus = isYaxcTv() && total > 1
    val upFocus = if (trackFocus) remember { FocusRequester() } else null
    val downFocus = if (trackFocus) remember { FocusRequester() } else null
    val focusedDirection = if (trackFocus) remember { mutableIntStateOf(0) } else null
    val edgeDestination = if (trackFocus) rememberSaveable { mutableIntStateOf(-1) } else null
    if (edgeDestination != null && index == edgeDestination.intValue) {
        // At the edge the pressed arrow becomes disabled; keep focus in the row.
        LaunchedEffect(index) {
            edgeDestination.intValue = -1
            if (index == 0 && canMoveDown) downFocus?.requestFocus()
            else if (index == total - 1 && canMoveUp) upFocus?.requestFocus()
        }
    }

    fun Modifier.trackArrow(requester: FocusRequester?, direction: Int): Modifier {
        if (requester == null || focusedDirection == null) return this
        return focusRequester(requester).onFocusChanged {
            if (it.isFocused) focusedDirection.intValue = direction
            else if (focusedDirection.intValue == direction) focusedDirection.intValue = 0
        }
    }

    IconButton(
        onClick = {
            if (focusedDirection?.intValue == -1 && index == 1) edgeDestination?.intValue = 0
            onMove(index - 1)
        },
        enabled = canMoveUp,
        colors = colors,
        modifier = Modifier.trackArrow(upFocus, -1).yaxcTvFocus(CircleShape),
    ) {
        Icon(Icons.Outlined.ArrowUpward, moveUpLabel)
    }
    IconButton(
        onClick = {
            if (focusedDirection?.intValue == 1 && index == total - 2) edgeDestination?.intValue = total - 1
            onMove(index + 1)
        },
        enabled = canMoveDown,
        colors = colors,
        modifier = Modifier.trackArrow(downFocus, 1).yaxcTvFocus(CircleShape),
    ) {
        Icon(Icons.Outlined.ArrowDownward, moveDownLabel)
    }
}
