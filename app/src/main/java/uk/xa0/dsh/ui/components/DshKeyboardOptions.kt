package uk.xa0.dsh.ui.components

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * The IME contract [DshTextField] hands the keyboard, as one function.
 *
 * A composed field carries no keyboard options in its semantics, so a JVM test
 * cannot read them off the node. Keeping the decision here lets a test state the
 * same contract the field states — no device, no IME, no real credential.
 *
 * A password overrides every text-field option the caller passed:
 *
 * - `KeyboardType.Password` keeps the keyboard in its secret mode, which is what
 *   turns suggestions and autocorrection off at the source;
 * - `KeyboardCapitalization.None` is the explicit "do not capitalise the first
 *   character" that several IMEs (Gboard and Samsung's among them) otherwise
 *   apply, so it is stated rather than inherited;
 * - `autoCorrect = false` forbids rewriting what was typed.
 *
 * The type alone is not enough: an IME that only sees a password variation may
 * still capitalise, so all three are always set together. The IME's action is
 * left alone — it decides whether `Done` submits, and that belongs to the
 * caller's form.
 */
fun dshKeyboardOptions(
    keyboardType: KeyboardType,
    capitalization: KeyboardCapitalization,
    autoCorrect: Boolean,
    imeAction: ImeAction,
    isPassword: Boolean,
): KeyboardOptions = if (isPassword) {
    KeyboardOptions(
        capitalization = KeyboardCapitalization.None,
        autoCorrect = false,
        keyboardType = KeyboardType.Password,
        imeAction = imeAction,
    )
} else {
    KeyboardOptions(
        capitalization = capitalization,
        autoCorrect = autoCorrect,
        keyboardType = keyboardType,
        imeAction = imeAction,
    )
}
