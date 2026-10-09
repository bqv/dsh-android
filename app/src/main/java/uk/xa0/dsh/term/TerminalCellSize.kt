package uk.xa0.dsh.term

/**
 * The terminal's cell size, as the one number the reader chooses.
 *
 * The panel does not lay text out per line the way the rest of the app does — it
 * measures one cell and draws a character grid on it (`ui/TerminalScreen.kt`). So
 * *the font size is the cell size*: the cell's width is the advance of one glyph in
 * the terminal's face, its height is the line box, and the number of columns and
 * rows the host is told about is the viewport divided by those two. One number moves
 * all four, which is why it is modelled here rather than as a loose `.sp` literal in
 * the composable.
 *
 * The advance is not guessed: the bundled face is Inconsolata, and its `hmtx` table
 * gives every glyph 500 units of a 1000-unit em — 0.5 em, which is what the panel's
 * `measure("MMMMMMMMMM") / 10` finds at runtime. [ADVANCE_EM] exists so arithmetic
 * about the grid can be done and tested without a device.
 *
 * [LINE_RATIO] is the ratio the panel shipped with — 12sp text in a 15sp cell — and it
 * is kept: the cell is what backgrounds and the block cursor are painted on, so
 * changing the ratio independently of the face would make the block cursor stop
 * matching the glyph it sits behind.
 */
data class TerminalCellSize(val fontSp: Float) {

    /** The line box, which is the cell's height. */
    val lineHeightSp: Float get() = fontSp * LINE_RATIO

    /** One step larger. [MAX_FONT_SP] is where it stops, not where it wraps. */
    fun larger(): TerminalCellSize = of(fontSp + STEP_SP)

    fun smaller(): TerminalCellSize = of(fontSp - STEP_SP)

    /**
     * [steps] whole steps at once, clamped once at the end.
     *
     * A pinch reports the steps it crossed as they are crossed, so this is the
     * gesture's one arithmetic: a cell cannot jump the range in a single report, and
     * a report that lands past an end simply lands on it.
     */
    fun stepped(steps: Int): TerminalCellSize = of(fontSp + steps * STEP_SP)

    /** At the end of the range, so a control can be disabled rather than appear to work. */
    val smallest: Boolean get() = fontSp <= MIN_FONT_SP
    val largest: Boolean get() = fontSp >= MAX_FONT_SP

    companion object {

        /**
         * 10sp, down from the 12sp the panel drew before this was choosable.
         *
         * The cell is 0.5 em wide, so a point of font size is worth about 8% of the
         * columns on a phone; 12sp put 53 columns on a 320dp screen and 68 on a
         * 411dp one, and the smaller default puts 64 and 82 there. It is a default,
         * not a limit — the pinch and the panel's two size buttons go either way.
         */
        const val DEFAULT_FONT_SP = 10f

        const val MIN_FONT_SP = 7f
        const val MAX_FONT_SP = 20f
        const val STEP_SP = 1f

        /** The shipped cell: 12sp of text in a 15sp line. */
        const val LINE_RATIO = 1.25f

        /** Inconsolata's `hmtx` advance, as a fraction of the em. */
        const val ADVANCE_EM = 0.5f

        /** A size from anywhere — a stored preference, a gesture — clamped and finite. */
        fun of(fontSp: Float): TerminalCellSize =
            TerminalCellSize(if (fontSp.isFinite()) fontSp.coerceIn(MIN_FONT_SP, MAX_FONT_SP) else DEFAULT_FONT_SP)
    }
}

/**
 * How many whole cells of [cellPx] fit along [extentPx].
 *
 * This is the division the panel and the renderer both need: the panel reports the
 * columns and rows it can show, and the renderer draws exactly the rows the panel
 * reported. Two copies of it would be two answers to one question, and the whole
 * point of the grid is that the screen and the PTY agree about it.
 *
 * The floor is not here — the panel floors at the host's own two columns, the
 * renderer floors at "no rows at all" for an unmeasured box — but an extent too
 * small for one cell is zero cells rather than a negative count or a crash.
 */
fun gridCells(extentPx: Int, cellPx: Float): Int =
    if (cellPx <= 0f || !cellPx.isFinite()) 0 else (extentPx / cellPx).toInt().coerceAtLeast(0)
