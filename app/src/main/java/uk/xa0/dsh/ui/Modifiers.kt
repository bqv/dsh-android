package uk.xa0.dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

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
