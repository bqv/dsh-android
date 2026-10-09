package uk.xa0.dsh.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.ln
import kotlin.math.pow

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

/**
 * Turns a two-finger span into whole steps of whatever the span is sizing.
 *
 * A pinch is a *ratio*, not a distance: the fingers start wherever they are and
 * 25% more span means one step larger, wherever that was. Reading it as pixels
 * instead would make the same gesture mean different things on different screens.
 *
 * It also has to be a *detent* rather than a continuous reading. A finger report
 * arrives once a frame with a fraction of a percent of jitter in it, and the
 * things a step sizes — a font, a grid the host is told about — must not be
 * rewritten on jitter. So the span is measured against a moving anchor and only
 * whole crossings are reported; the anchor then moves by exactly what was
 * reported, which is what keeps a slow pinch from losing the fraction it had
 * already accumulated.
 *
 * Kept out of the modifier so it can be tested as arithmetic: a gesture cannot be
 * asserted in a unit test, but "a 25% spread is one step and a 10% one is none"
 * can.
 */
class PinchDetents(
    private val perStep: Float = PinchDetents.STEP_RATIO,
    private val maxStepsPerReport: Int = PinchDetents.MAX_STEPS_PER_REPORT,
) {

    private var anchor = 0f

    /** Forget the gesture. A new pinch re-anchors on its own first span. */
    fun reset() {
        anchor = 0f
    }

    /**
     * Whole steps [span] is from the anchor, negative for a tightening pinch.
     *
     * The first report after a reset only anchors: a pinch's opening span is the
     * zero of its own scale, and treating it as a change would make every touch of
     * two fingers a step.
     *
     * The anchor then moves by exactly the ratio that was reported, not to the span
     * that was measured. Moving it to the span would leave the remainder of the
     * crossing as a fresh credit, so a still finger would keep crossing its own
     * boundary once per frame for as long as the report kept arriving.
     */
    fun steps(span: Float): Int {
        if (span <= 0f || !span.isFinite()) return 0
        if (anchor <= 0f) {
            anchor = span
            return 0
        }
        val raw = (ln(span / anchor) / ln(perStep)).toInt()
        val steps = raw.coerceIn(-maxStepsPerReport, maxStepsPerReport)
        if (steps != 0) anchor *= perStep.pow(steps.toFloat())
        return steps
    }

    companion object {
        /** One step per 25% of span. About 8% of a phone's columns, so a step is visible. */
        const val STEP_RATIO = 1.25f

        /**
         * A bound on one report, not on a gesture.
         *
         * A finger lifted and re-placed far away is a huge span with no movement at
         * all, and a size that jumps its whole range from one frame of that would be
         * a teleport dressed as a pinch. Crossing the range still takes several
         * frames of a deliberate spread, which is the point.
         */
        const val MAX_STEPS_PER_REPORT = 2
    }
}

/**
 * Two fingers changing a size, reported as whole steps.
 *
 * ## Why it lives here
 *
 * Beside [touchGate], because it is the same problem: a gesture that has to be told
 * apart from the ones already on the node. What that costs is different here — a
 * touch gate suspends the app's *own* moves under a finger, while this one has to
 * *claim* a gesture that a tap detector would otherwise read as a tap.
 *
 * ## What it claims, and what it leaves alone
 *
 * A one-finger gesture is left exactly as it was: the down, the moves and the up are
 * all observed in the Initial pass and none of them is consumed, so a tap on the grid
 * still reaches the panel's own tap target and still puts the soft keyboard back.
 * They have to be observed here rather than not at all, because the second finger of
 * a pinch arrives *after* the first down, and by the time it lands the tap detector
 * is already waiting for an up that a pinch never sends.
 *
 * Once a second finger is down the whole gesture is consumed, from that event to its
 * last up. That is what tells the tap detector the gesture was not a tap — a
 * two-finger spread whose fingers both lift would otherwise land as one — and it is
 * why nothing is consumed before then: consuming the first down would break the tap
 * for everybody.
 *
 * Deliberately *not* on the terminal's key row. That row scrolls horizontally, and a
 * second gesture on a node that already owns one is the trade this repository
 * refused for the transcript's shell block: it "costs a gesture that has to be told
 * apart from the list's". The grid is a fixed surface that owns no gesture but the
 * tap, so a two-finger gesture on it costs nothing that was not already ambiguous.
 */
fun Modifier.pinchCellSize(onStep: (Int) -> Unit): Modifier = composed {
    // The callback captures the cell size at composition time, and the handler is not
    // restarted between steps; without this it would step from the size the gesture
    // started at, and the second step of a pinch would do nothing.
    val step by rememberUpdatedState(onStep)
    val detents = remember { PinchDetents() }
    Modifier.pointerInput(detents) {
        awaitEachGesture {
            detents.reset()
            // True once this gesture is a pinch: from then on it is ours, including
            // the events after one of the fingers lifts.
            var claimed = false
            // Whether the last event had two fingers down. The transitions matter:
            // a pinch is a *span*, so a finger put back down is a new zero rather
            // than an enormous change to the old one.
            var pinching = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.filter { it.pressed }
                if (pressed.isEmpty()) return@awaitEachGesture
                if (pressed.size >= 2 && !pinching) detents.reset()
                pinching = pressed.size >= 2
                when {
                    pinching -> {
                        claimed = true
                        // Tell the tap detector this was not a tap, and keep telling
                        // it: a two-finger spread whose fingers both lift would
                        // otherwise land as one.
                        event.changes.forEach { it.consume() }
                        val steps = detents.steps((pressed[0].position - pressed[1].position).getDistance())
                        if (steps != 0) step(steps)
                    }
                    // One finger left after a pinch: still not a tap.
                    claimed -> event.changes.forEach { it.consume() }
                }
            }
        }
    }
}
