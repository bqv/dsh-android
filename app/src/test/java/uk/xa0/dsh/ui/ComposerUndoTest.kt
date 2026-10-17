package uk.xa0.dsh.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.saveable.SaverScope
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
import org.junit.Assert.assertTrue
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
 *  * Ctrl+Z and Ctrl+Shift+Z reach the same history through the field's preview
 *    key handler.
 *
 * ## What these cannot show
 *
 * That the seat *looks* enabled: the harness cannot read colour, so only the
 * semantic enabled state is asserted, not the tint that carries it on screen.
 * The chords are injected through `performKeyPress`, which is not an IME and not
 * a physical keyboard — whether a particular keyboard reports Ctrl+Shift+Z with
 * the shift bit set is a device claim, and the mapping itself is covered without
 * a window in `components/ComposerShortcutTest`. Undo history surviving a
 * rotation or a process death is `TextFieldState.Saver`'s job; the saved
 * representation is round-tripped in the last test here and in
 * `composer/ComposerDraftTest`, but no activity is actually recreated.
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
     * A `SaverScope` that approves everything, written as an object rather than a
     * lambda so the test does not depend on the interface being a Kotlin `fun`
     * interface.
     */
    private object AnythingScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    /**
     * One Ctrl chord, as a hardware keyboard reports it.
     *
     * `performKeyPress` takes Compose's `KeyEvent`, which is a value class wrapping
     * the platform event, so the Android event is the argument to that wrapper and
     * not to the test helper. Handing the platform event straight to
     * `performKeyPress` is a type mismatch; the JVM signature makes them look
     * interchangeable, and they are not.
     */
    private fun ctrl(keyCode: Int, shift: Boolean = false): KeyEvent = KeyEvent(
        AndroidKeyEvent(
            0L,
            0L,
            AndroidKeyEvent.ACTION_DOWN,
            keyCode,
            0,
            if (shift) {
                AndroidKeyEvent.META_CTRL_ON or AndroidKeyEvent.META_SHIFT_ON
            } else {
                AndroidKeyEvent.META_CTRL_ON
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

        field.performKeyPress(ctrl(KeyEvent.KEYCODE_Z))
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("", state.text.toString()) }

        field.performKeyPress(ctrl(KeyEvent.KEYCODE_Z, shift = true))
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
        field.performKeyPress(ctrl(KeyEvent.KEYCODE_Z))
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("", state.text.toString()) }

        field.performKeyPress(ctrl(KeyEvent.KEYCODE_Y))
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("hello", state.text.toString()) }
    }

    /**
     * The saved form keeps the undo history, not only the text. That is what
     * `rememberSaveable(draftKey, saver = TextFieldState.Saver)` in `ChatScreen`
     * rests on, and it is the half of "survives rotation and process death" that
     * can be checked without recreating an activity: round-trip the representation
     * Android would have parcelled and undo it.
     *
     * What this cannot show is the other half — that the activity really is
     * recreated and the registry really is asked to save. That is the platform's
     * contract with `rememberSaveable`, not this app's code.
     */
    @OptIn(ExperimentalFoundationApi::class)
    @Test
    fun `the saved form keeps the undo history`() {
        val state = TextFieldState()
        show(state)
        rule.onNode(hasSetTextAction()).performTextInput("hello")
        rule.waitForIdle()

        rule.runOnIdle {
            val saved = requireNotNull(with(AnythingScope) { TextFieldState.Saver.save(state) })
            val restored = requireNotNull(TextFieldState.Saver.restore(saved))

            assertEquals("hello", restored.text.toString())
            assertTrue("the undo history should come back with the text", restored.undoState.canUndo)
            restored.undoState.undo()
            assertEquals("", restored.text.toString())
        }
    }
}
