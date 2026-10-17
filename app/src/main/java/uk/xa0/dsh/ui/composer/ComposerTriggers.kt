package uk.xa0.dsh.ui.composer

import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.ui.text.TextRange
import uk.xa0.dsh.ui.components.CommandEntry

/**
 * Caret-accurate `/` and `@` detection — a port of the web's pure core,
 * `packages/client/ui-input-trigger/src/core/detect.ts`, plus the shared `@file`
 * token grammar it delegates to, `packages/context/file-reference/src/grammar.ts`
 * (`activeAtToken`).
 *
 * The rule is a function of (draft, caret), never of the draft's end: reading the
 * trailing token opened a menu for text nowhere near the caret and made a pick
 * rewrite the wrong part of the draft.
 *
 * One rule is deliberately *not* here: the web's guard tiers (`claimed`/`frozen`)
 * suppress `/` while a command claim owns the line. This client has no claim
 * state, so every hit is the web's `plain` tier.
 */

/** One trigger token live under the caret (web `TriggerHit`). */
class TriggerToken(
    /** `/` or `@`. */
    val trigger: Char,
    /** Text between the trigger char and the caret. */
    val query: String,
    /** True only for an open quoted `@"…` token, which may span whitespace. */
    val quoted: Boolean,
    /** Index of the trigger char; the span a pick replaces. */
    val start: Int,
    /** The caret, exclusive. */
    val end: Int,
)

/**
 * An `@` token longer than this is not a reference query: a pasted address, or a
 * long clause typed after an `@`, must not put the host's file index in front of
 * the caret. Unlike the web's browser-side candidates, every query here costs an
 * RPC, so the guard is this client's own.
 */
private const val MAX_AT_TOKEN = 96

/** Web `WORD_CHAR` — `[\p{L}\p{N}_]`. */
private val WORD_CHAR = Regex("[\\p{L}\\p{N}_]")

/**
 * The break class both `@` patterns share: JavaScript's `\s` set, which the web
 * regexes are written against. Spelled out because Android's engine rejects the
 * `(?U)` that stood in for it, and its default `\S` calls a no-break space a
 * token character — so the plain branch's complement is written out too.
 */
private const val AT_BREAK = "\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF"

/** Web `activeAtToken`, quoted branch: `(?:^|\s)(@"([^"]*))$`. */
private val QUOTED_AT = Regex("(?:^|[$AT_BREAK])(@\"([^\"]*))$")

/** Web `activeAtToken`, plain branch: `(?:^|\s)(@([^\s]*))$`. */
private val PLAIN_AT = Regex("(?:^|[$AT_BREAK])(@([^$AT_BREAK]*))$")

/**
 * The token at [caret], or null when the caret is not inside one.
 *
 * `@` is tried first, as the web does, so `/goal @wor` resolves to the reference;
 * a `/` is then found by scanning left from the caret and stopping at the first
 * whitespace, which is also what ends the plain `@` token.
 */
fun detectTriggerToken(draft: String, caret: Int): TriggerToken? {
    if (caret <= 0 || caret > draft.length) return null
    atToken(draft.substring(0, caret), caret)?.let { return it }
    for (i in caret - 1 downTo 0) {
        val ch = draft[i]
        // Whitespace ends the token: past it there is no trigger at this caret.
        if (ch.isWhitespace()) return null
        if (ch != '/') continue
        // A slash that fails the boundary is an ordinary token char (a URL path
        // slash, the second of `//`) and the scan continues past it.
        if (!boundaryOk(draft, i)) continue
        return TriggerToken('/', draft.substring(i + 1, caret), false, i, caret)
    }
    return null
}

/**
 * Web `activeAtToken`: an `@` opens the token only at the draft start or after
 * whitespace, so `user@host` is never a trigger; an unclosed `@"…` token stays
 * open across spaces until its closing quote.
 */
private fun atToken(before: String, caret: Int): TriggerToken? {
    val quoted = QUOTED_AT.find(before)
    if (quoted != null) {
        val prefix = quoted.groupValues[1]
        if (prefix.length > MAX_AT_TOKEN) return null
        return TriggerToken('@', quoted.groupValues[2], true, caret - prefix.length, caret)
    }
    val plain = PLAIN_AT.find(before) ?: return null
    val prefix = plain.groupValues[1]
    if (prefix.length > MAX_AT_TOKEN) return null
    return TriggerToken('@', plain.groupValues[2], false, caret - prefix.length, caret)
}

/**
 * Web `boundaryOk`: a `/` opens only at the draft start, after whitespace, or
 * after punctuation, never after a word char. Two URL carve-outs are pinned by
 * the web's tests: the second slash of `//` is dead (so `//` is never a command)
 * and so is a slash after a scheme colon (`https:/…`, `C:/…`).
 */
private fun boundaryOk(draft: String, index: Int): Boolean {
    if (index == 0) return true
    val prev = draft[index - 1]
    if (prev.isWhitespace()) return true
    if (WORD_CHAR.matches(prev.toString())) return false
    if (prev == '/') return false
    if (prev == ':' && index >= 2 && !draft[index - 2].isWhitespace()) return false
    return true
}

/**
 * What the composer's typing triggers resolved to for the caret.
 *
 * `@` and `/` are mutually exclusive by their first character, so at most one
 * token is ever live; the web's `MenuState` reduces to exactly this much for a
 * client that renders one menu at a time.
 */
class ComposerTriggers(
    val slash: TriggerToken?,
    val reference: TriggerToken?,
    /** The `/` roster, filtered by the live query; the sectioned roster when it is empty. */
    val shownEntries: List<CommandEntry>,
) {
    val slashQuery: String? get() = slash?.query
    val referenceQuery: String? get() = reference?.query
}

/**
 * Derive the live token and the filtered `/` roster for one caret position.
 *
 * Filtering matches the host's `rankByName`: name or label, case-insensitively;
 * an empty query is the sectioned roster. The web hides a row carrying an
 * `input.hint` unless the token is leading, but this client's [CommandEntry] has
 * no hint field, so no roster row is position-gated.
 *
 * The composer's text is a [CharSequence] because that is what
 * `TextFieldState.text` hands back; [detectTriggerToken] keeps its `String`
 * receiver so the grammar above is byte-for-byte the one it has always been.
 * The caret is passed in rather than read from a value object: with the
 * stateful field it is `TextFieldState.selection.end`, and passing it explicitly
 * is what lets a test ask about any position without composing a field.
 */
fun composerTriggers(
    text: CharSequence,
    caret: Int,
    commandEntries: List<CommandEntry>,
): ComposerTriggers {
    val token = detectTriggerToken(text.toString(), caret)
    val slash = token?.takeIf { it.trigger == '/' }
    val reference = token?.takeIf { it.trigger == '@' }
    val query = slash?.query
    val shown = if (query.isNullOrEmpty()) {
        commandEntries
    } else {
        commandEntries.filter {
            it.name.startsWith(query, ignoreCase = true) ||
                it.label.startsWith(query, ignoreCase = true)
        }
    }
    return ComposerTriggers(slash, reference, shown)
}

/**
 * Replace a picked token with its insertion text and park the caret after it —
 * the web's span insert (`span.start`..`span.end`), so a pick never appends to
 * the end of the draft.
 *
 * A null [token] means the pick came from the `+` launcher, which types no token;
 * the insertion then replaces the caret's selection. The recorded span is clamped
 * because a pick can land in the frame between an IME commit and recomposition,
 * and an out-of-range `replace` would crash rather than misplace a word.
 *
 * This is the `TextFieldState` form of the rule. It runs inside `state.edit`,
 * which does not record an undo step: a pick is the client's edit of the draft,
 * not the reader's typing, and the history is cleared by it — see the undo note
 * in `ui/components/Composer.kt`.
 */
fun TextFieldBuffer.replaceTrigger(token: TriggerToken?, insertion: String) {
    val start = (token?.start ?: selection.min).coerceIn(0, length)
    val end = (token?.end ?: selection.max).coerceIn(start, length)
    replace(start, end, insertion)
    selection = TextRange(start + insertion.length)
}

/**
 * Consume the live token for a pick that inserts nothing here (a bare command, or
 * a row that opens one of this client's sheets). A null [token] is the `+`
 * launcher, which typed nothing: the draft and any selection in it stay as they
 * are, because such a pick must not delete text the user selected.
 */
fun TextFieldBuffer.consumeTrigger(token: TriggerToken?) {
    if (token != null) replaceTrigger(token, "")
}
