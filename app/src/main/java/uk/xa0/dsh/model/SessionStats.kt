package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * The whole-log accounting the host serves beside a session snapshot: the
 * `sessionStats` projection (`packages/session/session-stats`) and the
 * `tokenUsage` projection (`packages/session/token-meter`), folded into the one
 * value the stat pills read.
 *
 * The two stay distinguishable ([hasStats] / [hasUsage]) because they settle
 * independently — a step can close before its provider usage is billed — and the
 * web treats "projection absent" differently from "projection zeroed": a missing
 * `tokenUsage` drops the usage pill entirely, while a zeroed one hides it by
 * having no token activity. Collapsing the two into one nullable field would
 * make a failed session (steps, no billing) render a bogus "0 tok" pill.
 *
 * Field names and units are the projection's own; every wall time is
 * milliseconds and every count is a non-negative whole number.
 */
data class SessionStats(
    /** True when the payload carried a `sessionStats` object at all. */
    val hasStats: Boolean = false,
    /** Distinct turns carrying at least one closed step. */
    val turns: Int = 0,
    /** Closed steps (`step/end`), completed/failed/cancelled alike. */
    val steps: Int = 0,
    /** Summed model wall time (`step/start` → `assistant/message`). */
    val llmMs: Long = 0,
    /** Summed matched `tool/call` → `tool/result` wall time. */
    val toolMs: Long = 0,
    /** Summed first-token latency over [ttftSteps]. */
    val ttftMs: Long = 0,
    /** Steps carrying a recorded first token. */
    val ttftSteps: Int = 0,
    /** Summed decode wall time over steps that also report output tokens. */
    val decodeMs: Long = 0,
    /** Summed provider output tokens over the same decode-timed steps. */
    val decodeTokens: Long = 0,
    /** True when the payload carried a `tokenUsage` object at all. */
    val hasUsage: Boolean = false,
    val uncachedInputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val outputTokens: Long = 0,
) {
    /** The three disjoint prompt-side billing buckets, as the web sums them. */
    val billedInputTokens: Long get() = uncachedInputTokens + cacheReadTokens + cacheWriteTokens

    /** The usage pill's headline: every prompt-side bucket plus output. */
    val totalTokens: Long get() = billedInputTokens + outputTokens

    /**
     * Web `StatsPills` gate #2: a session whose steps all settled without
     * billing (every request failed) shows its counts and no usage pill.
     */
    val hasTokenActivity: Boolean get() = hasUsage && (billedInputTokens > 0 || outputTokens > 0)

    /**
     * Web `StatsPills` gate #1: a window with no timed figure has no dialog rows,
     * so its pill stays a plain reading rather than a button opening an empty panel.
     */
    val hasTiming: Boolean get() = llmMs > 0 || toolMs > 0 || ttftSteps > 0 || decodeMs > 0

    /** Web `StatsPills`: `if (stats.steps === 0 && !hasTokens) return null`. */
    val visible: Boolean get() = steps > 0 || hasTokenActivity

    /** Web `decodeTokens / (decodeMs / 1000)`, or null when nothing was timed. */
    val tokensPerSecond: Double?
        get() = if (decodeMs > 0) decodeTokens * 1000.0 / decodeMs else null

    /** Web `formatTokensPerSecond`, or null when the speed segment drops out. */
    val tokensPerSecondText: String?
        get() = tokensPerSecond?.let(::formatTokensPerSecond)

    /** Web `cacheHitPercent`: whole-log cache-read share of prompt-side input. */
    val cacheHitPercent: String? get() = formatCacheHitPercent(cacheReadTokens, billedInputTokens)

    /** Web `stats.dialog.ttft`: the mean over the steps that recorded a first token. */
    val averageTtftMs: Long? get() = if (ttftSteps > 0) ttftMs / ttftSteps else null
}

/**
 * Reads both projections out of one session-projection payload.
 *
 * Returns null when the payload carries neither key, which is the parent's cue
 * to keep whatever value it already held: a session-list refresh that omits the
 * projections is not evidence that the session has no stats.
 *
 * @param values the object the host sends as a session's `projections`
 */
fun parseSessionStats(values: JSONObject?): SessionStats? {
    if (values == null) return null
    val stats = values.optJSONObject("sessionStats")
    val usage = values.optJSONObject("tokenUsage")
    if (stats == null && usage == null) return null
    return SessionStats(
        hasStats = stats != null,
        turns = stats?.optInt("turns", 0) ?: 0,
        steps = stats?.optInt("steps", 0) ?: 0,
        llmMs = stats?.optLong("llmMs", 0L) ?: 0L,
        toolMs = stats?.optLong("toolMs", 0L) ?: 0L,
        ttftMs = stats?.optLong("ttftMs", 0L) ?: 0L,
        ttftSteps = stats?.optInt("ttftSteps", 0) ?: 0,
        decodeMs = stats?.optLong("decodeMs", 0L) ?: 0L,
        decodeTokens = stats?.optLong("decodeTokens", 0L) ?: 0L,
        hasUsage = usage != null,
        uncachedInputTokens = usage?.optLong("uncachedInputTokens", 0L) ?: 0L,
        cacheReadTokens = usage?.optLong("cacheReadTokens", 0L) ?: 0L,
        cacheWriteTokens = usage?.optLong("cacheWriteTokens", 0L) ?: 0L,
        outputTokens = usage?.optLong("outputTokens", 0L) ?: 0L,
    )
}

// ------------------------------------------------------------------ formatting
//
// The web formats these in the Chat locale seat, whose English strings are
// literal templates: `'{value}K'` / `'{value}M'`, `'{seconds}s'`,
// `'{minutes}m{seconds}s'`, `'{tps} tok/s'`, `'{count} tok'`, and the group
// separator `','`. The app carries no locale seat, so those templates are
// inlined here rather than re-derived at each call site.

/**
 * Web `formatTokens` (`ui-chat/src/client/chat/token-format.ts`):
 * compact 517 / 12.2K / 517K / 1.2M. Under the thousand mark nothing is scaled;
 * above it the figure keeps one decimal until it reaches 100, where the decimal
 * would cost more width than it reads.
 */
fun formatCompactTokens(value: Long): String {
    fun scaled(candidate: Double): String =
        if (candidate >= 100) Math.round(candidate).toString() else oneDecimal(candidate)

    return when {
        value < 1_000 -> value.toString()
        value < 1_000_000 -> scaled(value / 1_000.0) + "K"
        else -> scaled(value / 1_000_000.0) + "M"
    }
}

/**
 * Web `formatExactTokens`: the unrounded integer with the locale's group
 * separator, for the usage panel's buckets (a compact "12.2K" loses the exact
 * digits the panel exists to show).
 */
fun formatExactTokens(value: Long): String = String.format(java.util.Locale.US, "%,d", value)

/**
 * Web `formatDuration` (`StatsPills.tsx`): `45.2s` under a minute, `2m42s` from
 * there on — the second component is rounded, so 119.6s reads `2m0s` exactly as
 * the web's second `Math.round` makes it.
 */
fun formatCompactDuration(ms: Long): String {
    val seconds = ms / 1_000.0
    if (seconds < 60) return oneDecimal(seconds) + "s"
    val whole = Math.round(seconds)
    return "${whole / 60}m${whole % 60}s"
}

/** Web `formatTokensPerSecond`: whole tokens from ten up, one decimal below. */
fun formatTokensPerSecond(tps: Double): String {
    val clamped = tps.coerceAtLeast(0.0)
    return if (clamped >= 10) Math.round(clamped).toString() else oneDecimal(clamped)
}

/**
 * Web `formatCacheHitPercent` (`token-format.ts`): the cache-read share of
 * prompt-side input, never rounding a partial hit up to a full 100%.
 *
 * The web deliberately avoids a plain `Math.round(100 * read / total)`: that
 * turns a 99.96% hit into "100%", which reads as "nothing was billed". So it
 * binary-searches the exact percentage units the ratio supports and, when a
 * partial hit still rounds to 100, widens the precision until it does not
 * (99.99999999999999% is a legal output).
 *
 * @param cacheReadTokens exact prompt tokens served from cache
 * @param promptTokens exact aggregate prompt tokens; 0 yields null (no billing)
 * @param decimalPlaces ordinary-ratio precision (the web only ever passes 0)
 */
fun formatCacheHitPercent(
    cacheReadTokens: Long,
    promptTokens: Long,
    decimalPlaces: Int = 0,
): String? {
    if (promptTokens == 0L) return null
    val missedInputTokens = promptTokens - cacheReadTokens
    if (missedInputTokens == 0L) return "100"

    val roundedUnits = roundedPercentUnits(cacheReadTokens, promptTokens, decimalPlaces)
    val fullHitUnits = if (decimalPlaces == 0) 100L else 1_000L
    if (roundedUnits < fullHitUnits) return displayPercentUnits(roundedUnits, decimalPlaces)

    // The ratio sits close enough to a full hit that the ordinary precision
    // cannot tell it apart. Widen by decimal digits until the distance from
    // 100% shows at all, then report the loss as the trailing digit.
    var distinguishingPlaces = 1
    var scaledDoubleGap = missedInputTokens * 200
    val denominatorTens = promptTokens / 10
    while (scaledDoubleGap <= denominatorTens) {
        scaledDoubleGap *= 10
        distinguishingPlaces += 1
    }
    val denominatorOnes = promptTokens % 10
    var roundedLoss = 5
    for (loss in 1 until 5) {
        val factor = loss * 2 + 1
        val threshold = factor * denominatorTens + factor * denominatorOnes / 10
        if (scaledDoubleGap <= threshold) {
            roundedLoss = loss
            break
        }
    }
    return "99." + "9".repeat(distinguishingPlaces - 1) + (10 - roundedLoss)
}

/**
 * Largest percentage in `decimalPlaces` units that the ratio still reaches,
 * found by binary search on the comparison itself so no floating point enters
 * the test. Ties round up, matching the web's positive-tie rule.
 */
private fun roundedPercentUnits(cacheReadTokens: Long, denominator: Long, decimalPlaces: Int): Long {
    val unitsPerPercent = if (decimalPlaces == 0) 1L else 10L
    val scale = unitsPerPercent * 100
    val doubledScale = scale * 2
    val denominatorQuotient = denominator / doubledScale
    val denominatorRemainder = denominator % doubledScale
    var lower = 0L
    var upper = scale
    while (lower < upper) {
        val candidate = (lower + upper + 1) / 2
        val factor = candidate * 2 - 1
        // Math.ceil of a positive quotient, kept in integers.
        val threshold = factor * denominatorQuotient +
            (factor * denominatorRemainder + doubledScale - 1) / doubledScale
        if (cacheReadTokens >= threshold) lower = candidate else upper = candidate - 1
    }
    return lower
}

/** Web `displayPercentUnits`: drop the tenths digit when it is a zero. */
private fun displayPercentUnits(units: Long, decimalPlaces: Int): String {
    if (decimalPlaces == 0) return units.toString()
    val whole = units / 10
    val tenths = units % 10
    return if (tenths == 0L) whole.toString() else "$whole.$tenths"
}

/**
 * One decimal, trimmed when it is a zero, so a whole number reads `45` rather
 * than the `45.0` that `Double.toString` would produce where the web's
 * `String(Math.round(x * 10) / 10)` produces `45`.
 */
private fun oneDecimal(value: Double): String {
    val scaled = Math.round(value * 10)
    val whole = scaled / 10
    val tenths = scaled % 10
    return if (tenths == 0L) whole.toString() else "$whole.$tenths"
}
