package uk.xa0.dsh.ui.components

import android.util.Log
import android.view.KeyEvent
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
 * PROBE — temporary. Delete this file and every `PROBE` line once the question
 * is answered.
 *
 * The composer's in-app undo and the Ctrl chords are ours; the software
 * keyboard's own undo is not. This file exists to find out which of the three
 * possible deliveries it uses, because they need different fixes and only the
 * phone can say which one is happening:
 *
 *  * a context-menu action — `performContextMenuAction(android.R.id.undo)`,
 *    the standard TextServices path. Compose 1.7.0's input connection returns
 *    false for that id and does nothing, so this probe installs a wrapper
 *    through the one interception point Compose exposes, logs the id and
 *    delegates;
 *  * a key event — `sendKeyEvent` with `KEYCODE_Z` and Ctrl. The wrapper logs
 *    those too, and the field's preview handler logs every event it sees;
 *  * the IME editing its own composing text. Then the connection only ever
 *    sees `setComposingText`/`commitText`/`deleteSurroundingText`, and nothing
 *    the app can do will undo committed text from the keyboard's button.
 *
 * Everything is `Log.i` under the one tag [IME_UNDO_PROBE_TAG], which is a
 * release build's logcat too — the app is not debuggable on the user's phone.
 */
const val IME_UNDO_PROBE_TAG = "ImeUndoProbe"

/** One line, one tag: `adb logcat -s ImeUndoProbe`. */ // PROBE — temporary
fun imeUndoProbe(message: String) {
    Log.i(IME_UNDO_PROBE_TAG, message) // PROBE — temporary
}

/**
 * Logs an undo and then does it, so every route into the history is visible in
 * the same log as the route that arrived.
 */ // PROBE — temporary
fun TextFieldState.probeUndo(source: String) {
    imeUndoProbe("undo($source) canUndo=${undoState.canUndo}") // PROBE — temporary
    undoState.undo()
}

/** The redo half of [probeUndo]. */ // PROBE — temporary
fun TextFieldState.probeRedo(source: String) {
    imeUndoProbe("redo($source) canRedo=${undoState.canRedo}") // PROBE — temporary
    undoState.redo()
}

/**
 * The field's own InputConnection, with the interesting calls logged.
 *
 * `InputConnectionWrapper` delegates every method to the real connection, and
 * each override here logs and then delegates: this must not change what the
 * field does, or the observation would be of the probe rather than of the IME.
 */ // PROBE — temporary
private class ProbeInputConnection(target: InputConnection) :
    InputConnectionWrapper(target, false) {

    override fun performContextMenuAction(id: Int): Boolean {
        imeUndoProbe("performContextMenuAction($id)") // PROBE — temporary
        return super.performContextMenuAction(id)
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        imeUndoProbe(
            "sendKeyEvent(keyCode=${event.keyCode} metaState=${event.metaState} action=${event.action})",
        ) // PROBE — temporary
        return super.sendKeyEvent(event)
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        imeUndoProbe(
            "commitText(length=${text?.length ?: 0} newCursorPosition=$newCursorPosition)",
        ) // PROBE — temporary
        return super.commitText(text, newCursorPosition)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        imeUndoProbe(
            "setComposingText(length=${text?.length ?: 0} newCursorPosition=$newCursorPosition)",
        ) // PROBE — temporary
        return super.setComposingText(text, newCursorPosition)
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        imeUndoProbe(
            "deleteSurroundingText(before=$beforeLength after=$afterLength)",
        ) // PROBE — temporary
        return super.deleteSurroundingText(beforeLength, afterLength)
    }

    override fun finishComposingText(): Boolean {
        imeUndoProbe("finishComposingText()") // PROBE — temporary
        return super.finishComposingText()
    }
}

/**
 * Wraps [content] — a single text field — so that the connection the IME is
 * given is a [ProbeInputConnection].
 *
 * This is the only place a Compose text field's `InputConnection` can be
 * reached in 1.7.0: the stateful field starts its session with
 * `establishTextInputSession`, and `InterceptPlatformTextInput` intercepts that
 * call and lets the request be wrapped. The request is passed straight through
 * to the real handler, so the session and the connection are the field's own.
 */ // PROBE — temporary
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ProbeComposerInput(content: @Composable () -> Unit) {
    val interceptor = remember {
        object : PlatformTextInputInterceptor {
            override suspend fun interceptStartInputMethod(
                request: PlatformTextInputMethodRequest,
                nextHandler: PlatformTextInputSession,
            ): Nothing {
                imeUndoProbe("interceptor: startInputMethod") // PROBE — temporary
                return nextHandler.startInputMethod(
                    PlatformTextInputMethodRequest { outAttrs ->
                        imeUndoProbe(
                            "createInputConnection(" +
                                "inputType=0x${Integer.toHexString(outAttrs.inputType)} " +
                                "imeOptions=0x${Integer.toHexString(outAttrs.imeOptions)})",
                        ) // PROBE — temporary
                        ProbeInputConnection(request.createInputConnection(outAttrs))
                    },
                )
            }
        }
    }
    InterceptPlatformTextInput(interceptor) { content() }
}
