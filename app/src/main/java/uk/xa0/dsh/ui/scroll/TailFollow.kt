package uk.xa0.dsh.ui.scroll

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull

/**
 * Following the tail of an ordinary, top-anchored list.
 *
 * The transcript used to render with `reverseLayout = true`, which pins the newest
 * row to the bottom without the app ever scrolling — paid for by the app having to
 * scroll for everything else, and by a layout whose anchoring nobody wrote down.
 * A reversed list anchors on the item at the *bottom* of the viewport and lays the
 * rest out upwards from it, so an item's bottom edge is fixed by the rows below it.
 * Grow a row and it grows upwards: measured in `ScrollBehaviourTest`, a 48dp row
 * grown to 240dp rose by exactly the 192dp it gained, taking its header off the
 * screen and leaving the body where the header had been. An ordinary list anchors
 * on the top, so the same row extends downwards and its top edge does not move at
 * all — also measured.
 *
 * What a top-anchored list does not do by itself is follow the end. This is that,
 * and it is deliberately the only thing that moves a transcript:
 *
 *  * **New rows** are followed with an animated scroll to the last index, and only
 *    when the reader was already at the tail.
 *  * **Growth inside the tail row** — a streaming answer — is corrected by exactly
 *    the number of pixels the tail is cut off by, with a raw delta. It is
 *    self-limiting: the correction can never be larger than the overflow that
 *    caused it, so it cannot become a jump, and it converges in one step because
 *    the overflow it produces is zero.
 *  * A raw *forward* delta needs no coroutine, does not take the scroll mutex that
 *    a programmatic `scrollToItem` holds until the next measure — which is what
 *    cancelled the reader's drags in the version this replaces — and cannot clamp,
 *    because only backwards scrolls clamp and only at the very start.
 *  * [holding] keeps all of it off a list a finger is on. `isScrollInProgress` is
 *    not enough on its own: it turns on when a drag passes touch slop, so the
 *    window between a finger landing and its first movement reads as idle.
 *
 * Following is released by a deliberate drag and re-armed by reaching the tail
 * again, so a reader who has scrolled up is never dragged back.
 */
@Composable
fun TailFollow(
    state: LazyListState,
    itemCount: Int,
    holding: () -> Boolean = { false },
) {
    var following by remember { mutableStateOf(true) }

    // Only a deliberate drag releases the follow.
    //
    // It is tempting to derive it from "is the reader at the tail", and that is
    // what the first version of this did — it deadlocks. Growing the tail row makes
    // the tail *not* at the tail (it is cut off by exactly the amount the follow
    // exists to correct), so the growth would release the follow and cancel the
    // correction that restores it, and the row would sit half off the screen for
    // good. Measured: the tail row grew to 200dp in a 300dp viewport and stayed at
    // 452, because releasing on that reading made the correction unreachable.
    //
    // A drag is the honest signal for "the reader has taken over", and it does not
    // fire for growth, for a stream, or for anything the app does by itself.
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            if (interaction is DragInteraction.Start) following = false
        }
    }

    // Reaching the tail — all of it, with nothing cut off — hands it back.
    LaunchedEffect(state) {
        snapshotFlow { atTail(state) }
            .filterNotNull()
            .distinctUntilChanged()
            .collect { atTail -> if (atTail) following = true }
    }

    // New rows: the one discrete move. `visible < 0` is a list that has not been
    // laid out yet, which is the session-opening case and must still come up at the
    // end.
    LaunchedEffect(itemCount) {
        if (itemCount <= 0 || !following || holding() || state.isScrollInProgress) return@LaunchedEffect
        val visible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
        if (visible < itemCount - 1) state.animateScrollToItem(itemCount - 1)
    }

    // Growth inside the tail: an exact, self-limiting correction.
    LaunchedEffect(state) {
        snapshotFlow { tailOverflow(state) }.collect { overflow ->
            if (overflow > 0f && following && !holding() && !state.isScrollInProgress) {
                state.dispatchRawDelta(overflow)
            }
        }
    }
}

/**
 * True when the last item in the list is the last item on screen and none of it is
 * cut off; null before there is a layout to ask.
 */
private fun atTail(state: LazyListState): Boolean? {
    val info = state.layoutInfo
    if (info.visibleItemsInfo.isEmpty()) return null
    val last = info.visibleItemsInfo.last()
    return last.index == info.totalItemsCount - 1 &&
        last.offset + last.size <= info.viewportEndOffset + 1
}

/**
 * How far the last item in the list extends past the bottom of the viewport, or
 * zero when that item is not on screen at all.
 *
 * The "not on screen" case is what makes this safe: a reader up in history has
 * rows below the fold, the item at the end of the viewport is not the last item in
 * the list, and the answer is zero however stale anything else is.
 */
private fun tailOverflow(state: LazyListState): Float {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index != info.totalItemsCount - 1) return 0f
    return (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0).toFloat()
}
