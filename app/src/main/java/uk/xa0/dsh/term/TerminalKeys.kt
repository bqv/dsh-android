package uk.xa0.dsh.term

/**
 * Keys that cannot be produced by a soft keyboard, encoded as the bytes a PTY
 * expects.
 *
 * This is Android-free on purpose: the encoding tables are exactly the sort of
 * thing that is silently wrong (a Home that sends `ESC[H` where `vim` wants `ESC OH`
 * in application mode, a missing `1;5` modifier on Ctrl+Arrow), and a JVM test can
 * pin every one of them. `ui/TerminalScreen.kt` maps a native key code onto
 * [TerminalKey] and nothing more.
 *
 * [TerminalInput] is the same class of thing one level up — what the *soft*
 * keyboard's input connection means on the wire — and lives in `term/` for the same
 * reason: the connection can only be exercised by hand, but the conversions it makes
 * on every commit are plain arithmetic over strings.
 */

enum class TerminalKey {
    UP, DOWN, LEFT, RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    TAB, ESCAPE, ENTER, BACKSPACE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12;

    companion object {

        /**
         * The key a native key code names, or null when it is not one a terminal owns.
         *
         * The mapping is by *key code* and lives here, next to the encodings, for one
         * reason: the panel now reads key events from two places — Compose's preview
         * handler and the native editor's own `dispatchKeyEvent` — and the two must
         * agree on every key. A Compose `Key` *is* a native key code on Android, so
         * `TerminalScreen` passes `key.nativeKeyCode` through this and there is
         * exactly one table.
         */
        fun ofNativeKeyCode(keyCode: Int): TerminalKey? = when (keyCode) {
            19 -> UP              // KEYCODE_DPAD_UP
            20 -> DOWN            // KEYCODE_DPAD_DOWN
            21 -> LEFT            // KEYCODE_DPAD_LEFT
            22 -> RIGHT           // KEYCODE_DPAD_RIGHT
            122 -> HOME           // KEYCODE_MOVE_HOME
            123 -> END            // KEYCODE_MOVE_END
            92 -> PAGE_UP         // KEYCODE_PAGE_UP
            93 -> PAGE_DOWN       // KEYCODE_PAGE_DOWN
            124 -> INSERT         // KEYCODE_INSERT
            112 -> DELETE         // KEYCODE_FORWARD_DEL
            61 -> TAB             // KEYCODE_TAB
            111 -> ESCAPE         // KEYCODE_ESCAPE
            66, 160 -> ENTER      // KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER
            67 -> BACKSPACE       // KEYCODE_DEL
            131 -> F1
            132 -> F2
            133 -> F3
            134 -> F4
            135 -> F5
            136 -> F6
            137 -> F7
            138 -> F8
            139 -> F9
            140 -> F10
            141 -> F11
            142 -> F12
            else -> null
        }
    }
}

object TerminalKeys {

    /**
     * `ESC[1;<mod>` / `SS3` encodings, following xterm.
     *
     * [applicationCursorKeys] is DECCKM (`?1`): a full-screen app that sets it
     * expects `SS3` (`ESC O A`) for an unmodified arrow, and the grid editor or
     * pager misreads a `CSI` arrow as Home/End. Modified keys always use the `CSI`
     * form, which is what xterm does — the `1;<mod>` parameter has no `SS3` spelling.
     */
    fun key(
        key: TerminalKey,
        ctrl: Boolean = false,
        alt: Boolean = false,
        shift: Boolean = false,
        applicationCursorKeys: Boolean = false,
    ): String {
        val modifier = 1 + (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val modified = modifier != 1

        fun cursor(final: Char, ss3: Char): String =
            if (modified) "\u001B[1;$modifier$final"
            else if (applicationCursorKeys) "\u001BO$ss3"
            else "\u001B[$final"

        fun tilde(code: Int): String =
            if (modified) "\u001B[$code;$modifier~" else "\u001B[$code~"

        fun function(ss3: Char, code: Int): String =
            if (code <= 4) {
                if (modified) "\u001B[1;$modifier$ss3" else "\u001BO$ss3"
            } else {
                tilde(code)
            }

        return when (key) {
            TerminalKey.UP -> cursor('A', 'A')
            TerminalKey.DOWN -> cursor('B', 'B')
            TerminalKey.RIGHT -> cursor('C', 'C')
            TerminalKey.LEFT -> cursor('D', 'D')
            TerminalKey.HOME -> cursor('H', 'H')
            TerminalKey.END -> cursor('F', 'F')
            TerminalKey.INSERT -> tilde(2)
            TerminalKey.DELETE -> tilde(3)
            TerminalKey.PAGE_UP -> tilde(5)
            TerminalKey.PAGE_DOWN -> tilde(6)
            // CBT: a shell's completion menu reads Shift+Tab as "go back".
            TerminalKey.TAB -> if (shift || ctrl) "\u001B[Z" else "\t"
            TerminalKey.ESCAPE -> "\u001B"
            TerminalKey.ENTER -> "\r"
            // DEL, not BS: that is what xterm's backspace sends, and readline is
            // configured for it on every host this app talks to.
            TerminalKey.BACKSPACE -> "\u007F"
            TerminalKey.F1 -> function('P', 1)
            TerminalKey.F2 -> function('Q', 2)
            TerminalKey.F3 -> function('R', 3)
            TerminalKey.F4 -> function('S', 4)
            TerminalKey.F5 -> function('P', 15)
            TerminalKey.F6 -> function('Q', 17)
            TerminalKey.F7 -> function('R', 18)
            TerminalKey.F8 -> function('S', 19)
            TerminalKey.F9 -> function('P', 20)
            TerminalKey.F10 -> function('Q', 21)
            TerminalKey.F11 -> function('R', 23)
            TerminalKey.F12 -> function('S', 24)
        }
    }

    /**
     * The C0 byte for Ctrl+[c], or null.
     *
     * Ctrl+A..Z map to 1..26; the punctuation chords (`C-@`, `C-[`, `C-\`, `C-]`,
     * `C-^`, `C-_`) are the ones a shell actually binds, and Ctrl+Space is NUL.
     */
    fun controlOf(c: Char): Char? = when {
        c in 'a'..'z' -> (c - 'a' + 1).toChar()
        c in 'A'..'Z' -> (c - 'A' + 1).toChar()
        c == '@' -> 0.toChar()
        c == '[' -> 27.toChar()
        c == '\\' -> 28.toChar()
        c == ']' -> 29.toChar()
        c == '^' -> 30.toChar()
        c == '_' -> 31.toChar()
        c == ' ' -> 0.toChar()
        c == '?' -> 127.toChar()
        else -> null
    }

    /**
     * A typed character, with the modifier prefixes a terminal understands.
     *
     * Alt is a literal ESC prefix (the "meta sends escape" convention); Ctrl is the
     * C0 mapping and falls through to the bare character for a chord that has none,
     * so Ctrl+1 types `1` rather than nothing at all.
     */
    fun typed(c: Char, ctrl: Boolean = false, alt: Boolean = false): String {
        val body = if (ctrl) (controlOf(c) ?: c).toString() else c.toString()
        return if (alt) "\u001B$body" else body
    }
}
