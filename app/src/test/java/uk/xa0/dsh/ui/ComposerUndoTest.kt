package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.Composer
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * Undo and redo in the composer, through both affordances.
 *
 * The migration to Compose's stateful text API is what makes these possible at
 * all: `TextFieldState.undoState` keeps the history, and the composer exposes it
 * as a 28dp seat in the action row and as the Ctrl chords a hardware keyboard
 * sends. What is pinned here:
 *
 *  * the seat exists before there is any history, and is disabled rather than
 *    absent — the row must not reflow when it becomes usable;
 *  * a tap undoes one edit and the seat becomes Redo when the undo stack empties,
 *    which is how redo keeps a home without a second 28dp control;
 *  * Ctrl+Z, Ctrl+Shift+Z and Ctrl+Y reach the same history through the field's
 *    preview key handler.
 *
 * ## What these cannot show
 *
 * That the seat *looks* enabled: the harness cannot read colour, so only the
 * semantic enabled state is asserted, not the tint that carries it on screen.
 * The chords are injected through `performKeyPress`, which is not an IME and not
 * a physical keyboard — whether a particular keyboard reports Ctrl+Shift+Z with
 * the shift bit set is a device claim, and the mapping itself is covered without
 * a window in `components/ComposerShortcutTest`.
 *
 * **Persistence is unverified.** `ChatScreen` seeds the draft with
 * `rememberSaveable(draftKey, saver = TextFieldState.Saver)`, and the saver's
 * claim — that it carries the text, the caret and the undo history — is read
 * from its bytecode, not tested. A round-trip test that called
 * `TextFieldState.Saver.save` directly was written and then removed: `save` is a
 * member extension on `SaverScope`, and two call shapes would not resolve in
 * this compiler, so the test was deleted rather than contorted for an assertion
 * about the platform's own contract. Neither rotation nor process death is
 * exercised by this file.
 *
 * The display is a phone's: the composer sits at the top of this box, and every
 * touch below is a real injection, so the window has to be tall enough to hold
 * the control it aims at.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w411dp-h891dp")
class ComposerUndoTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * The real composer with the real theme, in the chat screen's 16dp inset.
     *
     * A plain function around `setContent`, following `FilesPanelTest`: a
     * `@Composable` helper nested in the theme would resolve to the theme's own
     * member and fail confusingly.
     */
    private fun show(state: TextFieldState) {
        rule.setContent {
            DshTheme {
                Box(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.xl)) {
                    Composer(
                        state = state,
                        onSend = {},
                        onStop = {},
                        running = false,
                        modelTrigger = null,
                        onModelClick = {},
                        permissionLabel = null,
                        onPermissionClick = {},
                        planActive = false,
                        onExitPlan = {},
                        contextPercent = 0,
                        contextTokens = 0,
                        contextWindow = 0,
                        contextBreakdown = null,
                        attachments = emptyList(),
                        onToggleCommands = {},
                        onRemoveAttachment = {},
                    )
                }
            }
        }
        rule.waitForIdle()
    }

    /**
     * One Ctrl chord, as a keyboard reports it.
     *
     * `performKeyPress` takes Compose's `KeyEvent`, a value class wrapping the
     * platform event, so the platform event is the wrapper's argument and not the
     * helper's; handing the platform event straight to `performKeyPress` is a type
     * mismatch the JVM signature hides. The Android class is written out in full at
     * every use: both key classes are named `KeyEvent`, and the call sites once
     * said `KeyEvent.KEYCODE_Z`, which is the Compose type imported above and has
     * no key codes. A fully qualified name cannot be shadowed that way.
     */
    private fun ctrl(keyCode: Int, shift: Boolean = false): KeyEvent = KeyEvent(
        android.view.KeyEvent(
            0L,
            0L,
            android.view.KeyEvent.ACTION_DOWN,
            keyCode,
            0,
            if (shift) {
                android.view.KeyEvent.META_CTRL_ON or android.view.KeyEvent.META_SHIFT_ON
            } else {
                android.view.KeyEvent.META_CTRL_ON
            },
        ),
    )

    /**
     * The seat is composed before any history exists. `assertIsNotEnabled` is the
     * assertion that it cannot be tapped yet; the existence check beside it is the
     * one that matters for layout — a control that appears with the first keystroke
     * would push the chips along the row.
     */
    @Test
    fun `the history seat is present and disabled with nothing to undo`() {
        show(TextFieldState())

        rule.onNodeWithContentDescription("Undo").assertExists()
        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
    }

    /** Undo removes the typed text; with the undo stack empty the seat is Redo. */
    @Test
    fun `undo drops the typed text and the seat offers redo`() {
        val state = TextFieldState()
        show(state)

        rule.onNode(hasSetTextAction()).performTextInput("hello")
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("hello", state.text.toString()) }

        rule.onNodeWithContentDescription("Undo").assertIsEnabled().performClick()
        rule.waitForIdle()

        rule.runOnIdle { assertEquals("", state.text.toString()) }
        rule.onNodeWithContentDescription("Redo").assertIsEnabled()
    }

    /** Redo is the same history walked forward, from the seat the undo left. */
    @Test
    fun `redo brings the undone text back`() {
        val state = TextFieldState()
        show(state)

        rule.onNode(hasSetTextAction()).performTextInput("hello")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Undo").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Redo").performClick()
        rule.waitForIdle()

        rule.runOnIdle { assertEquals("hello", state.text.toString()) }
    }

    /**
     * The disabled seat and the enabled one occupy the same bounds. That is the
     * whole reason it is drawn disabled instead of omitted: the row is measured
     * once, and the first keystroke cannot shift the controls beside it.
     *
     * The box starts with text but no history — a seeded `TextFieldState` is a
     * programmatic edit, so the seat is disabled — and one typed character makes
     * it enabled. Starting with text keeps the placeholder out of this comparison:
     * the empty box draws a ghost line that the filled one does not, and that is a
     * different measurement from the one the seat's own state changes.
     */
    @Test
    fun `the seat keeps its bounds when it becomes enabled`() {
        val state = TextFieldState("hello")
        show(state)

        rule.onNodeWithContentDescription("Undo").assertIsNotEnabled()
        val before = rule.onNodeWithContentDescription("Undo").getBoundsInRoot()
        rule.onNode(hasSetTextAction()).performTextInput("!")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Undo").assertIsEnabled()
        val after = rule.onNodeWithContentDescription("Undo").getBoundsInRoot()

        assertEquals(before, after)
    }

    /** Ctrl+Z is the chord a keyboard sends; it must reach the same history. */
    @Test
    fun `ctrl z undoes and ctrl shift z redoes`() {
        val state = TextFieldState()
        show(state)

        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("hello")
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("hello", state.text.toString()) }

        field.performKeyPress(ctrl(android.view.KeyEvent.KEYCODE_Z))
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("", state.text.toString()) }

        field.performKeyPress(ctrl(android.view.KeyEvent.KEYCODE_Z, shift = true))
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("hello", state.text.toString()) }
    }

    /** Ctrl+Y is the other spelling of redo, and is taken the same way. */
    @Test
    fun `ctrl y also redoes`() {
        val state = TextFieldState()
        show(state)

        val field = rule.onNode(hasSetTextAction())
        field.performTextInput("hello")
        rule.waitForIdle()
        field.performKeyPress(ctrl(android.view.KeyEvent.KEYCODE_Z))
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("", state.text.toString()) }

        field.performKeyPress(ctrl(android.view.KeyEvent.KEYCODE_Y))
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("hello", state.text.toString()) }
    }
}
