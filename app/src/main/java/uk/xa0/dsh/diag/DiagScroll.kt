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
import androidx.compose.runtime.withFrameNanos
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
        // Two cheap reads drive the sampler, and the visible-key list — the only
        // allocation here — is rebuilt only when the first visible item changes.
        // Building it per emission meant a list of hashes per frame for the whole
        // of a scroll: instrumentation must not be the thing costing frames.
        var lastIndex = Int.MIN_VALUE
        var keys: List<String> = emptyList()
        var items = 0
        var viewport = 0
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .collect { (index, offset) ->
                if (index != lastIndex || keys.isEmpty()) {
                    val info = state.layoutInfo
                    keys = info.visibleItemsInfo.map { shortKey(it.key) }
                    viewport = info.viewportSize.height
                    lastIndex = index
                }
                items = state.layoutInfo.totalItemsCount
                ScrollDiag.watch(surface, index, offset, keys, items, viewport, state.isScrollInProgress)
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

/**
 * Frame times, batched every couple of seconds.
 *
 * One of these is enough for the whole app: every `withFrameNanos` callback fires on
 * the same frame, so a second watcher would only duplicate the same numbers. The
 * batch is dropped when the loop was idle, so a phone sitting on a desk does not
 * fill the log with 60fps of nothing.
 */
@Composable
fun DiagFrames(surface: String = "app") {
    LaunchedEffect(Unit) {
        var last = 0L
        var windowStart = 0L
        val deltas = ArrayList<Long>(256)
        while (true) {
            val now = withFrameNanos { it }
            if (last != 0L) {
                val ms = (now - last) / 1_000_000
                // A gap this long is the app not drawing at all — suspended, or in
                // the background — not a frame that took 5 hours.
                if (ms in 1..1_000) deltas += ms
            }
            last = now
            if (windowStart == 0L) windowStart = now
            if (now - windowStart >= 2_000_000_000L) {
                ScrollDiag.frames(surface, deltas)
                deltas.clear()
                windowStart = now
            }
        }
    }
}

/** A list's position, as [ScrollDiag.watch] wants it. */
fun LazyListState.diagOffset(): Int = firstVisibleItemIndex * 10_000 + firstVisibleItemScrollOffset

/** Keys can be whole prompt strings; a short hash is enough to spot a swap. */
private fun shortKey(key: Any?): String = (key?.hashCode() ?: 0).toUInt().toString(16)
