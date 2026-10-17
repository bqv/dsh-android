package uk.xa0.dsh.ui.components

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication

/**
 * The composer's hardware undo chords, as a pure mapping.
 *
 * `ComposerUndoTest` sends real key events through a composed field, which proves
 * the wiring but cannot enumerate the matrix cheaply. This is the matrix: which
 * modifiers and which of the two keys count, and — just as important — that the
 * key-up half of a press does not undo a second time.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ComposerShortcutTest {

    private fun shortcut(
        key: Key,
        ctrl: Boolean = true,
        shift: Boolean = false,
        down: Boolean = true,
    ) = composerShortcut(key = key, ctrl = ctrl, shift = shift, down = down)

    @Test
    fun `ctrl z is undo`() {
        assertEquals(ComposerShortcut.UNDO, shortcut(Key.Z))
    }

    @Test
    fun `ctrl shift z is redo`() {
        assertEquals(ComposerShortcut.REDO, shortcut(Key.Z, shift = true))
    }

    @Test
    fun `ctrl y is redo`() {
        assertEquals(ComposerShortcut.REDO, shortcut(Key.Y))
        assertEquals(ComposerShortcut.REDO, shortcut(Key.Y, shift = true))
    }

    /** Without Ctrl, Z is just a letter. */
    @Test
    fun `an unmodified key is not a shortcut`() {
        assertNull(shortcut(Key.Z, ctrl = false))
        assertNull(shortcut(Key.Y, ctrl = false))
    }

    /** The key-up half of the same press must not undo twice. */
    @Test
    fun `key up is not a shortcut`() {
        assertNull(shortcut(Key.Z, down = false))
        assertNull(shortcut(Key.Z, shift = true, down = false))
    }

    /** Anything else is left to the field and to the system. */
    @Test
    fun `other keys are left alone`() {
        assertNull(shortcut(Key.A))
        assertNull(shortcut(Key.X))
        assertNull(shortcut(Key.V))
    }
}
