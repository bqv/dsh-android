package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The soft keyboard's edit semantics.
 *
 * The panel's hidden text field cannot be driven from here — that takes a real IME
 * on a real device — but every decision the panel makes *about* an edit can be, and
 * those decisions are where the two bugs were: a field that had nothing for the
 * IME to delete, and a composition that was typed while it was still being composed.
 */
class SoftInputTest {

    private val anchor = SoftInput.ANCHOR

    private fun typed(edit: SoftEdit) = edit.write

    @Test
    fun `a committed character is typed without the anchor`() {
        val edit = SoftInput.editOf("${anchor}l", composing = false)
        assertEquals("l", typed(edit))
        assertFalse(edit.composing)
    }

    @Test
    fun `a multi-character commit is typed whole`() {
        assertEquals("ls -la", typed(SoftInput.editOf("${anchor}ls -la", composing = false)))
    }

    @Test
    fun `the anchor alone commits nothing`() {
        assertEquals("", typed(SoftInput.editOf(anchor, composing = false)))
    }

    /**
     * The old path could not reach a delete at all: the field was emptied on every
     * callback, so there was never anything for the IME to remove and no callback
     * to observe. An empty field is a delete, and a delete has to be a byte.
     */
    @Test
    fun `an empty field is the IME's backspace`() {
        val edit = SoftInput.editOf("", composing = false)
        assertEquals("\u007F", typed(edit))
        assertTrue(typed(edit).isNotEmpty())
    }

    /** DEL, never BS: the same choice [TerminalKeys.key] makes for the key row. */
    @Test
    fun `the IME's backspace is DEL and not BS`() {
        assertEquals("\u007F", SoftInput.DELETE)
        assertEquals(SoftInput.DELETE, SoftInput.editOf("", composing = false).write)
        assertNotEquals("\u0008", SoftInput.editOf("", composing = false).write)
    }

    /**
     * A composing IME reports in-progress text as the field's value on every
     * keystroke. Typing it would send half-formed words to the shell — and clearing
     * the field would cancel the composition the user is still writing.
     */
    @Test
    fun `composition text is never typed`() {
        val edit = SoftInput.editOf("${anchor}ni", composing = true)
        assertEquals("", typed(edit))
        assertTrue(edit.composing)
    }

    @Test
    fun `a composition that ends is typed`() {
        val edit = SoftInput.editOf("${anchor}\u4f60\u597d", composing = false)
        assertEquals("\u4f60\u597d", typed(edit))
        assertFalse(edit.composing)
    }

    @Test
    fun `a deleted composition character writes nothing`() {
        assertEquals("", typed(SoftInput.editOf(anchor, composing = true)))
    }

    @Test
    fun `an armed ctrl maps the committed text and is spent`() {
        val edit = SoftInput.editOf("${anchor}c", composing = false, ctrl = true)
        assertEquals("\u0003", typed(edit))
        assertTrue(edit.ctrlUsed)
    }

    @Test
    fun `an armed ctrl is not spent on a backspace`() {
        val edit = SoftInput.editOf("", composing = false, ctrl = true)
        assertEquals("\u007F", typed(edit))
        assertFalse(edit.ctrlUsed)
    }

    /**
     * An IME that replaces the field's whole value rather than editing around the
     * anchor must not be read as a backspace: nothing was deleted that the panel can
     * see, so nothing may be un-typed in the shell on its behalf.
     */
    @Test
    fun `a replaced field value is typed and not turned into a delete`() {
        val edit = SoftInput.editOf("hi", composing = false)
        assertEquals("hi", typed(edit))
        assertFalse(typed(edit).startsWith(SoftInput.DELETE))
    }

    /** Two edits in a row: the anchor is restored after each, so a second backspace lands. */
    @Test
    fun `a backspace after a keystroke still lands`() {
        assertEquals("x", typed(SoftInput.editOf("${anchor}x", composing = false)))
        assertEquals("\u007F", typed(SoftInput.editOf("", composing = false)))
    }

    /**
     * The pair that matters for a chord: the same edit, held without an armed Ctrl
     * and sent with one.
     *
     * A soft keyboard composes a lone letter as a word, so arming Ctrl and typing
     * `d` left the `d` sitting in the IME's composing region with nothing written.
     * Ctrl+D is EOT and has to leave the phone now, not when a word that may never
     * commit finally commits.
     */
    @Test
    fun `ctrl reaches the shell while the IME is still composing`() {
        val held = SoftInput.editOf("${anchor}d", composing = true)
        assertEquals("", typed(held))
        assertTrue(held.composing)

        val chord = SoftInput.editOf("${anchor}d", composing = true, ctrl = true)
        assertEquals("\u0004", typed(chord))
        assertTrue(chord.ctrlUsed)
        assertFalse(chord.composing)
    }

    /**
     * The arithmetic above assumes one anchor character: an edit that removes it
     * leaves exactly the empty string, and an edit that keeps it leaves the commit
     * as the suffix after exactly one character.
     */
    @Test
    fun `the anchor is a single character`() {
        assertEquals(1, SoftInput.ANCHOR.length)
        assertEquals("\u200B", SoftInput.ANCHOR)
    }
}
