@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package uk.xa0.dsh.ui.composer

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/**
 * Whether a failed send may put its text back into the composer.
 *
 * A submission clears the box, and if it turns out never to have reached a
 * session the host's text comes back through `UiState.draftRestore`. The old rule
 * asked only whether the box was empty, which was a fair proxy for "nothing has
 * been typed since the send" while an empty box could only come from a send or
 * from deleting every character.
 *
 * Undo broke that proxy: it can empty the box too, and an undo is a deliberate
 * edit even when the text it leaves behind is empty. So the restore is keyed on
 * **the send itself**. [armedBySend] is set by the send that cleared the box;
 * [undoneSinceSend] is the field's `canRedo`, which is true exactly when an undo
 * has happened since the last edit; the text must also still be empty, because an
 * empty box is the only place with nothing to overwrite. Typing needs no third
 * fact: a non-empty box already fails the rule.
 *
 * [undoneSinceSend] is passed in as a value, read at the moment of the decision,
 * rather than observed ahead of it. A failed send's restore and an undo can land
 * in the same frame, and a flag that a watcher clears when it gets around to
 * running can be overtaken by the order two coroutines resume in; a read at the
 * decision cannot.
 *
 * The consequence, stated plainly: "empty because the reader undid it" is not the
 * same fact as "empty because the send cleared it", and only the second may be
 * refilled. Emptying the box by hand still restores, as it always has — that is
 * the reader asking for the space back, not history being rewound.
 */
fun restoreSendFailure(
    armedBySend: Boolean,
    undoneSinceSend: Boolean,
    draftText: CharSequence,
): Boolean = armedBySend && !undoneSinceSend && draftText.isEmpty()

/**
 * Applies a failed send's restore to [state], and answers whether it did.
 *
 * The rule is [restoreSendFailure] and this function is the only thing that acts on
 * it, so the two cannot drift: an undo, a box with anything in it, or a send that was
 * not the one that cleared the box all leave [state] untouched, and the answer says
 * so — which is what lets the failure's banner claim the text is back only when it
 * actually is.
 *
 * The undo state is read inside the call, at the moment of the decision, for the
 * reason given on [restoreSendFailure]. The caret goes after the last character: a
 * restored draft is one the reader was in the middle of writing, so it comes back
 * ready to continue rather than parked at the start.
 */
fun applySendRestore(
    state: TextFieldState,
    text: String,
    armedBySend: Boolean,
): Boolean {
    if (!restoreSendFailure(armedBySend, state.undoState.canRedo, state.text)) return false
    state.edit {
        replace(0, length, text)
        selection = TextRange(length)
    }
    return true
}
