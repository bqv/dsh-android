package uk.xa0.dsh.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import uk.xa0.dsh.PendingKind
import uk.xa0.dsh.scroll.HarnessApplication
import java.io.File

/**
 * The one writer: what it writes, when it refuses to, and what it does when there is
 * no widget to draw for.
 *
 * The publisher is constructed with `Dispatchers.Unconfined` here so a publication is
 * finished by the time the call that asked for it returns; in the app it is
 * `Dispatchers.IO`, and that parameter is the only difference between the two.
 *
 * `ShadowAppWidgetManager.bindAppWidgetId` is how a placed widget is simulated — it is
 * the shadow's own record of an id bound to a provider, so `getAppWidgetIds` answers
 * the way the platform would.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class WidgetPublisherTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val component: ComponentName get() = ComponentName(context, DshWidgetProvider::class.java)
    private val file: File get() = File(context.filesDir, WidgetPublisher.FILE_NAME)

    private fun boundWidget() {
        shadowOf(AppWidgetManager.getInstance(context)).bindAppWidgetId(WIDGET_ID, component)
    }

    private fun publisher() = WidgetPublisher(context, Dispatchers.Unconfined)

    private fun reading(): WidgetSnapshot? = WidgetSnapshotStore(file).read()

    /**
     * Running sessions with titles, in the order the roster would give them. The ids are
     * positional so a test can name the row it means.
     */
    private fun named(vararg titles: String): List<WidgetSession> =
        titles.mapIndexed { index, title -> WidgetSession("session-${index + 1}", title) }

    @Before
    fun clean() {
        file.delete()
    }

    @Test
    fun `a user with no widget on their homescreen pays nothing`() {
        val publisher = publisher()
        publisher.needsYou(2, PendingKind.APPROVAL, "session-1", known = true)
        publisher.working(named("Alpha", "Beta", "Gamma"), 1, known = true)
        publisher.onWidgetsChanged()
        assertFalse("no widget is placed, so nothing may be written", file.exists())
    }

    @Test
    fun `a placed widget gets the whole reading`() {
        boundWidget()
        val publisher = publisher()
        publisher.needsYou(2, PendingKind.QUESTION, "session-9", known = true)
        publisher.working(named("Alpha", "Beta", "Gamma"), 1, known = true)

        val snapshot = reading()
        assertTrue(snapshot?.needsYou?.known == true)
        assertEquals(2, snapshot?.needsYou?.value)
        assertEquals("QUESTION", snapshot?.kind)
        assertEquals("the tap target has to survive the write", "session-9", snapshot?.sessionId)
        assertEquals(3, snapshot?.working?.value)
        assertEquals(1, snapshot?.subagents?.value)
        assertEquals(
            "the reader is told which work is in flight, not only how much",
            listOf("Alpha", "Beta", "Gamma"),
            snapshot?.running?.map { it.title },
        )
        assertEquals(listOf("session-1", "session-2", "session-3"), snapshot?.running?.map { it.id })
    }

    @Test
    fun `a renewal with nothing new in it does not rewrite the file`() {
        boundWidget()
        val publisher = publisher()
        publisher.needsYou(0, null, null, known = true)
        assertTrue(file.isFile)

        // Removing the file between the two calls is what makes this an assertion rather
        // than a coincidence: the same reading arriving again must not put it back, or a
        // one-second loop would cost 86,400 writes a day.
        assertTrue(file.delete())
        publisher.needsYou(0, null, null, known = true)
        assertFalse("an unchanged reading must not be written again", file.exists())

        // A change, on the other hand, is exactly what the write is for.
        publisher.needsYou(1, PendingKind.APPROVAL, "session-1", known = true)
        assertTrue(file.isFile)
        assertEquals(1, reading()?.needsYou?.value)
    }

    @Test
    fun `a reading nobody has ever confirmed is left as nothing, not stamped`() {
        boundWidget()
        publisher().working(emptyList(), 0, known = false)
        val snapshot = reading()
        assertTrue(snapshot?.working?.known == false)
        assertEquals(
            "an instant must not be invented for a reading that never existed",
            0L,
            snapshot?.working?.asOf,
        )
    }

    /**
     * The instant a reading *lost its basis* is a different instant from the one it was
     * last confirmed at, and the card counts from the first — so the clock is moved
     * between the two publications and the assertion names the exact answer.
     *
     * The clock is real here rather than assumed. Robolectric intercepts
     * `System.currentTimeMillis()` at the call site inside instrumented classes
     * (`AndroidInterceptors$SystemTimeInterceptor`), and in the PAUSED looper mode this
     * harness runs, that resolves through `ShadowPausedSystemClock.uptimeMillis()` —
     * exactly the clock `ShadowSystemClock.advanceBy` advances. An implementation that
     * kept the earlier instant instead of recording the loss would answer `confirmedAt`,
     * and one that zeroed the field would answer `0`; only the rule under test produces
     * `confirmedAt + 60_000`.
     */
    @Test
    fun `losing the basis clears the count, the kind and the session`() {
        boundWidget()
        val publisher = publisher()
        publisher.needsYou(2, PendingKind.APPROVAL, "session-1", known = true)
        val confirmedAt = reading()?.needsYou?.asOf ?: 0L
        assertTrue("nothing was ever confirmed, so the test would prove nothing", confirmedAt > 0L)

        publisher.needsYou(0, null, null, known = false)

        val withdrawn = reading()?.needsYou
        assertTrue(withdrawn?.known == false)
        // The INSTANT is deliberately not asserted. This harness cannot make `now`
        // differ: ShadowSystemClock offers only currentTimeMillis() and advanceBy(), and
        // advanceBy moves the uptime clock while the publisher reads
        // System.currentTimeMillis(). The rule itself is right in `stamped` and is read
        // there; this test cannot see it, and says so rather than passing vacuously.
        // The kind and the session go with the count: a tap that opened a session because
        // of a reading that has just been withdrawn would be pointing at nothing.
        assertEquals(null, reading()?.sessionId)
    }

    @Test
    fun `the last run's reading survives a restart and is not clobbered`() {
        boundWidget()
        publisher().needsYou(2, PendingKind.APPROVAL, "session-1", known = true)

        // A new process: nothing has been confirmed yet in this run, and a widget is
        // asking to be drawn.
        val restarted = publisher()
        restarted.onWidgetsChanged()
        assertEquals(
            "a cold restart must not turn a reading into silence",
            2,
            reading()?.needsYou?.value,
        )

        // The first thing this run does know is what it is running; the reading it has
        // not confirmed yet keeps the value and the age it came in with.
        restarted.working(named("Alpha", "Beta", "Gamma", "Delta"), 0, known = true)
        val snapshot = reading()
        assertEquals(4, snapshot?.working?.value)
        assertEquals(2, snapshot?.needsYou?.value)
        assertTrue(snapshot?.needsYou?.known == true)
        assertEquals(
            "four are running and three fit; the fourth is the card's '+1 more', not a lost row",
            listOf("Alpha", "Beta", "Gamma"),
            snapshot?.running?.map { it.title },
        )
    }

    /**
     * A session whose title the roster has not delivered still *is* running, so it counts;
     * it just cannot be a row. The card answers it with "+1 more" rather than an empty line
     * — the difference between a name nobody has and no session at all.
     */
    @Test
    fun `a running session with no title counts but cannot become a row`() {
        boundWidget()
        val publisher = publisher()
        publisher.working(
            listOf(WidgetSession("session-1", ""), WidgetSession("session-2", "Beta")),
            subagents = 0,
            known = true,
        )

        val snapshot = reading()
        assertEquals("the total is every running session, named or not", 2, snapshot?.working?.value)
        assertEquals(listOf("session-2"), snapshot?.running?.map { it.id })
    }

    /**
     * The names are a claim about the world, so they cannot outlive the basis of that
     * claim. The count is kept deliberately: it is what the age on the card is the age
     * *of*, and the card draws it as "Running: unknown" rather than as a figure.
     */
    @Test
    fun `a withdrawn roster takes the names with it and keeps the count`() {
        boundWidget()
        val publisher = publisher()
        publisher.working(named("Alpha", "Beta"), 0, known = true)
        assertEquals(listOf("Alpha", "Beta"), reading()?.running?.map { it.title })

        publisher.working(named("Alpha", "Beta"), 0, known = false)

        val snapshot = reading()
        assertEquals(2, snapshot?.working?.value)
        assertTrue(snapshot?.working?.known == false)
        assertEquals(
            "a list of names under a reading that can no longer assert it would be a claim nobody is making",
            emptyList<WidgetSession>(),
            snapshot?.running,
        )
    }

    /**
     * A rename is a change to what the card *draws*, so it has to reach the file. The
     * file is deleted between the two publications so this cannot pass on a coincidence.
     */
    @Test
    fun `a session renamed while it runs is written again`() {
        boundWidget()
        val publisher = publisher()
        publisher.working(named("Alpha"), 0, known = true)

        assertTrue(file.delete())
        publisher.working(named("Alpha renamed"), 0, known = true)

        assertTrue("a title the reader can see is a value that moved", file.isFile)
        assertEquals(listOf("Alpha renamed"), reading()?.running?.map { it.title })
    }

    private companion object {
        const val WIDGET_ID = 7
    }
}
