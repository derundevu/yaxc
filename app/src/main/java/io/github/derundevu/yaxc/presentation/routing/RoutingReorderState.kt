package io.github.derundevu.yaxc.presentation.routing

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stable-key, section-local preview. The editor's precedence changes only on a successful drop. */
internal class RoutingReorderState(
    private val listState: LazyListState,
    private val scope: CoroutineScope,
    private val hysteresisPx: Float,
    private val spacingPx: Float,
) {
    var activeKey by mutableStateOf<String?>(null)
        private set
    var previewOrder by mutableStateOf<List<String>?>(null)
        private set
    private var dragging by mutableStateOf(false)
    val isDragging: Boolean get() = dragging
    private var settlingStarted by mutableStateOf(false)
    private var draggedTop by mutableFloatStateOf(0f)
    private var startOrder = emptyList<String>()
    private var startOffsets = emptyMap<String, Float>()
    private var targetIndex = -1
    private var sectionStartIndex = 0
    private var currentSections = emptyList<List<String>>()
    private var startSections = emptyList<List<String>>()
    private var releaseJob: Job? = null
    private var compactLayoutJob: Job? = null
    private var pendingAnchorIndex: Int? = null
    private val settlingTop = Animatable(0f)
    private var press: Pair<MutableInteractionSource, PressInteraction.Press>? = null

    fun <T> ordered(items: List<T>, key: (T) -> String): List<T> {
        val order = previewOrder ?: return items
        if (activeKey !in order || items.none { key(it) == activeKey }) return items
        val byKey = items.associateByTo(LinkedHashMap(items.size), key)
        return buildList(items.size) {
            order.forEach { id -> byKey.remove(id)?.let(::add) }
            addAll(byKey.values)
        }
    }

    /** An external replacement, deletion, busy state or conflict must never commit a stale index. */
    @Composable
    fun synchronize(sections: List<List<String>>, enabled: Boolean) {
        SideEffect {
            currentSections = sections
            val key = activeKey ?: return@SideEffect
            val current = sections.firstOrNull { key in it }
            if (current == null) {
                abandon()
            } else if (dragging && (!enabled || current != startOrder || sections != startSections)) {
                finish(cancelled = true, currentKeys = current, anchorViewport = false)
            } else if (!dragging && (current == previewOrder || current != startOrder)) {
                previewOrder = null
            }
            pendingAnchorIndex?.let { index ->
                pendingAnchorIndex = null
                // Restore bodies and the final keyed order before forcing the anchored measure.
                // Requesting this in the pointer callback can measure the old compact contents.
                listState.requestScrollToItem(index, -draggedTop.roundToInt())
            }
        }
    }

    @Composable
    fun headerModifier(
        key: String,
        sectionKeys: List<String>,
        enabled: Boolean,
        interactionSource: MutableInteractionSource? = null,
        onMove: (Int, Int) -> Unit,
    ): Modifier {
        val currentKeys = rememberUpdatedState(sectionKeys)
        val currentEnabled = rememberUpdatedState(enabled)
        val currentMove = rememberUpdatedState(onMove)
        // Preview reorders and form edits must not restart the pointer-input coroutine mid-gesture.
        return Modifier.pointerInput(this, key) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    if (currentEnabled.value) start(key, currentKeys.value, interactionSource, position)
                },
                onDragCancel = {
                    if (activeKey == key) finish(cancelled = true, currentKeys = currentKeys.value)
                },
                onDragEnd = {
                    if (activeKey == key) finish(
                        cancelled = !currentEnabled.value,
                        currentKeys = currentKeys.value,
                        onMove = currentMove.value,
                    )
                },
                onDrag = { change, amount ->
                    if (dragging && activeKey == key) {
                        change.consume()
                        if (!currentEnabled.value || currentKeys.value != startOrder) {
                            finish(cancelled = true, currentKeys = currentKeys.value)
                        } else {
                            move(amount.y)
                        }
                    }
                },
            )
        }
    }

    private fun start(
        key: String,
        keys: List<String>,
        interactionSource: MutableInteractionSource?,
        position: Offset,
    ) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        val index = keys.indexOf(key).takeIf { it >= 0 } ?: return
        abandon()
        startOrder = keys
        startSections = currentSections
        startOffsets = listState.layoutInfo.visibleItemsInfo.mapNotNull { visible ->
            (visible.key as? String)?.takeIf { it in keys }?.let { it to visible.offset.toFloat() }
        }.toMap()
        sectionStartIndex = item.index - index
        targetIndex = index
        draggedTop = item.offset.toFloat()
        previewOrder = keys
        activeKey = key
        dragging = true
        if (interactionSource != null) {
            val interaction = PressInteraction.Press(position)
            interactionSource.tryEmit(interaction)
            press = interactionSource to interaction
        }
        // Compact editor bodies before measuring slots, just like Main's source-group headers.
        listState.requestScrollToItem(item.index, -item.offset)
        val initialTop = draggedTop
        compactLayoutJob = scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            if (!dragging || activeKey != key) return@launch
            val sizes = listState.layoutInfo.visibleItemsInfo.associate { it.key to it.size }
            val offsets = mutableMapOf(key to initialTop)
            var top = initialTop
            for (next in index + 1 until keys.size) {
                val previousSize = sizes[keys[next - 1]] ?: break
                if (keys[next] !in sizes) break
                top += previousSize + spacingPx
                offsets[keys[next]] = top
            }
            top = initialTop
            for (previous in index - 1 downTo 0) {
                val size = sizes[keys[previous]] ?: break
                top -= size + spacingPx
                offsets[keys[previous]] = top
            }
            startOffsets = offsets
            move(0f)
        }
    }

    private fun move(delta: Float) {
        val key = activeKey ?: return
        draggedTop += delta
        // Compare header/top positions, not card centers: an expanded card can pass a short one.
        // Frozen slots also avoid oscillation when animateItem moves a neighbour under the finger.
        val nearest = startOffsets.minByOrNull { abs(it.value - draggedTop) } ?: return
        val currentOffset = startOffsets[startOrder[targetIndex]] ?: return
        if (nearest.key != startOrder[targetIndex] &&
            abs(currentOffset - draggedTop) - abs(nearest.value - draggedTop) > hysteresisPx
        ) {
            targetIndex = startOrder.indexOf(nearest.key)
            previewOrder = startOrder.toMutableList().apply {
                remove(key)
                add(targetIndex, key)
            }
            keepViewport()
        }
    }

    private fun keepViewport() {
        // Suppress LazyColumn's first-visible-key anchoring when that key is itself being moved.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    private fun finish(
        cancelled: Boolean,
        currentKeys: List<String>,
        onMove: ((Int, Int) -> Unit)? = null,
        anchorViewport: Boolean = true,
    ) {
        if (!dragging) return
        val key = activeKey ?: return
        val commit = !cancelled && currentKeys == startOrder
        val finalOrder = if (commit) previewOrder ?: startOrder else currentKeys
        val from = currentKeys.indexOf(key)
        val to = finalOrder.indexOf(key)
        compactLayoutJob?.cancel()
        compactLayoutJob = null
        dragging = false
        settlingStarted = false
        releasePress(cancelled = !commit)
        previewOrder = if (commit && finalOrder != currentKeys) finalOrder else null
        if (to < 0) {
            abandon()
            return
        }
        // Apply the anchor after composition restores editor bodies, before their first measure.
        pendingAnchorIndex = if (anchorViewport && currentKeys == startOrder) sectionStartIndex + to else null
        if (commit && from >= 0 && from != to) onMove?.invoke(from, to)
        releaseJob = scope.launch {
            withFrameNanos { }
            withFrameNanos { }
            settlingTop.snapTo(draggedTop)
            settlingStarted = true
            // Animate an absolute viewport position, never a fraction of a changing lazy offset.
            // Restoring another expanded editor can remeasure after the drop; compensate that
            // layout change completely and retarget the same spring without a position reset.
            snapshotFlow {
                if (activeKey == key) listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.key == key }?.offset else null
            }.takeWhile { activeKey == key }.collectLatest { offset ->
                if (offset == null) {
                    abandon()
                    return@collectLatest
                }
                settlingTop.animateTo(offset.toFloat(), spring(dampingRatio = 0.86f, stiffness = 520f))
                activeKey = null
                previewOrder = null
                startOrder = emptyList()
                startOffsets = emptyMap()
            }
        }
    }

    private fun releasePress(cancelled: Boolean) {
        press?.let { (source, interaction) ->
            source.tryEmit(if (cancelled) PressInteraction.Cancel(interaction) else PressInteraction.Release(interaction))
        }
        press = null
    }

    private fun abandon() {
        compactLayoutJob?.cancel()
        compactLayoutJob = null
        releaseJob?.cancel()
        releaseJob = null
        pendingAnchorIndex = null
        releasePress(cancelled = true)
        activeKey = null
        previewOrder = null
        dragging = false
        startOrder = emptyList()
        startOffsets = emptyMap()
    }

    fun translation(key: String): Float {
        if (activeKey != key) return 0f
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return 0f
        return (if (dragging || !settlingStarted) draggedTop else settlingTop.value) - item.offset
    }
}

internal fun LazyItemScope.routingReorderItemModifier(reorder: RoutingReorderState, key: String): Modifier {
    val active = reorder.activeKey == key
    val placement = if (active) Modifier else Modifier.animateItem(
        fadeInSpec = null,
        fadeOutSpec = null,
        placementSpec = spring(dampingRatio = 0.86f, stiffness = 520f),
    )
    return placement.graphicsLayer { translationY = reorder.translation(key) }.zIndex(if (active) 3f else 0f)
}
