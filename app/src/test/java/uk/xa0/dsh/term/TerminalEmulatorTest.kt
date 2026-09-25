package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The VT core, verified against bytes.
 *
 * The host ships an xterm-serialized screen plus live output, so the emulator is
 * fed escape sequences directly here rather than through the app. Every test that
 * claims a sequence works asserts the *effect* of that sequence — a cursor that
 * moved, a cell that is a specific box-drawing character — because "it did not
 * throw" is exactly the test that would pass with the parser ignoring everything.
 */
class TerminalEmulatorTest {

    private fun emulator(columns: Int = 10, rows: Int = 4, scrollback: Int = 16) =
        TerminalEmulator(columns, rows, scrollback)

    /** One row as text, with the unused tail removed. */
    private fun TerminalEmulator.line(index: Int): String {
        val row = row(index)
        return (0 until columns).joinToString("") { row.cells[it].text() }.trimEnd()
    }

    private fun TerminalEmulator.rawLine(index: Int): String {
        val row = row(index)
        return (0 until columns).joinToString("") { row.cells[it].text() }
    }

    // ------------------------------------------------------------ cursor address

    @Test
    fun `cursor position is one-based and clamped to the grid`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("\u001B[2;3H")
        assertEquals(1, term.cursorRow)
        assertEquals(2, term.cursorCol)
        term.append("\u001B[99;99H")
        assertEquals(3, term.cursorRow)
        assertEquals(9, term.cursorCol)
    }

    @Test
    fun `cursor position is where the next glyph lands`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("\u001B[3;4HZ")
        assertEquals("Z", term.row(2).cells[3].text())
        assertEquals(2, term.cursorRow)
        assertEquals(4, term.cursorCol)
    }

    @Test
    fun `relative cursor movement steps from the current position`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("\u001B[3;5H")
        term.append("\u001B[2A")   // up 2
        term.append("\u001B[3C")   // forward 3
        assertEquals(0, term.cursorRow)
        assertEquals(7, term.cursorCol)
        term.append("\u001B[1D")
        assertEquals(6, term.cursorCol)
    }

    @Test
    fun `a sequence without parameters uses the default of one`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("\u001B[3;3H")
        term.append("\u001B[H")
        assertEquals(0, term.cursorRow)
        assertEquals(0, term.cursorCol)
    }

    /**
     * The parameter accumulator is index-addressed, so a sequence with fewer
     * parameters than the one before it must not inherit the leftovers. Without the
     * clear-on-CSI this reads as column 53 and the cursor lands off the grid.
     */
    @Test
    fun `a shorter parameter list does not inherit the previous one`() {
        val term = emulator(columns = 80, rows = 4)
        term.append("\u001B[2;7H")   // a two-parameter sequence
        assertEquals(1, term.cursorRow)
        assertEquals(6, term.cursorCol)
        // Column 4. Without the accumulator clear this reads the leftover `2` as a
        // tens digit, giving column 24, and lands somewhere else entirely.
        term.append("\u001B[4G")
        assertEquals(3, term.cursorCol)
        assertEquals(1, term.cursorRow)
    }

    // ------------------------------------------------------------------ erasing

    @Test
    fun `erase in line from the cursor clears only the tail`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abcdef\u001B[1;3H\u001B[K")
        assertEquals("ab", term.line(0))
        assertEquals('a', term.row(0).cells[0].first)
        assertEquals('b', term.row(0).cells[1].first)
        assertTrue(term.row(0).cells[5].isBlank())
    }

    /**
     * Negative control for the test above.
     *
     * The same bytes with the `ESC[K` removed must leave the row intact. Read
     * together, the two prove the first test is observing the erase and not merely
     * "nothing exploded": if the parser ignored `EL`, this one still passes and that
     * one fails.
     */
    @Test
    fun `without the erase sequence the row is untouched`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abcdef\u001B[1;3H")
        assertEquals("abcdef", term.line(0))
    }

    @Test
    fun `erase in line before the cursor keeps the tail`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abcdef\u001B[1;3H\u001B[1K")
        assertEquals("   def", term.rawLine(0))
    }

    @Test
    fun `erase in display below the cursor clears every later row`() {
        val term = emulator(columns = 4, rows = 3)
        term.append("aaa\r\nbbb\r\nccc")
        term.append("\u001B[2;2H\u001B[0J")
        assertEquals("b", term.line(1))
        assertEquals("", term.line(2))
    }

    @Test
    fun `erase in display above the cursor clears every earlier row`() {
        val term = emulator(columns = 4, rows = 3)
        term.append("aaa\r\nbbb\r\nccc")
        term.append("\u001B[2;2H\u001B[1J")
        assertEquals("", term.line(0))
        assertEquals("  b", term.rawLine(1).take(3))
        assertEquals("ccc", term.line(2))
    }

    // ---------------------------------------------------------------- scrolling

    @Test
    fun `a line feed at the bottom scrolls the screen and keeps the history`() {
        val term = emulator(columns = 4, rows = 2, scrollback = 8)
        term.append("aa\r\nbb\r\ncc")
        assertEquals("bb", term.line(0))
        assertEquals("cc", term.line(1))
        assertEquals(1, term.scrollbackSize)
        assertEquals("aa", scrollbackText(term, 0))
    }

    @Test
    fun `reverse index moves up and scrolls down at the top`() {
        val term = emulator(columns = 4, rows = 3)
        term.append("aaa\r\nbbb\r\nccc")
        // `ESC M`, not `CSI M` — the CSI form is delete-line, which is a different
        // sequence and is covered below.
        term.append("\u001BM")
        assertEquals(1, term.cursorRow)
        term.append("\u001B[1;1H")
        term.append("\u001BM")
        assertEquals("", term.line(0))
        assertEquals("aaa", term.line(1))
    }

    @Test
    fun `scrolling up via CSI S pushes rows into history`() {
        val term = emulator(columns = 4, rows = 3, scrollback = 8)
        term.append("aaa\r\nbbb\r\nccc\u001B[2S")
        assertEquals("ccc", term.line(0))
        assertEquals("", term.line(1))
        assertEquals(2, term.scrollbackSize)
    }

    // ------------------------------------------------------- insert and delete

    @Test
    fun `insert line pushes rows down from the cursor`() {
        val term = emulator(columns = 4, rows = 3)
        term.append("aaa\r\nbbb\r\nccc")
        term.append("\u001B[1;1H\u001B[L")
        assertEquals("", term.line(0))
        assertEquals("aaa", term.line(1))
        assertEquals("bbb", term.line(2))
    }

    @Test
    fun `delete line pulls the rows below up`() {
        val term = emulator(columns = 4, rows = 3)
        term.append("aaa\r\nbbb\r\nccc")
        term.append("\u001B[1;1H\u001B[M")
        assertEquals("bbb", term.line(0))
        assertEquals("ccc", term.line(1))
        assertEquals("", term.line(2))
    }

    @Test
    fun `delete character shifts the rest of the row left`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abcdef\u001B[1;2H\u001B[2P")
        assertEquals("adef", term.line(0))
    }

    @Test
    fun `insert character shifts the rest of the row right`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abcdef\u001B[1;2H\u001B[2@")
        assertEquals("a  bcd", term.line(0))
    }

    @Test
    fun `erase character blanks without moving the cursor`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abcdef\u001B[1;2H\u001B[2X")
        assertEquals("a  def", term.line(0))
        assertEquals(1, term.cursorCol)
    }

    // ------------------------------------------------------- alternate screen

    @Test
    fun `alternate screen hides the main screen and restores it on exit`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("main")
        term.append("\u001B[?1049h")
        assertTrue(term.alternateActive)
        assertEquals("", term.line(0))
        term.append("alt")
        assertEquals("alt", term.line(0))
        term.append("\u001B[?1049l")
        assertFalse(term.alternateActive)
        assertEquals("main", term.line(0))
    }

    @Test
    fun `the alternate screen keeps no scrollback`() {
        val term = emulator(columns = 4, rows = 2, scrollback = 8)
        term.append("\u001B[?1049h")
        term.append("a\r\nb\r\nc\r\nd")
        assertEquals(0, term.scrollbackSize)
        assertEquals("c", term.line(0))
        assertEquals("d", term.line(1))
    }

    @Test
    fun `cursor visibility follows the private mode`() {
        val term = emulator()
        assertTrue(term.cursorVisible)
        term.append("\u001B[?25l")
        assertFalse(term.cursorVisible)
        term.append("\u001B[?25h")
        assertTrue(term.cursorVisible)
    }

    // ---------------------------------------------------------------------- SGR

    @Test
    fun `sixteen colour SGR sets palette indices and reset restores the default`() {
        val term = emulator(columns = 4, rows = 2)
        term.append("\u001B[31mR")
        assertEquals(1, term.row(0).cells[0].fg)
        term.append("\u001B[42mG")
        assertEquals(2, term.row(0).cells[1].bg)
        term.append("\u001B[0mX")
        assertEquals(COLOR_DEFAULT, term.row(0).cells[2].fg)
        assertEquals(COLOR_DEFAULT, term.row(0).cells[2].bg)
    }

    @Test
    fun `bright colours use the upper half of the palette`() {
        val term = emulator(columns = 4, rows = 2)
        term.append("\u001B[91mA")
        assertEquals(9, term.row(0).cells[0].fg)
        term.append("\u001B[104mB")
        assertEquals(12, term.row(0).cells[1].bg)
    }

    @Test
    fun `256-colour SGR is an index, not a pair of attributes`() {
        val term = emulator(columns = 4, rows = 2)
        term.append("\u001B[38;5;196mA")
        assertEquals(196, term.row(0).cells[0].fg)
        term.append("\u001B[48;5;21mB")
        assertEquals(21, term.row(0).cells[1].bg)
    }

    @Test
    fun `truecolour SGR packs the triple`() {
        val term = emulator(columns = 4, rows = 2)
        term.append("\u001B[38;2;10;20;30mA")
        assertEquals(rgbColor(10, 20, 30), term.row(0).cells[0].fg)
        assertEquals(0x1000000 or (10 shl 16) or (20 shl 8) or 30, term.row(0).cells[0].fg)
        term.append("\u001B[48;2;255;0;128mB")
        assertEquals(rgbColor(255, 0, 128), term.row(0).cells[1].bg)
    }

    @Test
    fun `text attributes are set and cleared`() {
        val term = emulator(columns = 4, rows = 2)
        term.append("\u001B[1;4;31mA")
        val bold = term.row(0).cells[0].attrs
        assertTrue(bold and ATTR_BOLD != 0)
        assertTrue(bold and ATTR_UNDERLINE != 0)
        assertEquals(1, term.row(0).cells[0].fg)
        term.append("\u001B[22;24mB")
        assertEquals(0, term.row(0).cells[1].attrs)
    }

    @Test
    fun `a truecolour sequence does not leak into the following character`() {
        val term = emulator(columns = 4, rows = 2)
        term.append("\u001B[38;2;1;2;3mA")
        assertEquals(rgbColor(1, 2, 3), term.row(0).cells[0].fg)
        term.append("\u001B[0mB")
        assertEquals(COLOR_DEFAULT, term.row(0).cells[1].fg)
    }

    // --------------------------------------------------------- DEC special set

    @Test
    fun `DEC special graphics maps the line drawing set`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("\u001B(0lqk\u001B(B")
        assertEquals('\u250C', term.row(0).cells[0].first) // l -> upper left corner
        assertEquals('\u2500', term.row(0).cells[1].first) // q -> horizontal line
        assertEquals('\u2510', term.row(0).cells[2].first) // k -> upper right corner
    }

    @Test
    fun `returning to the ASCII set restores plain letters`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("\u001B(0q\u001B(Bq")
        assertEquals('\u2500', term.row(0).cells[0].first)
        assertEquals('q', term.row(0).cells[1].first)
    }

    @Test
    fun `the vertical and junction glyphs are the box set and not ASCII`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("\u001B(0xntu\u001B(B")
        assertEquals('\u2502', term.row(0).cells[0].first) // x -> vertical
        assertEquals('\u253C', term.row(0).cells[1].first) // n -> crossing
        assertEquals('\u251C', term.row(0).cells[2].first) // t -> left tee
        assertEquals('\u2524', term.row(0).cells[3].first) // u -> right tee
    }

    // --------------------------------------------------------------------- UTF-8

    @Test
    fun `a multi byte character split across chunks is decoded exactly once`() {
        val term = emulator(columns = 8, rows = 2)
        val bytes = "h\u00e9llo".toByteArray(Charsets.UTF_8)
        // `h` then the first byte of the two-byte `e-acute`.
        term.append(bytes, 2)
        assertEquals("h", term.line(0))
        term.append(bytes.copyOfRange(2, bytes.size))
        assertEquals("h\u00e9llo", term.line(0))
    }

    @Test
    fun `a four byte character becomes one wide cell holding a surrogate pair`() {
        val term = emulator(columns = 8, rows = 2)
        term.append("\uD83D\uDE00")
        val cell = term.row(0).cells[0]
        assertEquals("\uD83D\uDE00", cell.text())
        assertEquals('\uD83D', cell.first)
        assertEquals('\uDE00', cell.second)
        // Emoji are East Asian Wide, so the grid still aligns behind them.
        assertEquals(2, cell.width)
        assertEquals(2, term.cursorCol)
        assertEquals(0, term.row(0).cells[1].width)
    }

    @Test
    fun `an invalid byte becomes a replacement character rather than breaking the stream`() {
        val term = emulator(columns = 8, rows = 2)
        term.append(byteArrayOf(0xFF.toByte(), 'a'.code.toByte()))
        assertEquals("\uFFFD" + "a", term.line(0))
    }

    @Test
    fun `a full width character occupies two columns`() {
        val term = emulator(columns = 8, rows = 2)
        term.append("\u4E2D")
        assertEquals(2, term.cursorCol)
        assertEquals(2, term.row(0).cells[0].width)
        assertEquals(0, term.row(0).cells[1].width)
    }

    // ----------------------------------------------------------- wrap and controls

    @Test
    fun `autowrap moves the next glyph to the following row`() {
        val term = emulator(columns = 4, rows = 3)
        term.append("abcdX")
        assertEquals("abcd", term.line(0))
        assertEquals("X", term.line(1))
    }

    @Test
    fun `wrap mode off overwrites the last column in place`() {
        val term = emulator(columns = 4, rows = 3)
        term.append("\u001B[?7labcdX")
        assertEquals("abcX", term.line(0))
        assertEquals("", term.line(1))
        term.append("\u001B[?7h")
        assertTrue(term.automaticWrap)
    }

    @Test
    fun `carriage return and backspace move the cursor without erasing`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abc\rXY")
        assertEquals("XYc", term.line(0))
        term.append("\u0008Z")
        assertEquals("XZc", term.line(0))
    }

    @Test
    fun `tab advances to the next multiple of eight`() {
        val term = emulator(columns = 20, rows = 2)
        term.append("a\tb")
        assertEquals(9, term.cursorCol)
        assertEquals('b', term.row(0).cells[8].first)
    }

    @Test
    fun `cursor column and row reports answer through the reply buffer`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("\u001B[3;4H")
        term.append("\u001B[6n")
        assertEquals("\u001B[3;4R", term.takeReplies())
        assertEquals("", term.takeReplies())
    }

    @Test
    fun `primary device attributes are answered`() {
        val term = emulator()
        term.append("\u001B[c")
        assertEquals("\u001B[?6c", term.takeReplies())
    }

    // --------------------------------------------------------------------- resize

    @Test
    fun `resizing narrower truncates each row to the new width`() {
        val term = emulator(columns = 8, rows = 2)
        term.append("abcdefgh")
        term.resize(4, 2)
        assertEquals("abcd", term.line(0))
    }

    @Test
    fun `shrinking rows pushes the lost top rows into history`() {
        val term = emulator(columns = 4, rows = 4, scrollback = 8)
        term.append("a\r\nb\r\nc\r\nd")
        term.resize(4, 2)
        assertEquals(2, term.rows)
        assertEquals(2, term.scrollbackSize)
        assertEquals("a", scrollbackText(term, 0))
        assertEquals("b", scrollbackText(term, 1))
        assertEquals("c", term.line(0))
        assertEquals("d", term.line(1))
    }

    @Test
    fun `growing rows keeps the content next to the cursor`() {
        val term = emulator(columns = 4, rows = 2)
        term.append("aaa\r\nbbb")
        term.resize(4, 4)
        assertEquals("aaa", term.line(2))
        assertEquals("bbb", term.line(3))
        assertEquals(3, term.cursorRow)
    }

    @Test
    fun `a snapshot reset clears both screens and the history`() {
        val term = emulator(columns = 4, rows = 2, scrollback = 8)
        term.append("a\r\nb\r\nc")
        term.append("\u001B[?1049h")
        term.append("alt")
        term.resetForSnapshot()
        assertFalse(term.alternateActive)
        assertEquals(0, term.scrollbackSize)
        assertEquals("", term.line(0))
        assertEquals(0, term.cursorRow)
        assertEquals(0, term.cursorCol)
        assertTrue(term.cursorVisible)
        assertTrue(term.automaticWrap)
    }

    /**
     * The host's snapshot is `SerializeAddon.serialize()` output, which repaints the
     * buffer from the home position and appends the modes itself. Feeding it after a
     * reset has to leave both the screen and the mode in the state the host is in.
     */
    @Test
    fun `a serialized snapshot repaints the screen and restores its modes`() {
        val term = emulator(columns = 10, rows = 3)
        term.resetForSnapshot()
        term.append("hello\u001B[0m\r\nworld\u001B[0m")
        term.append("\u001B[?1h\u001B[?25l")
        assertEquals("hello", term.line(0))
        assertEquals("world", term.line(1))
        assertTrue(term.applicationCursorKeys)
        assertFalse(term.cursorVisible)
    }

    private fun scrollbackText(term: TerminalEmulator, index: Int): String {
        val row = term.scrollbackRow(index)
        return (0 until term.columns).joinToString("") { row.cells[it].text() }.trimEnd()
    }

    @Test
    fun `appending a string and appending its bytes agree`() {
        val bytes = emulator()
        val text = emulator()
        bytes.append("caf\u00e9 \u4e2d".toByteArray(Charsets.UTF_8))
        text.append("caf\u00e9 \u4e2d")
        assertEquals(bytes.line(0), text.line(0))
        assertNotEquals("caf", text.line(0))
    }
}
