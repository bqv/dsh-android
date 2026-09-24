package uk.xa0.dsh.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The app mark: a double chevron. Original artwork rather than the DeepSeek
 * whale, following the DSH brand guidelines' rule that third-party clients
 * should not reuse official brand assets.
 */
@Composable
fun DshMark(
    size: Dp = 22.dp,
    primary: Color = DshTheme.colors.ink,
    accent: Color = DshTheme.colors.accent,
) {
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = Stroke(width = w * 0.115f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        val left = Path().apply {
            moveTo(w * 0.20f, h * 0.26f)
            lineTo(w * 0.45f, h * 0.50f)
            lineTo(w * 0.20f, h * 0.74f)
        }
        val right = Path().apply {
            moveTo(w * 0.52f, h * 0.26f)
            lineTo(w * 0.77f, h * 0.50f)
            lineTo(w * 0.52f, h * 0.74f)
        }
        drawPath(left, primary, style = stroke)
        drawPath(right, accent, style = stroke)
    }
}

/** Inline wordmark used in the setup hero and the sidebar lockup. */
@Composable
fun DshWordmark(modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        DshMark(size = 20.dp)
        Spacer(Modifier.width(DshSpacing.md))
        Text(
            text = "DSH",
            style = DshType.heading2.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified),
            color = DshTheme.colors.ink,
        )
    }
}

/**
 * DSH's input surface: `specific-login-input` fill, hairline stroke, no Material
 * chrome (no floating label, no ripple, no outline box).
 */
@Composable
fun DshTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    label: String? = null,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    minHeight: Dp = 44.dp,
    /** When set, the caller drives focus — the directory browser auto-focuses its editors. */
    focusRequester: FocusRequester? = null,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)

    Column(modifier) {
        if (label != null) {
            Text(
                text = label,
                style = DshType.bodySmall,
                color = colors.labelSecondary,
                modifier = Modifier.padding(bottom = DshSpacing.sm, start = DshSpacing.xxs),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.selector)
                .border(0.5.dp, colors.borderL2, shape)
                .padding(horizontal = 14.dp, vertical = DshSpacing.lg),
        ) {
            if (value.isEmpty()) {
                Text(placeholder, style = DshType.messageBody, color = colors.labelCaption)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = singleLine,
                textStyle = DshType.messageBody.copy(color = colors.labelPrimary),
                cursorBrush = SolidColor(colors.accent),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
                // Let the text drive its own height for single-line fields so a
                // larger font scale can never clip the value.
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .then(if (singleLine) Modifier else Modifier.heightIn(min = minHeight)),
            )
        }
    }
}

/** Flat primary button used on the setup screen. */
@Composable
fun DshPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Box(
        modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(if (enabled) colors.ink else colors.labelDimmed)
            .clickableNoRipple(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = DshType.labelLarge,
            color = if (enabled) colors.bgBase else colors.labelTertiary,
        )
    }
}
