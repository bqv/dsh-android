package uk.xa0.dsh.diag

import android.os.SystemClock
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * The Compose half of [ScrollDiag]: one call to watch a surface's position, and
 * one modifier to watch the finger.
 *
 * Both are additive. Nothing here consumes a pointer event, holds a gesture,
 * requests a frame, or writes Compose state, so attaching them cannot change how
 * a screen scrolls — which matters, because the point of the exercise is to find
 * out what the screen does *before* it is changed.
 */

/** Watches a lazily-composed list: position, visible keys, item count, extent. */
@Composable
fun DiagLazyList(surface: String, state: LazyListState) {
    // What the list's own gesture detector did, as opposed to what the finger
    // did. A drag that the mutex cancels shows up here and nowhere else.
    LaunchedEffect(surface, state) {
        state.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> ScrollDiag.dragEvent(surface, "start")
                is DragInteraction.Stop -> ScrollDiag.dragEvent(surface, "stop")
                is DragInteraction.Cancel -> ScrollDiag.dragEvent(surface, "cancel")
                else -> Unit
            }
        }
    }
    LaunchedEffect(surface, state) {
        snapshotFlow {
            val info = state.layoutInfo
            Sample(
                index = state.firstVisibleItemIndex,
                offset = state.firstVisibleItemScrollOffset,
                keys = info.visibleItemsInfo.map { shortKey(it.key) },
                items = info.totalItemsCount,
                viewport = info.viewportSize.height,
                scrolling = state.isScrollInProgress,
            )
        }.collect { s ->
            ScrollDiag.watch(surface, s.index, s.offset, s.keys, s.items, s.viewport, s.scrolling)
        }
    }
}

/** Watches a pixel-scrolled column. */
@Composable
fun DiagScrollColumn(surface: String, state: ScrollState) {
    LaunchedEffect(surface, state) {
        snapshotFlow { state.value to state.isScrollInProgress }
            .collect { (value, scrolling) -> ScrollDiag.watchPx(surface, value, scrolling) }
    }
}

/**
 * Records every gesture that lands on this modifier's node: where the finger
 * came down, how far it travelled, whether a child scrollable took the event,
 * and whether the thing being watched actually moved as a result.
 *
 * [offset] is a monotone read of the surface's own scroll position, used only to
 * answer "did it move at all". For a `LazyListState` the index is scaled so that
 * a change in either component reads as movement.
 *
 * The down is taken in the Initial pass (before children) and the movements in
 * the Final pass (after them), so [ScrollDiag] sees both a gesture that a child
 * swallowed and one the list never received.
 */
fun Modifier.diagDrag(surface: String, offset: () -> Int = { 0 }): Modifier = composed {
    // Keyed on the surface alone. `offset` is a lambda literal at every call
    // site, so keying on it too would tear the pointer handler down on each
    // recomposition — which, mid-gesture, is a gesture that never reports.
    val current = rememberUpdatedState(offset)
    Modifier.pointerInput(surface) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val before = current.value()
            val startedAt = down.uptimeMillis
            var last = down.position
            var dx = 0f
            var dy = 0f
            var consumed = false
            ScrollDiag.gestureDown(surface, down.position.x, down.position.y, size.width, size.height)
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (change.isConsumed) consumed = true
                    dx += change.position.x - last.x
                    dy += change.position.y - last.y
                    last = change.position
                    if (!change.pressed) break
                }
            } finally {
                // Also on cancellation, so a handler torn down mid-drag cannot
                // leave the surface reading as "a finger is still down".
                val after = current.value()
                ScrollDiag.gestureUp(
                    surface = surface,
                    dx = dx,
                    dy = dy,
                    ms = SystemClock.uptimeMillis() - startedAt,
                    moved = after != before,
                    childConsumed = consumed,
                    offsetBefore = before,
                    offsetAfter = after,
                )
            }
        }
    }
}

/** A list's position, as [ScrollDiag.watch] wants it. */
fun LazyListState.diagOffset(): Int = firstVisibleItemIndex * 10_000 + firstVisibleItemScrollOffset

private class Sample(
    val index: Int,
    val offset: Int,
    val keys: List<String>,
    val items: Int,
    val viewport: Int,
    val scrolling: Boolean,
)

/** Keys can be whole prompt strings; a short hash is enough to spot a swap. */
private fun shortKey(key: Any?): String = (key?.hashCode() ?: 0).toUInt().toString(16)
