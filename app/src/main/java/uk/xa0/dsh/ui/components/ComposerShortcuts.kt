package uk.xa0.dsh.ui.components

import androidx.compose.ui.input.key.Key

/**
 * The edit-history chords the composer takes from a hardware keyboard.
 *
 * A phone with a keyboard attached is the case this exists for: the on-screen row
 * has one 28dp seat for history, and typing on a real keyboard is exactly when
 * the fingers reach for the familiar chord instead. Ctrl+Z undoes;
 * Ctrl+Shift+Z and Ctrl+Y both redo, because both spellings are in wide use and
 * neither costs anything to accept.
 */
enum class ComposerShortcut {
    UNDO,
    REDO,
}

/**
 * Map one key event's facts to the chord it is, or null when it is not one of
 * ours.
 *
 * Deliberately a pure function of the primitive facts rather than of a platform
 * `KeyEvent`: the wiring in `ui/components/Composer.kt` reads them off the event
 * once, and a JVM test can ask about every combination without a window, a
 * keyboard, or an Android key code. [down] is false for the key-up half of a
 * press — taking that would undo twice for one tap — and holding a chord down
 * repeats it, which is how every other editor behaves.
 *
 * Only Ctrl is recognised. The app targets phones; Meta (the Windows key) and
 * Alt chords are left to the text field and to the system.
 */
fun composerShortcut(
    key: Key,
    ctrl: Boolean,
    shift: Boolean,
    down: Boolean,
): ComposerShortcut? {
    if (!down || !ctrl) return null
    return when (key) {
        Key.Z -> if (shift) ComposerShortcut.REDO else ComposerShortcut.UNDO
        Key.Y -> ComposerShortcut.REDO
        else -> null
    }
}
