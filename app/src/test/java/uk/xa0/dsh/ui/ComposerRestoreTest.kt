@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.Composer
import uk.xa0.dsh.ui.composer.applySendRestore
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * What happens to the box when a send's failure hands the text back.
 *
 * [uk.xa0.dsh.ui.composer.restoreSendFailure] is the rule and
 * [applySendRestore] is the only thing that acts on it; this file drives the second
 * against a real `TextFieldState`, because the fact the rule depends on — `canRedo`
 * after an undo — only exists once a reader's own edit has gone through a composed
 * field. A programmatic edit wipes the history, which is why the send's clear alone
 * can never be undone and why this needs the field rather than a bare state.
 *
 * The four cases:
 *
 *  * the send's failure puts its text back, with the caret after it;
 *  * text typed since is not overwritten, and the answer says nothing was restored;
 *  * a box emptied by the reader's own undo is not refilled;
 *  * a send that carried no text arms nothing, so nothing is restored.
 *
 * ## What these cannot show
 *
 * That the send **reaches** this at all. `DshViewModel.dispatchSend` is what hands
 * the text over, and nothing on the JVM harness can construct the view model — it
 * wants an `Application`, a configured host and a network. The wiring is therefore
 * a read of the source, not a test: every prompt failure calls `restoreDraft`, and
 * the failure shapes (a refused envelope, a 504 page, a timeout, a dropped socket)
 * all arrive in the same `onFailure` block.
 *
 * Nor can this show what the banner says: that is `consumeDraftRestore`'s work in
 * the view model, in the same untestable class. And nothing here runs on a device;
 * the composer's layout is exercised by `ComposerUndoTest`, which this borrows its
 * harness from.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w411dp-h891dp")
class ComposerRestoreTest {

    @get:Rule
    val rule = createComposeRule()

    /**
     * The real composer with the real theme, in the chat screen's 16dp inset.
     *
     * A plain function around `setContent`, following `ComposerUndoTest`: a
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
     * The clear a send performs, as `ChatScreen.takeDraftForSend` performs it.
     *
     * The same `state.edit` call, so it clears the history in the same way: a
     * message that has been sent must not come back on undo, and a box cleared by
     * an edit is what the restore is allowed to refill.
     */
    private fun sendClears(state: TextFieldState) {
        rule.runOnIdle { state.edit { replace(0, length, "") } }
        rule.waitForIdle()
    }

    /** The ordinary failure: the send emptied the box and the box is still empty. */
    @Test
    fun `a failed send puts its text back`() {
        val state = TextFieldState()
        show(state)
        rule.onNode(hasSetTextAction()).performTextInput("retry me")
        rule.waitForIdle()
        sendClears(state)
        rule.runOnIdle { assertEquals("", state.text.toString()) }

        val restored = rule.runOnIdle { applySendRestore(state, "retry me", armedBySend = true) }

        assertTrue(restored)
        rule.runOnIdle {
            assertEquals("retry me", state.text.toString())
            // The caret comes back after the last character, ready to continue.
            assertEquals("retry me".length, state.selection.start)
            assertEquals("retry me".length, state.selection.end)
        }
    }

    /** Whatever the reader has typed since is theirs: the failure does not touch it. */
    @Test
    fun `text typed since the send is not overwritten`() {
        val state = TextFieldState()
        show(state)
        rule.onNode(hasSetTextAction()).performTextInput("the first try")
        rule.waitForIdle()
        sendClears(state)
        rule.onNode(hasSetTextAction()).performTextInput("a new thought")
        rule.waitForIdle()

        val restored = rule.runOnIdle { applySendRestore(state, "the first try", armedBySend = true) }

        assertFalse(restored)
        rule.runOnIdle { assertEquals("a new thought", state.text.toString()) }
    }

    /**
     * The undo case: the box is empty, but the reader emptied it.
     *
     * `canRedo` is what tells the two empty boxes apart, and it is read inside
     * [applySendRestore] at the moment of the decision.
     */
    @Test
    fun `an empty box left by undo is not refilled`() {
        val state = TextFieldState()
        show(state)
        rule.onNode(hasSetTextAction()).performTextInput("retry me")
        rule.waitForIdle()

        rule.runOnIdle { state.undoState.undo() }
        rule.waitForIdle()
        rule.runOnIdle { assertEquals("", state.text.toString()) }

        val restored = rule.runOnIdle { applySendRestore(state, "retry me", armedBySend = true) }

        assertFalse(restored)
        rule.runOnIdle { assertEquals("", state.text.toString()) }
    }

    /** An attachment-only send carried no text, so there is nothing to hand back. */
    @Test
    fun `a send that carried no text arms nothing`() {
        val state = TextFieldState()
        show(state)

        val restored = rule.runOnIdle { applySendRestore(state, "anything", armedBySend = false) }

        assertFalse(restored)
        rule.runOnIdle { assertEquals("", state.text.toString()) }
    }
}
