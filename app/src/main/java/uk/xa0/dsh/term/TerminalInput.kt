package uk.xa0.dsh.term

/**
 * What a soft keyboard's *input connection* means on the wire.
 *
 * The panel used to keep an invisible `BasicTextField` holding one zero-width anchor
 * and read every edit as a delta against it. That layer produced two measured
 * defects on the emulator, both of them in the IME round trip rather than in the
 * injector: an 8-key burst in one `input keyevent` call echoed `aabcdefgh` once in
 * three (a re-committed character — the anchor was still in the field when the next
 * key arrived), and `KeyboardType.Ascii` handed the shell to GBoard's composer, so
 * `vim` reached the PTY as `vI'm` and no letter echoed until the word committed.
 *
 * So the text field is gone: the panel now hosts a native editor whose
 * `InputConnection` writes what the user actually did straight to the PTY, the way
 * Termux does. This object is the part of that decision that is plain arithmetic
 * over strings, and it is here — Android-free — so a JVM test can pin it. The
 * `InputConnection` itself is not JVM-testable and stays device-only.
 *
 * [TerminalKeys] still owns the key *encodings*; this owns the two conversions the
 * connection needs on top of them: committed text to bytes, and a delete request to
 * a delete byte.
 */
object TerminalInput {

    /**
     * What an IME's own delete means on the wire: DEL, the same byte as the key row
     * and as xterm's backspace. Never BS — readline is configured for DEL.
     */
    const val BACKSPACE: String = "\u007F"

    /**
     * A delete *after* the cursor, which is the same key `TerminalKeys` encodes for
     * the keyboard's Delete. Termux ignores a right-length delete entirely; there is
     * no reason for us to lose it.
     */
    const val FORWARD_DELETE: String = "\u001B[3~"

    /**
     * The zero-width space the removed hidden field kept as its rest value.
     *
     * Nothing puts it on the wire any more because nothing holds it, but a stray one
     * — from a clipboard, a hostile IME, or a leftover of the old machinery — is
     * dropped here rather than typed into the shell, where it would be an invisible
     * character in a command.
     */
    const val ANCHOR: Char = '\u200B'

    /**
     * The character a Ctrl chord is *about*, from the key code rather than the text.
     *
     * Android's `KeyEvent.getUnicodeChar()` returns 0 for Ctrl+letter — the modifier
     * suppresses the character — and Compose's `utf16CodePoint` is that same value.
     * So a chord cannot be read from the character at all: a keyboard with its own
     * Ctrl key (the only way a phone can produce these) sends KEYCODE_D with
     * metaState CTRL and *no* unicode char, and a handler that bails on `code <= 0`
     * one line before it looks for a chord drops every one of them. Read the key
     * code instead: KEYCODE_A..Z = 29..54, KEYCODE_0..9 = 7..16, plus the
     * punctuation chords a shell actually binds.
     */
    fun chordCharacterOf(keyCode: Int): Char? = when (keyCode) {
        in 29..54 -> 'a' + (keyCode - 29)   // KEYCODE_A..KEYCODE_Z
        in 7..16 -> '0' + (keyCode - 7)     // KEYCODE_0..KEYCODE_9
        68 -> '`'
        69 -> '-'
        70 -> '='
        71 -> '['
        72 -> ']'
        73 -> '\\'
        74 -> ';'
        75 -> '\''
        76 -> '/'
        77 -> '@'
        else -> null
    }

    /**
     * The bytes for text an IME committed.
     *
     * @param ctrl true when the panel's Ctrl cap is armed, in which case each
     *   character goes out as its C0 control byte — that is how a soft keyboard's
     *   Ctrl reaches the shell, since the modifier never appears in the committed
     *   text. A character with no control mapping is typed as itself, the same
     *   fallback [TerminalKeys.typed] makes, so an armed Ctrl cannot swallow a key.
     */
    fun bytesFor(text: CharSequence, ctrl: Boolean = false): String {
        val out = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            if (codePoint == ANCHOR.code) continue
            if (ctrl) {
                val chord = TerminalKeys.controlOf(codePoint.toChar())
                if (chord != null) {
                    out.append(chord)
                    continue
                }
            }
            // The AOSP keyboard and its descendants send Enter as '\n' *text*
            // rather than as a key event; a terminal expects '\r'.
            if (codePoint == '\n'.code) {
                out.append('\r')
                continue
            }
            out.appendCodePoint(codePoint)
        }
        return out.toString()
    }

    /**
     * Whether a key event is a Ctrl chord.
     *
     * Three sources, and they are not equivalent. The panel's Ctrl cap is our own state
     * and always counts — it is the only Ctrl a phone without a keyboard Ctrl button
     * can produce. The event's own Ctrl bit counts only when the event came from the
     * window system: Gboard, on a `TYPE_NULL` editor, sets `META_CTRL_ON` on *every*
     * key event it synthesises. Measured on the emulator (Android 14, Gboard):
     * tapping `c`, `a` and `t` delivered
     * `KeyEvent { action=ACTION_DOWN, keyCode=KEYCODE_C, metaState=META_CTRL_ON,
     * flags=0x6, deviceId=-1, source=0x101 }` and two more like it. Believing that
     * bit turned the word `cat` into ^C ^A ^T; the same flag sits on the backspace
     * (`KEYCODE_DEL`) and would have made it a chord with no mapping.
     *
     * [keyboardCtrlHeld] is how a soft keyboard's *own* Ctrl button is honoured
     * despite that. Measured on a real phone (OnePlus Nord CE 3 Lite, Gboard), its
     * Ctrl button is a key of its own: holding it and pressing `c` sends
     * `KEYCODE_CTRL_LEFT` ACTION_DOWN, then `KEYCODE_C` with `metaState=12288`
     * (`META_CTRL_ON|META_CTRL_LEFT_ON`) and **no character**, then the ups. That is
     * a signal Gboard only sends for a chord, so the caller tracks it and passes it
     * here; the modifier bit alone stays untrusted.
     *
     * @param eventCtrl the event's own `isCtrlPressed`
     * @param eventMetaIsTrustworthy false for a key event an IME synthesised
     * @param latchArmed the panel's Ctrl cap
     * @param keyboardCtrlHeld the keyboard's own Ctrl key is down (one-shot, see the caller)
     */
    fun isChord(
        eventCtrl: Boolean,
        eventMetaIsTrustworthy: Boolean,
        latchArmed: Boolean,
        keyboardCtrlHeld: Boolean = false,
    ): Boolean = latchArmed || (eventCtrl && (eventMetaIsTrustworthy || keyboardCtrlHeld))

    /**
     * The character a key event types, or null when it types nothing.
     *
     * Almost always the event's own unicode char. The fallback matters because the
     * key-character map is per *device*, and a virtual-device map that yields nothing
     * for a printable key would otherwise silently drop it — the one failure mode
     * this panel is not allowed to have. The same table the chords come from supplies
     * the letter, with Shift applied by hand, since the map that would have applied
     * it is the one that failed.
     */
    fun characterOf(keyCode: Int, unicode: Int, shift: Boolean = false): Char? = when {
        unicode > 0 -> unicode.toChar()
        else -> chordCharacterOf(keyCode)?.let { if (shift) it.uppercaseChar() else it }
    }

    /**
     * The bytes a `deleteSurroundingText` request means.
     *
     * One DEL per character to the left. The stock Samsung keyboard with "Auto check
     * spelling" enabled sends `beforeLength > 1` in a single call (Termux's comment
     * on the same case), so the count has to be respected rather than turned into a
     * single byte.
     */
    fun deleteBytes(beforeLength: Int, afterLength: Int): String =
        BACKSPACE.repeat(beforeLength.coerceAtLeast(0)) +
            FORWARD_DELETE.repeat(afterLength.coerceAtLeast(0))
}
