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
}
