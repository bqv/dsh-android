package uk.xa0.dsh.term

/**
 * Keys that cannot be produced by a soft keyboard, encoded as the bytes a PTY
 * expects.
 *
 * This is Android-free on purpose: the encoding tables are exactly the sort of
 * thing that is silently wrong (a Home that sends `ESC[H` where `vim` wants `ESC OH`
 * in application mode, a missing `1;5` modifier on Ctrl+Arrow), and a JVM test can
 * pin every one of them. `ui/TerminalScreen.kt` maps `android.view.Key` onto
 * [TerminalKey] and nothing more.
 *
 * [SoftInput] is the same class of thing one level up — what the *soft* keyboard's
 * edits mean on the wire — and lives here for the same reason: the panel's hidden
 * text field can only be exercised by hand, but the decision it makes on every
 * edit is plain arithmetic over strings.
 */

enum class TerminalKey {
    UP, DOWN, LEFT, RIGHT,
    HOME, END, PAGE_UP, PAGE_DOWN, INSERT, DELETE,
    TAB, ESCAPE, ENTER, BACKSPACE,
    F1, F2, F3, F4, F5, F6, F7, F8, F9, F10, F11, F12,
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

/**
 * The soft keyboard's half of the typing path.
 *
 * A soft keyboard does not send key events; it *edits a text field*. So the panel
 * keeps one invisible [ANCHOR] character in its hidden field, with the cursor after
 * it, and reads every callback as an edit against that anchor:
 *
 * - the anchor is gone and the field is empty: something deleted the character in
 *   front of the cursor. That is a backspace, and it is the *only* reading that
 *   makes one reliable, because every mechanism an IME can use —
 *   `deleteSurroundingText`, an unpaired `KEYCODE_DEL`, an edit that removes the
 *   last character — needs a character to remove first. An empty field is exactly
 *   why a soft backspace used to do nothing at all.
 * - the anchor plus text: that text was committed, so it is typed.
 * - the anchor alone: nothing was typed.
 *
 * While the IME reports a composition (every CJK keyboard, gesture typing, most
 * autocorrect flows) the text in the field is *not* committed. Writing it would
 * type half-formed words into the shell and clear the field out from under the
 * composer, so the panel holds it and writes nothing until the composition ends.
 * `autoCorrect = false` does not turn composition off.
 */
object SoftInput {

    /**
     * Zero-width space: the one character the hidden field holds at rest.
     *
     * Zero-width because the field is invisible but never empty, and the anchor has
     * to be a character an IME will happily delete or insert around without
     * treating it as a word.
     */
    const val ANCHOR: String = "\u200B"

    /** What an IME's own delete means on the wire: DEL, the same byte as the key row. */
    const val DELETE: String = "\u007F"

    /**
     * Reads one edit of the hidden field.
     *
     * @param nextText the field's new text, exactly as the IME reported it
     * @param composing true while the IME reports an in-progress composition
     * @param ctrl true when the panel's Ctrl key is armed
     */
    fun editOf(nextText: String, composing: Boolean, ctrl: Boolean = false): SoftEdit {
        if (composing) return SoftEdit(write = "", composing = true, ctrlUsed = false)
        val anchor = nextText.indexOf(ANCHOR)
        if (anchor < 0) {
            // No anchor and nothing else: the IME deleted it, which is a backspace.
            if (nextText.isEmpty()) return SoftEdit(DELETE, composing = false, ctrlUsed = false)
            // No anchor but text: the IME replaced the field's whole value instead
            // of editing around the anchor. Nothing was visibly deleted, so the text
            // is the commit and no DEL is invented for it.
            return SoftEdit(applyCtrl(nextText, ctrl), composing = false, ctrlUsed = ctrl)
        }
        val committed = nextText.substring(anchor + ANCHOR.length)
        if (committed.isEmpty()) return SoftEdit("", composing = false, ctrlUsed = false)
        return SoftEdit(applyCtrl(committed, ctrl), composing = false, ctrlUsed = ctrl)
    }

    private fun applyCtrl(text: String, ctrl: Boolean): String =
        if (!ctrl) text else text.map { TerminalKeys.typed(it, ctrl = true) }.joinToString("")
}

/**
 * What one edit of the soft keyboard's hidden field means for the PTY.
 *
 * @property write the bytes to write; empty when the edit committed nothing
 * @property composing true while the IME is mid-composition, in which case the panel
 *   must keep the value the IME reported and [write] is empty
 * @property ctrlUsed true when an armed Ctrl was spent on [write], so the panel can
 *   drop the armed state; a backspace never spends it
 */
data class SoftEdit(
    val write: String,
    val composing: Boolean,
    val ctrlUsed: Boolean,
)
