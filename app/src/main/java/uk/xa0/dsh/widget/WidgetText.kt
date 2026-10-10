package uk.xa0.dsh.widget

/**
 * How loudly the headline is drawn. Colour is the only emphasis a `RemoteViews`
 * layout has — no weight, no size, no rich text — so it carries the one distinction
 * that matters: something is waiting on you, or nothing is.
 */
enum class WidgetLevel { QUIET, ATTENTION, UNKNOWN }

/**
 * The three lines a widget draws, worked out entirely from a [WidgetSnapshot] and
 * nothing else. Pure on purpose: this is where the feature's honesty lives, and it is
 * the only part of it that can be tested without a launcher, a device or a clock.
 *
 * ## The rule
 *
 * A widget is read out of context and out of time. Its reader has not just opened the
 * app, cannot see which tab was last used, and cannot tell whether the app has spoken
 * in five seconds or five hours. So no line here is ever allowed to claim more than the
 * snapshot can support:
 *
 *  * **"nothing is waiting" is only said when the app confirmed it.** A reading the app
 *    could not confirm draws [NEEDS_UNKNOWN], never "Nothing needs you". This is the
 *    Router tab's "not asked" rule, applied to a homescreen.
 *  * **Everything drawn is stamped, and the stamp is a bound over the whole card.**
 *    [WidgetText.ageBase] is the *oldest* instant any reading that has a line here was
 *    confirmed, and [WidgetText.stampLabel] says so in words ([STAMP_OLDEST]) rather than
 *    describing the app's own activity. The widget renders the instant as a Chronometer
 *    that counts up by itself — the only mechanism in `RemoteViews` that keeps an age true
 *    with no process of ours running, so a reading stops being renewed by simply getting
 *    older, out loud, instead of staying quietly wrong.
 *  * **Nothing is ever claimed about *now*.** There is no "live" lamp and no "connected"
 *    badge: those would need a wake-up to withdraw, and a force-stopped app has no alarms
 *    left to withdraw them with. An age that ticks cannot be left behind.
 */
data class WidgetText(
    val headline: String,
    /** Null when there is nothing honest to put on a second line. */
    val detail: String?,
    /**
     * The word before the ticking age, or the whole footer when there is no age. When
     * there is an age it is [WidgetCopy.STAMP_OLDEST], which names *what* the age bounds —
     * the oldest reading on the card — rather than describing the app's own activity.
     */
    val stampLabel: String,
    /** The Chronometer's base, or null when the footer is a static sentence. */
    val ageBase: Long?,
    val level: WidgetLevel,
)

object WidgetCopy {

    const val NEEDS_UNKNOWN = "Needs you: unknown"
    const val NEEDS_NOTHING = "Nothing needs you"
    const val RUNNING_UNKNOWN = "Running: unknown"
    const val RUNNING_NOTHING = "Nothing running"

    /**
     * The footer's word, and it names the **bound** rather than the app's activity.
     *
     * "checked 12s ago" was the first draft, and it was the wrong sentence: it says
     * something about when the app last looked, which a reader takes as "the app is live
     * and nothing is wrong", while the number under it is the age of the *oldest* reading
     * on the card — the worst line, and the one that bounds how much any of them may be
     * trusted. A label that describes the wrong thing is a lie even when every number in
     * it is right, which is the rule the model chip and the Router tab are both held to.
     * So it says what the number is: the oldest reading was confirmed that long ago, and
     * everything above it is at least that fresh.
     *
     * It deliberately does **not** name *which* line is the oldest, though this layer
     * knows and could. The two readings are renewed by two different owners on two
     * different clocks, so in the app's own idle state the answer flips between "needs
     * you" and "running" every quarter hour for no reason a reader could act on — and a
     * label that changes for no reason is one people stop reading. The bound is the
     * stable, honest claim that a single `Chronometer` can carry. See `docs/WIDGET.md`.
     */
    const val STAMP_OLDEST = "oldest reading"

    const val STAMP_OPEN = "Open DSH to connect"
    const val TITLE = "DSH"

    /** The three blocking interactions, in the words `AttentionCenter` already uses. */
    private const val KIND_APPROVAL = "APPROVAL"
    private const val KIND_QUESTION = "QUESTION"
    private const val KIND_PLAN_REVIEW = "PLAN_REVIEW"

    /**
     * A snapshot that is not there at all is not an error and not an empty world: it is
     * a widget on a phone where this app has never written anything down. It says so.
     */
    fun of(snapshot: WidgetSnapshot?): WidgetText {
        snapshot ?: return WidgetText(
            headline = NEEDS_UNKNOWN,
            detail = RUNNING_UNKNOWN,
            stampLabel = STAMP_OPEN,
            ageBase = null,
            level = WidgetLevel.UNKNOWN,
        )

        val needs = snapshot.needsYou
        val headline = when {
            !needs.known -> NEEDS_UNKNOWN
            needs.value <= 0 -> NEEDS_NOTHING
            needs.value == 1 -> singleHeadline(snapshot.kind)
            else -> "${needs.value} need you"
        }

        val working = snapshot.working
        val detail = when {
            !working.known -> RUNNING_UNKNOWN
            working.value <= 0 -> RUNNING_NOTHING
            else -> "${working.value} running" + subagentSuffix(snapshot.subagents)
        }

        val stamp = snapshot.confirmedAt
        return WidgetText(
            headline = headline,
            detail = detail,
            stampLabel = if (stamp != null) STAMP_OLDEST else STAMP_OPEN,
            ageBase = stamp,
            level = when {
                needs.known && needs.value > 0 -> WidgetLevel.ATTENTION
                needs.known -> WidgetLevel.QUIET
                else -> WidgetLevel.UNKNOWN
            },
        )
    }

    /**
     * The kind word for a single waiting item.
     *
     * The `else` is unreachable from this app: its only caller is
     * `AttentionCenter.refreshWidget`, which names a kind whenever it publishes a count
     * above zero. It is here because this layer is total over a *persisted* type — a file
     * written by a later build, or by a caller that does not exist yet, must still draw
     * something that is true. "Someone needs you" says exactly what the snapshot supports
     * (one item, unnamed) and claims no kind, which is why it is not a guess dressed as
     * one.
     */
    private fun singleHeadline(kind: String?): String = when (kind) {
        KIND_APPROVAL -> "Approval waiting"
        KIND_QUESTION -> "Question waiting"
        KIND_PLAN_REVIEW -> "Plan to review"
        else -> "Someone needs you"
    }

    /**
     * Only ever a qualifier on a count that is already there. A subagent count is never
     * allowed to stand alone, because "1 subagent" without "3 running" reads as a
     * session rather than as part of one.
     */
    private fun subagentSuffix(subagents: WidgetReading): String = when {
        !subagents.known || subagents.value <= 0 -> ""
        subagents.value == 1 -> " · 1 subagent"
        else -> " · ${subagents.value} subagents"
    }
}
