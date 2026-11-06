package com.oasisreader.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** What the fast scroller needs from a lazy grid or list. */
class ScrollTarget(
    val first: () -> Int,
    val total: () -> Int,
    val visible: () -> Int,
    val scrolling: () -> Boolean,
    val scrollTo: suspend (Int) -> Unit,
)

fun LazyGridState.scrollTarget() = ScrollTarget(
    first = { firstVisibleItemIndex },
    total = { layoutInfo.totalItemsCount },
    visible = { layoutInfo.visibleItemsInfo.size },
    scrolling = { isScrollInProgress },
    scrollTo = { scrollToItem(it) },
)

fun LazyListState.scrollTarget() = ScrollTarget(
    first = { firstVisibleItemIndex },
    total = { layoutInfo.totalItemsCount },
    visible = { layoutInfo.visibleItemsInfo.size },
    scrolling = { isScrollInProgress },
    scrollTo = { scrollToItem(it) },
)

/**
 * Over a long list: a thumb on the right edge to drag (with a bubble saying where — an initial,
 * a date, a 컬렉션 name — from [label]), and ⬆️ 맨 위로 once a little way down.
 * Only for lists longer than about three screens. Shared by 서재, 보관함 관리, 하이라이트.
 */
@Composable
fun BoxScope.FastScroller(target: ScrollTarget, label: (Int) -> String? = { null }, bottomPadding: androidx.compose.ui.unit.Dp = 16.dp) {
    val scope = rememberCoroutineScope()
    val total by remember { derivedStateOf { target.total() } }
    val visible by remember { derivedStateOf { target.visible().coerceAtLeast(1) } }
    val first by remember { derivedStateOf { target.first() } }
    val scrolling by remember { derivedStateOf { target.scrolling() } }
    var dragging by remember { mutableStateOf(false) }
    var dragFrac by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) shown = true else { delay(1500); shown = false }
    }
    val long = total > visible * 3

    // ⬆️ 맨 위로
    AnimatedVisibility(
        visible = first > visible, enter = fadeIn(), exit = fadeOut(),
        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 20.dp, bottom = bottomPadding),
    ) {
        SmallFloatingActionButton(onClick = { scope.launch { target.scrollTo(0) } }) { Text("⬆️") }
    }
    if (!long) return

    BoxWithConstraints(Modifier.matchParentSize().padding(vertical = 8.dp)) {
        val density = LocalDensity.current
        val thumbH = 44.dp
        val trackPx = with(density) { (maxHeight - thumbH).toPx() }.coerceAtLeast(1f)
        val maxFirst = (total - visible).coerceAtLeast(1)
        val frac = if (dragging) dragFrac else (first.toFloat() / maxFirst).coerceIn(0f, 1f)
        fun jump(f: Float) {
            dragFrac = f.coerceIn(0f, 1f)
            val idx = (dragFrac * maxFirst).roundToInt().coerceIn(0, (total - 1).coerceAtLeast(0))
            scope.launch { target.scrollTo(idx) }
        }
        Box(
            Modifier.align(Alignment.TopEnd).fillMaxHeight().width(28.dp).pointerInput(total, visible, trackPx) {
                detectVerticalDragGestures(
                    onDragStart = { o -> dragging = true; jump((o.y - thumbH.toPx() / 2) / trackPx) },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                    onVerticalDrag = { change, dy -> change.consume(); jump(dragFrac + dy / trackPx) },
                )
            },
        ) {
            if (shown || dragging) Box(
                Modifier.align(Alignment.TopEnd).offset { IntOffset(0, (frac * trackPx).roundToInt()) }
                    .padding(end = 3.dp).size(width = 6.dp, height = thumbH)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (dragging) 0.9f else 0.55f), RoundedCornerShape(3.dp)),
            )
        }
        if (dragging) {
            val idx = (dragFrac * maxFirst).roundToInt().coerceIn(0, (total - 1).coerceAtLeast(0))
            label(idx)?.let { text ->
                Surface(
                    shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.primaryContainer, tonalElevation = 4.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(end = 40.dp).widthIn(max = 220.dp)
                        .offset { IntOffset(0, (dragFrac * trackPx).roundToInt()) },
                ) {
                    Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
                }
            }
        }
    }
}
