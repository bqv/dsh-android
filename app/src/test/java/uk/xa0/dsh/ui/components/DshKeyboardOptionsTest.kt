package uk.xa0.dsh.ui.components

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication

/**
 * The IME contract of a field, stated where a JVM test can read it.
 *
 * A composed `BasicTextField` carries no keyboard options in its semantics, so
 * the masking and toggle behaviour in `ui/PasswordFieldTest` cannot say anything
 * about what the keyboard is allowed to do to the value. This is the other half:
 * the same function the field calls, asked directly.
 *
 * ## What these cannot show
 *
 * That a particular IME honours these flags. `KeyboardType.Password`,
 * `KeyboardCapitalization.None` and `autoCorrect = false` are requests, and the
 * report was written by an IME that had been handed prose options. Whether
 * Gboard still capitalises the first character — or whether the host accepts the
 * exact bytes — needs a device and a real keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class DshKeyboardOptionsTest {

    /**
     * A password overrides the prose options a caller might pass: the keyboard
     * type is the password variation, capitalisation is explicitly off (not
     * "unspecified", which lets the IME decide), and autocorrection is off.
     */
    @Test
    fun `a password asks for the password keyboard with no capitalisation or autocorrect`() {
        val options = dshKeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.Sentences,
            autoCorrect = true,
            imeAction = ImeAction.Done,
            isPassword = true,
        )

        assertEquals(KeyboardType.Password, options.keyboardType)
        assertEquals(KeyboardCapitalization.None, options.capitalization)
        assertFalse(options.autoCorrect)
    }

    /** The action belongs to the form, so a password keeps whatever it declared. */
    @Test
    fun `a password keeps the form's IME action`() {
        val options = dshKeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            imeAction = ImeAction.Done,
            isPassword = true,
        )

        assertEquals(ImeAction.Done, options.imeAction)
    }

    /**
     * An identifier keeps the layout it chose — `Text`, not `Email`, which would
     * change the keyboard for a field that is not an address — while still
     * forbidding the IME's own edits.
     */
    @Test
    fun `an identifier keeps its type and forbids the IME's own edits`() {
        val options = dshKeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            imeAction = ImeAction.Next,
            isPassword = false,
        )

        assertEquals(KeyboardType.Text, options.keyboardType)
        assertEquals(KeyboardCapitalization.None, options.capitalization)
        assertFalse(options.autoCorrect)
        assertEquals(ImeAction.Next, options.imeAction)
    }
}
