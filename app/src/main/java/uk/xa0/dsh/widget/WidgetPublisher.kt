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
 * which is the only honest reason for an age to start counting. `AttentionCenter`
 * renews from `DshConnectionService`'s keep-alive loop and from every pending-set
 * change; the view model renews from the loop that already watches the socket. When
 * neither is running, nothing at all happens here, which is the point: there is no
 * poll, no `JobScheduler` and no `updatePeriodMillis` keeping a dead app awake to
 * say the same number again.
 *
 * ## What a renewal costs
 *
 * Callers may push as often as they like — [working] is called from a one-second
 * loop — and this class collapses that into a file write and a widget update at
 * most once per [HEARTBEAT_MS], or immediately when a value actually moves. That
 * keeps the ticking age in [WidgetCopy] small while the app is genuinely looking at
 * the world, so "checked 0:20 ago" means what it says instead of drifting to hours
 * while the app is fine.
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
    private var kind: String? = null
    private var sessionId: String? = null

    /**
     * Whether an owner has published in *this* process run. False means the readings
     * above came from the file, so the widget's tap has to be able to draw them
     * synchronously — see [onWidgetsChanged].
     */
    @Volatile
    private var everPublished = false

    /** Only ever touched by [publish] and the constructor, both of which are serial. */
    private var lastWritten: WidgetSnapshot? = null
    private var lastWriteAt = 0L

    init {
        // Seeded from the last run, not reset. A restart does not make yesterday's
        // reading false — it makes it old, which is what its age already says. Starting
        // from nothing would instead claim that nothing was ever seen, which is a worse
        // lie than an old number.
        store.read()?.let { previous ->
            needsYouReading = previous.needsYou
            workingReading = previous.working
            subagentReading = previous.subagents
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
     * Publishes what is running: sessions with an agent working, and how many of those
     * are subagents. The two move together, and so does their confidence — a roster
     * read is what establishes either.
     */
    fun working(sessions: Int, subagents: Int, known: Boolean) {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            workingReading = stamped(workingReading, known, sessions, now)
            subagentReading = stamped(subagentReading, known, subagents, now)
            everPublished = true
        }
        wake.trySend(Unit)
    }

    /**
     * Called by [DshWidgetProvider] when a widget appears, and by nothing else.
     *
     * If an owner has already published, the ordinary path draws it. If not — the
     * receiver was started cold by the host to add a widget — the last run's file is
     * drawn *here and now*, because there is no guarantee this process outlives the
     * broadcast, and an asynchronously drawn widget that never arrives is a blank
     * card.
     */
    fun onWidgetsChanged() {
        if (everPublished) {
            wake.trySend(Unit)
            return
        }
        val snapshot = store.read() ?: WidgetSnapshot.NEVER
        draw(snapshot, placedWidgetIds())
    }

    private fun publish() {
        val snapshot = synchronized(lock) {
            WidgetSnapshot(needsYouReading, workingReading, subagentReading, kind, sessionId)
        }
        val ids = placedWidgetIds()
        // No widget on any home screen: the reading is still held in memory, so adding
        // one is drawn from what this run knows, but nothing is written and nobody is
        // woken. A user who never placed the widget pays nothing for it.
        if (ids.isEmpty()) return

        val now = System.currentTimeMillis()
        if (!snapshot.sameValuesAs(lastWritten) || now - lastWriteAt >= HEARTBEAT_MS) {
            store.write(snapshot)
            lastWritten = snapshot
            lastWriteAt = now
        }
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
     * Stamps a reading with the instant its owner just spoke.
     *
     * A confirmed reading is stamped now. A reading that has just *lost* its basis is
     * also stamped now: that instant is the last one at which it was still true, and
     * it is what the footer will count from. A reading that was never confirmed has no
     * such instant and is left exactly as it was — inventing one would put "checked
     * 0:01 ago" under a number nobody ever had.
     */
    private fun stamped(previous: WidgetReading, known: Boolean, value: Int, now: Long): WidgetReading =
        when {
            known -> WidgetReading(known = true, value = value, asOf = now)
            previous.known -> WidgetReading(known = false, value = value, asOf = now)
            else -> previous
        }

    companion object {
        /**
         * Under `files/`, where only this app can read it. It is deliberately not
         * exportable, not shared and not a second config file: the widget's process is
         * this app's process, so there is nothing to negotiate with.
         */
        const val FILE_NAME = "widget/snapshot.json"

        /**
         * How long a reading may go un-renewed before the file is stamped again.
         *
         * Fifteen minutes is not a round number picked for looks: it is
         * `AttentionCenter.QUESTION_TIMEOUT_MS`, the longest a waterfall can still be
         * answerable. While the app is connected it re-stamps at least this often, so
         * the widget's age stays inside the window in which a count could still be
         * acted on. It costs one widget update per quarter hour, and only while a
         * widget is placed *and* the app is already running — never a wake-up of a
         * dead app, which is what `updatePeriodMillis` would buy.
         */
        const val HEARTBEAT_MS = 15 * 60 * 1000L
    }
}
