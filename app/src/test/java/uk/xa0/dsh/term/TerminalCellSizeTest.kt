package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cell size, and the grid it implies.
 *
 * This is arithmetic, not a rendering claim: the panel measures a cell and divides
 * the viewport by it, so what a size *means* for the number of columns is decided
 * here and nowhere else. The measurements themselves (the advance the face really
 * has, the line box the text engine really produces) are `TextMeasurer`'s and cannot
 * be asserted on this JVM — `docs/HANDOFF.md` records that Robolectric lays text out
 * with stub metrics — which is exactly why the advance is a named constant taken from
 * the font's own `hmtx` table rather than a number only a phone can produce.
 */
class TerminalCellSizeTest {

    // ------------------------------------------------------------------ the size

    @Test
    fun `the default cell is smaller than the one the panel used to draw`() {
        // The panel drew 12sp of Inconsolata in a 15sp line before the size became
        // choosable. The point of the change is that the default is smaller than that.
        assertTrue(
            "the default must be smaller than the 12sp cell this replaced, was ${TerminalCellSize.DEFAULT_FONT_SP}",
            TerminalCellSize.DEFAULT_FONT_SP < 12f,
        )
        assertEquals(15f, TerminalCellSize(12f).lineHeightSp, 0.0001f)
        assertTrue(TerminalCellSize.DEFAULT_FONT_SP > TerminalCellSize.MIN_FONT_SP)
    }

    @Test
    fun `a step moves one point and stops at the ends`() {
        val size = TerminalCellSize.of(10f)

        assertEquals(11f, size.larger().fontSp, 0f)
        assertEquals(9f, size.smaller().fontSp, 0f)
        assertEquals(13f, size.stepped(3).fontSp, 0f)
        assertEquals(8f, size.stepped(-2).fontSp, 0f)

        val smallest = TerminalCellSize.of(TerminalCellSize.MIN_FONT_SP)
        assertTrue(smallest.smallest)
        assertEquals(TerminalCellSize.MIN_FONT_SP, smallest.smaller().fontSp, 0f)
        assertEquals(TerminalCellSize.MIN_FONT_SP, smallest.stepped(-5).fontSp, 0f)

        val largest = TerminalCellSize.of(TerminalCellSize.MAX_FONT_SP)
        assertTrue(largest.largest)
        assertEquals(TerminalCellSize.MAX_FONT_SP, largest.larger().fontSp, 0f)
        // A report that lands past an end lands on it; it does not wrap to the other.
        assertEquals(TerminalCellSize.MAX_FONT_SP, largest.stepped(4).fontSp, 0f)
    }

    @Test
    fun `a size from anywhere is clamped and finite`() {
        // A stored preference written by another version, or edited under `run-as`.
        assertEquals(TerminalCellSize.MIN_FONT_SP, TerminalCellSize.of(0f).fontSp, 0f)
        assertEquals(TerminalCellSize.MIN_FONT_SP, TerminalCellSize.of(-40f).fontSp, 0f)
        assertEquals(TerminalCellSize.MAX_FONT_SP, TerminalCellSize.of(96f).fontSp, 0f)
        // NaN survives coerceIn's comparisons, so it needs the explicit case: a NaN
        // sp would reach the text engine as a NaN font size.
        assertEquals(
            TerminalCellSize.DEFAULT_FONT_SP,
            TerminalCellSize.of(Float.NaN).fontSp,
            0f,
        )
    }

    // ----------------------------------------------------------------- the grid

    /**
     * The arithmetic, stated as arithmetic.
     *
     * A monospace cell is `ADVANCE_EM` of the em wide and the line box tall, and the
     * viewport is `widthDp × density` px. Density cancels between the two, which is
     * why the same size gives the same columns on any screen of that dp width.
     */
    private fun columnsAt(widthDp: Int, density: Float, fontSp: Float): Int =
        gridCells(
            extentPx = (widthDp * density).toInt(),
            cellPx = TerminalCellSize.ADVANCE_EM * fontSp * density,
        )

    private fun rowsAt(heightDp: Int, density: Float, fontSp: Float): Int =
        gridCells(
            extentPx = (heightDp * density).toInt(),
            cellPx = fontSp * TerminalCellSize.LINE_RATIO * density,
        )

    @Test
    fun `the phone's two widths, before and after the smaller default`() {
        // 320dp at density 2: 640px of viewport. A 12sp cell is 0.5 × 12 × 2 = 12px
        // wide, so 640 / 12 = 53 columns; a 10sp cell is 10px, so 640 / 10 = 64.
        assertEquals(53, columnsAt(320, 2f, 12f))
        assertEquals(64, columnsAt(320, 2f, TerminalCellSize.DEFAULT_FONT_SP))

        // 411dp at density 2: 822px. 822 / 12 = 68 (truncated); 822 / 10 = 82.
        assertEquals(68, columnsAt(411, 2f, 12f))
        assertEquals(82, columnsAt(411, 2f, TerminalCellSize.DEFAULT_FONT_SP))
    }

    @Test
    fun `the density cancels, so a dp width is what decides the columns`() {
        // The same 411dp screen at a real phone's density gives the same answer,
        // because both the viewport and the cell are scaled by it.
        assertEquals(82, columnsAt(411, 2.625f, TerminalCellSize.DEFAULT_FONT_SP))
        assertEquals(64, columnsAt(320, 3f, TerminalCellSize.DEFAULT_FONT_SP))
        // The old cell at that density, for the same reason.
        assertEquals(68, columnsAt(411, 2.625f, 12f))
    }

    @Test
    fun `a smaller cell fits more rows as well as more columns`() {
        // 600dp of grid at density 2 is 1200px. The line box is 1.25 × the font size:
        // 15sp → 30px → 40 rows, and 12.5sp → 25px → 48.
        assertEquals(40, rowsAt(600, 2f, 12f))
        assertEquals(48, rowsAt(600, 2f, TerminalCellSize.DEFAULT_FONT_SP))
    }

    @Test
    fun `an unmeasured box is no cells, never a negative count`() {
        assertEquals(0, gridCells(0, 10f))
        assertEquals(0, gridCells(4, 5f))
        assertEquals(0, gridCells(100, 0f))
        assertEquals(0, gridCells(100, Float.NaN))
    }
}
