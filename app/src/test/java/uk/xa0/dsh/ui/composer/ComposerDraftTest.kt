package uk.xa0.dsh.ui.composer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.text.TextRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication

/**
 * The two rules about the draft that are not the widget: when a failed send may
 * put its text back, and what the saved form of the field has to carry.
 *
 * ## Restore versus undo
 *
 * The rule is [restoreSendFailure], and the case worth naming is the one that
 * did not exist before undo: a send clears the composer, the reader types
 * something new and then undoes it, and only then the send's failure arrives.
 * The box is empty, but it is not the box the send emptied — `canRedo` is true,
 * the rule refuses the restore, and the failure must not refill a box the reader
 * emptied on purpose. These tests ask the rule directly; that the composer arms
 * it and reads `canRedo` into it is read from `ui/ChatScreen.kt`, which no unit
 * test can reach.
 *
 * ## The saved form
 *
 * The draft is `rememberSaveable`, so the state has to survive rotation and
 * process death through Android's saved instance state. `TextFieldState.Saver` is
 * the platform's own representation for that, and this round-trips it by hand —
 * no activity recreation needed — to show that the text and the caret come back.
 * `ComposerUndoTest` round-trips the same saver after typing, which is where the
 * undo history in it is shown to survive too.
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

    /**
     * A `SaverScope` that approves everything, written as an object rather than a
     * lambda so the test does not depend on the interface being a Kotlin `fun`
     * interface.
     */
    private object AnythingScope : SaverScope {
        override fun canBeSaved(value: Any): Boolean = true
    }

    /** The saver `rememberSaveable` uses keeps the text and the caret. */
    @Test
    fun `the field's own saver keeps the text and the selection`() {
        val state = TextFieldState("hello /mod", initialSelection = TextRange(10))
        val saved = requireNotNull(TextFieldState.Saver.save(AnythingScope, state))
        val restored = requireNotNull(TextFieldState.Saver.restore(saved))

        assertEquals("hello /mod", restored.text.toString())
        assertEquals(TextRange(10), restored.selection)
    }
}
