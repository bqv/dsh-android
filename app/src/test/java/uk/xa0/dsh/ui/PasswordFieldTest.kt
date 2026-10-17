package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The sign-in password field: masked by default, revealable without moving, and
 * unable to post the same credentials twice.
 *
 * The reported failure was a lockout caused by a keyboard that capitalised or
 * autocorrected a password the user could not read back. Three separate promises
 * are checked here:
 *
 *  1. the field starts masked, and its accessible label says what the control
 *     will do ("Show password" while masked, "Hide password" while revealed);
 *  2. the reveal control is in the tree before any text is typed and occupies
 *     the same bounds after a flip, so it cannot appear under or shove the field;
 *  3. `Done` on the password field posts the form, and neither a second `Done`,
 *     nor a tap after it, nor two taps on Connect in the same frame post a
 *     second time.
 *
 * ## What these cannot show
 *
 * Nothing here proves what Gboard or Samsung's IME does with the field: the
 * harness types through Compose's own semantics, never through an IME, so the
 * capitalisation and autocorrection flags are asserted in
 * `components/DshKeyboardOptionsTest` at the `KeyboardOptions` level rather than
 * on a device. The harness also cannot measure text or colour, so "the label
 * fits", "the eye is legible" and "the dots are the right size" are device
 * claims. Finally, the `Account` field's own options are not visible in the
 * semantics tree at all; that wiring is read from `SetupScreen` source, while
 * this file proves only the password path end to end.
 *
 * The screen is composed on a display tall enough for the whole form. That is
 * not decoration: `performClick` on Android is a real touch injection, not the
 * semantic click action, and Robolectric's default screen is 470dp high while
 * this form's Connect button starts at roughly 530dp. Without the height below,
 * a "click" lands outside the window, reaches nothing, and reads as a form that
 * posted zero times.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = HarnessApplication::class,
    qualifiers = "w480dp-h1000dp",
)
class PasswordFieldTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * One field under the app's own theme, in a box the size of a phone screen.
     *
     * A plain function around `setContent`, following `FilesPanelTest`: the theme
     * and the real composables sit directly in the rule's own content lambda.
     */
    private fun showField(
        value: String,
        isPassword: Boolean = true,
        onClear: (() -> Unit)? = null,
    ) {
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 400.dp, height = 240.dp)) {
                    DshTextField(
                        value = value,
                        onValueChange = {},
                        placeholder = "password",
                        modifier = Modifier.fillMaxWidth(),
                        isPassword = isPassword,
                        onClear = onClear,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /** The sign-in form itself, with the host and account already filled in. */
    private fun showSetup(onConnect: (String, String, String, String, String) -> Unit) {
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 420.dp, height = 900.dp)) {
                    SetupScreen(
                        initialUrl = "http://host/",
                        initialUsername = "tony",
                        error = null,
                        busy = false,
                        startupStep = null,
                        onConnect = onConnect,
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * The password node, found by the one semantic a masked `BasicTextField`
     * publishes: `Password`. That it matches is itself the masking assertion —
     * Compose only sets it while `PasswordVisualTransformation` is in force.
     */
    private fun maskedField() = rule.onNode(
        hasSetTextAction() and SemanticsMatcher.keyIsDefined(SemanticsProperties.Password),
    )

    @Test
    fun `a password starts masked and says what the control would do`() {
        showField("hunter2")

        maskedField().assertExists()
        rule.onNodeWithContentDescription("Show password").assertExists()
        rule.onNodeWithContentDescription("Hide password").assertDoesNotExist()
    }

    @Test
    fun `the toggle reveals, re-masks, and never edits the value`() {
        var value by mutableStateOf("hunter2")
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 400.dp, height = 240.dp)) {
                    DshTextField(
                        value = value,
                        onValueChange = { value = it },
                        placeholder = "password",
                        modifier = Modifier.fillMaxWidth(),
                        isPassword = true,
                    )
                }
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Show password").performClick()
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Hide password").assertExists()
        rule.onNodeWithContentDescription("Show password").assertDoesNotExist()
        // The label follows the state, and so does the field itself: the password
        // semantic is published only while the transformation is masking.
        maskedField().assertDoesNotExist()
        rule.runOnIdle { assertEquals("hunter2", value) }

        rule.onNodeWithContentDescription("Hide password").performClick()
        rule.waitForIdle()

        rule.onNodeWithContentDescription("Show password").assertExists()
        maskedField().assertExists()
        rule.runOnIdle { assertEquals("hunter2", value) }
    }

    /**
     * The control is not conditional on the value. An eye that appears with the
     * first character would move the text as someone starts typing — and would
     * leave a saved password with no way to read it back before touching it.
     */
    @Test
    fun `the toggle is there before a single character is typed`() {
        showField("")

        rule.onNodeWithContentDescription("Show password").assertExists()
        rule.onNodeWithContentDescription("Hide password").assertDoesNotExist()
    }

    /**
     * The slot has one footprint in both states. The harness can read bounds even
     * though it cannot measure text, so this is a real comparison of the node
     * the thumb lands on rather than a claim about how the flip looks.
     */
    @Test
    fun `revealing does not move the toggle`() {
        showField("hunter2")
        val before = rule.onNodeWithContentDescription("Show password").getBoundsInRoot()

        rule.onNodeWithContentDescription("Show password").performClick()
        rule.waitForIdle()
        val after = rule.onNodeWithContentDescription("Hide password").getBoundsInRoot()

        assertEquals(before.left, after.left)
        assertEquals(before.top, after.top)
        assertEquals(before.right, after.right)
        assertEquals(before.bottom, after.bottom)
    }

    /**
     * The clear control and the reveal toggle share one slot rather than stack:
     * a secret keeps the control that helps it, and "x" stays a query affordance.
     */
    @Test
    fun `a secret never stacks the clear control beside the reveal toggle`() {
        showField("hunter2", onClear = {})

        rule.onNodeWithContentDescription("Clear").assertDoesNotExist()
        rule.onNodeWithContentDescription("Show password").assertExists()
    }

    /** `Done` is the submit path the form did not have. */
    @Test
    fun `Done on the sign-in password field submits the form`() {
        var calls = 0
        var sentPassword = ""
        showSetup { _, _, _, password, _ ->
            calls += 1
            sentPassword = password
        }

        val field = maskedField()
        field.performTextInput("hunter2")
        rule.waitForIdle()
        field.performImeAction()
        rule.waitForIdle()

        rule.runOnIdle {
            assertEquals(1, calls)
            assertEquals("hunter2", sentPassword)
        }
    }

    /**
     * A thumb's double tap: both taps are queued and dispatched before any
     * recomposition, which is the test API's documented behaviour and the exact
     * window the latch exists for. The button is still enabled when the second
     * tap arrives (its pointer node is only updated on recomposition), so the
     * second tap does reach `submit`, and only the latch can stop it.
     *
     * `assertIsEnabled` first is deliberate: if the form ever lapsed into a
     * state where the button was dead before the first tap, this test would say
     * so on that line rather than reporting "submitted zero times", which is
     * also what a tap that missed the window looks like.
     */
    @Test
    fun `a double tap on Connect submits once`() {
        var calls = 0
        showSetup { _, _, _, _, _ -> calls += 1 }

        val connect = rule.onNodeWithText("Connect")
        connect.assertIsEnabled()
        // The form scrolls on a short screen; a touch at a point outside the
        // viewport reaches nothing, which is what made this test read as zero
        // submissions before the height above and this scroll were added.
        connect.performScrollTo()
        connect.performTouchInput {
            click()
            click()
        }

        rule.runOnIdle { assertEquals(1, calls) }
    }

    /**
     * The latch itself, independent of touch dispatch and of the viewport. A
     * disabled `clickable` keeps its semantic action and that action calls the
     * handler, so two semantic activations both reach `submit` — the case a
     * pointer-level "disabled means no second tap" would hide. This is the test
     * that stays red if the `submitting` guard is removed; without it the count
     * is two.
     */
    @Test
    fun `two activations that both reach the handler submit once`() {
        var calls = 0
        showSetup { _, _, _, _, _ -> calls += 1 }

        val connect = rule.onNodeWithText("Connect")
        connect.performSemanticsAction(SemanticsActions.OnClick)
        connect.performSemanticsAction(SemanticsActions.OnClick)

        rule.runOnIdle { assertEquals(1, calls) }
    }

    /** The other order the report names: the IME's `Done`, then a tap. */
    @Test
    fun `Done then a tap submits once`() {
        var calls = 0
        showSetup { _, _, _, _, _ -> calls += 1 }

        val field = maskedField()
        field.performTextInput("hunter2")
        field.performImeAction()
        rule.waitForIdle()
        val connect = rule.onNodeWithText("Connect")
        connect.performScrollTo()
        connect.performClick()

        rule.runOnIdle { assertEquals(1, calls) }
    }
}
