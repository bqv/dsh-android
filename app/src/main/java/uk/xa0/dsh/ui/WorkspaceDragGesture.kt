package uk.xa0.dsh.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import uk.xa0.dsh.model.reorderWorkspaceSiblings
import uk.xa0.dsh.model.workspaceDropSlot

/**
 * The drawer's Workspace drag: one header, lifted, with a live preview order.
 *
 * ## Why the gesture lives on the list
 *
 * The drawer's list is a `LazyColumn` with sticky headers, a fold tap and a `+` on
 * every header, and a live preview reorders the sections under the finger. A
 * detector on a header row would sit in a composition the reorder is moving, and
 * would be cancelled by the very move it is making. This detector therefore lives on
 * the list's own (stable) node, which is an **ancestor** of the scrollable: reading
 * events on the [PointerEventPass.Initial] pass means a claimed drag reaches the
 * scrollable already consumed, so the list cannot scroll at the same time.
 *
 * Session rows never start a drag because only header bounds are offered to
 * [canStart]; a header's measurements are supplied by the drawer.
 *
 * ## Threshold
 *
 * A long press only arms the gesture; the drag itself begins once the finger has
 * travelled [threshold] (the platform's touch slop). Under that distance nothing is
 * consumed, so the header's own `clickable` still sees an ordinary tap and folds the
 * section — a held tap with a little wobble is still a fold. Once the threshold is
 * crossed every change, including the final up, is consumed, which is also what
 * stops a release from folding the section the reader just moved.
 *
 * ## Cancel
 *
 * The caller vetoes a move by returning false from [onMove] (the pointer left the
 * list); a system cancellation of the pointer coroutine calls [onCancel] too. In
 * both cases the drawer drops its preview and sends nothing, so the list returns to
 * the order it had when the drag began.
 *
 * The caller owns the preview state ([WorkspaceDragState]) and the request; this
 * file only recognises the gesture and reports the pointer's local position.
 */
internal suspend fun PointerInputScope.detectWorkspaceDrag(
    threshold: Float,
    canStart: (Offset) -> Boolean,
    onStart: (Offset) -> Unit,
    onMove: (Offset) -> Boolean,
    onEnd: () -> Unit,
    onCancel: () -> Unit,
) {
    try {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!canStart(down.position)) return@awaitEachGesture
            // A long press that turns into a scroll or is consumed elsewhere returns
            // null here, and whatever claimed it keeps the gesture.
            val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture

            var travelled = Offset.Zero
            var started = false
            var cancelled = false
            var pointer = longPress
            while (true) {
                // Initial runs from the list down to the scrollable, so the drag is
                // already consumed by the time the list's own gesture sees it.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == pointer.id } ?: break
                if (change.changedToUpIgnoreConsumed()) {
                    if (started) {
                        // Consuming the up is what keeps the row's clickable from
                        // reading a drag as a fold on release.
                        change.consume()
                        if (!cancelled) onEnd()
                    }
                    return@awaitEachGesture
                }
                if (cancelled) {
                    // Swallow what remains of a cancelled drag so the list does not
                    // start scrolling out from under the finger that left it.
                    change.consume()
                    continue
                }
                if (!started && change.isConsumed) return@awaitEachGesture
                travelled += change.positionChange()
                if (!started && travelled.getDistance() >= threshold) {
                    started = true
                    onStart(change.position)
                }
                if (started) {
                    if (!onMove(change.position)) {
                        cancelled = true
                        onCancel()
                    }
                    change.consume()
                }
                pointer = change
            }
            if (started && !cancelled) onCancel()
        }
    } catch (cancelled: CancellationException) {
        // The list left the composition, the drawer closed, or the registry moved
        // under the drag: the preview must not outlive the gesture.
        onCancel()
        throw cancelled
    }
}

/** Which edge of the list a drag is close enough to for the list to scroll itself. */
internal enum class WorkspaceDragZone { Top, Bottom }

/** How close to an edge the finger must be for auto-scroll to start. */
internal val WORKSPACE_DRAG_EDGE = 56.dp

/** How far the list moves on the first frame inside the edge band. */
internal val WORKSPACE_DRAG_SCROLL_MIN = 2.dp

/** How far it moves on a frame at the very edge. */
internal val WORKSPACE_DRAG_SCROLL_MAX = 12.dp

/**
 * How far outside the list a finger may stray before the drag cancels instead of
 * committing. Without a little slack a thumb that drifts over the drawer's own
 * padding would throw the move away.
 */
internal val WORKSPACE_DRAG_CANCEL_SLOP = 24.dp

/**
 * The preview a live drag is showing: the sibling order and where the finger is.
 *
 * Only [previewSiblings] is read during composition, and it changes at most once per
 * crossed header; [pointerY] is observed by the frame-clock auto-scroll, never by a
 * composable, so a finger moving within one slot does not recompose the drawer. The
 * insertion line is not state at all: it is read from the header's measured bounds at
 * draw time, so it always sits on the boundary the lifted row currently occupies.
 */
internal class WorkspaceDragState(
    val workspaceId: String,
    val startSiblings: List<String>,
) {
    /** The sibling order drawn while the finger is down; the order a release commits. */
    var previewSiblings by mutableStateOf(startSiblings)
        private set

    /** The finger's y in window coordinates, updated on every move and every frame. */
    var pointerY by mutableStateOf(0f)
        private set

    fun movePointer(windowY: Float) {
        pointerY = windowY
    }

    /**
     * Recompute the slot and move the preview order if the finger has crossed a header.
     *
     * Called on every pointer move and after every auto-scroll frame. Headers outside
     * the viewport are ignored: a stale measurement from a disposed row is not
     * evidence that the finger is near it.
     *
     * @param headerBounds Workspace id to its header's window bounds.
     * @param list the list's window bounds.
     */
    fun updateDrop(headerBounds: Map<String, Rect>, list: Rect) {
        if (list.height <= 0f) return
        val others = previewSiblings.filterNot { it == workspaceId }
        val centres = HashMap<String, Float>(others.size)
        for (id in others) {
            val bounds = headerBounds[id] ?: continue
            if (bounds.bottom <= list.top || bounds.top >= list.bottom) continue
            centres[id] = bounds.top + bounds.height / 2f
        }
        val slot = workspaceDropSlot(previewSiblings, workspaceId, pointerY, centres)
        // No sibling header is on screen: the finger is inside a tall section and
        // there is no crossing to name yet. Keeping the preview is the only honest
        // answer; auto-scroll will reveal the next header.
        if (slot < 0) return
        val next = reorderWorkspaceSiblings(previewSiblings, workspaceId, slot)
        if (next != previewSiblings) previewSiblings = next
    }

    /** The edge band the finger is in, or null when it is away from both edges. */
    fun zone(list: Rect, edge: Float): WorkspaceDragZone? {
        if (list.height <= 0f || edge <= 0f) return null
        return when {
            pointerY < list.top + edge -> WorkspaceDragZone.Top
            pointerY > list.bottom - edge -> WorkspaceDragZone.Bottom
            else -> null
        }
    }

    /**
     * How far into the edge band the finger is, from 0 at the inner boundary to 1 at
     * or beyond the edge; this is what makes auto-scroll speed up as it is pushed
     * further.
     */
    fun penetration(list: Rect, edge: Float, zone: WorkspaceDragZone): Float {
        if (edge <= 0f) return 1f
        return when (zone) {
            WorkspaceDragZone.Top -> ((list.top + edge - pointerY) / edge).coerceIn(0f, 1f)
            WorkspaceDragZone.Bottom -> ((pointerY - (list.bottom - edge)) / edge).coerceIn(0f, 1f)
        }
    }
}

/**
 * Whether a drag has left the list far enough to count as a cancel.
 *
 * The horizontal check is what makes dragging sideways out of the drawer cancel;
 * the vertical one allows the auto-scroll band to sit inside the list without the
 * edge itself reading as "outside".
 *
 * @param pointer the finger in window coordinates.
 * @param list the list's window bounds.
 * @param slop how far outside the bounds is still tolerated.
 */
internal fun isOutsideDragBounds(pointer: Offset, list: Rect, slop: Float): Boolean =
    pointer.x < list.left - slop ||
        pointer.x > list.right + slop ||
        pointer.y < list.top - slop ||
        pointer.y > list.bottom + slop
