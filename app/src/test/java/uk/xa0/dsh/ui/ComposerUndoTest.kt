package uk.xa0.dsh.ui

import android.content.Context
import android.view.View
import android.view.inputmethod.BaseInputConnection
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performKeyPress
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.Composer
import uk.xa0.dsh.ui.components.ComposerImeUndoConnection
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * Undo and redo in the composer, through the two routes that remain.
 *
 * `TextFieldState.undoState` keeps the history of edits the **reader** made, and
 * two routes reach it: the Ctrl chords a hardware keyboard sends, and the software
 * keyboard's own undo and redo, which arrive as `android.R.id.undo`/`redo`
 * context-menu actions and are wired to the history by `ComposerImeUndo`. The
 * in-app seat that used to sit beside `+` is gone — the keyboard's route is the
 * one the user now uses — so what is pinned here is that both remaining routes
 * move the same history:
 *
 *  * the keyboard's undo action drops the typed text, and its redo action brings
 *    it back;
 *  * Ctrl+Z, Ctrl+Shift+Z and Ctrl+Y reach the same history through the field's
 *    preview key handler.
 *
 * ## How the keyboard route is driven here
 *
 * The JVM harness never starts an IME session, so the connection `ComposerImeUndo`
 * installs around the real field cannot be reached from a test. What is driven
 * instead is the same [ComposerImeUndoConnection] over the state the composed
 * field writes to, with the composer's two history steps as its callbacks: the
 * `performContextMenuAction(android.R.id.undo)` call below is the call the
 * keyboard makes. That pins the history and the wrapper's call values; that the
 * wrapper is installed in the session is not shown by any test on this harness.
 *
 * ## What these cannot show
 *
 * That a particular keyboard sends these ids: that is a device claim, observed on
 * the phone, and the bridge's own boundary is pinned in
 * `components/ComposerImeUndoTest`. The chords are injected through
 * `performKeyPress`, which is not an IME and not a physical keyboard — whether a
 * particular keyboard reports Ctrl+Shift+Z with the shift bit set is a device
 * claim, and the mapping itself is covered without a window in
 * `components/ComposerShortcutTest`.
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
 * The display is a phone's, and the composer is composed with no model trigger,
 * so the action row cannot affect what is under test.
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
     * The keyboard's route, over the composed composer's own state.
     *
     * The target is a real `BaseInputConnection` over a plain view: it is the
     * delegation target, and nothing here needs it to edit text. `fullEditor =
     * false` keeps it from asking the view for an editable it does not have. Undo
     * and redo call the same two `undoState` steps the composer's bridge calls.
     */
    private fun keyboardConnection(state: TextFieldState): ComposerImeUndoConnection {
        val context: Context = ApplicationProvider.getApplicationContext()
        return ComposerImeUndoConnection(
            target = BaseInputConnection(View(context), false),
            onUndo = { state.undoState.undo() },
            onRedo = { state.undoState.redo() },
        )
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

    /** The keyboard's undo action removes the typed edit from the field. */
    @Test
    fun `the keyboard's undo drops the typed text`() {
        val state = TextFieldState()
        show(state)

        rule.onNode(hasSetTextAction()).performTextInput("hello")
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("hello", state.text.toString()) }

        val keyboard = keyboardConnection(state)
        assertTrue(rule.runOnIdle { keyboard.performContextMenuAction(android.R.id.undo) })
        rule.waitForIdle()

        rule.runOnIdle { assertEquals("", state.text.toString()) }
    }

    /** Redo is the same history walked forward, through the keyboard's own action. */
    @Test
    fun `the keyboard's redo brings the undone text back`() {
        val state = TextFieldState()
        show(state)

        rule.onNode(hasSetTextAction()).performTextInput("hello")
        rule.waitForIdle()
        val keyboard = keyboardConnection(state)
        rule.runOnIdle { keyboard.performContextMenuAction(android.R.id.undo) }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("", state.text.toString()) }

        assertTrue(rule.runOnIdle { keyboard.performContextMenuAction(android.R.id.redo) })
        rule.waitForIdle()

        rule.runOnIdle { assertEquals("hello", state.text.toString()) }
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
