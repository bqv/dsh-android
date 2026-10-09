package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The grid the panel draws and the grid the host is told about are one number.
 *
 * A cell size that changed the drawn grid without telling the host would be worse
 * than no feature: the PTY lays its bytes out for the size it believes in, so a
 * panel drawing a different one cuts every long line at the wrong column. The
 * composable's own half of that chain cannot be asserted here — the
 * `TextMeasurer` metrics are stubs on this JVM (`docs/HANDOFF.md`) — but the
 * arithmetic either side of it can, and it is the arithmetic that decides both
 * answers: [gridCells] turns the cell into columns and rows, and
 * [TerminalAttachmentSession.measure] is what puts that pair on the wire *and* sizes
 * the screen to it.
 */
class TerminalCellResizeTest {

    private val environment = TerminalEnvironmentInfo(
        cwd = "/workspace", maxInputBytes = 65536, maxCols = 500, maxRows = 200, scrollback = 1000,
    )

    private fun info(cols: Int, rows: Int) = TerminalInfo(
        id = "term-1",
        title = "shell",
        shell = "/bin/bash",
        cwd = "/workspace",
        cols = cols,
        rows = rows,
        state = TerminalState.RUNNING,
    )

    private fun session(
        cols: Int = 80,
        rows: Int = 24,
        limits: TerminalEnvironmentInfo = environment,
    ) = TerminalAttachmentSession(
        TerminalEmulator(cols, rows, limits.scrollback),
        limits,
        info(cols, rows),
    )

    /**
     * What the panel measures for [cell] on a viewport, in the panel's own two steps:
     * the cell in px (the face's advance wide, the line box tall — `TextMeasurer`'s
     * job on the device, arithmetic here), then the viewport divided by it.
     */
    private fun measuredGrid(
        widthPx: Int,
        heightPx: Int,
        density: Float,
        cell: TerminalCellSize,
    ): Pair<Int, Int> = gridCells(widthPx, TerminalCellSize.ADVANCE_EM * cell.fontSp * density)
        .coerceAtLeast(2) to gridCells(heightPx, cell.lineHeightSp * density).coerceAtLeast(1)

    @Test
    fun `a smaller cell is a bigger grid, and the bigger grid is what is sent`() {
        val session = session()

        // A 320dp viewport at density 2: 640px wide, 1200px of grid. The shipped
        // 12sp cell is 12px × 30px, so 53 columns and 40 rows; the new 10sp default is
        // 10px × 25px, so 64 and 48.
        val shipped = measuredGrid(640, 1200, 2f, TerminalCellSize(12f))
        assertEquals(53 to 40, shipped)
        val smaller = measuredGrid(640, 1200, 2f, TerminalCellSize(TerminalCellSize.DEFAULT_FONT_SP))
        assertEquals(64 to 48, smaller)

        // The pair is what the host is told, and the screen is sized to the same pair
        // — not to the raw measurement, and not left behind at the previous grid.
        assertEquals(shipped, session.measure(shipped.first, shipped.second))
        assertEquals(shipped.first, session.emulator.columns)
        assertEquals(shipped.second, session.emulator.rows)

        assertEquals(smaller, session.measure(smaller.first, smaller.second))
        assertEquals(smaller.first, session.emulator.columns)
        assertEquals(smaller.second, session.emulator.rows)

        // ...and a re-measure at the size the host already has asks for nothing, so a
        // step that does not move the grid does not chatter at the PTY.
        assertNull(session.measure(smaller.first, smaller.second))
    }

    @Test
    fun `the host's limits still win over a very small cell`() {
        // 7sp on a 411dp viewport measures more columns and rows than this deployment
        // allows, and the host refuses an oversized grid rather than clipping it.
        val limits = environment.copy(maxCols = 100, maxRows = 40)
        val session = session(limits = limits)

        val measured = measuredGrid(1078, 1200, 2.625f, TerminalCellSize(TerminalCellSize.MIN_FONT_SP))
        assertTrue("the raw measurement must exceed the limit for this test to mean anything", measured.first > 100)
        assertTrue(measured.second > 40)

        // What goes on the wire is what the screen becomes: the clamped pair, in both
        // places, never the measurement at one end and the clamp at the other.
        assertEquals(100 to 40, session.measure(measured.first, measured.second))
        assertEquals(100, session.emulator.columns)
        assertEquals(40, session.emulator.rows)
    }
}
