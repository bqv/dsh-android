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

    // ------------------------------------------------------ scroll regions DECSTBM

    /**
     * `ESC[2;3r` splits the screen: a line feed at the region's bottom row scrolls
     * the region and leaves everything outside it alone. `less`, `vim` and `htop`
     * all use it, so losing it corrupts a full-screen application rather than a
     * cosmetic detail — and nothing in the suite observed it before this test.
     */
    @Test
    fun `a scroll region confines scrolling to its rows`() {
        val term = emulator(columns = 4, rows = 4, scrollback = 8)
        term.append("000\r\n111\r\n222\r\n333")
        // Region = rows 1..2 (0-based), cursor on the region's bottom row.
        term.append("\u001B[2;3r\u001B[3;1H")
        term.append("\r\nX")
        assertEquals("000", term.line(0)) // above the region: untouched
        assertEquals("222", term.line(1)) // the region scrolled up over "111"
        assertEquals("X", term.line(2))
        assertEquals("333", term.line(3)) // below the region: untouched
        // A region scroll is a repaint, not history.
        assertEquals(0, term.scrollbackSize)
    }

    /**
     * Paired control for the test above: the same bytes with no DECSTBM. Here the
     * line feed only moves the cursor down a row, so the region test above is
     * observing the region and not merely "some cursor movement happened".
     */
    @Test
    fun `without a scroll region the same line feed only moves the cursor`() {
        val term = emulator(columns = 4, rows = 4, scrollback = 8)
        term.append("000\r\n111\r\n222\r\n333")
        term.append("\u001B[3;1H")
        term.append("\r\nX")
        assertEquals("111", term.line(1))
        assertEquals("222", term.line(2))
        assertEquals("X33", term.line(3))
        assertEquals(0, term.scrollbackSize)
    }

    /**
     * A one-row region is not a region: xterm rejects `ESC[1;1r` rather than
     * scrolling a single row in place, and the cursor then still belongs to the
     * whole screen.
     */
    @Test
    fun `a one-row region is rejected`() {
        val term = emulator(columns = 4, rows = 4, scrollback = 8)
        term.append("000\r\n111\r\n222\r\n333")
        term.append("\u001B[1;1r\u001B[4;1H\r\nX")
        // The rejected region must leave full-screen scrolling in place: "000"
        // leaves the top and becomes history.
        assertEquals("111", term.line(0))
        assertEquals("X", term.line(3))
        assertEquals(1, term.scrollbackSize)
        assertEquals("000", scrollbackText(term, 0))
    }

    // --------------------------------------------------------------- origin mode

    /**
     * DECOM (`?6h`): with a region set, `CSI 1;1H` addresses the region's top-left,
     * and addressing is clamped to the region. This is the pair to the control
     * below — the same sequence has a different meaning in each mode.
     */
    @Test
    fun `origin mode addresses the cursor from the top of the scroll region`() {
        val term = emulator(columns = 4, rows = 4, scrollback = 8)
        term.append("000\r\n111\r\n222\r\n333")
        term.append("\u001B[2;3r") // region = rows 1..2
        term.append("\u001B[?6h")
        term.append("\u001B[1;1H")
        assertEquals(1, term.cursorRow)
        assertEquals(0, term.cursorCol)
        term.append("X")
        assertEquals("X11", term.line(1))
        assertEquals("000", term.line(0))
        // The cursor cannot be addressed outside the region either.
        term.append("\u001B[9;1H")
        assertEquals(2, term.cursorRow)
        assertEquals(0, term.cursorCol)
        // Leaving the mode returns addressing to the whole screen.
        term.append("\u001B[?6l\u001B[1;1H")
        assertEquals(0, term.cursorRow)
    }

    /**
     * Paired control: the identical home sequence without `?6h` addresses the screen
     * from its real origin. Read with the test above, the two prove the mode — not
     * the sequence — decides where the cursor lands.
     */
    @Test
    fun `without origin mode the same home sequence addresses the screen`() {
        val term = emulator(columns = 4, rows = 4, scrollback = 8)
        term.append("000\r\n111\r\n222\r\n333")
        term.append("\u001B[2;3r")
        term.append("\u001B[1;1H")
        assertEquals(0, term.cursorRow)
        term.append("X")
        assertEquals("X00", term.line(0))
        assertEquals("111", term.line(1))
    }

    // --------------------------------------------------------- bounded scrollback

    /**
     * The scrollback is a bounded tail, not a log: at the limit the oldest row is
     * evicted and the newest is kept. Only the count is asserted here because a
     * regression that removes the bound is invisible until the array grows without
     * limit.
     */
    @Test
    fun `the scrollback is bounded to its limit and evicts the oldest row`() {
        val term = emulator(columns = 4, rows = 2, scrollback = 2)
        term.append("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, term.scrollbackSize)
        assertEquals("2", scrollbackText(term, 0))
        assertEquals("3", scrollbackText(term, 1))
        assertNotEquals("1", scrollbackText(term, 0)) // the evicted row is really gone
        assertEquals("4", term.line(0))
        assertEquals("5", term.line(1))
    }

    /**
     * Paired control for the test above: the same bytes with a larger limit keep the
     * row the smaller limit evicted. Read together, the two prove eviction is caused
     * by the *limit* and not by losing rows for some other reason.
     */
    @Test
    fun `a larger scrollback limit keeps the rows the smaller one evicts`() {
        val small = emulator(columns = 4, rows = 2, scrollback = 2)
        val large = emulator(columns = 4, rows = 2, scrollback = 8)
        for (term in listOf(small, large)) term.append("1\r\n2\r\n3\r\n4\r\n5")
        assertEquals(2, small.scrollbackSize)
        assertEquals(3, large.scrollbackSize)
        assertEquals("2", scrollbackText(small, 0))
        assertEquals("1", scrollbackText(large, 0))
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

    /**
     * ANSI mode 4 (`ESC[4h`, IRM) is not `CSI @`: it makes every *character* shift
     * the row, and turning it off restores overwriting. The two halves are the
     * paired controls — the same two glyphs land differently depending on the mode.
     */
    @Test
    fun `insert mode shifts cells right and turning it off overwrites again`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abcdef\u001B[1;1H\u001B[4h")
        term.append("XY")
        assertEquals("XYabcd", term.line(0))
        term.append("\u001B[4l")
        term.append("Z")
        assertEquals("XYZbcd", term.line(0))
    }

    /** Control for the test above: with no `4h`, the same glyphs overwrite. */
    @Test
    fun `without insert mode the same characters overwrite`() {
        val term = emulator(columns = 6, rows = 2)
        term.append("abcdef\u001B[1;1HXY")
        assertEquals("XYcdef", term.line(0))
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

    /**
     * `?1049` is "save the cursor, switch, clear", so `?1049l` has to put the cursor
     * back where it was. The existing `?1049` tests only checked the *content* of the
     * main screen; they stayed green with `saveCursor()` neutered, so the restore was
     * never observed.
     */
    @Test
    fun `leaving the alternate screen restores the cursor saved on entry`() {
        val term = emulator(columns = 6, rows = 3)
        term.append("main\u001B[3;5H")
        term.append("\u001B[?1049h")
        assertEquals(0, term.cursorRow) // the alternate screen starts at home
        assertEquals(0, term.cursorCol)
        term.append("alt")
        term.append("\u001B[?1049l")
        assertEquals("main", term.line(0))
        assertEquals(2, term.cursorRow)
        assertEquals(4, term.cursorCol)
    }

    // ------------------------------------------------------ cursor save and restore

    /**
     * DECSC/DECRC (`ESC 7`, `ESC 8`): the saved cursor carries the rendition as well
     * as the position, so the restored cell must be red even though the cursor was
     * left green by the intervening move.
     */
    @Test
    fun `DECSC and DECRC restore the cursor and its attributes`() {
        val term = emulator(columns = 6, rows = 3)
        term.append("abc\u001B[2;3H\u001B[31m\u001B7")
        term.append("\u001B[1;1H\u001B[32mG")
        assertEquals(0, term.cursorRow)
        assertEquals(1, term.cursorCol)
        assertEquals(2, term.row(0).cells[0].fg) // green: the move really happened
        term.append("\u001B8X")
        assertEquals(1, term.cursorRow)
        assertEquals(3, term.cursorCol)
        assertEquals('X', term.row(1).cells[2].first)
        assertEquals(1, term.row(1).cells[2].fg) // red, restored with the position
    }

    /**
     * `CSI s` / `CSI u` are the other spelling of DECSC/DECRC. They are dispatched
     * separately, so they need their own test: neutering `ESC 7`/`ESC 8` must not
     * make this one fail, and vice versa.
     */
    @Test
    fun `CSI s and CSI u save and restore the cursor too`() {
        val term = emulator(columns = 6, rows = 3)
        term.append("abc\u001B[2;3H\u001B[s")
        term.append("\u001B[1;1H")
        assertEquals(0, term.cursorRow)
        assertEquals(0, term.cursorCol)
        term.append("\u001B[uX")
        assertEquals(1, term.cursorRow)
        assertEquals(3, term.cursorCol)
        assertEquals('X', term.row(1).cells[2].first)
    }

    /** Control: with no DECRC the cursor stays where the last move put it. */
    @Test
    fun `without DECRC the cursor stays where the last move put it`() {
        val term = emulator(columns = 6, rows = 3)
        term.append("abc\u001B[2;3H\u001B[31m\u001B7\u001B[1;1H\u001B[32mX")
        assertEquals(0, term.cursorRow)
        assertEquals(1, term.cursorCol)
        assertEquals('X', term.row(0).cells[0].first)
        assertEquals(2, term.row(0).cells[0].fg)
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

    /**
     * SGR 8 conceals a cell and 28 reveals it again. `ATTR_HIDDEN` was unreachable
     * without these codes, which left the renderer's hidden branch dead code.
     */
    @Test
    fun `SGR 8 conceals a cell and SGR 28 reveals it again`() {
        val term = emulator(columns = 4, rows = 2)
        term.append("\u001B[8mA")
        assertTrue(term.row(0).cells[0].attrs and ATTR_HIDDEN != 0)
        term.append("\u001B[28mB")
        assertEquals(0, term.row(0).cells[1].attrs and ATTR_HIDDEN)
        // A full reset clears it too, like every other attribute.
        term.append("\u001B[8mC\u001B[0mD")
        assertTrue(term.row(0).cells[2].attrs and ATTR_HIDDEN != 0)
        assertEquals(0, term.row(0).cells[3].attrs)
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

    /**
     * REP (`CSI b`) repeats the last printed *graphic* character; intervening SGR
     * must not clear it, because line-drawing programs repaint a run and then
     * repeat it.
     */
    @Test
    fun `REP repeats the last printed character`() {
        val term = emulator(columns = 8, rows = 2)
        term.append("a\u001B[3b")
        assertEquals("aaaa", term.line(0))
        assertEquals(4, term.cursorCol)
        term.append("\u001B[0m\u001B[2b")
        assertEquals("aaaaaa", term.line(0))
        assertEquals(6, term.cursorCol)
    }

    /**
     * Control for the test above: with nothing printed yet there is nothing to
     * repeat, so REP must write nothing — not a space and not the last control. The
     * colour is armed first so that a spurious space would be visible.
     */
    @Test
    fun `REP before any printable character writes nothing`() {
        val term = emulator(columns = 8, rows = 2)
        term.append("\u001B[41m")
        term.append("\u001B[3b")
        assertEquals(0, term.cursorCol)
        assertEquals(COLOR_DEFAULT, term.row(0).cells[0].bg)
        assertEquals(COLOR_DEFAULT, term.row(0).cells[3].bg)
    }

    @Test
    fun `cursor column and row reports answer through the reply buffer`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("\u001B[3;4H")
        term.append("\u001B[6n")
        assertEquals("\u001B[3;4R", term.takeReplies())
        assertEquals("", term.takeReplies())
    }

    /**
     * The same report asked privately (`ESC[?6n`) must be answered in the private
     * form — the pair to the test above, which pins the non-private spelling. An
     * application that asks with the `?` discards a reply without it and waits.
     */
    @Test
    fun `a private cursor position report is answered in the private form`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("\u001B[3;4H")
        term.append("\u001B[?6n")
        assertEquals("\u001B[?3;4R", term.takeReplies())
    }

    @Test
    fun `primary device attributes are answered`() {
        val term = emulator()
        term.append("\u001B[c")
        assertEquals("\u001B[?6c", term.takeReplies())
    }

    /**
     * DA2 (`ESC[>c`) is a *separate* request from DA1, and a program that waits for
     * it hangs forever if the reply never comes. The exact reply is pinned so this
     * cannot silently degrade into "any reply at all".
     */
    @Test
    fun `secondary device attributes are answered in the secondary form`() {
        val term = emulator()
        term.append("\u001B[>c")
        assertEquals("\u001B[>0;1;0c", term.takeReplies())
        // And DA1 is still DA1: the two requests must not answer each other's form.
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

    // ------------------------------------------- the host's real snapshot format

    /**
     * Real `SerializeAddon` output, not a hand-written approximation of it.
     *
     * Generated with `@xterm/headless` 6.0.0 + `@xterm/addon-serialize` 0.14.0 from
     * a 10x4 terminal that had printed `hello`, switched to green, printed `world`,
     * then set application-cursor-keys (`?1h`) and insert mode (`4h`) and *hid the
     * cursor* (`?25l`):
     *
     *     t.write("hello\x1b[32m\r\nworld\x1b[0m\x1b[?1h\x1b[4h\x1b[?25l")
     *     ser.serialize() == "hello\r\n\x1b[32mworld\x1b[0m\x1b[?1h\x1b[4h"
     *
     * Note what the dump does *not* contain: `serialize()` emits no `?25` for either
     * state, so a hidden-cursor terminal produces a dump that is byte-identical to a
     * visible one. That is the whole of W3 — a snapshot cannot carry cursor
     * visibility, so the emulator must not invent it.
     */
    private val realMainScreenDump = "hello\r\n\u001B[32mworld\u001B[0m\u001B[?1h\u001B[4h"

    /**
     * Real dump of an `htop`-shaped alternate-screen session, 12x4, from the same
     * addon: `$ htop` on the main screen, `?1049h`, then a box-drawn panel with a
     * reversed selection and application cursor keys. The box characters are literal
     * code points — the addon serializes the buffer's cells, not the `ESC(0` that
     * produced them.
     */
    private val realAlternateScreenDump =
        "\$ htop\u001B[?1049h\u001B[H\u250C\u2500\u2500\u2500\u2510\r\n\u2502 1 \u001B[7m2 \u001B[0m\u001B[?1h"

    /**
     * The host's snapshot is real `SerializeAddon.serialize()` output: it repaints
     * the whole buffer from the home position and appends the modes itself. The
     * expected rows and cursor below are xterm's own buffer state for the dump.
     */
    @Test
    fun `a real SerializeAddon dump repaints the screen and restores its modes`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("stale\r\ncontent")
        term.resetForSnapshot()
        term.append(realMainScreenDump)
        assertEquals("hello", term.line(0))
        assertEquals("world", term.line(1))
        assertEquals("", term.line(2))
        assertEquals(1, term.cursorRow) // xterm's own cursorY for the dump
        assertEquals(5, term.cursorCol) // and cursorX
        assertEquals(2, term.row(1).cells[0].fg) // the dump's [32m landed on 'w'
        assertTrue(term.applicationCursorKeys) // ?1h is in the trailing mode block
    }

    /**
     * The addon serializes the *whole* buffer, scrollback included, as rows. A dump
     * longer than the screen must scroll through and land in the emulator's own
     * history, or a reconnect silently drops everything above the fold. Generated
     * from five rows written into a 10x3 terminal: xterm's visible rows are `c`, `d`,
     * `e`, and its scrollback holds `a`, `b`.
     */
    @Test
    fun `a real whole-buffer dump replays its scrollback into the emulator's history`() {
        val term = emulator(columns = 10, rows = 3, scrollback = 16)
        term.resetForSnapshot()
        term.append("a\r\nb\r\nc\r\nd\r\ne")
        assertEquals("c", term.line(0))
        assertEquals("d", term.line(1))
        assertEquals("e", term.line(2))
        assertEquals(2, term.scrollbackSize)
        assertEquals("a", scrollbackText(term, 0))
        assertEquals("b", scrollbackText(term, 1))
    }

    /** The addon never emits `?1049l`: the replayed parser must stay on the alt screen. */
    @Test
    fun `a real alternate-screen dump leaves the parser on the alternate screen`() {
        val term = emulator(columns = 12, rows = 4)
        term.resetForSnapshot()
        term.append(realAlternateScreenDump)
        assertTrue(term.alternateActive)
        assertEquals("\u250C\u2500\u2500\u2500\u2510", term.line(0))
        assertEquals("\u2502 1 2", term.line(1))
        assertEquals(1, term.cursorRow)
        assertEquals(6, term.cursorCol)
        assertTrue(term.applicationCursorKeys)
    }

    // ------------------------------------------------------ W3: cursor visibility

    /**
     * W3. `htop`, `less` and `vim` hide the cursor, and the host's dump cannot say so
     * (see [realMainScreenDump]); the previous value is the only honest information
     * available. Forcing `cursorVisible = true` in `resetForSnapshot` made a spurious
     * block cursor reappear in the middle of the application's screen after every
     * re-entry or reconnect.
     */
    @Test
    fun `a snapshot reset does not force a hidden cursor visible`() {
        val term = emulator(columns = 10, rows = 4)
        term.append("\u001B[?25l")
        assertFalse(term.cursorVisible)
        term.resetForSnapshot()
        term.append(realMainScreenDump)
        assertFalse(term.cursorVisible)
    }

    /**
     * Paired control for the test above: a cursor that was visible is still visible
     * after the reset. Together the two prove the reset *carries* the previous value
     * instead of picking one.
     */
    @Test
    fun `a snapshot reset leaves a visible cursor visible`() {
        val term = emulator(columns = 10, rows = 4)
        assertTrue(term.cursorVisible)
        term.resetForSnapshot()
        term.append(realMainScreenDump)
        assertTrue(term.cursorVisible)
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

    /**
     * A BEL is counted, and an OSC's BEL terminator is not a bell.
     *
     * That distinction is the whole test: both are the byte 0x07, the second is
     * consumed by the string parser before the C0 handler ever sees it, and a counter
     * that missed it would notify the reader every time a program set its window
     * title — which `htop` does on every repaint.
     */
    @Test
    fun `a bell is counted but a string terminator is not a bell`() {
        val term = emulator()
        term.append("\u001B]0;htop\u0007")
        assertEquals(0, term.bellCount)

        term.append("\u0007")
        assertEquals("one bell is one", 1, term.bellCount)

        term.append("x\u0007\u0007")
        assertEquals("two more bells are two more", 3, term.bellCount)
        assertEquals("and the text still rendered", "x", term.line(0))
    }
}
