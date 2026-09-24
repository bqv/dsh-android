package uk.xa0.dsh.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The five semantics the host tracks for a subject:
 * green done / amber needs-you / blue running chase / red error / grey idle.
 */
enum class DotState { DONE, WARNING, ONGOING, ERROR, IDLE }

/**
 * Port of DSH's `StateDot` (`ui-primitives/src/StateDot.tsx`).
 *
 * `ONGOING` is the distinctive one: an 8-cell chase around the ring of a 3×3
 * grid, drawn as a *flat* keyframe step rather than a tween — each cell holds a
 * discrete brightness and decays over the next three cells, which is what gives
 * it the retro rotating-square look. Cells are 2px on a 10px grid, clockwise
 * from top-left, each phase-shifted by 125ms.
 *
 * The solid states are a single colour drawn twice: a full-size halo at 10%
 * opacity with a 60%-scale core inset by 20%.
 */
@Composable
fun StateDot(
    state: DotState,
    size: Dp = 10.dp,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    when (state) {
        DotState.ONGOING -> OngoingChase(size = size, modifier = modifier)
        DotState.DONE -> SolidDot(colors.success, size, modifier)
        DotState.WARNING -> SolidDot(colors.warn, size, modifier)
        DotState.ERROR -> SolidDot(colors.error, size, modifier)
        // Idle is the absence of activity, not a fourth outcome: it stays on the
        // tertiary label colour so it recedes beside the outcome colours.
        DotState.IDLE -> SolidDot(colors.labelTertiary, size, modifier)
    }
}

/** Outer ring cells of a 3×3 matrix, clockwise from top-left, on a 10-unit grid. */
private val MATRIX_CELLS = listOf(
    0 to 0, 4 to 0, 8 to 0, 8 to 4, 8 to 8, 4 to 8, 0 to 8, 0 to 4,
)

@Composable
private fun OngoingChase(size: Dp, modifier: Modifier) {
    val color = DshTheme.colors.ongoing
    val transition = rememberInfiniteTransition(label = "dot-chase")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "chase",
    )

    Canvas(modifier.size(size)) {
        val cellW = this.size.width * 2f / 10f
        val cellH = this.size.height * 2f / 10f
        MATRIX_CELLS.forEachIndexed { index, (gridX, gridY) ->
            // Negative per-cell delay == starting the cycle further along.
            val phase = (((progress + (index - MATRIX_CELLS.size) * 0.125f) % 1f) + 1f) % 1f
            val alpha = when {
                phase < 0.125f -> 1f
                phase < 0.25f -> 0.6f
                phase < 0.375f -> 0.35f
                else -> 0.15f
            }
            drawRect(
                color = color.copy(alpha = alpha),
                topLeft = Offset(this.size.width * gridX / 10f, this.size.height * gridY / 10f),
                size = Size(cellW, cellH),
            )
        }
    }
}

@Composable
private fun SolidDot(color: Color, size: Dp, modifier: Modifier) {
    Box(modifier.size(size)) {
        Box(
            Modifier
                .matchParentSize()
                .clip(CircleShape)
                .background(color.copy(alpha = 0.1f)),
        )
        Box(
            Modifier
                .matchParentSize()
                .padding(size * 0.2f)
                .clip(CircleShape)
                .background(color),
        )
    }
}
