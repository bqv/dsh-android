package uk.xa0.dsh.ui.components

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputSession

/**
 * Gives the software keyboard's own undo and redo the composer's history.
 *
 * ## Why this exists
 *
 * Compose 1.7.0's stateful text field is served by
 * `StatelessInputConnection.android.kt` in `androidx.compose.foundation`. Its
 * `performContextMenuAction` knows `selectAll`, `cut`, `copy`, `paste`,
 * `startSelectingText`, `stopSelectingText`, `copyUrl` and `switchInputMethod`
 * — and returns false for every id, including `android.R.id.undo` and
 * `android.R.id.redo`, which it does not handle at all. The legacy
 * `RecordingInputConnection.android.kt` is the same. So an IME that asks the
 * field to undo, the standard TextServices path, is told "not handled" and the
 * edit never happens.
 *
 * The field's own Ctrl+Z bindings are not this path: those are key events, and
 * `Composer`'s preview handler already takes them. This bridge is for the
 * keyboard's toolbar button, which has no key event to send.
 *
 * ## How
 *
 * The field starts its input session with `establishTextInputSession`, and
 * `InterceptPlatformTextInput` intercepts that call. The intercepted request is
 * wrapped in one that hands back [ComposerImeUndoConnection]; the request itself
 * is passed straight through, so the session, the field and every other
 * `InputConnection` call are Compose's own. Undo and redo are claimed and routed
 * to `TextFieldState.undoState`, which is the same history the in-app control and
 * the Ctrl chords use. Everything else, including `copy` and `paste`, falls
 * through to the field.
 *
 * The connection's callbacks are delivered on the main thread, but a wrapper
 * that calls back into snapshot state is not the place to rely on that: if it
 * ever arrives elsewhere the action is posted to the main thread, and the
 * keyboard is still told the action was handled.
 *
 * ## What it cannot do
 *
 * A keyboard that undoes only its own composing text, instead of asking the
 * field to undo, never reaches this or any other app-side hook: it edits the
 * text it is composing and nothing it has already committed can come back. And
 * a keyboard with no undo control at all has nothing to route — that is why the
 * in-app seat stays.
 *
 * `@ExperimentalComposeUiApi` is the only API Compose 1.7.0 offers for reaching
 * a field's `InputConnection` at all; the alternative is no IME undo path.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ComposerImeUndo(state: TextFieldState, content: @Composable () -> Unit) {
    // Keyed on the state: the interceptor is a stable object for the life of the
    // draft. A new draft is a new history, and the session restart that follows
    // is the same restart a new field would cause anyway.
    val interceptor = remember(state) {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession,
            ): Nothing = nextHandler.startInputMethod(
                PlatformTextInputMethodRequest { outAttrs ->
                    ComposerImeUndoConnection(
                        target = request.createInputConnection(outAttrs),
                        onUndo = { state.undoState.undo() },
                        onRedo = { state.undoState.redo() },
                    )
                },
            )
        }
    }
    InterceptPlatformTextInput(interceptor) { content() }
}

/**
 * The field's own connection with `android.R.id.undo` and `android.R.id.redo`
 * answered from the composer's history.
 *
 * Public rather than internal for the unit test: the test source set is not a
 * friend of `main`, so `internal` is invisible there.
 *
 * `InputConnectionWrapper` delegates every other member to [target], which keeps
 * the bridge transparent: this class only exists for the two ids Compose drops.
 */
class ComposerImeUndoConnection(
    target: InputConnection,
    private val onUndo: () -> Unit,
    private val onRedo: () -> Unit,
) : InputConnectionWrapper(target, false) {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }

    override fun performContextMenuAction(id: Int): Boolean = when (id) {
        android.R.id.undo -> claim(onUndo)
        android.R.id.redo -> claim(onRedo)
        else -> super.performContextMenuAction(id)
    }

    /**
     * Runs one history step on the main thread and reports it as handled.
     *
     * Claimed even when the history is empty: the request was for this field's
     * text, and telling the keyboard "not handled" would invite it to try
     * something else that cannot see the history either.
     */
    private fun claim(action: () -> Unit): Boolean {
        val main = Looper.getMainLooper()
        val current = Looper.myLooper()
        if (current == null || current == main) action() else mainHandler.post(action)
        return true
    }
}
