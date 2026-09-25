package uk.xa0.dsh.term

/**
 * A terminal screen and the byte-stream parser that feeds it.
 *
 * The host runs the real PTY through xterm.js and ships the app an escape-sequence
 * stream: a fresh attachment starts with a *serialized screen* (which is itself a
 * repaint, not text) followed by live output. So the app needs an emulator for the
 * same reason the web client needs xterm — and because it must be native, this one
 * lives here.
 *
 * Scope is "good enough to run `htop`", which is the bar the user set: cursor
 * addressing, EL/ED, line and character insert/delete, scroll regions, the
 * private modes a full-screen TUI switches, SGR through truecolor, and the DEC
 * line-drawing charset `htop` draws its boxes with.
 *
 * Not implemented, deliberately: mouse reporting (the events can be parsed but a
 * phone has no pointer to report), sixel/kitty graphics, OSC 8 hyperlinks,
 * bracketed paste, and scrollback *rendering*. The scrollback tail is retained so
 * a scroll view can be added later; it is not drawn.
 *
 * Threading: this class is not synchronised. It is mutated on the main thread by
 * the view model's stream collector and read on the main thread by the Canvas, so
 * in-place mutation needs no lock — and the UI reads cells directly rather than a
 * copy (see `ui/TerminalScreen.kt`).
 */
class TerminalEmulator(columns: Int, rows: Int, scrollback: Int = 1000) {

    var columns: Int = columns.coerceAtLeast(2)
        private set

    var rows: Int = rows.coerceAtLeast(1)
        private set

    /** Bounded history of lines that scrolled off the top. Not rendered in the MVP. */
    var scrollbackLimit: Int = scrollback.coerceAtLeast(0)
        private set

    var cursorRow = 0
        private set

    var cursorCol = 0
        private set

    var cursorVisible = true
        private set

    /** DECAWM (`?7`): when off, output past the last column overwrites it in place. */
    var automaticWrap = true
        private set

    /** DECCKM (`?1`): arrows report as `SS3` in application mode. */
    var applicationCursorKeys = false
        private set

    var alternateActive = false
        private set

    var title: String = ""
        private set

    /** Anything the emulator must send *back* to the PTY (cursor reports, DA). */
    private val replies = StringBuilder()

    private var lines: Array<TerminalRow> = Array(this.rows) { TerminalRow(this.columns) }
    private var mainLines: Array<TerminalRow> = lines
    private var altLines: Array<TerminalRow> = Array(this.rows) { TerminalRow(this.columns) }

    private val scrollback = ArrayDeque<TerminalRow>()

    /** DECSTBM. Full screen until a program sets a region (vim, less). */
    private var scrollTop = 0
    private var scrollBottom = this.rows - 1

    private var fg = COLOR_DEFAULT
    private var bg = COLOR_DEFAULT
    private var attrs = 0

    /** Deferred wrap, as in xterm: the wrap happens when the *next* glyph is written. */
    private var pendingWrap = false
    private var insertMode = false

    /** DECOM (`?6`): cursor addressing is relative to the scroll region. */
    private var originMode = false

    /** DEC Special Graphics, selected with `ESC(0`, shifted in with SO/SI. */
    private var g0Special = false
    private var g1Special = false
    private var shiftedG1 = false

    private var savedCursorRow = 0
    private var savedCursorCol = 0
    private var savedFg = COLOR_DEFAULT
    private var savedBg = COLOR_DEFAULT
    private var savedAttrs = 0
    private var savedG0 = false
    private var savedG1 = false
    private var savedShifted = false
    private var savedPendingWrap = false

    // ------------------------------------------------------------------ parsing

    private var state = GROUND
    private var lastPrintable: Char = '\u0000'
    private var osc = StringBuilder()

    private val params = IntArray(MAX_PARAMS)
    private var paramCount = 0
    private var privateMarker: Char = '\u0000'
    private var intermediate: Char = '\u0000'

    private var utf8Remaining = 0
    private var utf8CodePoint = 0
    private var utf8Min = 0

    /** Feeds a chunk of UTF-8 output. Split sequences resume on the next call. */
    fun append(data: ByteArray, length: Int = data.size) {
        var i = 0
        while (i < length) {
            processByte(data[i].toInt() and 0xFF)
            i++
        }
    }

    fun append(text: String) = append(text.toByteArray(Charsets.UTF_8))

    private fun processByte(b: Int) {
        if (utf8Remaining > 0) {
            if (b and 0xC0 == 0x80) {
                utf8CodePoint = (utf8CodePoint shl 6) or (b and 0x3F)
                utf8Remaining--
                if (utf8Remaining == 0) {
                    emit(if (utf8CodePoint < utf8Min) REPLACEMENT else utf8CodePoint)
                }
                return
            }
            // A sequence broken mid-way: report it, then reinterpret this byte as
            // the start of a new one rather than swallowing it.
            utf8Remaining = 0
            emit(REPLACEMENT)
        }
        when {
            b < 0x80 -> processAscii(b)
            b and 0xE0 == 0xC0 -> {
                utf8CodePoint = b and 0x1F; utf8Remaining = 1; utf8Min = 0x80
            }
            b and 0xF0 == 0xE0 -> {
                utf8CodePoint = b and 0x0F; utf8Remaining = 2; utf8Min = 0x800
            }
            b and 0xF8 == 0xF0 -> {
                utf8CodePoint = b and 0x07; utf8Remaining = 3; utf8Min = 0x10000
            }
            else -> emit(REPLACEMENT)
        }
    }

    private fun processAscii(b: Int) {
        val c = b.toChar()

        // ESC is the one byte whose meaning depends on the state; a string
        // sequence ends on `ESC \` (ST), not on ESC alone.
        if (c == ESC) {
            if (state == OSC || state == DCS) {
                state = if (state == OSC) OSC_ESC else DCS_ESC
                return
            }
            state = ESCAPE
            privateMarker = '\u0000'
            intermediate = '\u0000'
            paramCount = 0
            return
        }
        if (state == OSC_ESC || state == DCS_ESC) {
            // `ESC \` closes the string; anything else continues it.
            if (c == '\\') {
                finishString()
                state = GROUND
                return
            }
            if (state == DCS_ESC) state = DCS else state = OSC
            // fall through: the byte still belongs to the string
        }
        if (state == OSC) {
            if (c == '\u0007') { finishString(); state = GROUND } else if (osc.length < 4096) osc.append(c)
            return
        }
        if (state == DCS) return

        // C0 controls execute in every non-string state, and do not leave a
        // sequence in progress.
        if (b < 0x20 || b == 0x7F) {
            control(c)
            return
        }

        when (state) {
            GROUND -> emit(b)
            ESCAPE -> escape(c)
            CHARSET -> {
                when (intermediate) {
                    '(' -> g0Special = c == '0'
                    ')' -> g1Special = c == '0'
                }
                state = GROUND
            }
            CSI -> csi(c)
            else -> state = GROUND
        }
    }

    private fun control(c: Char) {
        when (c) {
            '\u0008' -> { if (cursorCol > 0) { cursorCol--; pendingWrap = false } }
            '\u0009' -> {
                val next = ((cursorCol / 8) + 1) * 8
                cursorCol = if (next >= columns) columns - 1 else next
                pendingWrap = false
            }
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\r' -> { cursorCol = 0; pendingWrap = false }
            '\u000E' -> shiftedG1 = true
            '\u000F' -> shiftedG1 = false
            else -> Unit // BEL/ENQ/DEL carry nothing this client acts on.
        }
    }

    private fun escape(c: Char) {
        when (c) {
            '(' , ')' -> { intermediate = c; state = CHARSET }
            '[' -> {
                state = CSI
                paramCount = 0
                privateMarker = '\u0000'
                intermediate = '\u0000'
                // The parameter accumulator is index-addressed, so a sequence that
                // omits trailing parameters must not inherit the previous one's.
                // Without this, `ESC[5H` followed by `ESC[3H` reads as column 53.
                java.util.Arrays.fill(params, 0)
            }
            ']' -> { state = OSC; osc = StringBuilder() }
            'P', '^', '_' -> state = DCS
            'D' -> lineFeed()
            'E' -> { cursorCol = 0; lineFeed() }
            'M' -> reverseIndex()
            '7' -> saveCursor()
            '8' -> restoreCursor()
            'c' -> reset()
            '=', '>' -> Unit // DECKPAM/DECKPNM: keypad mode, unused without a keypad.
            else -> Unit
        }
        if (state == ESCAPE) state = GROUND
    }

    private fun csi(c: Char) {
        if (c in '0'..'9' || c == ';' || c == ':') {
            if (c == ';' || c == ':') {
                if (paramCount < MAX_PARAMS - 1) paramCount++
            } else {
                if (paramCount >= MAX_PARAMS) return
                params[paramCount] = (params[paramCount] * 10 + (c - '0')).coerceAtMost(99_999)
            }
            return
        }
        if (c == '?' || c == '>' || c == '<' || c == '=') {
            privateMarker = c
            return
        }
        if (c in ' '..'/' ) {
            intermediate = c
            return
        }
        state = GROUND
        dispatchCsi(c)
    }

    private fun dispatchCsi(final: Char) {
        val n = count()
        when (final) {
            'A' -> moveCursor(0, -maxOf(1, param(0, 1)))
            'B' -> moveCursor(0, maxOf(1, param(0, 1)))
            'C' -> moveCursor(maxOf(1, param(0, 1)), 0)
            'D' -> moveCursor(-maxOf(1, param(0, 1)), 0)
            'E' -> { cursorCol = 0; moveCursor(0, maxOf(1, param(0, 1))) }
            'F' -> { cursorCol = 0; moveCursor(0, -maxOf(1, param(0, 1))) }
            'G', '\u0060' -> setCursorColumn(param(0, 1) - 1)
            'H', 'f' -> setCursorPosition(param(0, 1) - 1, param(1, 1) - 1)
            'd' -> setCursorRow(param(0, 1) - 1)
            'J' -> eraseInDisplay(param(0, 0))
            'K' -> eraseInLine(param(0, 0))
            'L' -> insertLines(maxOf(1, param(0, 1)))
            'M' -> deleteLines(maxOf(1, param(0, 1)))
            '@' -> insertChars(maxOf(1, param(0, 1)))
            'P' -> deleteChars(maxOf(1, param(0, 1)))
            'X' -> eraseChars(maxOf(1, param(0, 1)))
            'S' -> scrollUpRegion(maxOf(1, param(0, 1)))
            'T' -> scrollDownRegion(maxOf(1, param(0, 1)))
            'r' -> {
                val top = param(0, 1) - 1
                val bottom = if (n >= 2) param(1, rows) - 1 else rows - 1
                if (top in 0 until bottom && bottom < rows) {
                    scrollTop = top
                    scrollBottom = bottom
                    setCursorPosition(0, 0)
                }
            }
            'm' -> selectGraphicRendition(n)
            'h' -> setMode(true)
            'l' -> setMode(false)
            's' -> saveCursor()
            'u' -> restoreCursor()
            'n' -> deviceStatusReport()
            'c' -> {
                // Primary DA: answer as a VT102, which is all a shell needs to know.
                if (privateMarker != '>') replies.append("\u001B[?6c")
            }
            'b' -> if (lastPrintable != '\u0000') repeatLast(lastPrintable, maxOf(1, param(0, 1)))
            't' -> Unit // Window manipulation: nothing a phone-sized panel should obey.
            else -> Unit
        }
    }

    private fun count(): Int = paramCount + 1
    private fun param(index: Int, default: Int): Int {
        if (index > paramCount) return default
        val value = params[index]
        return if (value == 0) default else value
    }

    /** SGR. `0` resets everything, which is also the `htop` repaint path. */
    private fun selectGraphicRendition(n: Int) {
        if (n == 0) {
            fg = COLOR_DEFAULT
            bg = COLOR_DEFAULT
            attrs = 0
            return
        }
        var i = 0
        while (i < n) {
            val code = params[i]
            when {
                code == 0 -> { fg = COLOR_DEFAULT; bg = COLOR_DEFAULT; attrs = 0 }
                code == 1 -> attrs = attrs or ATTR_BOLD
                code == 2 -> attrs = attrs or ATTR_DIM
                code == 3 -> attrs = attrs or ATTR_ITALIC
                code == 4 -> attrs = attrs or ATTR_UNDERLINE
                code == 7 -> attrs = attrs or ATTR_REVERSE
                code == 9 -> attrs = attrs or ATTR_STRIKE
                code == 21 || code == 22 -> attrs = attrs and (ATTR_BOLD or ATTR_DIM).inv()
                code == 23 -> attrs = attrs and ATTR_ITALIC.inv()
                code == 24 -> attrs = attrs and ATTR_UNDERLINE.inv()
                code == 27 -> attrs = attrs and ATTR_REVERSE.inv()
                code == 29 -> attrs = attrs and ATTR_STRIKE.inv()
                code in 30..37 -> fg = code - 30
                code == 39 -> fg = COLOR_DEFAULT
                code in 40..47 -> bg = code - 40
                code == 49 -> bg = COLOR_DEFAULT
                code in 90..97 -> fg = code - 90 + 8
                code in 100..107 -> bg = code - 100 + 8
                code == 38 || code == 48 -> {
                    val (colour, consumed) = extendedColour(i, n)
                    if (colour != null) { if (code == 38) fg = colour else bg = colour }
                    i += consumed
                }
                else -> Unit
            }
            i++
        }
    }

    /**
     * `38;5;n` / `38;2;r;g;b`, and xterm's colon-separated spelling.
     *
     * Returns the colour and how many *extra* parameters it consumed. A malformed
     * or truncated sequence yields null but still consumes what it saw, so a bad
     * SGR cannot be misread as a pile of unrelated attribute changes.
     */
    private fun extendedColour(start: Int, n: Int): Pair<Int?, Int> {
        if (start + 1 >= n) return null to 0
        return when (params[start + 1]) {
            5 -> if (start + 2 < n) (params[start + 2] and 0xFF) to 2 else null to 1
            2 -> if (start + 4 < n) {
                rgbColor(params[start + 2], params[start + 3], params[start + 4]) to 4
            } else {
                null to (n - start - 1)
            }
            else -> null to 1
        }
    }

    /**
     * DEC private modes (`?1049`, `?25`, `?7`) and the ANSI set (`4`, insert).
     *
     * `?1049` is the alternate screen: a full-screen TUI switches on entry and off
     * on exit, and the main screen's contents and cursor must come back untouched.
     */
    private fun setMode(enabled: Boolean) {
        val n = count()
        var i = 0
        while (i < n) {
            val mode = params[i]
            if (privateMarker == '?') {
                when (mode) {
                    1 -> applicationCursorKeys = enabled
                    6 -> originMode = enabled
                    7 -> { automaticWrap = enabled; if (!enabled) pendingWrap = false }
                    25 -> cursorVisible = enabled
                    1049 -> if (enabled) enterAlternateScreen() else leaveAlternateScreen()
                    47, 1047 -> if (enabled) enterAlternateScreen() else leaveAlternateScreen()
                    1048 -> if (enabled) saveCursor() else restoreCursor()
                    else -> Unit // Mouse reporting and focus events are accepted and ignored.
                }
            } else {
                when (mode) {
                    4 -> insertMode = enabled
                    else -> Unit
                }
            }
            i++
        }
    }

    private fun deviceStatusReport() {
        when (param(0, 0)) {
            5 -> replies.append("\u001B[0n")
            6 -> replies.append("\u001B[${cursorRow + 1};${cursorCol + 1}R")
            else -> Unit
        }
    }

    /** Bytes the PTY must receive back. Drained by the client after each chunk. */
    fun takeReplies(): String {
        if (replies.isEmpty()) return ""
        val out = replies.toString()
        replies.setLength(0)
        return out
    }

    // ------------------------------------------------------------------ screen

    fun row(index: Int): TerminalRow = lines[index.coerceIn(0, rows - 1)]

    val scrollbackSize: Int get() = scrollback.size

    fun scrollbackRow(index: Int): TerminalRow = scrollback[index]

    fun isSpecialCharsetActive(): Boolean = if (shiftedG1) g1Special else g0Special

    private fun emit(codePoint: Int) = putCodePoint(codePoint)

    /** DEC Special Graphics: `htop`'s box characters arrive as plain ASCII 0x5F–0x7E. */
    private fun mapCharset(codePoint: Int): Int {
        if (codePoint < 0x5F || codePoint > 0x7E) return codePoint
        if (!isSpecialCharsetActive()) return codePoint
        return ACS[codePoint - 0x5F].code
    }

    private fun putCodePoint(raw: Int) {
        val codePoint = mapCharset(raw)
        val width = if (codePoint == REPLACEMENT) 1 else TermWidth.of(codePoint)

        if (width == 0) {
            // A combining mark belongs to the cell the cursor just left.
            val col = if (pendingWrap) cursorCol else cursorCol - 1
            if (col in 0 until columns) {
                val cell = lines[cursorRow].cells[col]
                cell.combining += String(Character.toChars(codePoint))
                lastPrintable = '\u0000'
            }
            return
        }

        if (pendingWrap && automaticWrap) {
            cursorCol = 0
            pendingWrap = false
            advanceLine()
        }
        if (width == 2 && cursorCol >= columns - 1) {
            if (!automaticWrap) { cursorCol = columns - 1 } else {
                lines[cursorRow].cells[cursorCol].blank(bg)
                cursorCol = 0
                advanceLine()
            }
        }

        val row = lines[cursorRow]
        if (insertMode) shiftCellsRight(row, cursorCol, width)
        val cell = row.cells[cursorCol]
        if (codePoint <= 0xFFFF) {
            cell.first = codePoint.toChar()
            cell.second = '\u0000'
        } else {
            val pair = Character.toChars(codePoint)
            cell.first = pair[0]
            cell.second = pair[1]
        }
        cell.combining = ""
        cell.width = width
        cell.fg = fg
        cell.bg = bg
        cell.attrs = attrs
        if (width == 2 && cursorCol + 1 < columns) {
            val tail = row.cells[cursorCol + 1]
            tail.first = ' '
            tail.second = '\u0000'
            tail.combining = ""
            tail.width = 0
            tail.fg = fg
            tail.bg = bg
            tail.attrs = attrs
            lastPrintable = '\u0000'
        } else {
            lastPrintable = if (codePoint <= 0xFFFF) codePoint.toChar() else '\u0000'
        }

        if (cursorCol + width >= columns) {
            if (automaticWrap) {
                cursorCol = columns - 1
                pendingWrap = true
            } else {
                cursorCol = columns - 1
            }
        } else {
            cursorCol += width
        }
    }

    /** Repaints the last printable character (`CSI b`), used by line-drawing apps. */
    private fun repeatLast(c: Char, count: Int) {
        var i = 0
        while (i < count) {
            putCodePoint(c.code)
            i++
        }
    }

    private fun lineFeed() {
        pendingWrap = false
        advanceLine()
    }

    /**
     * Moves to the next line, scrolling only at the bottom of the scroll region.
     *
     * `pendingWrap` is cleared by the caller where a wrap has already been
     * consumed; a bare LF must not leave the cursor "pending" on the old row.
     */
    private fun advanceLine() {
        if (cursorRow == scrollBottom) {
            scrollUpRegion(1)
        } else if (cursorRow < rows - 1) {
            cursorRow++
        }
    }

    private fun reverseIndex() {
        pendingWrap = false
        if (cursorRow == scrollTop) scrollDownRegion(1) else if (cursorRow > 0) cursorRow--
    }

    private fun scrollUpRegion(n: Int) {
        val count = n.coerceAtMost(scrollBottom - scrollTop + 1)
        repeat(count) {
            val dropped = lines[scrollTop]
            // Only the primary screen keeps history, and only for lines leaving the
            // top of the *whole* screen — a region scroll is a repaint, not history.
            // The row is *copied* because `copyRow` below overwrites it in place.
            if (!alternateActive && scrollTop == 0 && scrollbackLimit > 0) pushScrollback(dropped.copy())
            for (i in scrollTop until scrollBottom) {
                copyRow(lines[i + 1], lines[i])
            }
            lines[scrollBottom].blank(bg)
        }
    }

    private fun scrollDownRegion(n: Int) {
        val count = n.coerceAtMost(scrollBottom - scrollTop + 1)
        repeat(count) {
            for (i in scrollBottom downTo scrollTop + 1) {
                copyRow(lines[i - 1], lines[i])
            }
            lines[scrollTop].blank(bg)
        }
    }

    private fun pushScrollback(row: TerminalRow) {
        scrollback.addLast(row)
        while (scrollback.size > scrollbackLimit) scrollback.removeFirst()
    }

    private fun copyRow(from: TerminalRow, to: TerminalRow) {
        val keep = minOf(from.columns, to.columns)
        var i = 0
        while (i < keep) {
            to.cells[i].copyFrom(from.cells[i])
            i++
        }
        while (i < to.columns) {
            to.cells[i].blank(bg)
            i++
        }
    }

    private fun moveCursor(dx: Int, dy: Int) {
        pendingWrap = false
        cursorCol = (cursorCol + dx).coerceIn(0, columns - 1)
        cursorRow = (cursorRow + dy).coerceIn(0, rows - 1)
    }

    private fun setCursorPosition(row: Int, col: Int) {
        pendingWrap = false
        val base = if (originMode) scrollTop else 0
        val limit = if (originMode) scrollBottom else rows - 1
        cursorRow = (row + base).coerceIn(base, limit)
        cursorCol = col.coerceIn(0, columns - 1)
    }

    private fun setCursorRow(row: Int) {
        pendingWrap = false
        val base = if (originMode) scrollTop else 0
        val limit = if (originMode) scrollBottom else rows - 1
        cursorRow = (row + base).coerceIn(base, limit)
    }

    private fun setCursorColumn(col: Int) {
        pendingWrap = false
        cursorCol = col.coerceIn(0, columns - 1)
    }

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> {
                eraseInLine(0)
                for (r in cursorRow + 1 until rows) lines[r].blank(bg)
            }
            1 -> {
                eraseInLine(1)
                for (r in 0 until cursorRow) lines[r].blank(bg)
            }
            2 -> for (r in 0 until rows) lines[r].blank(bg)
            3 -> scrollback.clear()
            else -> Unit
        }
    }

    private fun eraseInLine(mode: Int) {
        val row = lines[cursorRow]
        when (mode) {
            0 -> { var c = cursorCol; while (c < columns) { row.cells[c].blank(bg); c++ } }
            1 -> { var c = 0; while (c <= cursorCol && c < columns) { row.cells[c].blank(bg); c++ } }
            2 -> row.blank(bg)
            else -> Unit
        }
    }

    private fun insertLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceAtMost(scrollBottom - cursorRow + 1)
        var i = scrollBottom
        while (i >= cursorRow + count) {
            copyRow(lines[i - count], lines[i])
            i--
        }
        for (r in cursorRow until cursorRow + count) lines[r].blank(bg)
    }

    private fun deleteLines(n: Int) {
        if (cursorRow < scrollTop || cursorRow > scrollBottom) return
        val count = n.coerceAtMost(scrollBottom - cursorRow + 1)
        var i = cursorRow
        while (i <= scrollBottom - count) {
            copyRow(lines[i + count], lines[i])
            i++
        }
        for (r in scrollBottom - count + 1..scrollBottom) lines[r].blank(bg)
    }

    private fun insertChars(n: Int) {
        val row = lines[cursorRow]
        val count = n.coerceAtMost(columns - cursorCol)
        var i = columns - 1
        while (i >= cursorCol + count) {
            row.cells[i].copyFrom(row.cells[i - count])
            i--
        }
        for (c in cursorCol until cursorCol + count) row.cells[c].blank(bg)
    }

    private fun deleteChars(n: Int) {
        val row = lines[cursorRow]
        val count = n.coerceAtMost(columns - cursorCol)
        var i = cursorCol
        while (i + count < columns) {
            row.cells[i].copyFrom(row.cells[i + count])
            i++
        }
        for (c in columns - count until columns) row.cells[c].blank(bg)
    }

    private fun eraseChars(n: Int) {
        val row = lines[cursorRow]
        var c = cursorCol
        val end = minOf(columns, cursorCol + n)
        while (c < end) { row.cells[c].blank(bg); c++ }
    }

    private fun shiftCellsRight(row: TerminalRow, from: Int, count: Int) {
        var i = columns - 1
        while (i >= from + count) {
            row.cells[i].copyFrom(row.cells[i - count])
            i--
        }
        for (c in from until minOf(columns, from + count)) row.cells[c].blank(bg)
    }

    private fun saveCursor() {
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        savedFg = fg
        savedBg = bg
        savedAttrs = attrs
        savedG0 = g0Special
        savedG1 = g1Special
        savedShifted = shiftedG1
        savedPendingWrap = pendingWrap
    }

    private fun restoreCursor() {
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, columns - 1)
        fg = savedFg
        bg = savedBg
        attrs = savedAttrs
        g0Special = savedG0
        g1Special = savedG1
        shiftedG1 = savedShifted
        pendingWrap = savedPendingWrap
    }

    private fun enterAlternateScreen() {
        if (alternateActive) return
        // `?1049` is "save the cursor, switch, clear" — the saved position is what
        // `?1049l` restores, and xterm's own serializer relies on it when it replays
        // an alternate-screen session into a fresh terminal.
        saveCursor()
        mainLines = lines
        alternateActive = true
        lines = altLines
        if (lines.size != rows) lines = Array(rows) { TerminalRow(columns) }
        for (r in lines) r.blank(bg)
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
    }

    private fun leaveAlternateScreen() {
        if (!alternateActive) return
        altLines = lines
        alternateActive = false
        lines = mainLines
        cursorRow = savedCursorRow.coerceIn(0, rows - 1)
        cursorCol = savedCursorCol.coerceIn(0, columns - 1)
        pendingWrap = false
    }

    private fun reset() {
        fg = COLOR_DEFAULT
        bg = COLOR_DEFAULT
        attrs = 0
        pendingWrap = false
        insertMode = false
        g0Special = false
        g1Special = false
        shiftedG1 = false
        automaticWrap = true
        cursorVisible = true
        applicationCursorKeys = false
        originMode = false
        cursorRow = 0
        cursorCol = 0
        scrollTop = 0
        scrollBottom = rows - 1
        alternateActive = false
        lines = mainLines
        for (r in mainLines) r.blank(COLOR_DEFAULT)
        for (r in altLines) r.blank(COLOR_DEFAULT)
        scrollback.clear()
    }

    /**
     * Puts the screen in the state a fresh emulator would be in, ready for a
     * `terminal/follow` snapshot.
     *
     * The host's snapshot is xterm's `SerializeAddon.serialize()` output, and that
     * addon's contract is that the string is written into a *new* terminal: it
     * repaints the whole buffer from the home position, appends `?1049h` itself when
     * the app is on the alternate screen, and restores the modes at the end. Feeding
     * it into a dirty screen would leave whatever the previous generation wrote in
     * cells the snapshot does not repaint, and would scroll it into the wrong place.
     *
     * Dimensions are preserved: the caller sizes the grid from `info` first.
     */
    fun resetForSnapshot() {
        fg = COLOR_DEFAULT
        bg = COLOR_DEFAULT
        attrs = 0
        insertMode = false
        g0Special = false
        g1Special = false
        shiftedG1 = false
        automaticWrap = true
        cursorVisible = true
        applicationCursorKeys = false
        originMode = false
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
        scrollTop = 0
        scrollBottom = rows - 1
        alternateActive = false
        lines = mainLines
        for (r in mainLines) r.blank(COLOR_DEFAULT)
        for (r in altLines) r.blank(COLOR_DEFAULT)
        scrollback.clear()
        state = GROUND
        utf8Remaining = 0
        replies.setLength(0)
    }

    private fun finishString() {
        if (state == OSC || state == OSC_ESC) {
            // OSC 0/2 set the window title; the tab label uses it.
            val text = osc.toString()
            val semicolon = text.indexOf(';')
            if (semicolon > 0) {
                val code = text.substring(0, semicolon)
                if (code == "0" || code == "2") title = text.substring(semicolon + 1).take(120)
            }
        }
        osc = StringBuilder()
    }

    /**
     * Changes the grid, keeping the reader's place.
     *
     * Rows are dropped from the top and pushed to the scrollback, so shrinking
     * (which happens every time the soft keyboard opens) keeps the prompt and the
     * cursor's line on screen. Growing adds rows above the old content for the same
     * reason — the alternative, keeping content at the top, slides the prompt up
     * under the header whenever the keyboard closes.
     */
    fun resize(columns: Int, rows: Int) {
        val nextColumns = columns.coerceAtLeast(2)
        val nextRows = rows.coerceAtLeast(1)
        if (nextColumns == this.columns && nextRows == this.rows) return

        val oldRows = this.rows
        if (alternateActive) {
            altLines = reflow(altLines, oldRows, nextColumns, nextRows, toScrollback = false)
            // The main screen is restored by `?1049l`; it has to come back at the
            // new size, or leaving a full-screen app resizes the grid to nothing.
            mainLines = reflow(mainLines, oldRows, nextColumns, nextRows, toScrollback = false)
            lines = altLines
        } else {
            mainLines = reflow(mainLines, oldRows, nextColumns, nextRows, toScrollback = true)
            altLines = Array(nextRows) { TerminalRow(nextColumns) }
            lines = mainLines
        }

        this.columns = nextColumns
        this.rows = nextRows
        val dropped = maxOf(0, oldRows - nextRows)
        val offset = if (nextRows > oldRows) nextRows - oldRows else 0
        cursorRow = (cursorRow - dropped + offset).coerceIn(0, nextRows - 1)
        cursorCol = cursorCol.coerceIn(0, nextColumns - 1)
        pendingWrap = false
        // A region set by DECSTBM is not preserved across a resize by xterm either;
        // keeping stale bounds here would leave scrolls writing outside the grid.
        scrollTop = 0
        scrollBottom = nextRows - 1
    }

    private fun reflow(
        old: Array<TerminalRow>,
        oldRows: Int,
        nextColumns: Int,
        nextRows: Int,
        toScrollback: Boolean,
    ): Array<TerminalRow> {
        val dropped = maxOf(0, oldRows - nextRows)
        val offset = maxOf(0, nextRows - oldRows)
        val next = Array(nextRows) { TerminalRow(nextColumns) }
        for (i in 0 until dropped) {
            val row = old[i].resize(nextColumns)
            if (toScrollback && scrollbackLimit > 0) pushScrollback(row)
        }
        val keep = minOf(nextRows, oldRows)
        for (i in 0 until keep) {
            val source = old[i + dropped]
            next[i + offset] = if (source.columns == nextColumns) source else source.resize(nextColumns)
        }
        return next
    }

    /** The DEC Special Graphics set, in code-point order from `_` to `~`. */
    private companion object {
        const val GROUND = 0
        const val ESCAPE = 1
        const val CSI = 2
        const val CHARSET = 3
        const val OSC = 4
        const val OSC_ESC = 5
        const val DCS = 6
        const val DCS_ESC = 7
        const val MAX_PARAMS = 24
        const val ESC = '\u001B'
        const val REPLACEMENT = 0xFFFD

        val ACS: CharArray = charArrayOf(
            '\u00A0', '\u25C6', '\u2592', '\u2409', '\u240C', '\u240D', '\u240A', '\u00B0',
            '\u00B1', '\u2424', '\u240B', '\u2518', '\u2510', '\u250C', '\u2514', '\u253C',
            '\u23BA', '\u23BB', '\u2500', '\u23BC', '\u23BD', '\u251C', '\u2524', '\u2534',
            '\u252C', '\u2502', '\u2264', '\u2265', '\u03C0', '\u2260', '\u00A3', '\u00B7',
        )
    }
}
