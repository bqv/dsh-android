package uk.xa0.dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Clickable without the Material ripple — DSH's chrome uses flat hover fills, not ripples. */
fun Modifier.clickableNoRipple(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    clickable(
        enabled = enabled,
        interactionSource = interaction,
        indication = null,
        onClick = onClick,
    )
}

/**
 * Holds an expanding row's top edge still while it changes height.
 *
 * A `LazyColumn(reverseLayout = true)` anchors on the item at the *bottom* of the
 * viewport and lays the rest out upwards from it. Growing an item therefore
 * extends it upwards: its bottom edge is fixed by the rows below it, so the whole
 * row floats up by exactly the height it gained, and the body you just revealed
 * lands where the header used to be. That is the jolt when a section is opened —
 * worst on a tool row, where the body can be hundreds of pixels tall.
 *
 * Compensating with a scroll of the same size puts the top edge back and lets the
 * row grow downward instead — which is what an ordinary, top-anchored list does
 * on its own.
 *
 * The scroll *direction* is deliberately not assumed. `reverseLayout` inverts the
 * scroll axis, and a correction applied the wrong way doubles the jump rather than
 * removing it, so the first one is checked against the next layout and reversed if
 * it went the wrong way. The answer is kept for the rest of the process.
 *
 * Only a change in the row's own height triggers it, and never while a scroll is
 * in progress — so a row that is being streamed into is left alone, and the
 * bottom-pinning follow is not fought.
 */
fun Modifier.anchorTopOnResize(listState: LazyListState): Modifier = composed {
    val scope = rememberCoroutineScope()
    val st = remember { Anchor() }
    Modifier.onGloballyPositioned { coords ->
        val top = coords.positionInWindow().y
        val height = coords.size.height
        if (!st.busy && st.height != 0 && st.height != height && !listState.isScrollInProgress) {
            val delta = top - st.top
            if (delta != 0f) {
                st.busy = true
                st.target = st.top
                scope.launch {
                    val sent = delta * st.sign
                    listState.scrollBy(sent)
                    withFrameNanos { }
                    // Only judge it if the layout actually moved: an unchanged
                    // reading means the scroll did not land, not that it went the
                    // wrong way.
                    if (st.top != top && abs(st.top - st.target) > abs(delta)) {
                        st.sign = -st.sign
                        listState.scrollBy(-2f * sent)
                        withFrameNanos { }
                    }
                    st.busy = false
                }
            }
        }
        st.top = top
        st.height = height
    }
}

private class Anchor {
    var top = 0f
    var height = 0
    var target = 0f

    /** 1 or -1: which way a scroll moves this list's content. Learned once. */
    var sign = 1f
    var busy = false
}

/**
 * Whether a finger is down on a scroll surface right now.
 *
 * This is deliberately not `LazyListState.isScrollInProgress`. That flag turns on
 * when a drag passes touch slop, so the window between a finger landing and its
 * first movement — which is every scroll you are about to make — reads as idle.
 * Anything that moves a list on its own has to ask about the finger, or it moves
 * the list out from under one that has not moved yet. Measured on the phone
 * before this existed: 29 of the chat list's own programmatic scrolls fired while
 * a finger was down, and 17 on the drawer.
 *
 * It also cannot be read from `interactionSource`: `DragInteraction.Start` has the
 * same slop problem, and a programmatic `scrollToItem` produces no interaction at
 * all.
 */
@Stable
class TouchGate internal constructor() {
    internal val down = mutableStateOf(false)

    /** Read this inside an effect, not in composition: reading it in a composable
     *  would recompose whatever reads it on every touch. */
    val isDown: Boolean get() = down.value
}

@Composable
fun rememberTouchGate(): TouchGate = remember { TouchGate() }

/**
 * Marks [gate] down for as long as a finger is anywhere on this node.
 *
 * Observed in the Initial pass, before children, so a down that a row's own
 * control consumes is still seen here; nothing is ever consumed, so the gesture
 * reaches the scrollable underneath unchanged.
 */
fun Modifier.touchGate(gate: TouchGate): Modifier = composed {
    Modifier.pointerInput(gate) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            gate.down.value = true
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                    if (event.changes.none { it.pressed }) break
                }
            } finally {
                // Also on cancellation, so a torn-down handler cannot leave the
                // gate stuck down and the list never following again.
                gate.down.value = false
            }
        }
    }
}
