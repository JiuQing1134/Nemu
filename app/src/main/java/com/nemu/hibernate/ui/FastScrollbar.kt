package com.nemu.hibernate.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Thin draggable fast-scroll thumb for long lists. Drag anywhere on the right
 * strip to jump proportionally; the thumb tracks the first visible item.
 */
@Composable
fun FastScrollbar(
    state: LazyListState,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    val first = info.visibleItemsInfo.firstOrNull()?.index ?: 0
    if (total <= 0 || visible <= 0 || total <= visible) return

    val scope = rememberCoroutineScope()
    val maxFirst = (total - visible).coerceAtLeast(1)
    val fraction = (first.toFloat() / maxFirst).coerceIn(0f, 1f)
    // Drag events arrive per pixel; only start a scroll when the target
    // index actually changes, and cancel the previous in-flight jump. This
    // avoids a queue of coroutines competing with list composition.
    val scrollJob = remember { arrayOfNulls<Job>(1) }
    val lastTarget = remember(total, visible) { intArrayOf(-1) }

    fun jumpTo(y: Float, trackHeight: Float, thumbHeight: Float) {
        val usable = (trackHeight - thumbHeight).coerceAtLeast(1f)
        val f = ((y - thumbHeight / 2f) / usable).coerceIn(0f, 1f)
        val target = (f * (total - 1)).roundToInt().coerceIn(0, total - 1)
        if (target == lastTarget[0]) return
        lastTarget[0] = target
        scrollJob[0]?.cancel()
        scrollJob[0] = scope.launch { state.scrollToItem(target) }
    }

    BoxWithConstraints(
        modifier = modifier
            .width(26.dp)
            .fillMaxHeight()
            .pointerInput(total, visible) {
                detectVerticalDragGestures(
                    onDragStart = { offset: Offset ->
                        jumpTo(
                            offset.y,
                            size.height.toFloat(),
                            size.height.toFloat() * visible / total
                        )
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        jumpTo(
                            change.position.y,
                            size.height.toFloat(),
                            size.height.toFloat() * visible / total
                        )
                    }
                )
            }
            .pointerInput(total, visible) {
                detectTapGestures { offset ->
                    jumpTo(
                        offset.y,
                        size.height.toFloat(),
                        size.height.toFloat() * visible / total
                    )
                }
            }
    ) {
        val density = LocalDensity.current
        val trackPx = with(density) { maxHeight.toPx() }
        val thumbPx = (trackPx * visible / total)
            .coerceAtLeast(with(density) { 40.dp.toPx() })
        // Track
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .width(9.dp)
                .fillMaxHeight()
                .background(
                    color.copy(alpha = 0.10f),
                    RoundedCornerShape(percent = 50)
                )
        )
        // Thumb
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset {
                    IntOffset(
                        0,
                        ((trackPx - thumbPx) * fraction).roundToInt()
                    )
                }
                .width(9.dp)
                .height(with(density) { thumbPx.toDp() })
                .background(
                    color.copy(alpha = 0.65f),
                    RoundedCornerShape(percent = 50)
                )
        )
    }
}
