package uk.xa0.dsh.ui.scroll

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first

/**
 * Following the tail of an ordinary, top-anchored list.
 *
 * The transcript uses this instead of `reverseLayout = true`, which pins the newest
 * row to the bottom without the app ever scrolling — paid for by a layout whose
 * anchoring nobody wrote down. A reversed list anchors on the item at the *bottom*
 * of the viewport and lays the rest out upwards from it, so an item's bottom edge is
 * fixed by the rows below it. Grow a row and it grows upwards: measured in
 * `ScrollBehaviourTest`, a 48px row grown to 200px rose by exactly the 192px it
 * gained, taking its own header off the screen and leaving the body where the header
 * had been. An ordinary list anchors on the top, so the same row extends downwards
 * and its top edge does not move at all — also measured.
 *
 * What a top-anchored list does not do by itself is follow the end. This is that,
 * and it is deliberately the only thing that moves a transcript:
 *
 *  * **New rows** are followed with an animated scroll to the last index, and only
 *    when the reader was already at the tail.
 *  * **Growth inside the tail row** — a streaming answer — is corrected by exactly
 *    the number of pixels the tail is cut off by, with a raw delta. It is
 *    self-limiting: the correction can never be larger than the overflow that caused
 *    it, so it cannot become a jump, and it converges in one step because the
 *    overflow it produces is zero.
 *  * A raw *forward* delta needs no coroutine, does not take the scroll mutex that a
 *    programmatic `scrollToItem` holds until the next measure — which is what
 *    cancelled the reader's drags in the version this replaces — and cannot clamp,
 *    because only backwards scrolls clamp and only at the very start.
 *  * [holding] keeps all of it off a list a finger is on. `isScrollInProgress` is
 *    not enough on its own: it turns on when a drag passes touch slop, so the window
 *    between a finger landing and its first movement reads as idle.
 *
 * Only a deliberate drag releases the follow. Deriving that from "is the reader at
 * the tail" instead looks obvious and deadlocks: growth makes the tail *not* at the
 * tail — that is what being cut off means — so the growth would release the follow
 * and cancel the correction that restores it. Measured with the first version: the
 * tail row sat at 452px in a 300px viewport for good.
 *
 * [rearmKey] is for the two moments that mean "follow this again" regardless of
 * where the reader is — opening a session, and sending a message. On a rearm the
 * list lands on the newest row without an animation, because arriving somewhere is
 * not a scroll.
 *
 * @return whether the list is following its tail, for a UI that offers to go back.
 */
@Composable
fun TailFollow(
    state: LazyListState,
    rearmKey: Any? = Unit,
    holding: () -> Boolean = { false },
): State<Boolean> {
    val following = remember { mutableStateOf(true) }

    LaunchedEffect(rearmKey) {
        following.value = true
        // The count is zero until the first measure, and scrolling before that is
        // scrolling an empty list.
        val count = snapshotFlow { state.layoutInfo.totalItemsCount }.filter { it > 0 }.first()
        state.scrollToItem(count - 1)
    }

    // A deliberate drag is the reader taking over, whoever else agrees. Letting go
    // while still at the tail hands it straight back, so a nudge at the bottom is
    // not a decision to stop following.
    LaunchedEffect(state) {
        state.interactionSource.interactions.collect { interaction ->
            when (interaction) {
                is DragInteraction.Start -> following.value = false
                is DragInteraction.Stop, is DragInteraction.Cancel ->
                    if (showingTail(state)) following.value = true

                else -> Unit
            }
        }
    }

    // ...and so is leaving the tail, whether or not a drag did it — the rail, a jump
    // to a session's start, or anything else that moves the list.
    //
    // This is a *position* rule as well as a gesture one because the gesture alone is
    // not enough: released only on a drag, the follow stayed armed when the reader
    // left by the rail, and the next streamed token pulled them back to the bottom.
    // That is the reported "scroll to bottom is not reliable" — it was following when
    // it should not have been, and the button below is offered on the same flag.
    //
    // The rule is `showingTail`, not `atTail`, and the difference is the whole
    // reason this works: it asks whether the last row in the list is the last row on
    // *screen*. A reader up in history has rows below the fold, so it is false and the
    // follow is released; a tail row that has just grown is still the last row on
    // screen, so it stays true and the correction that keeps it visible is still
    // allowed to run. Asking whether the tail is *fully* visible instead would make
    // growth release the follow and cancel its own correction — measured with that
    // version, a 200px row sat at 452px in a 300px viewport for good.
    LaunchedEffect(state) {
        var lastCount = state.layoutInfo.totalItemsCount
        snapshotFlow {
            val info = state.layoutInfo
            info.totalItemsCount to (info.visibleItemsInfo.lastOrNull()?.index ?: -1)
        }.collect { (count, lastVisible) ->
            // A change in how many rows there are is the transcript growing, folding
            // or paging — not the reader moving. Taking it for a move is what made
            // appending a row release the follow before it could bring that row into
            // view, so the newest row simply never arrived.
            if (count != lastCount) {
                lastCount = count
                return@collect
            }
            following.value = lastVisible == count - 1
        }
    }

    // New rows: the one discrete move. `visible < 0` is a list that has not been
    // laid out yet.
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.totalItemsCount }.collect { count ->
            if (count <= 0 || !following.value || holding() || state.isScrollInProgress) return@collect
            val visible = state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (visible < count - 1) state.animateScrollToItem(count - 1)
        }
    }

    // Growth inside the tail: an exact, self-limiting correction.
    LaunchedEffect(state) {
        snapshotFlow { tailOverflow(state) }.collect { overflow ->
            if (overflow > 0f && following.value && !holding() && !state.isScrollInProgress) {
                state.dispatchRawDelta(overflow)
            }
        }
    }

    return following
}

/**
 * True when the last item in the list is the last item on screen — the reader is at
 * the tail, whether or not the row at the end of it is wholly visible.
 *
 * "On screen" rather than "wholly visible" is deliberate: growth in the tail row
 * cuts it off by exactly the amount the follow exists to correct, so a stricter test
 * would read growth as the reader leaving and stop following at precisely the moment
 * following is needed. False before there is a layout to ask.
 */
private fun showingTail(state: LazyListState): Boolean {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return false
    return last.index == info.totalItemsCount - 1
}

/**
 * How far the last item in the list extends past the bottom of the viewport, or zero
 * when that item is not on screen at all.
 *
 * The "not on screen" case is what makes this safe: a reader up in history has rows
 * below the fold, the item at the end of the viewport is not the last item in the
 * list, and the answer is zero however stale anything else is.
 */
private fun tailOverflow(state: LazyListState): Float {
    val info = state.layoutInfo
    val last = info.visibleItemsInfo.lastOrNull() ?: return 0f
    if (last.index != info.totalItemsCount - 1) return 0f
    return (last.offset + last.size - info.viewportEndOffset).coerceAtLeast(0).toFloat()
}
