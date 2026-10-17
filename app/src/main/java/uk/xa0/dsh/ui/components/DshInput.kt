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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
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
    /**
     * What the IME may do to the value.
     *
     * These default to the component's previous behaviour rather than to the
     * safest possible setting, because this field also draws a prose answer (the
     * approval card's free-text box). A caller that knows its value is an
     * identifier or a query must say so — the sign-in form does, for the account
     * name — and a password ignores both in favour of [dshKeyboardOptions]'s
     * fixed secret contract.
     */
    keyboardCapitalization: KeyboardCapitalization = KeyboardCapitalization.None,
    autoCorrect: Boolean = true,
    imeAction: ImeAction = ImeAction.Default,
    /**
     * The field's submit path for the IME's `Done`/`Go` action.
     *
     * Without it Compose's default for `Done` is to put the keyboard away, which
     * on a sign-in form looks like a submit that did nothing.
     */
    onImeDone: (() -> Unit)? = null,
    singleLine: Boolean = true,
    minHeight: Dp = 44.dp,
    /**
     * The field's own vertical inset around its text. A drawer's search box wants
     * less than a form field does — it sits directly under the brand lockup, whose
     * centred content already contributes a band of whitespace.
     */
    verticalPadding: Dp = DshSpacing.lg,
    /** When set, the caller drives focus — the directory browser auto-focuses its editors. */
    focusRequester: FocusRequester? = null,
    /**
     * When set, a trailing clear affordance is drawn while [value] is not empty,
     * and this is called to clear it. Opt-in rather than automatic: a form field's
     * value is not a query, and an "x" beside a password, a host URL or an API key
     * would offer to throw away something the user may not be able to see.
     */
    onClear: (() -> Unit)? = null,
    /** The clear affordance's accessibility label; callers pass the surface's own copy. */
    clearContentDescription: String = "Clear",
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    // The text itself is the focus target clearing hands focus back to. The
    // affordance is a clickable, which is focusable in its own right, so on a
    // device that moves focus to whatever was tapped the caret would otherwise
    // land on a control that is about to disappear from the tree. A caller's own
    // requester is preferred because it names the same node.
    val ownFocus = remember { FocusRequester() }
    val fieldFocus = focusRequester ?: ownFocus
    // Masked until the person using the screen asks otherwise, including after a
    // rotation: the last thing typed into a password field is not the thing to
    // uncover by default.
    var revealed by rememberSaveable { mutableStateOf(false) }
    val masked = isPassword && !revealed
    val keyboardActions = onImeDone?.let { done ->
        KeyboardActions(onDone = { done() }, onGo = { done() })
    } ?: KeyboardActions.Default

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
                .padding(horizontal = 14.dp, vertical = verticalPadding),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(placeholder, style = DshType.messageBody, color = colors.labelCaption)
                    }
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = singleLine,
                        textStyle = DshType.messageBody.copy(color = colors.labelPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        visualTransformation = if (masked) PasswordVisualTransformation() else VisualTransformation.None,
                        keyboardOptions = dshKeyboardOptions(
                            keyboardType = keyboardType,
                            capitalization = keyboardCapitalization,
                            autoCorrect = autoCorrect,
                            imeAction = imeAction,
                            isPassword = isPassword,
                        ),
                        keyboardActions = keyboardActions,
                        // Let the text drive its own height for single-line fields so a
                        // larger font scale can never clip the value.
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldFocus)
                            .then(if (singleLine) Modifier else Modifier.heightIn(min = minHeight)),
                    )
                }
                // One trailing slot, never two controls stacked. A password's slot
                // is always the reveal toggle, whether or not there is anything to
                // reveal: it must not appear with the first character or grow on
                // the flip, or the field would resize under the thumb that just
                // tapped it. That also settles the collision with the clear
                // affordance — a secret keeps the one control that helps it, and
                // the "x" stays a query affordance. The clear is drawn only while
                // there is something to clear, which is what takes it away the
                // moment the last character is backspaced out; there is no
                // separate visibility state to fall out of step.
                when {
                    isPassword -> TrailingIcon(
                        imageVector = if (revealed) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                        contentDescription = if (revealed) "Hide password" else "Show password",
                        // Deliberately no focus request: revealing is not editing,
                        // so the caret and the keyboard stay exactly where they
                        // were and the value is untouched.
                        onClick = { revealed = !revealed },
                    )

                    onClear != null && value.isNotEmpty() -> TrailingIcon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = clearContentDescription,
                        onClick = {
                            fieldFocus.requestFocus()
                            onClear()
                        },
                    )
                }
            }
        }
    }
}

/**
 * One trailing control, at the size of the line the field already claims.
 *
 * Sizing lives before the glyph, not inside it, so the slot has the same
 * footprint whatever is drawn in it — which is what makes the reveal toggle
 * unable to move the field when it flips.
 */
@Composable
private fun TrailingIcon(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Icon(
        imageVector = imageVector,
        contentDescription = contentDescription,
        tint = DshTheme.colors.labelTertiary,
        modifier = Modifier
            // One line of `messageBody` (14sp/24sp) at the default font scale, so
            // the affordance stays inside the height the text already claims. A
            // box taller than the line would grow the field the moment it was
            // drawn and shove everything below it down the screen. The inset
            // leaves a 16dp glyph in that target.
            .size(24.dp)
            .clip(CircleShape)
            .clickableNoRipple(onClick = onClick)
            .padding(DshSpacing.xs),
    )
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
