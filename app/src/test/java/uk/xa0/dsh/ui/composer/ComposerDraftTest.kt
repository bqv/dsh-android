package uk.xa0.dsh.ui.composer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication

/**
 * The rule about the draft that is not the widget: when a failed send may put its
 * text back.
 *
 * ## Restore versus undo
 *
 * The rule is [restoreSendFailure], and the case worth naming is the one that did
 * not exist before undo: a send clears the composer, the reader types something
 * new and then undoes it, and only then the send's failure arrives. The box is
 * empty, but it is not the box the send emptied — `canRedo` is true, the rule
 * refuses the restore, and the failure must not refill a box the reader emptied
 * on purpose. These tests ask the rule directly; that the composer arms it and
 * reads `canRedo` into it is read from `ui/ChatScreen.kt`, which no unit test can
 * reach.
 *
 * ## What used to be here
 *
 * A round-trip of `TextFieldState.Saver` stood beside these, asserting that the
 * text and the caret come back through the saved form. It was removed: `save` is
 * a member extension on `SaverScope` and two call shapes would not resolve in this
 * compiler, and the assertion was about the platform's own contract rather than
 * about this app's code. So the saver's behaviour — text, caret and undo history
 * surviving rotation and process death — is read from its bytecode and is
 * **unverified by any test**; `ComposerUndoTest` says the same in its own words.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ComposerDraftTest {

    /** The ordinary failure: the send emptied the box and the box is still empty. */
    @Test
    fun `a failed send is restored into the box that send emptied`() {
        assertTrue(restoreSendFailure(armedBySend = true, undoneSinceSend = false, draftText = ""))
    }

    /** Nothing restores into a box that no send armed, empty or not. */
    @Test
    fun `a box no send armed is never refilled`() {
        assertFalse(restoreSendFailure(armedBySend = false, undoneSinceSend = false, draftText = ""))
    }

    /**
     * The undo case. The box is empty, but not because this send cleared it: an
     * undo emptied it, which is an edit in its own right. `canRedo` is what says
     * so.
     */
    @Test
    fun `an empty box left by undo is not refilled`() {
        assertFalse(restoreSendFailure(armedBySend = true, undoneSinceSend = true, draftText = ""))
    }

    /** Nothing is ever restored over text the reader has put there since. */
    @Test
    fun `nothing is restored over text that is already there`() {
        assertFalse(restoreSendFailure(armedBySend = true, undoneSinceSend = false, draftText = "typed since"))
        assertFalse(restoreSendFailure(armedBySend = true, undoneSinceSend = true, draftText = "typed since"))
    }
}
