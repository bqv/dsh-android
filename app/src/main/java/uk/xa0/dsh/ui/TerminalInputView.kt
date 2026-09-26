package uk.xa0.dsh.ui

import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import uk.xa0.dsh.term.TerminalInput
import uk.xa0.dsh.term.TerminalKey
import uk.xa0.dsh.term.TerminalKeys

/** The panel's input-boundary tag: a keystroke that never arrives and one that is dropped look identical. */
private const val TAG = "DshTerm"

/**
 * The native editor a soft keyboard types into, and the only keystroke source the
 * shell has.
 *
 * There is no text field here in any meaningful sense: the view is empty, never
 * renders, and holds no text. What it is *for* is `onCreateInputConnection` — a
 * terminal wants an editor that does not edit, so this one returns a
 * [BaseInputConnection] whose `commitText`, `sendKeyEvent` and
 * `deleteSurroundingText` write straight to the PTY. That is Termux's design
 * (`TerminalView.java`, `onCreateInputConnection` at line 309, and the handler that
 * follows it), and it removes the IME as an *interpreter* of what the user did.
 *
 * The hidden `BasicTextField` this replaced held a zero-width anchor and read every
 * callback as a delta against it. Both measured defects lived in that layer: an
 * 8-key burst in one `input keyevent` call echoed `aabcdefgh` once in three (a
 * character re-committed because the anchor was still in the field), and an
 * `Ascii`-flavoured field handed input to GBoard's composer — no per-keystroke
 * echo, and autocorrect rewrote `vim` into `vI'm`.
 *
 * The view is 1dp and transparent; the grid is drawn behind it on a Canvas, and the
 * panel's own tap target puts focus back.
 */
class TerminalInputView(
    context: Context,
    private val writeToPty: (String) -> Unit,
    private val ctrlArmed: () -> Boolean,
    private val ctrlSpent: () -> Unit,
) : View(context) {

    /**
     * DECCKM (`?1`), read at key time: a full-screen program can change it between
     * one key and the next, and a captured copy would send the wrong arrow form.
     */
    private var applicationCursorKeys: () -> Boolean = { false }

    /**
     * Whether to advertise Termux's fallback input type instead of `TYPE_NULL`.
     *
     * `TYPE_NULL` is the correct value and the default: it makes the IME send the
     * keys the user pressed instead of routing them through its own composer. But
     * some keyboards do not reset their internal state on it — the Samsung stock
     * keyboards are the cited case (termux-app#686) — and for those Termux switches
     * to `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD | TYPE_TEXT_FLAG_NO_SUGGESTIONS`,
     * which suppresses suggestions without being a text field either. It is not a
     * valid AOSP value (no `TYPE_CLASS_*` bit, so a LatinIME build logs
     * "Unexpected input class: inputType=0x00080090"), which is exactly why it is
     * the fallback and not the default.
     *
     * Nothing sets this by hand: an IME that offers a composition on `TYPE_NULL` is
     * one that did not reset, so the connection flips it once and restarts input.
     */
    var charBasedInput: Boolean = false
        private set

    init {
        // A plain View is neither focusable nor an editor by default, and both are
        // what make the IME attach to it at all.
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Color.TRANSPARENT)
    }

    fun setApplicationCursorKeys(read: () -> Boolean) {
        applicationCursorKeys = read
    }

    /** DECCKM as it is *now*: the input connection reads it per key event. */
    internal fun cursorKeys(): Boolean = applicationCursorKeys()

    /** Put the cursor and the keyboard back: what a tap on the grid does. */
    fun showKeyboard() {
        requestFocus()
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(this, 0)
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = if (charBasedInput) {
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        } else {
            InputType.TYPE_NULL
        }
        // Termux's note, kept because it cost someone a bug report: IME_ACTION_NONE
        // cannot be used here, because it makes it impossible to type a newline from
        // the on-screen keyboard (termux-app#221). A terminal's Enter is the one key
        // it cannot afford to lose.
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN
        outAttrs.initialCapsMode = 0
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        Log.d(
            TAG,
            "onCreateInputConnection inputType=0x${Integer.toHexString(outAttrs.inputType)} " +
                "charBased=$charBasedInput",
        )
        return TerminalInputConnection(
            view = this,
            writeToPty = writeToPty,
            ctrlArmed = ctrlArmed,
            ctrlSpent = ctrlSpent,
        )
    }

    /**
     * Hardware (and IME-dispatched) key events the Compose preview did not consume.
     *
     * `dispatchKeyEvent`, not `onKeyDown`: it sees `ACTION_MULTIPLE` too, which is
     * how a keyboard that has no key for a character sends that character's text.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleTerminalKeyEvent(event, applicationCursorKeys(), ctrlArmed(), ctrlSpent, writeToPty)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * An IME offered a composition while we advertised `TYPE_NULL`.
     *
     * That is the misbehaving-keyboard case the fallback exists for. Nothing is
     * written for the composition (it is not committed text), the type is switched,
     * and input is restarted so the IME re-reads `EditorInfo` and stops composing.
     */
    internal fun onCompositionOffered() {
        if (charBasedInput) return
        charBasedInput = true
        Log.d(TAG, "IME offered a composition on TYPE_NULL; switching to the visible-password fallback")
        post {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.restartInput(this)
        }
    }
}

/**
 * The connection that writes what the user did, and nothing else.
 *
 * `BaseInputConnection(view, true)` is a *full* editor connection, which matters for
 * one non-obvious reason: with `fullEditor = false` its `commitText` also synthesises
 * key events for the text and clears its buffer, and `finishComposingText` with it
 * replays the buffered text. A full-editor connection does neither, so the only
 * thing that ever reaches the PTY is what the methods below decide.
 */
private class TerminalInputConnection(
    private val view: TerminalInputView,
    private val writeToPty: (String) -> Unit,
    private val ctrlArmed: () -> Boolean,
    private val ctrlSpent: () -> Unit,
) : BaseInputConnection(view, true) {

    /** `commitText` is the ordinary path: one keystroke in, the same bytes out. */
    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val committed = text?.toString().orEmpty()
        val armed = ctrlArmed()
        val bytes = TerminalInput.bytesFor(committed, ctrl = armed)
        Log.d(TAG, "ime commitText text=${render(committed)} ctrlArmed=$armed -> ${render(bytes)}")
        if (bytes.isNotEmpty()) {
            if (armed) ctrlSpent()
            writeToPty(bytes)
        }
        // Nothing is kept: an editor with state is exactly the anchor machinery this
        // replaced, and a leftover character is what re-committed `a` into `aabcdefgh`.
        getEditable()?.clear()
        return true
    }

    /**
     * A composition on `TYPE_NULL` means the keyboard did not reset, so nothing is
     * written for it — half a word must never reach a shell — and the input type is
     * switched once. `super` keeps the text so `finishComposingText` can deliver it
     * if the IME commits that way instead.
     */
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        Log.d(TAG, "ime setComposingText text=${render(text?.toString().orEmpty())} (held, not written)")
        super.setComposingText(text, newCursorPosition)
        view.onCompositionOffered()
        return true
    }

    /**
     * A composition the user finished without committing: an IME that only ever
     * composes still has to deliver its word. This is Termux's `finishComposingText`.
     */
    override fun finishComposingText(): Boolean {
        val editable = getEditable()
        val held = editable?.toString().orEmpty()
        val bytes = TerminalInput.bytesFor(held, ctrl = ctrlArmed())
        if (bytes.isNotEmpty()) {
            Log.d(TAG, "ime finishComposingText held=${render(held)} -> ${render(bytes)}")
            writeToPty(bytes)
        }
        editable?.clear()
        return true
    }

    /**
     * A soft backspace. One DEL per character the IME says it deleted — the Samsung
     * stock keyboard with "Auto check spelling" sends a left length above one in a
     * single call, so a single byte would leave characters behind.
     */
    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        super.deleteSurroundingText(beforeLength, afterLength)
        val bytes = TerminalInput.deleteBytes(beforeLength, afterLength)
        Log.d(TAG, "ime deleteSurroundingText($beforeLength, $afterLength) -> ${bytes.length}B")
        if (bytes.isNotEmpty()) writeToPty(bytes)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        super.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        val bytes = TerminalInput.deleteBytes(beforeLength, afterLength)
        Log.d(TAG, "ime deleteSurroundingTextInCodePoints($beforeLength, $afterLength) -> ${bytes.length}B")
        if (bytes.isNotEmpty()) writeToPty(bytes)
        return true
    }

    /**
     * The keyboard's own Ctrl key, down as of the key *before* this one.
     *
     * Gboard's Ctrl button is a key in its own right: holding it sends
     * `KEYCODE_CTRL_LEFT` ACTION_DOWN, then the chorded key, then the ups — measured
     * on a phone, where tapping a plain `x` arrives as `commitText` instead. So the
     * chord is the key that *follows* a Ctrl down, and the flag is one-shot: it is
     * read by the next key and cleared, which also means a keyboard that never sends
     * the Ctrl *up* cannot strand it over the following keystrokes.
     */
    private var imeCtrlArmed = false

    private fun isCtrlKey(keyCode: Int): Boolean =
        keyCode == KeyEvent.KEYCODE_CTRL_LEFT || keyCode == KeyEvent.KEYCODE_CTRL_RIGHT

    /**
     * A key event the IME synthesised. On `TYPE_NULL` this is the *ordinary* path for
     * Gboard, which sends key events with `deviceId = -1` instead of calling
     * `commitText` (Termux's comment on the same branch); Hacker's Keyboard,
     * OpenBoard and the LG keyboard call `commitText` instead. Both are handled, and
     * the whole event is logged, because its modifier state is not trustworthy —
     * see [handleTerminalKeyEvent]'s `trustMeta`.
     */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        Log.d(TAG, "ime sendKeyEvent $event")
        // One-shot: the arm applies to the key that follows the Ctrl down. The Ctrl
        // key's own event must not consume it, so it is read before the update below.
        val chordKey = imeCtrlArmed && !isCtrlKey(event.keyCode)
        val handled = handleTerminalKeyEvent(
            event = event,
            applicationCursorKeys = view.cursorKeys(),
            ctrlArmed = ctrlArmed(),
            ctrlSpent = ctrlSpent,
            write = writeToPty,
            // Measured on the emulator's Gboard (Android 14, TYPE_NULL): every
            // synthesised key event carries META_CTRL_ON. Tapping `c`, `a`, `t`
            // arrived as keyCode 31/29/48 with `metaState=4096`. Trusting that bit
            // would send ^C ^A ^T for the word `cat`.
            trustMeta = false,
            imeCtrlHeld = chordKey,
        )
        imeCtrlArmed = isCtrlKey(event.keyCode) && event.action == KeyEvent.ACTION_DOWN
        if (handled) return true
        return super.sendKeyEvent(event)
    }
}

/**
 * The one key-event handler, shared by the Compose preview and the native editor.
 *
 * It has to be shared: which of the two sees a given event depends on where Android
 * focus sits, and a key handled twice would be typed twice while a key handled by
 * neither would vanish. Both callers consume what this returns true for.
 *
 * @param ctrlArmed the panel's Ctrl cap. It is *panel* state, not a meta state, so
 *   it does not appear in the event at all — and on `TYPE_NULL` a soft keyboard
 *   sends letters as plain key events, which is exactly the path the latch has to
 *   work on.
 * @param trustMeta whether the event's own modifier bits mean what they say. They do
 *   for a key event the window system delivered, and they do not for one an IME
 *   synthesised: Gboard sets `META_CTRL_ON` on *every* key event it sends through
 *   `sendKeyEvent`, so a tapped `c` that is honoured as `Ctrl+C` makes the soft
 *   keyboard unusable. On that path the only Ctrl is the panel's own latch, and a
 *   keyboard that really means a chord can still supply the C0 byte itself (below),
 *   which is honoured whatever this says.
 * @param imeCtrlHeld the keyboard's own Ctrl key is down for *this* key: Gboard
 *   sends `KEYCODE_CTRL_LEFT` DOWN on the key before a chord and UP after it, which
 *   is a signal it does not send for the spurious `META_CTRL_ON` its ordinary keys
 *   carry. A soft keyboard's own Ctrl button therefore works without the panel.
 */
internal fun handleTerminalKeyEvent(
    event: KeyEvent,
    applicationCursorKeys: Boolean,
    ctrlArmed: Boolean,
    ctrlSpent: () -> Unit,
    write: (String) -> Unit,
    trustMeta: Boolean = true,
    imeCtrlHeld: Boolean = false,
): Boolean {
    if (event.action == KeyEvent.ACTION_MULTIPLE) {
        // A character with no key of its own arrives as text on a KEYCODE_UNKNOWN
        // event; there is nothing to encode, so it is committed like any other text.
        val characters = event.characters
        if (characters.isNullOrEmpty()) return false
        val bytes = TerminalInput.bytesFor(characters, ctrl = ctrlArmed)
        Log.d(TAG, "key multiple text=${render(characters)} -> ${render(bytes)}")
        if (bytes.isNotEmpty()) write(bytes)
        return true
    }
    if (event.action != KeyEvent.ACTION_DOWN) return false

    TerminalKey.ofNativeKeyCode(event.keyCode)?.let { key ->
        Log.d(TAG, "key ${key.name} from keyCode=${event.keyCode} ctrl=${event.isCtrlPressed} alt=${event.isAltPressed}")
        write(
            TerminalKeys.key(
                key = key,
                ctrl = event.isCtrlPressed,
                alt = event.isAltPressed,
                shift = event.isShiftPressed,
                applicationCursorKeys = applicationCursorKeys,
            ),
        )
        return true
    }

    val unicode = event.unicodeChar

    // A control character *as text*: a keyboard that has already made the decision
    // (Termux names the penti keyboard) hands over the C0 byte, and it goes out
    // whatever any modifier bit says. Read before the chord branch so a keyboard that
    // populates the character for a real chord still reaches the shell.
    if (unicode in 1..31) {
        if (ctrlArmed) ctrlSpent()
        Log.d(TAG, "key control byte $unicode from keyCode=${event.keyCode} meta=${event.metaState}")
        write(unicode.toChar().toString())
        return true
    }

    // The panel's Ctrl cap is panel state and is always honoured; the event's own
    // Ctrl bit only when the event came from the window system rather than from an
    // IME that flags everything with it (see `trustMeta`) — or when the keyboard's
    // own Ctrl key is down (`imeCtrlHeld`).
    val ctrl = TerminalInput.isChord(
        event.isCtrlPressed,
        eventMetaIsTrustworthy = trustMeta,
        latchArmed = ctrlArmed,
        keyboardCtrlHeld = imeCtrlHeld,
    )
    if (ctrl) {
        // A chord arrives as a key code with no character (see
        // `TerminalInput.chordCharacterOf`) — getUnicodeChar returns 0 with Ctrl held
        // — so it is resolved from the code.
        val chord = TerminalInput.chordCharacterOf(event.keyCode)?.let { TerminalKeys.controlOf(it) }
        if (chord == null) {
            Log.d(
                TAG,
                "ctrl chord ignored: keyCode=${event.keyCode} unicode=$unicode " +
                    "meta=${event.metaState} armed=$ctrlArmed trustMeta=$trustMeta",
            )
            return false
        }
        if (ctrlArmed) ctrlSpent()
        Log.d(TAG, "ctrl chord -> ${chord.code} from keyCode=${event.keyCode} armed=$ctrlArmed trustMeta=$trustMeta")
        write(chord.toString())
        return true
    }

    // The character the key event carries, with a fallback for a keyboard whose
    // key-character map yields nothing for a printable key: a key may never be
    // dropped silently, and the map that failed is also the one that would have
    // applied Shift, so the fallback applies it by hand.
    val character = TerminalInput.characterOf(event.keyCode, unicode, event.isShiftPressed)
    if (character == null) {
        Log.d(TAG, "key ignored: keyCode=${event.keyCode} meta=${event.metaState} (no character)")
        return false
    }
    val bytes = TerminalKeys.typed(character, alt = event.isAltPressed)
    Log.d(TAG, "key text=${render(bytes)} from keyCode=${event.keyCode} alt=${event.isAltPressed} unicode=$unicode")
    write(bytes)
    return true
}

/**
 * A character as a log line can be read.
 *
 * The old logging printed the anchor and an IME's non-breaking space as `₩` and `¨`,
 * which made two different bugs look like one. Control bytes are named, and the two
 * characters that actually caused trouble are spelled out.
 */
internal fun render(text: String): String = text.map {
    when {
        it == TerminalInput.ANCHOR -> "<zwsp>"
        it == '\u00A0' -> "<nbsp>"
        it.code < 32 || it.code == 127 -> "^" + (it.code + 64)
        else -> it
    }
}.joinToString("")
