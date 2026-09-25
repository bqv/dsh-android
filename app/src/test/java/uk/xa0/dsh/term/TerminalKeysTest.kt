package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Key encoding.
 *
 * A soft keyboard cannot produce Esc, Ctrl, Tab or the arrows, so every one of them
 * is encoded here and sent as bytes. The encodings are conventional and easy to get
 * subtly wrong — `ESC[H` where `vim` wants `ESC OH`, a Ctrl+Arrow without its `1;5`
 * modifier — so each asserts the exact escape sequence.
 */
class TerminalKeysTest {

    @Test
    fun `arrows use CSI normally and SS3 in application cursor mode`() {
        assertEquals("\u001B[A", TerminalKeys.key(TerminalKey.UP))
        assertEquals("\u001B[B", TerminalKeys.key(TerminalKey.DOWN))
        assertEquals("\u001B[C", TerminalKeys.key(TerminalKey.RIGHT))
        assertEquals("\u001B[D", TerminalKeys.key(TerminalKey.LEFT))

        assertEquals("\u001BOA", TerminalKeys.key(TerminalKey.UP, applicationCursorKeys = true))
        assertEquals("\u001BOD", TerminalKeys.key(TerminalKey.LEFT, applicationCursorKeys = true))
    }

    @Test
    fun `home and end follow the cursor key mode too`() {
        assertEquals("\u001B[H", TerminalKeys.key(TerminalKey.HOME))
        assertEquals("\u001B[F", TerminalKeys.key(TerminalKey.END))
        assertEquals("\u001BOH", TerminalKeys.key(TerminalKey.HOME, applicationCursorKeys = true))
    }

    @Test
    fun `a modified arrow carries the modifier parameter and never SS3`() {
        assertEquals("\u001B[1;5A", TerminalKeys.key(TerminalKey.UP, ctrl = true))
        assertEquals("\u001B[1;2B", TerminalKeys.key(TerminalKey.DOWN, shift = true))
        assertEquals("\u001B[1;3D", TerminalKeys.key(TerminalKey.LEFT, alt = true))
        // Ctrl+Shift is 6, and application mode does not change the modified form.
        assertEquals("\u001B[1;6C", TerminalKeys.key(TerminalKey.RIGHT, ctrl = true, shift = true, applicationCursorKeys = true))
    }

    @Test
    fun `navigation keys use the tilde form`() {
        assertEquals("\u001B[2~", TerminalKeys.key(TerminalKey.INSERT))
        assertEquals("\u001B[3~", TerminalKeys.key(TerminalKey.DELETE))
        assertEquals("\u001B[5~", TerminalKeys.key(TerminalKey.PAGE_UP))
        assertEquals("\u001B[6~", TerminalKeys.key(TerminalKey.PAGE_DOWN))
        assertEquals("\u001B[5;5~", TerminalKeys.key(TerminalKey.PAGE_UP, ctrl = true))
    }

    @Test
    fun `F1 to F4 use SS3 and the rest use the tilde form`() {
        assertEquals("\u001BOP", TerminalKeys.key(TerminalKey.F1))
        assertEquals("\u001BOQ", TerminalKeys.key(TerminalKey.F2))
        assertEquals("\u001BOR", TerminalKeys.key(TerminalKey.F3))
        assertEquals("\u001BOS", TerminalKeys.key(TerminalKey.F4))
        assertEquals("\u001B[15~", TerminalKeys.key(TerminalKey.F5))
        assertEquals("\u001B[17~", TerminalKeys.key(TerminalKey.F6))
        assertEquals("\u001B[21~", TerminalKeys.key(TerminalKey.F10))
        assertEquals("\u001B[24~", TerminalKeys.key(TerminalKey.F12))
    }

    @Test
    fun `tab is a tab and shift tab is back tab`() {
        assertEquals("\t", TerminalKeys.key(TerminalKey.TAB))
        assertEquals("\u001B[Z", TerminalKeys.key(TerminalKey.TAB, shift = true))
    }

    @Test
    fun `escape and enter are their control characters`() {
        assertEquals("\u001B", TerminalKeys.key(TerminalKey.ESCAPE))
        assertEquals("\r", TerminalKeys.key(TerminalKey.ENTER))
    }

    /**
     * Backspace must be DEL, not BS: readline and every host this app talks to are
     * configured for `\u007f`, and `\u0008` is what the shell turns into a literal
     * `^H`. Asserting the negative as well is the point — both are plausible
     * "backspace" bytes, so only naming the wrong one proves the choice was made.
     */
    @Test
    fun `backspace is DEL and not BS`() {
        assertEquals("\u007F", TerminalKeys.key(TerminalKey.BACKSPACE))
        assertNotEquals("\u0008", TerminalKeys.key(TerminalKey.BACKSPACE))
    }

    @Test
    fun `control chords map to the C0 range`() {
        assertEquals('\u0001', TerminalKeys.controlOf('a'))
        assertEquals('\u0001', TerminalKeys.controlOf('A'))
        assertEquals('\u0003', TerminalKeys.controlOf('c'))
        assertEquals('\u001A', TerminalKeys.controlOf('z'))
        assertEquals('\u001B', TerminalKeys.controlOf('['))
        assertEquals('\u001C', TerminalKeys.controlOf('\\'))
        assertEquals('\u001D', TerminalKeys.controlOf(']'))
        assertEquals('\u0000', TerminalKeys.controlOf(' '))
        assertEquals('\u0000', TerminalKeys.controlOf('@'))
    }

    @Test
    fun `a chord with no control mapping has none`() {
        assertNull(TerminalKeys.controlOf('1'))
        assertNull(TerminalKeys.controlOf('\u00e9'))
    }

    @Test
    fun `typed characters carry their modifiers`() {
        assertEquals("a", TerminalKeys.typed('a'))
        assertEquals("\u0003", TerminalKeys.typed('c', ctrl = true))
        assertEquals("\u001Ba", TerminalKeys.typed('a', alt = true))
        assertEquals("\u001B\u0003", TerminalKeys.typed('c', ctrl = true, alt = true))
        // A chord with no C0 mapping types the character rather than nothing.
        assertEquals("7", TerminalKeys.typed('7', ctrl = true))
    }

    @Test
    fun `every function key has a distinct encoding`() {
        val all = listOf(
            TerminalKey.F1, TerminalKey.F2, TerminalKey.F3, TerminalKey.F4, TerminalKey.F5,
            TerminalKey.F6, TerminalKey.F7, TerminalKey.F8, TerminalKey.F9, TerminalKey.F10,
            TerminalKey.F11, TerminalKey.F12,
        ).map { TerminalKeys.key(it) }
        assertEquals(all.size, all.toSet().size)
        assertTrue(all.none { it.isEmpty() })
    }

    @Test
    fun `arrow keys are never a bare letter`() {
        val up = TerminalKeys.key(TerminalKey.UP)
        assertTrue(up.startsWith("\u001B"))
        assertFalse(up == "A")
    }
}
