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

    @Before
    fun clean() {
        file.delete()
    }

    @Test
    fun `a user with no widget on their homescreen pays nothing`() {
        val publisher = publisher()
        publisher.needsYou(2, PendingKind.APPROVAL, "session-1", known = true)
        publisher.working(3, 1, known = true)
        publisher.onWidgetsChanged()
        assertFalse("no widget is placed, so nothing may be written", file.exists())
    }

    @Test
    fun `a placed widget gets the whole reading`() {
        boundWidget()
        val publisher = publisher()
        publisher.needsYou(2, PendingKind.QUESTION, "session-9", known = true)
        publisher.working(3, 1, known = true)

        val snapshot = reading()
        assertTrue(snapshot?.needsYou?.known == true)
        assertEquals(2, snapshot?.needsYou?.value)
        assertEquals("QUESTION", snapshot?.kind)
        assertEquals("the tap target has to survive the write", "session-9", snapshot?.sessionId)
        assertEquals(3, snapshot?.working?.value)
        assertEquals(1, snapshot?.subagents?.value)
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
        publisher().working(0, 0, known = false)
        val snapshot = reading()
        assertTrue(snapshot?.working?.known == false)
        assertEquals(
            "an instant must not be invented for a reading that never existed",
            0L,
            snapshot?.working?.asOf,
        )
    }

    @Test
    fun `losing the basis records the instant it was lost`() {
        boundWidget()
        val publisher = publisher()
        publisher.needsYou(2, PendingKind.APPROVAL, "session-1", known = true)
        val known = reading()?.needsYou?.asOf ?: 0L
        assertTrue(known > 0L)

        publisher.needsYou(0, null, null, known = false)
        val withdrawn = reading()?.needsYou
        assertTrue(withdrawn?.known == false)
        assertTrue(
            "the last instant the reading was true is what the card counts from",
            (withdrawn?.asOf ?: 0L) >= known,
        )
        // The name and the session go with the count: a tap that opened a session because
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
        restarted.working(4, 0, known = true)
        val snapshot = reading()
        assertEquals(4, snapshot?.working?.value)
        assertEquals(2, snapshot?.needsYou?.value)
        assertTrue(snapshot?.needsYou?.known == true)
    }

    private companion object {
        const val WIDGET_ID = 7
    }
}
