package uk.xa0.dsh.widget

import android.content.Context
import android.view.View
import android.widget.Chronometer
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import uk.xa0.dsh.Attention
import uk.xa0.dsh.MainActivity
import uk.xa0.dsh.R
import uk.xa0.dsh.scroll.HarnessApplication

/**
 * What actually lands on the card, drawn rather than described.
 *
 * `RemoteViews` is applied into a real parent here and the resulting views are read
 * back, so these assertions are about the widget a launcher would draw and not about
 * the code that asked for it. This is the one part of the feature a device would
 * otherwise have to tell us, and it does not need one: layout inflation, view lookup
 * and text are all real on this harness.
 *
 * What the harness *cannot* see is where text breaks — its metrics are stubs, so a
 * thousand characters measure as one line (see `docs/HANDOFF.md`). So nothing here
 * asserts a height, a wrap or an overflow: `ellipsize` and `maxLines` are asserted as
 * the properties they are, in the layout, and the rest is written down in
 * `docs/WIDGET.md` as unverified on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class WidgetRenderTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun draw(text: WidgetText, sessionId: String? = null): View =
        WidgetRenderer.render(context, text, sessionId).apply(context, FrameLayout(context))

    private fun View.headline(): TextView = findViewById(R.id.widget_headline)
    private fun View.detail(): TextView = findViewById(R.id.widget_detail)
    private fun View.stamp(): TextView = findViewById(R.id.widget_stamp)
    private fun View.age(): Chronometer = findViewById(R.id.widget_age)

    private fun View.row(index: Int): TextView = findViewById(ROW_IDS[index])
    private fun View.moreRows(): TextView = findViewById(R.id.widget_row_more)

    @Test
    fun `every line of the text lands in its own view`() {
        val at = 1_700_000_000_000L
        val view = draw(
            WidgetText(
                headline = "Approval waiting",
                detail = "2 running · 1 subagent",
                stampLabel = WidgetCopy.STAMP_OLDEST,
                ageBase = at,
                level = WidgetLevel.ATTENTION,
            ),
        )

        assertEquals("Approval waiting", view.headline().text.toString())
        assertEquals("2 running · 1 subagent", view.detail().text.toString())
        // The literal, not the constant: the word on the card is the claim.
        assertEquals("oldest reading", view.stamp().text.toString())
        assertEquals(View.VISIBLE, view.age().visibility)
        // The base is the reading's instant, not "now": the Chronometer's whole job is to
        // derive the age from it in the launcher's own process, with nothing of ours alive.
        assertEquals(at, view.age().base)
    }

    @Test
    fun `a card with nothing to date has no age, only a sentence`() {
        val view = draw(
            WidgetText(
                headline = WidgetCopy.NEEDS_UNKNOWN,
                detail = WidgetCopy.RUNNING_UNKNOWN,
                stampLabel = WidgetCopy.STAMP_OPEN,
                ageBase = null,
                level = WidgetLevel.UNKNOWN,
            ),
        )
        assertEquals(WidgetCopy.NEEDS_UNKNOWN, view.headline().text.toString())
        assertEquals(WidgetCopy.STAMP_OPEN, view.stamp().text.toString())
        assertEquals("no age may be drawn without an instant to count from", View.GONE, view.age().visibility)
    }

    @Test
    fun `the accent is the only way a widget can raise its voice`() {
        val quiet = draw(
            WidgetText("Nothing needs you", "Nothing running", WidgetCopy.STAMP_OLDEST, 1L, WidgetLevel.QUIET),
        )
        val loud = draw(
            WidgetText("Approval waiting", "1 running", WidgetCopy.STAMP_OLDEST, 1L, WidgetLevel.ATTENTION),
        )
        val unknown = draw(
            WidgetText(WidgetCopy.NEEDS_UNKNOWN, null, WidgetCopy.STAMP_OPEN, null, WidgetLevel.UNKNOWN),
        )
        assertEquals(context.getColor(R.color.widget_text_quiet), quiet.headline().currentTextColor)
        assertEquals(context.getColor(R.color.widget_text_attention), loud.headline().currentTextColor)
        assertEquals(context.getColor(R.color.widget_text_unknown), unknown.headline().currentTextColor)
    }

    @Test
    fun `a detail line that does not exist is hidden rather than left blank`() {
        val view = draw(
            WidgetText("Nothing needs you", null, WidgetCopy.STAMP_OLDEST, 1L, WidgetLevel.QUIET),
        )
        assertEquals(View.GONE, view.detail().visibility)
    }

    @Test
    fun `named sessions land in the fixed row views, and the count line gives way to them`() {
        val view = draw(
            WidgetCopy.of(
                WidgetSnapshot(
                    needsYou = WidgetReading(known = true, value = 0, asOf = 1L),
                    working = WidgetReading(known = true, value = 2, asOf = 1L),
                    subagents = WidgetReading(known = false, value = 0, asOf = 0L),
                    running = listOf(
                        WidgetSession("session-a", "Fix the login bug"),
                        WidgetSession("session-b", "Widget sessions"),
                    ),
                ),
            ),
        )

        assertEquals(View.GONE, view.detail().visibility)
        assertEquals(View.VISIBLE, view.row(0).visibility)
        assertEquals("· Fix the login bug", view.row(0).text.toString())
        assertEquals(View.VISIBLE, view.row(1).visibility)
        assertEquals("· Widget sessions", view.row(1).text.toString())
        assertEquals("a fixed layout draws no more rows than it was given", View.GONE, view.row(2).visibility)
        assertEquals(View.GONE, view.moreRows().visibility)
    }

    @Test
    fun `an overflow is drawn as its own line rather than dropped`() {
        val view = draw(
            WidgetCopy.of(
                WidgetSnapshot(
                    needsYou = WidgetReading(known = true, value = 0, asOf = 1L),
                    working = WidgetReading(known = true, value = 5, asOf = 1L),
                    subagents = WidgetReading(known = false, value = 0, asOf = 0L),
                    running = listOf(
                        WidgetSession("session-a", "Alpha"),
                        WidgetSession("session-b", "Beta"),
                        WidgetSession("session-c", "Gamma"),
                    ),
                ),
            ),
        )

        assertEquals(View.VISIBLE, view.moreRows().visibility)
        assertEquals("+2 more", view.moreRows().text.toString())
        assertEquals(View.VISIBLE, view.row(2).visibility)
    }

    @Test
    fun `a card that cannot name anything draws no rows at all`() {
        val view = draw(
            WidgetCopy.of(
                WidgetSnapshot(
                    needsYou = WidgetReading(known = true, value = 0, asOf = 1L),
                    working = WidgetReading(known = true, value = 4, asOf = 1L),
                    subagents = WidgetReading(known = false, value = 0, asOf = 0L),
                ),
            ),
        )

        assertEquals("4 running", view.detail().text.toString())
        assertEquals(View.VISIBLE, view.detail().visibility)
        assertEquals(View.GONE, view.row(0).visibility)
        assertEquals(View.GONE, view.moreRows().visibility)
    }

    /**
     * Every row's tap has to be its own. `Intent.filterEquals` — which is what decides
     * whether two `PendingIntent`s are the same one — compares everything *except* the
     * extras, so three rows carrying three session ids would collapse into a single
     * intent, and all three arrows would open whichever row was drawn last. That is why
     * the row index is part of the request code, and this is the test that would fail if
     * it were ever dropped.
     */
    @Test
    fun `each named row keeps its own session in its own tap`() {
        val saved = listOf("session-a", "session-b", "session-c").mapIndexed { index, id ->
            shadowOf(WidgetRenderer.sessionIntent(context, id, index)).savedIntent
        }

        assertEquals(
            listOf("session-a", "session-b", "session-c"),
            saved.map { it.getStringExtra(Attention.EXTRA_SESSION) },
        )
        // The same path in as a notification: no second extra, no second activity.
        assertEquals(
            setOf(MainActivity::class.java.name),
            saved.map { it.component?.className }.toSet(),
        )
    }

    @Test
    fun `the tap carries the session that needs the user, through the existing launch path`() {
        val pending = WidgetRenderer.contentIntent(context, "session-a771fb9b")
        val saved = shadowOf(pending).savedIntent
        assertEquals(MainActivity::class.java.name, saved.component?.className)
        // The same extra `Attention.notify` sets and `MainActivity.consume` reads: a
        // widget that invented its own way in would be a second path to keep working.
        assertEquals("session-a771fb9b", saved.getStringExtra(Attention.EXTRA_SESSION))
    }

    @Test
    fun `a tap with nothing waiting just opens the app`() {
        val saved = shadowOf(WidgetRenderer.contentIntent(context, null)).savedIntent
        assertEquals(MainActivity::class.java.name, saved.component?.className)
        assertNull(saved.getStringExtra(Attention.EXTRA_SESSION))
    }

    @Test
    fun `a widget can be drawn with nothing known and no session at all`() {
        val view = draw(WidgetCopy.of(null), sessionId = null)
        assertEquals(WidgetCopy.NEEDS_UNKNOWN, view.headline().text.toString())
    }

    private companion object {
        val ROW_IDS = intArrayOf(R.id.widget_row_0, R.id.widget_row_1, R.id.widget_row_2)
    }
}
