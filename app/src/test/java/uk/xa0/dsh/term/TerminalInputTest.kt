package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input connection's semantics.
 *
 * The connection itself cannot be driven from here — that takes a real IME on a real
 * device — but every decision it makes about a commit, a key event or a delete is
 * plain arithmetic over strings, and those decisions are where the two measured
 * defects lived: a field that re-committed a character into `aabcdefgh`, and a
 * composer that rewrote `vim` into `vI'm`. The anchor those defects grew out of is
 * gone, and these tests pin that it cannot come back on the wire.
 */
class TerminalInputTest {

    // ---------------------------------------------------------------- committed text

    @Test
    fun `a committed character is written as itself`() {
        assertEquals("l", TerminalInput.bytesFor("l"))
    }

    @Test
    fun `a multi-character commit is written whole and in order`() {
        assertEquals("ls -la", TerminalInput.bytesFor("ls -la"))
        assertEquals("abcdefgh", TerminalInput.bytesFor("abcdefgh"))
    }

    /**
     * The old path's `aabcdefgh`: the field still held `a` when `b` arrived, so the
     * commit was `ab` and `a` went out twice. This conversion has no memory to
     * prepend, and the connection forwards the *commit* rather than a buffer it
     * keeps — an editor with state was the whole defect.
     */
    @Test
    fun `each commit is converted on its own terms`() {
        assertEquals("a", TerminalInput.bytesFor("a"))
        assertEquals("b", TerminalInput.bytesFor("b"))
        assertEquals("ab", TerminalInput.bytesFor("ab"))
        assertNotEquals(TerminalInput.bytesFor("a"), TerminalInput.bytesFor("ab"))
    }

    /** The AOSP keyboard sends Enter as `\n` text; a terminal expects `\r`. */
    @Test
    fun `a newline in committed text becomes a carriage return`() {
        assertEquals("\r", TerminalInput.bytesFor("\n"))
        assertEquals("ls\r", TerminalInput.bytesFor("ls\n"))
        // An explicit CR is already what a PTY wants and is left alone.
        assertEquals("\r", TerminalInput.bytesFor("\r"))
    }

    /**
     * An armed Ctrl is a chord, and the modifier never appears in the committed text.
     * This is the only way a soft keyboard's Ctrl reaches the shell.
     */
    @Test
    fun `an armed ctrl maps committed text to its C0 byte`() {
        assertEquals("\u0003", TerminalInput.bytesFor("c", ctrl = true))
        assertEquals("\u0004", TerminalInput.bytesFor("d", ctrl = true))
        assertEquals("\u000C", TerminalInput.bytesFor("l", ctrl = true))
        // A whole word commits as one string: every character is a chord.
        assertEquals("\u0015\u000B", TerminalInput.bytesFor("uk", ctrl = true))
    }

    @Test
    fun `an armed ctrl still types a character that has no control mapping`() {
        assertEquals("1", TerminalInput.bytesFor("1", ctrl = true))
        assertEquals("!", TerminalInput.bytesFor("!", ctrl = true))
    }

    @Test
    fun `an armed ctrl maps the punctuation chords a shell binds`() {
        assertEquals("\u0000", TerminalInput.bytesFor("@", ctrl = true))
        assertEquals("\u001B", TerminalInput.bytesFor("[", ctrl = true))
        assertEquals("\u001C", TerminalInput.bytesFor("\\", ctrl = true))
        assertEquals("\u001F", TerminalInput.bytesFor("_", ctrl = true))
    }

    /** A raw C0 byte from a keyboard that sends one passes straight through, armed or not. */
    @Test
    fun `a raw control byte is written unchanged`() {
        assertEquals("\u0003", TerminalInput.bytesFor("\u0003"))
        assertEquals("\u0003", TerminalInput.bytesFor("\u0003", ctrl = true))
    }

    /**
     * The removed hidden field's anchor. Nothing holds one any more, so the property
     * to pin is that *no* trace of it can reach a shell: an invisible character in a
     * command is a bug report waiting to happen.
     */
    @Test
    fun `the zero-width anchor never reaches the shell`() {
        assertEquals('\u200B', TerminalInput.ANCHOR)
        assertEquals("", TerminalInput.bytesFor(TerminalInput.ANCHOR.toString()))
        assertEquals("ls", TerminalInput.bytesFor("${TerminalInput.ANCHOR}l${TerminalInput.ANCHOR}s"))
    }

    /**
     * Nothing else is rewritten: whatever the IME committed is what the shell sees.
     * (The non-breaking space is the character GBoard's composer appended to `cat`
     * on the old path; under TYPE_NULL the composer is not involved, and if one ever
     * arrives the log names it rather than the conversion silently editing it.)
     */
    @Test
    fun `committed text is not otherwise rewritten`() {
        assertEquals("cat\u00A0", TerminalInput.bytesFor("cat\u00A0"))
        assertEquals("vim", TerminalInput.bytesFor("vim"))
    }

    @Test
    fun `an astral character survives as one code point`() {
        val emoji = "\uD83D\uDE00"
        assertEquals(emoji, TerminalInput.bytesFor(emoji))
    }

    // --------------------------------------------------------------------- deletes

    /** DEL, never BS: the same choice `TerminalKeys` makes for the key row. */
    @Test
    fun `one delete is one DEL`() {
        assertEquals("\u007F", TerminalInput.deleteBytes(1, 0))
        assertEquals(TerminalInput.BACKSPACE, TerminalInput.deleteBytes(1, 0))
        assertNotEquals("\u0008", TerminalInput.deleteBytes(1, 0))
    }

    /**
     * The Samsung stock keyboard with "Auto check spelling" sends a left length above
     * one in a single `deleteSurroundingText`, so a single byte would leave the rest
     * of the word behind.
     */
    @Test
    fun `a longer left delete is one DEL per character`() {
        assertEquals("\u007F\u007F\u007F", TerminalInput.deleteBytes(3, 0))
    }

    @Test
    fun `a delete after the cursor is the Delete key`() {
        assertEquals(TerminalInput.FORWARD_DELETE, TerminalInput.deleteBytes(0, 1))
        assertEquals("\u001B[3~", TerminalInput.deleteBytes(0, 1))
    }

    @Test
    fun `a delete of nothing and a negative length are both nothing`() {
        assertEquals("", TerminalInput.deleteBytes(0, 0))
        assertEquals("", TerminalInput.deleteBytes(-1, -1))
    }

    // ------------------------------------------------------------------- chords

    /**
     * Gboard on a `TYPE_NULL` editor flags *every* key event it synthesises with
     * `META_CTRL_ON` — measured: tapping `c`, `a`, `t` delivered keyCode 31/29/48 with
     * `metaState=4096`, `flags=0x6`, `deviceId=-1`. So an IME's Ctrl bit is not
     * evidence of a chord, and believing it types ^C ^A ^T for `cat`.
     */
    @Test
    fun `a key event from an IME is never a chord on its own ctrl bit`() {
        assertFalse(TerminalInput.isChord(eventCtrl = true, eventMetaIsTrustworthy = false, latchArmed = false))
        assertFalse(TerminalInput.isChord(eventCtrl = false, eventMetaIsTrustworthy = false, latchArmed = false))
    }

    /** A key event the window system delivered means what it says. */
    @Test
    fun `a window key event's ctrl bit is a chord`() {
        assertTrue(TerminalInput.isChord(eventCtrl = true, eventMetaIsTrustworthy = true, latchArmed = false))
        assertFalse(TerminalInput.isChord(eventCtrl = false, eventMetaIsTrustworthy = true, latchArmed = false))
    }

    /** The panel's own latch is the one Ctrl a phone without a Ctrl key can produce. */
    @Test
    fun `the latch is a chord whatever the event says`() {
        assertTrue(TerminalInput.isChord(eventCtrl = false, eventMetaIsTrustworthy = true, latchArmed = true))
        assertTrue(TerminalInput.isChord(eventCtrl = false, eventMetaIsTrustworthy = false, latchArmed = true))
    }

    /**
     * Android's `KeyEvent.getUnicodeChar()` returns 0 whenever Ctrl is held — the
     * modifier suppresses the character — so a chord has to be read from the key
     * code. A handler that bails on the missing character first drops every Ctrl
     * chord a soft keyboard sends.
     */
    @Test
    fun `a chord is read from the key code`() {
        assertEquals('c', TerminalInput.chordCharacterOf(31))
        assertEquals('d', TerminalInput.chordCharacterOf(32))
        assertEquals('a', TerminalInput.chordCharacterOf(29))
        assertEquals('z', TerminalInput.chordCharacterOf(54))
        assertEquals('0', TerminalInput.chordCharacterOf(7))
        assertEquals('9', TerminalInput.chordCharacterOf(16))
    }

    @Test
    fun `the punctuation chords have their characters too`() {
        assertEquals('`', TerminalInput.chordCharacterOf(68))
        assertEquals('-', TerminalInput.chordCharacterOf(69))
        assertEquals('=', TerminalInput.chordCharacterOf(70))
        assertEquals('[', TerminalInput.chordCharacterOf(71))
        assertEquals(']', TerminalInput.chordCharacterOf(72))
        assertEquals('\\', TerminalInput.chordCharacterOf(73))
        assertEquals(';', TerminalInput.chordCharacterOf(74))
        assertEquals('\'', TerminalInput.chordCharacterOf(75))
        assertEquals('/', TerminalInput.chordCharacterOf(76))
        assertEquals('@', TerminalInput.chordCharacterOf(77))
    }

    @Test
    fun `a key that is not a chord has no character`() {
        assertNull(TerminalInput.chordCharacterOf(66))   // KEYCODE_ENTER
        assertNull(TerminalInput.chordCharacterOf(67))   // KEYCODE_DEL
        assertNull(TerminalInput.chordCharacterOf(61))   // KEYCODE_TAB
        assertNull(TerminalInput.chordCharacterOf(0))
    }

    /**
     * The event's own character wins whenever it has one — including the uppercase
     * one a Shift-modified event carries.
     */
    @Test
    fun `a key event's own character is what is typed`() {
        assertEquals('a', TerminalInput.characterOf(29, 'a'.code))
        assertEquals('A', TerminalInput.characterOf(29, 'A'.code))
        assertEquals('?', TerminalInput.characterOf(76, '?'.code))
    }

    /**
     * A keyboard whose key-character map yields nothing for a printable key must not
     * lose that key: the panel's whole contract is that input is never dropped
     * silently. The chord table supplies the letter, and Shift is applied by hand
     * because the map that would have applied it is the one that failed.
     */
    @Test
    fun `a letter survives a key event with no character`() {
        assertEquals('p', TerminalInput.characterOf(44, 0))
        assertEquals('s', TerminalInput.characterOf(47, 0))
        assertEquals('5', TerminalInput.characterOf(12, 0))
        assertEquals('P', TerminalInput.characterOf(44, 0, shift = true))
        // Nothing printable and no character: still nothing, and the caller logs it.
        assertNull(TerminalInput.characterOf(66, 0))
        assertNull(TerminalInput.characterOf(0, 0))
    }

    // ------------------------------------------------------- key codes the panel owns

    @Test
    fun `every terminal key is reachable from its native key code`() {
        assertEquals(TerminalKey.UP, TerminalKey.ofNativeKeyCode(19))
        assertEquals(TerminalKey.DOWN, TerminalKey.ofNativeKeyCode(20))
        assertEquals(TerminalKey.LEFT, TerminalKey.ofNativeKeyCode(21))
        assertEquals(TerminalKey.RIGHT, TerminalKey.ofNativeKeyCode(22))
        assertEquals(TerminalKey.HOME, TerminalKey.ofNativeKeyCode(122))
        assertEquals(TerminalKey.END, TerminalKey.ofNativeKeyCode(123))
        assertEquals(TerminalKey.PAGE_UP, TerminalKey.ofNativeKeyCode(92))
        assertEquals(TerminalKey.PAGE_DOWN, TerminalKey.ofNativeKeyCode(93))
        assertEquals(TerminalKey.INSERT, TerminalKey.ofNativeKeyCode(124))
        assertEquals(TerminalKey.DELETE, TerminalKey.ofNativeKeyCode(112))
        assertEquals(TerminalKey.TAB, TerminalKey.ofNativeKeyCode(61))
        assertEquals(TerminalKey.ESCAPE, TerminalKey.ofNativeKeyCode(111))
        assertEquals(TerminalKey.ENTER, TerminalKey.ofNativeKeyCode(66))
        assertEquals(TerminalKey.ENTER, TerminalKey.ofNativeKeyCode(160))
        assertEquals(TerminalKey.BACKSPACE, TerminalKey.ofNativeKeyCode(67))
    }

    @Test
    fun `the function key row is contiguous`() {
        val expected = listOf(
            TerminalKey.F1, TerminalKey.F2, TerminalKey.F3, TerminalKey.F4, TerminalKey.F5, TerminalKey.F6,
            TerminalKey.F7, TerminalKey.F8, TerminalKey.F9, TerminalKey.F10, TerminalKey.F11, TerminalKey.F12,
        )
        assertEquals(expected, (131..142).map { TerminalKey.ofNativeKeyCode(it) })
    }

    /**
     * The distinction that matters for a soft backspace: KEYCODE_DEL is Backspace
     * (DEL on the wire) and KEYCODE_FORWARD_DEL is the Delete key (`ESC[3~`). Reading
     * them the other way round deletes the wrong character.
     */
    @Test
    fun `backspace and delete are different keys`() {
        assertNotEquals(TerminalKey.ofNativeKeyCode(67), TerminalKey.ofNativeKeyCode(112))
        assertEquals(TerminalKey.BACKSPACE, TerminalKey.ofNativeKeyCode(67))
        assertEquals(TerminalKey.DELETE, TerminalKey.ofNativeKeyCode(112))
    }

    /**
     * A letter is *not* a terminal key: it is text, and the panel types the character
     * the key event carries. Treating KEYCODE_A as a key would send an escape
     * sequence for `a`.
     */
    @Test
    fun `a letter is text and not a terminal key`() {
        assertNull(TerminalKey.ofNativeKeyCode(29))   // KEYCODE_A
        assertNull(TerminalKey.ofNativeKeyCode(32))   // KEYCODE_D
        assertNull(TerminalKey.ofNativeKeyCode(7))    // KEYCODE_0
        assertNull(TerminalKey.ofNativeKeyCode(0))
        // But it *is* a chord character, which is how Ctrl+D is read.
        assertEquals('d', TerminalInput.chordCharacterOf(32))
    }

    /** Every terminal key has a distinct, non-empty encoding — none is a bare letter. */
    @Test
    fun `every terminal key still encodes to bytes`() {
        val seen = mutableMapOf<String, TerminalKey>()
        for (key in TerminalKey.values()) {
            val bytes = TerminalKeys.key(key)
            assertTrue("$key encoded to nothing", bytes.isNotEmpty())
            // A terminal key is a control byte or an escape sequence; never a
            // printable character and never a letter.
            val first = bytes[0]
            assertTrue(
                "$key encoded to '$bytes', which is not a control byte or an escape",
                bytes.length > 1 || first.code < 32 || first.code == 127,
            )
            val clash = seen.put(bytes, key)
            assertNull("$key and $clash both encode to '$bytes'", clash)
        }
    }
}
