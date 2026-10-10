package uk.xa0.dsh.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import uk.xa0.dsh.PendingKind
import java.io.File

/**
 * The **one** writer of the widget's snapshot.
 *
 * Two components of this app know something a homescreen might want: [uk.xa0.dsh.AttentionCenter],
 * which owns what is waiting on the user and is alive as long as the connection
 * service is, and `DshViewModel`, which owns the roster fold that says what is
 * running. Both are already the authority for their own fact, and neither is asked
 * for a second one here — they push their reading in, and this object is the only
 * thing that touches the file or the widget. There is no second source of truth and
 * no third party that could disagree with the screen.
 *
 * ## Who keeps a reading alive
 *
 * A reading is renewed only by the component that can still see the truth, so a
 * reading stops being renewed exactly when the app loses the ability to confirm it —
 * which is the only honest reason for the stamp to stop advancing. `AttentionCenter`
 * renews from `DshConnectionService`'s keep-alive loop and from every pending-set
 * change; the view model renews from the loop that already watches the socket. When
 * neither is running, nothing at all happens here, which is the point: there is no
 * poll, no `JobScheduler` and no `updatePeriodMillis` keeping a dead app awake to
 * say the same number again.
 *
 * ## What a renewal costs
 *
 * Callers may push as often as they like — [working] is called from a one-second
 * loop — and this class collapses that into a file write **and a widget update** at
 * most once per [HEARTBEAT_MS], or immediately when a value actually moves. Both
 * halves of that matter, and the second is the one this feature is paying for: an
 * `updateAppWidget` is a binder call into the system and a `RemoteViews` re-applied
 * in the launcher's process, so a draw per renewal is up to 86,400 of them a day for
 * as long as the app holds its socket — screen on or off, widget on screen or not.
 * [publishDue] is what stops that. The heartbeat is what still makes an unchanged
 * reading worth saying again: the footer prints an instant rather than ticking an
 * age ([WidgetCopy.stampTime]), so the re-draw is the only thing that moves that
 * instant forward, and it is a bound the card owes the reader rather than a refresh
 * the reader is paying for by the second.
 *
 * ## What it deliberately does when no widget is placed
 *
 * Nothing. Not a file, not an update, not a wake-up. A feature nobody has put on
 * their homescreen must cost exactly zero.
 */
class WidgetPublisher(
    private val context: Context,
    /**
     * `Dispatchers.IO` in the app; a test passes `Dispatchers.Unconfined` so the
     * publication is complete by the time the call returns. A parameter rather than a
     * test hook: the write and the widget update both have to be off the main thread,
     * and that is the whole of what this seam decides.
     */
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val store = WidgetSnapshotStore(File(context.filesDir, FILE_NAME))
    private val manager: AppWidgetManager? =
        runCatching { AppWidgetManager.getInstance(context) }.getOrNull()
    private val component = ComponentName(context, DshWidgetProvider::class.java)

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    /** Conflated: a burst of roster frames is one publication, not a hundred. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var needsYouReading = WidgetReading.NEVER
    private var workingReading = WidgetReading.NEVER
    private var subagentReading = WidgetReading.NEVER

    /**
     * The named running sessions behind [workingReading], already capped and already
     * stripped of rows a card could not draw. Empty whenever the reading is withdrawn,
     * because a name is a claim about a session being at work and a withdrawn reading
     * cannot make one.
     */
    private var runningSessions: List<WidgetSession> = emptyList()
    private var kind: String? = null
    private var sessionId: String? = null

    /**
     * Whether an owner has published in *this* process run. False means the readings
     * above came from the file, so the widget's tap has to be able to draw them
     * synchronously — see [onWidgetsChanged].
     */
    @Volatile
    private var everPublished = false

    /**
     * A draw that has been asked for regardless of the heartbeat: the host has just added
     * or re-bound a widget and has nothing on screen yet. Sticky until [publish] consumes
     * it, so a conflated burst of requests cannot lose the one that matters — the flag
     * survives whatever the channel collapses it into.
     */
    private var drawRequested = false

    /** Only ever touched by [publish] and the constructor, both of which are serial. */
    private var lastWritten: WidgetSnapshot? = null
    private var lastWriteAt = 0L

    init {
        // Seeded from the last run, not reset. A restart does not make yesterday's
        // reading false — it makes it old, which is what its instant already says. Starting
        // from nothing would instead claim that nothing was ever seen, which is a worse
        // lie than an old number.
        store.read()?.let { previous ->
            needsYouReading = previous.needsYou
            workingReading = previous.working
            subagentReading = previous.subagents
            runningSessions = previous.running
            kind = previous.kind
            sessionId = previous.sessionId
            lastWritten = previous
        }
        scope.launch { for (ignored in wake) runCatching { publish() } }
    }

    /**
     * Publishes what is waiting on the user: the count, the highest-precedence kind
     * behind it (the same ranking the drawer's status dot uses) and the session to
     * open. [known] is false when the app cannot currently see the event stream, and
     * that is a different statement from a count of zero.
     */
    fun needsYou(count: Int, kind: PendingKind?, sessionId: String?, known: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            needsYouReading = stamped(needsYouReading, known, count, now)
            // A kind and a session are only meaningful *behind* a count, so they are
            // dropped with it rather than left over to name a tap into nowhere.
            this.kind = if (known && count > 0) kind?.name else null
            this.sessionId = if (known && count > 0) sessionId else null
            everPublished = true
        }
        wake.trySend(Unit)
    }

    /**
     * Publishes what is running: every session with an agent working, in the app's own
     * roster order, plus how many of those are subagents. The count is [running]'s own
     * size rather than a second figure passed alongside it, because a count and a list
     * that arrive separately can disagree, and a card that names three sessions while
     * saying two are running is the kind of lie this widget exists to avoid.
     *
     * [running] carries **every** running session, not just the first few. This method
     * decides what may be named ([namedSessions]) and what is withheld, so the "+N more"
     * on the card is arithmetic over one list rather than a second opinion.
     *
     * The two readings move together, and so does their confidence — a roster read is
     * what establishes either, so `known = false` withdraws the count and the names in
     * the same breath.
     */
    fun working(running: List<WidgetSession>, subagents: Int, known: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            workingReading = stamped(workingReading, known, running.size, now)
            subagentReading = stamped(subagentReading, known, subagents, now)
            // Names go with the basis. A withdrawn reading is drawn as "Running: unknown"
            // and must not leave a list of sessions in the file that a later run (or a
            // later build) could read as still true.
            runningSessions = if (known) namedSessions(running) else emptyList()
            everPublished = true
        }
        wake.trySend(Unit)
    }

    /**
     * Called by [DshWidgetProvider] when a widget appears, and by nothing else.
     *
     * If an owner has already published, the ordinary path draws it — but *forced*, past
     * [publishDue], because a widget that has just been placed has nothing on screen and
     * an unchanged reading is exactly what it needs. If no owner has published yet — the
     * receiver was started cold by the host to add a widget — the last run's file is
     * drawn *here and now*, because there is no guarantee this process outlives the
     * broadcast, and an asynchronously drawn widget that never arrives is a blank card.
     */
    fun onWidgetsChanged() {
        val published = synchronized(lock) {
            drawRequested = true
            everPublished
        }
        if (published) {
            wake.trySend(Unit)
            return
        }
        val snapshot = store.read() ?: WidgetSnapshot.NEVER
        draw(snapshot, placedWidgetIds())
    }

    private fun publish() {
        val forced = synchronized(lock) {
            val requested = drawRequested
            drawRequested = false
            requested
        }
        val snapshot = synchronized(lock) {
            WidgetSnapshot(
                needsYouReading,
                workingReading,
                subagentReading,
                runningSessions,
                kind,
                sessionId,
            )
        }
        val now = System.currentTimeMillis()
        // The gate, and it is deliberately in front of `placedWidgetIds()` as well as in
        // front of the write: a renewal with nothing new in it must not cost a binder call
        // into the system service, let alone one into the launcher's process. `lastWriteAt`
        // moves here, before the ids are asked for, so a phone with no widget placed is
        // asked once per heartbeat rather than once per renewal.
        if (!publishDue(snapshot, lastWritten, lastWriteAt, now, forced)) return
        lastWritten = snapshot
        lastWriteAt = now

        val ids = placedWidgetIds()
        // No widget on any home screen: nothing is written and nobody is woken. A user who
        // never placed one pays nothing here, and the reading stays in memory so that
        // placing one later is drawn from what this run already knows.
        if (ids.isEmpty()) return
        store.write(snapshot)
        draw(snapshot, ids)
    }

    private fun draw(snapshot: WidgetSnapshot, ids: IntArray) {
        if (ids.isEmpty()) return
        val views = WidgetRenderer.render(context, WidgetCopy.of(snapshot), snapshot.sessionId)
        runCatching { manager?.updateAppWidget(ids, views) }
    }

    private fun placedWidgetIds(): IntArray =
        runCatching { manager?.getAppWidgetIds(component) ?: IntArray(0) }.getOrDefault(IntArray(0))

    /**
     * The running sessions the card may name, from the roster order it was given.
     *
     * Public, not `internal`, for the same wall [stamped] is: this repo's unit-test
     * source set is not a friend of main, so a rule that must be tested has to be
     * reachable.
     *
     * The order is the caller's, which is the app's own roster order — most recently
     * updated first, the order the drawer lists them in. The widget must not invent a
     * ranking of its own, because a card that reordered work would disagree with the
     * screen the reader opens next.
     *
     * Two filters, and both are honesty rather than tidiness:
     *
     *  * **No id, no row.** A name the reader cannot tap is worse than no row: it looks
     *    like something they can act on. (The decoder refuses such a row as well.)
     *  * **No title, no row.** A blank title would draw as an empty line, which reads as
     *    a session that has no name rather than one whose name the app has not been
     *    given. That row still counts towards [working]'s total, so the card answers it
     *    with "+N more" instead of silently pretending it is not running.
     *
     * Only the first [WidgetSnapshot.MAX_NAMED_SESSIONS] survive; the rest are what the
     * "+N more" line is for.
     */
    fun namedSessions(running: List<WidgetSession>): List<WidgetSession> =
        running.asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .take(WidgetSnapshot.MAX_NAMED_SESSIONS)
            .toList()

    /**
     * Stamps a reading with the instant its owner just spoke.
     *
     * Public, not `internal`, because this repo's unit-test source set is not a friend of
 * main (`internal` here is unresolved from the tests - the same wall another agent hit).
 * It is reachable so the rule can be tested directly with two instants: this harness cannot
 * move the wall clock (`ShadowSystemClock` offers only `currentTimeMillis` and
 * `advanceBy`, and that moves the uptime clock), so a test that needs `now` to differ
 * must supply it rather than wait for it.
 *
 * A confirmed reading is stamped now. A reading that has just *lost* its basis is
     * also stamped now: that instant is the last one at which it was still true, and
     * it is what the footer will count from. A reading that was never confirmed has no
     * such instant and is left exactly as it was — inventing one would put "checked
     * 0:01 ago" under a number nobody ever had.
     */
    fun stamped(previous: WidgetReading, known: Boolean, value: Int, now: Long): WidgetReading =
        when {
            known -> WidgetReading(known = true, value = value, asOf = now)
            previous.known -> WidgetReading(known = false, value = value, asOf = now)
            else -> previous
        }

    /**
     * Whether a publication is worth a file write **and** a widget update.
     *
     * Public, and with the clock supplied, for the same reason [stamped] is: this repo's
     * unit-test source set cannot see an `internal` symbol, and this harness cannot move
     * the wall clock, so a rule about the passage of time has to be handed the two instants
     * rather than made to wait for one of them.
     *
     * The rule is the whole battery story. [working] is renewed once a second while the app
     * holds its socket, and before this gate existed each of those renewals reached the
     * launcher: an `updateAppWidget` binder call, a `RemoteViews` re-applied, and a
     * `Chronometer` base reset, up to 86,400 times a day, screen on or off, widget on screen
     * or not. Only two things are worth any of that:
     *
     *  * **A value that moved.** A count, a name, a kind, the session a tap would open —
     *    each is a thing the card draws, so each has to reach it at once. The comparison
     *    ignores `asOf` ([WidgetSnapshot.sameValuesAs]), which is what makes a renewal with
     *    nothing new in it free: a fresher instant for the same value is not news.
     *  * **The heartbeat.** Without it an unchanged card would never be re-drawn, and the
     *    instant in the footer would stop advancing while the app was still listening —
     *    so the card would say "11 Oct 12:03" for as long as the app ran. [HEARTBEAT_MS]
     *    is the longest the stamp may lag the truth, and it is the same window
     *    `AttentionCenter` gives a waterfall to be answered in.
     *
     * [forced] is the third case and not a rule: a widget has just been placed or re-bound
     * and has nothing on screen, so it is drawn whatever the other two say.
     */
    fun publishDue(
        snapshot: WidgetSnapshot,
        previous: WidgetSnapshot?,
        writtenAt: Long,
        now: Long,
        forced: Boolean = false,
    ): Boolean = forced || !snapshot.sameValuesAs(previous) || now - writtenAt >= HEARTBEAT_MS

    companion object {
        /**
         * Under `files/`, where only this app can read it. It is deliberately not
         * exportable, not shared and not a second config file: the widget's process is
         * this app's process, so there is nothing to negotiate with.
         */
        const val FILE_NAME = "widget/snapshot.json"

        /**
         * How long a reading may go un-renewed before it is written and drawn again.
         *
         * Fifteen minutes is not a round number picked for looks: it is
         * `AttentionCenter.QUESTION_TIMEOUT_MS`, the longest a waterfall can still be
         * answerable. While the app is connected it re-stamps at least this often, so
         * the instant the footer prints stays inside the window in which a count could
         * still be acted on. It costs one widget update per quarter hour, and only while
         * a widget is placed *and* the app is already running — never a wake-up of a
         * dead app, which is what `updatePeriodMillis` would buy.
         */
        const val HEARTBEAT_MS = 15 * 60 * 1000L
    }
}
