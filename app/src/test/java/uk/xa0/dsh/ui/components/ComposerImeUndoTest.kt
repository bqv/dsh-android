package uk.xa0.dsh.ui.components

import android.content.Context
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.InputConnection
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication

/**
 * The bridge that answers the software keyboard's own undo.
 *
 * Compose 1.7.0's stateful field returns false for `android.R.id.undo` and
 * `android.R.id.redo` without touching the history, so an IME's undo button did
 * nothing. [ComposerImeUndoConnection] is the answer to those two ids and
 * nothing else; this file pins that boundary.
 *
 * ## What these cannot show
 *
 * That a particular keyboard sends these ids. That is a device claim, observed
 * on the phone rather than here: the test only proves that *if* the IME asks the
 * field to undo, the composer's history moves and the action is reported
 * handled. Nor can it show that the interception is installed around the field —
 * `ComposerUndoTest` composes the real composer, which is where that wiring is
 * exercised, but the JVM harness never starts an IME session of its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ComposerImeUndoTest {

    /**
     * A real `BaseInputConnection` over a plain view: it is the delegation
     * target, and nothing here needs it to edit text. `fullEditor = false` keeps
     * it from asking the view for an editable it does not have.
     */
    private fun target(): InputConnection {
        val context: Context = ApplicationProvider.getApplicationContext()
        return BaseInputConnection(View(context), false)
    }

    @Test
    fun `the keyboard's undo moves the composer's history`() {
        var undos = 0
        val connection = ComposerImeUndoConnection(
            target = target(),
            onUndo = { undos++ },
            onRedo = {},
        )

        assertTrue(connection.performContextMenuAction(android.R.id.undo))
        assertEquals(1, undos)
    }

    @Test
    fun `the keyboard's redo moves it forward`() {
        var redos = 0
        val connection = ComposerImeUndoConnection(
            target = target(),
            onUndo = {},
            onRedo = { redos++ },
        )

        assertTrue(connection.performContextMenuAction(android.R.id.redo))
        assertEquals(1, redos)
    }

    /**
     * Undo and redo are the only ids the bridge takes. Select-all and the
     * clipboard stay the field's own, and must not touch the history.
     */
    @Test
    fun `other context menu actions are left to the field`() {
        var undos = 0
        var redos = 0
        val connection = ComposerImeUndoConnection(
            target = target(),
            onUndo = { undos++ },
            onRedo = { redos++ },
        )

        connection.performContextMenuAction(android.R.id.selectAll)
        connection.performContextMenuAction(android.R.id.copy)
        connection.performContextMenuAction(android.R.id.cut)
        connection.performContextMenuAction(android.R.id.paste)

        assertEquals(0, undos)
        assertEquals(0, redos)
    }
}
