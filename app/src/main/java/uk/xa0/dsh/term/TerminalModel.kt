package uk.xa0.dsh.term

/**
 * The render model for one terminal cell, its colours and its attributes.
 *
 * The web client hands this job to xterm.js and hands the app a *serialized*
 * screen, so the app has to own a screen model of its own. Everything in `term/`
 * is deliberately free of Android imports: the parser is verified by JVM unit
 * tests against bytes, which is the only verification available here (no device
 * work is permitted on this feature), and that is only possible if nothing in the
 * package needs `android.jar`.
 *
 * Why not Termux's `terminal-emulator` AAR (which does resolve, see the report):
 * its `TerminalEmulator` constructor takes a `TerminalSessionClient`, whose every
 * method takes a `TerminalSession` — an Android class — and its screen is packed
 * into `char[]`/`long[]` with `WIDE_CHAR` sentinels that its own `terminal-view`
 * renderer decodes. Adopting it would have meant an Android dependency through the
 * parser and still writing a screen-model extraction by hand, with no JVM tests on
 * either half.
 */

/** "Use the terminal's default" sentinel for a foreground or background colour. */
const val COLOR_DEFAULT = -1

/**
 * Marks a 24-bit truecolor value, so one `Int` can carry "default", a 0–255
 * palette index and a direct RGB triple without a wrapper object per cell.
 */
const val COLOR_RGB_FLAG = 0x1000000

/** Packs an SGR 38;2;r;g;b / 48;2;r;g;b triple into the [COLOR_RGB_FLAG] form. */
fun rgbColor(r: Int, g: Int, b: Int): Int =
    COLOR_RGB_FLAG or (r and 0xFF shl 16) or (g and 0xFF shl 8) or (b and 0xFF)

const val ATTR_BOLD = 1 shl 0
const val ATTR_DIM = 1 shl 1
const val ATTR_ITALIC = 1 shl 2
const val ATTR_UNDERLINE = 1 shl 3
const val ATTR_REVERSE = 1 shl 4
const val ATTR_STRIKE = 1 shl 5
const val ATTR_HIDDEN = 1 shl 6

/**
 * One screen cell.
 *
 * A cell is a mutable class rather than a data class on purpose: a repainting
 * `htop` rewrites most of a 100×40 grid many times a second, and a fresh
 * immutable cell per write would allocate thousands of objects per repaint for no
 * benefit — the UI reads the model in place (see `ui/TerminalScreen.kt`).
 *
 * Non-BMP code points live in [second] rather than in a `String`, so the common
 * (BMP) path never allocates. [width] 0 marks the trailing half of a wide cell.
 */
class TerminalCell {
    var first: Char = ' '
    var second: Char = '\u0000'
    /** Zero-width marks (combining accents) that follow the base character. */
    var combining: String = ""
    var width: Int = 1
    var fg: Int = COLOR_DEFAULT
    var bg: Int = COLOR_DEFAULT
    var attrs: Int = 0

    /** The grapheme this cell draws, as the UI's text run needs it. */
    fun text(): String {
        if (second == '\u0000' && combining.isEmpty()) return first.toString()
        val text = if (second == '\u0000') first.toString() else "$first$second"
        return if (combining.isEmpty()) text else text + combining
    }

    fun copyFrom(other: TerminalCell) {
        first = other.first
        second = other.second
        combining = other.combining
        width = other.width
        fg = other.fg
        bg = other.bg
        attrs = other.attrs
    }

    /**
     * Resets to a blank cell carrying [bg].
     *
     * Erase operations keep the *current* background (xterm's "background colour
     * erase" rule) because every TUI that paints a panel relies on it; resetting
     * to the default background here would leave holes in `htop`'s panels.
     */
    fun blank(bg: Int = COLOR_DEFAULT) {
        first = ' '
        second = '\u0000'
        combining = ""
        width = 1
        fg = COLOR_DEFAULT
        this.bg = bg
        attrs = 0
    }

    fun isBlank(): Boolean =
        first == ' ' && second == '\u0000' && combining.isEmpty() &&
            fg == COLOR_DEFAULT && bg == COLOR_DEFAULT && attrs == 0
}

/** One screen row. Rows are fixed-width, which is what makes addressing trivial. */
class TerminalRow(val columns: Int) {
    val cells: Array<TerminalCell> = Array(columns) { TerminalCell() }

    fun blank(bg: Int = COLOR_DEFAULT) {
        for (cell in cells) cell.blank(bg)
    }

    fun resize(columns: Int): TerminalRow {
        val next = TerminalRow(columns)
        val keep = minOf(columns, this.columns)
        for (i in 0 until keep) next.cells[i].copyFrom(cells[i])
        return next
    }

    /** An independent row. Scrolling hands rows to the scrollback and then reuses them. */
    fun copy(): TerminalRow {
        val next = TerminalRow(columns)
        for (i in 0 until columns) next.cells[i].copyFrom(cells[i])
        return next
    }
}

/**
 * `wcwidth`: the number of columns a code point occupies.
 *
 * Needed for correct alignment, not for decoration — a full-width CJK glyph drawn
 * as one cell shifts every column after it, and a combining mark written as its own
 * cell does the same. The ranges are the standard ones (Markus Kuhn's tables,
 * trimmed to the scripts that realistically appear in a shell).
 */
object TermWidth {

    private val COMBINING = intArrayOf(
        0x0300, 0x036F, 0x0483, 0x0489, 0x0591, 0x05BD, 0x05BF, 0x05BF,
        0x05C1, 0x05C2, 0x05C4, 0x05C5, 0x0610, 0x061A, 0x064B, 0x065F,
        0x0670, 0x0670, 0x06D6, 0x06DC, 0x06DF, 0x06E4, 0x06E7, 0x06E8,
        0x06EA, 0x06ED, 0x0711, 0x0711, 0x0730, 0x074A, 0x07A6, 0x07B0,
        0x0900, 0x0902, 0x093C, 0x093C, 0x0941, 0x0948, 0x094D, 0x094D,
        0x0951, 0x0954, 0x0962, 0x0963, 0x0E31, 0x0E31, 0x0E34, 0x0E3A,
        0x0E47, 0x0E4E, 0x1AB0, 0x1AFF, 0x1DC0, 0x1DFF, 0x200B, 0x200F,
        0x2028, 0x202E, 0x20D0, 0x20F0, 0xFE00, 0xFE0F, 0xFE20, 0xFE2F,
        0xFEFF, 0xFEFF, 0xE0100, 0xE01EF,
    )

    private val WIDE = intArrayOf(
        0x1100, 0x115F, 0x2329, 0x232A, 0x2E80, 0x303E, 0x3041, 0x33FF,
        0x3400, 0x4DBF, 0x4E00, 0x9FFF, 0xA000, 0xA4CF, 0xA960, 0xA97F,
        0xAC00, 0xD7A3, 0xF900, 0xFAFF, 0xFE10, 0xFE19, 0xFE30, 0xFE6F,
        0xFF00, 0xFF60, 0xFFE0, 0xFFE6, 0x1F300, 0x1F64F, 0x1F900, 0x1F9FF,
        0x20000, 0x3FFFD,
    )

    /** 0 for a combining mark, 2 for a full-width glyph, 1 otherwise. */
    fun of(codePoint: Int): Int = when {
        codePoint < 0x20 -> 0
        codePoint < 0x7F -> 1
        inRanges(COMBINING, codePoint) -> 0
        inRanges(WIDE, codePoint) -> 2
        codePoint < 0xA0 -> 0
        else -> 1
    }

    fun isCombining(codePoint: Int): Boolean = inRanges(COMBINING, codePoint)

    private fun inRanges(ranges: IntArray, codePoint: Int): Boolean {
        var low = 0
        var high = ranges.size / 2 - 1
        while (low <= high) {
            val mid = (low + high) / 2
            val start = ranges[mid * 2]
            val end = ranges[mid * 2 + 1]
            when {
                codePoint < start -> high = mid - 1
                codePoint > end -> low = mid + 1
                else -> return true
            }
        }
        return false
    }
}
