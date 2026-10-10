package uk.xa0.dsh.widget

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * How loudly the headline is drawn. Colour is the only emphasis a `RemoteViews`
 * layout has — no weight, no size, no rich text — so it carries the one distinction
 * that matters: something is waiting on you, or nothing is.
 */
enum class WidgetLevel { QUIET, ATTENTION, UNKNOWN }

/**
 * The lines a widget draws, worked out entirely from a [WidgetSnapshot] and
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
 *    [WidgetText.ageInstant] is the *oldest* instant any reading that has a line here was
 *    confirmed, and [WidgetText.stampLabel] says so in words ([STAMP_OLDEST]) rather than
 *    describing the app's own activity. The widget prints that instant as a date and a
 *    time ([stampTime]) rather than counting up from it, because an instant is complete
 *    the moment it is written: it cannot rot, and it asks nothing of the launcher to stay
 *    true, where a ticking age costs a re-render every second the card is on screen. A
 *    reading stops being renewed by the stamp ceasing to advance, in plain sight, instead
 *    of staying quietly wrong.
 *
 *    The date is written even though the common case is today, and that is the part of
 *    this that is not cosmetic: a bare clock time is only unambiguous inside its own day,
 *    and the card is drawn once and then read at a moment it cannot know. `12:03` drawn
 *    yesterday would read as seven minutes old to somebody glancing at it just after
 *    noon, which is the one thing the footer may never do.
 *  * **Nothing is ever claimed about *now*.** There is no "live" lamp and no "connected"
 *    badge: those would need a wake-up to withdraw, and a force-stopped app has no alarms
 *    left to withdraw them with. A stamp that names a past instant is the only kind of
 *    claim that cannot be left behind — there is nothing to withdraw, because nothing in
 *    it ever becomes untrue.
 *  * **A name is only drawn behind a reading that can still assert it.** The running
 *    list is the detail of the *running* reading, not a fact of its own: when that
 *    reading is withdrawn the list is gone with it, and the card says `Running: unknown`
 *    rather than showing an empty list — which a reader could only read as "nothing is
 *    running", the one thing an unknown roster does not entitle anyone to say.
 */
data class WidgetText(
    val headline: String,
    /** Null when there is nothing honest to put on a second line. */
    val detail: String?,
    /**
     * The word before the instant, or the whole footer when there is no instant. When
     * there is one it is [WidgetCopy.STAMP_OLDEST], which names *what* the instant bounds —
     * the oldest reading on the card — rather than describing the app's own activity.
     */
    val stampLabel: String,
    /**
     * The instant the *oldest* reading on the card was confirmed, or null when the footer
     * is a static sentence.
     *
     * An instant and not a span, because the footer prints it ([stampTime]) rather than
     * deriving anything from it: what the card says is complete when it is drawn and true
     * forever after, so nothing has to be kept running to keep it from rotting.
     */
    val ageInstant: Long?,
    val level: WidgetLevel,
    /**
     * The running sessions the card names, in the app's own roster order, already cut to
     * fit a tile ([WidgetCopy.tileTitle]) and already prefixed with the list bullet.
     *
     * Empty whenever [detail] says the running fact in words instead — a withdrawn
     * roster, a confirmed zero, or a count whose names the app was never given. The two
     * are mutually exclusive by construction: [WidgetCopy.of] never fills both.
     */
    val rows: List<WidgetSession> = emptyList(),
    /**
     * "+2 more", when more sessions are running than [rows] can name. Null when nothing
     * is withheld — including when the card is not naming anything at all, because then
     * [detail] carries the total instead and a second figure would be the same number
     * twice.
     *
     * It is never null merely because the list ran out of room: an overflow the reader
     * is not told about is the silent drop this feature must not do.
     */
    val moreLabel: String? = null,
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
     * and nothing is wrong", while the value under it is a fact about the *oldest* reading
     * on the card — the worst line, and the one that bounds how much any of them may be
     * trusted. A label that describes the wrong thing is a lie even when every number in
     * it is right, which is the rule the model chip and the Router tab are both held to.
     * So it says what the value is: the oldest reading was confirmed then, and everything
     * above it is at least that fresh.
     *
     * It deliberately does **not** name *which* line is the oldest, though this layer
     * knows and could. The two readings are renewed by two different owners on two
     * different clocks, so in the app's own idle state the answer flips between "needs
     * you" and "running" every quarter hour for no reason a reader could act on — and a
     * label that changes for no reason is one people stop reading. The bound is the
     * stable, honest claim the footer can carry on one line. See `docs/WIDGET.md`.
     */
    const val STAMP_OLDEST = "oldest reading"

    const val STAMP_OPEN = "Open DSH to connect"
    const val TITLE = "DSH"

    /**
     * The bullet every named row carries.
     *
     * A row is a session title, and a session title is a sentence somebody wrote. Without
     * a mark, a list under a headline like "Approval waiting" reads as a continuation of
     * the sentence; the bullet is the only thing in a one-line `TextView` that says "this
     * is one of several". It lives in the copy layer rather than the renderer because it
     * is punctuation, and because it is then assertable without a launcher.
     */
    const val ROW_BULLET = "· "

    /**
     * How many characters of a session title survive onto a tile.
     *
     * A DSH title is written by a user or generated by the host, and it can be a whole
     * sentence. This is the bound on what the launcher is handed; the row's own
     * `ellipsize="end"` is the second, visual bound, and the one this harness cannot see.
     * Trimming a copy here rather than handing the whole title to the view means the same
     * cut is assertable without a device — and the snapshot keeps the roster's own title,
     * so nothing downstream has to guess what was dropped.
     */
    /**
     * Effectively no cut. The row `TextView` already ellipsizes at the width the
     * launcher actually gives it, so a character cap here truncated a second time and
     * left only the short title occupying a wide card - which read as large margins on
     * both sides. The cap stays as a sanity bound for a pathological title; the collapse
     * of whitespace above it is the part that matters, since a newline would change the
     * row's height and there is no room for a second line.
     */
    const val TITLE_MAX = 96

    /**
     * The most names the card can draw. The same number the snapshot will carry, because
     * a document that held more rows than the layout can show would be a silent drop
     * waiting to happen.
     */
    const val MAX_ROWS = WidgetSnapshot.MAX_NAMED_SESSIONS

    /** The character the trim leaves behind, so a cut title never looks like a whole one. */
    const val ELLIPSIS = "\u2026"

    /** Newlines and runs of spaces in a title, collapsed to one space apiece. */
    private val WHITESPACE = Regex("\\s+")

    /**
     * A session title, made to fit one tile row.
     *
     * Whitespace is collapsed first: a title can carry a newline or a run of spaces, and
     * either would change a row's height in a layout whose whole budget was spent in
     * advance. Then the string is cut to [TITLE_MAX] characters with [ELLIPSIS], so a
     * reader can see that it was cut.
     */
    fun tileTitle(raw: String): String {
        val flat = raw.replace(WHITESPACE, " ").trim()
        if (flat.length <= TITLE_MAX) return flat
        return flat.take(TITLE_MAX - ELLIPSIS.length).trimEnd() + ELLIPSIS
    }

    /** "+2 more", the only shape an overflow may take. */
    fun moreLabel(withheld: Int): String = "+$withheld more"

    /**
     * How an instant is written on the card: `11 Oct 12:03`.
     *
     * The day and the month come first because they are the part that cannot be left out
     * — see [stampTime] — and the same `d MMM` the drawer's own `relativeTime` falls back
     * to for a reading older than a week, so the two surfaces write a date the same way.
     * The time is 24-hour because every other timestamp in this app is (`RouterTab`,
     * `SessionsDrawer`, `ChatRows`). Only the month's name and the digits follow the
     * reader's locale: the order is the app's, the words are the reader's.
     */
    const val STAMP_PATTERN = "d MMM HH:mm"

    /**
     * An instant the footer prints, as a local date and time.
     *
     * The **date is not optional**, and it is the whole reason this is not simply
     * `DateFormat.getTimeFormat`. A clock time is unambiguous only inside its own day, and
     * the card is drawn once and then read at a moment it cannot know and cannot be woken
     * for: `12:03` drawn yesterday reads as seven minutes old to somebody glancing at the
     * card just after noon. An instant that always names its day cannot be read as fresher
     * than it is, on any day, at any hour, with no wake-up to correct it.
     *
     * Both the locale and the zone are parameters so a test can pin them; a stamp whose
     * answer moves with the machine's own settings is not a fact anybody can assert.
     * A fresh formatter per call rather than a cached one, because a shared
     * `SimpleDateFormat` is not thread-safe and this is called once per widget update.
     */
    fun stampTime(
        instant: Long,
        locale: Locale = Locale.getDefault(),
        zone: TimeZone = TimeZone.getDefault(),
    ): String = SimpleDateFormat(STAMP_PATTERN, locale)
        .apply { timeZone = zone }
        .format(Date(instant))

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
            ageInstant = null,
            level = WidgetLevel.UNKNOWN,
        )

        val needs = snapshot.needsYou
        val headline = when {
            !needs.known -> NEEDS_UNKNOWN
            needs.value <= 0 -> NEEDS_NOTHING
            needs.value == 1 -> singleHeadline(snapshot.kind)
            else -> "${needs.value} need you"
        }

        val running = runningLine(snapshot)

        val stamp = snapshot.confirmedAt
        return WidgetText(
            headline = headline,
            detail = running.detail,
            stampLabel = if (stamp != null) STAMP_OLDEST else STAMP_OPEN,
            ageInstant = stamp,
            level = when {
                needs.known && needs.value > 0 -> WidgetLevel.ATTENTION
                needs.known -> WidgetLevel.QUIET
                else -> WidgetLevel.UNKNOWN
            },
            rows = running.rows,
            moreLabel = running.more,
        )
    }

    /**
     * What the card can say about what is running: either a handful of names, or one line
     * that says why it cannot name them.
     *
     * The three "cannot name them" cases are different statements and are kept apart,
     * which is the whole reason this is not a `when` that folds them into a string:
     *
     *  * **Not known.** The app cannot see the roster, so it has not learned that nothing
     *    is running. [RUNNING_UNKNOWN], and no rows: an empty list here would be read as
     *    the opposite claim.
     *  * **Confirmed empty.** The app looked and nothing is running. [RUNNING_NOTHING].
     *  * **Count known, names not.** A document written before this change, or a roster
     *    whose running rows have no titles yet. The count is still true and still the
     *    honest answer — it is not an empty list, and it carries the subagent qualifier
     *    that would otherwise have been lost.
     *
     * Only in the fourth case — names in hand — does the card drop the count line: the
     * names are what the reader asked for, and the total is still recoverable from the
     * rows plus [WidgetText.moreLabel] when anything is withheld.
     */
    private fun runningLine(snapshot: WidgetSnapshot): Running {
        val working = snapshot.working
        if (!working.known) return Running(RUNNING_UNKNOWN, emptyList(), null)
        if (working.value <= 0) return Running(RUNNING_NOTHING, emptyList(), null)

        // Trimmed here rather than in the publisher: the file keeps the roster's own
        // title, and how much of it fits is a fact about the card. A title that trims to
        // nothing is dropped, and the clamp below turns that into "+1 more" rather than
        // an invisible loss.
        val named = snapshot.running.asSequence()
            .map { it.id to tileTitle(it.title) }
            .filter { (_, title) -> title.isNotEmpty() }
            .take(MAX_ROWS)
            .map { (id, title) -> WidgetSession(id, ROW_BULLET + title) }
            .toList()

        if (named.isEmpty()) {
            return Running(
                detail = "${working.value} running" + subagentSuffix(snapshot.subagents),
                rows = emptyList(),
                more = null,
            )
        }

        // The count is the authority on the total; the rows are a sample of it. Clamped
        // because a document written by a future build must not be able to make the card
        // print a negative overflow.
        val withheld = (working.value - named.size).coerceAtLeast(0)
        return Running(
            detail = null,
            rows = named,
            more = if (withheld > 0) moreLabel(withheld) else null,
        )
    }

    /**
     * One line's worth of the running fact: the words, the rows, and the overflow — the
     * three fields [WidgetText] draws from. A private carrier rather than three values
     * threaded through [of], because they are one decision.
     */
    private data class Running(
        val detail: String?,
        val rows: List<WidgetSession>,
        val more: String?,
    )

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
