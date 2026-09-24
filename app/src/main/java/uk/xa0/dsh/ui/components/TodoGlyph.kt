package uk.xa0.dsh.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * Port of the web To-dos panel's status glyphs (`TodoPanel.tsx` + its module CSS).
 *
 * All three are drawn on a 14×14 artboard and centred in a 16dp cell: a 6.4-unit
 * ring at 1.2 units of stroke. `completed` adds a check, `pending` dashes the ring
 * (2.4 on / 2.4 off) and `in_progress` paints the ring with a colour→transparent
 * gradient and rotates it once per second.
 *
 * The spin is the point. A static dot for "in progress" reads as just another
 * bullet, which is why the web animates this one glyph and nothing else in the row.
 */
@Composable
fun TodoGlyph(
    isDone: Boolean,
    isActive: Boolean,
    size: Dp = 14.dp,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    when {
        isDone -> CompletedTodoGlyph(colors.success, size, modifier)
        isActive -> ProgressTodoGlyph(colors.accent, size, modifier)
        else -> PendingTodoGlyph(colors.labelCaption, size, modifier)
    }
}

/** Artboard units, matching the svg `viewBox="0 0 14 14"`. */
private const val ARTBOARD = 14f
private const val RING_RADIUS = 6.4f
private const val RING_STROKE = 1.2f

/** One artboard unit in pixels for the composable's measured size. */
private val DrawScope.unit: Float get() = size.width / ARTBOARD

private fun DrawScope.ring(color: Color, brush: Brush? = null, dashed: Boolean = false) {
    val scale = unit
    val style = Stroke(
        width = RING_STROKE * scale,
        pathEffect = if (dashed) PathEffect.dashPathEffect(floatArrayOf(2.4f * scale, 2.4f * scale)) else null,
    )
    if (brush != null) {
        drawCircle(brush = brush, radius = RING_RADIUS * scale, style = style)
    } else {
        drawCircle(color = color, radius = RING_RADIUS * scale, style = style)
    }
}

/** Ring plus the tick of the svg's check path, rounded the same way. */
@Composable
private fun CompletedTodoGlyph(color: Color, size: Dp, modifier: Modifier) {
    Canvas(modifier.size(size)) {
        ring(color)
        val scale = unit
        val check = Path().apply {
            moveTo(3.1f * scale, 7.2f * scale)
            lineTo(6.0f * scale, 9.3f * scale)
            lineTo(10.1f * scale, 5.2f * scale)
        }
        drawPath(
            path = check,
            color = color,
            style = Stroke(
                width = 1.35f * scale,
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
            ),
        )
    }
}

@Composable
private fun PendingTodoGlyph(color: Color, size: Dp, modifier: Modifier) {
    Canvas(modifier.size(size)) { ring(color, dashed = true) }
}

@Composable
private fun ProgressTodoGlyph(color: Color, size: Dp, modifier: Modifier) {
    val transition = rememberInfiniteTransition(label = "todo-spin")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing), RepeatMode.Restart),
        label = "spin",
    )
    Canvas(modifier.size(size)) {
        val scale = unit
        // The web's gradient runs bottom-left to top-right and fades the blue out;
        // rotating the whole canvas is what animates it.
        val brush = Brush.linearGradient(
            colors = listOf(color, color.copy(alpha = 0f)),
            start = Offset(2.5f * scale, 12f * scale),
            end = Offset(10.5f * scale, 3.5f * scale),
        )
        rotate(angle) { ring(color = color, brush = brush) }
    }
}
