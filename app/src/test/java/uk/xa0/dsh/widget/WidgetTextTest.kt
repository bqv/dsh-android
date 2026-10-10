package uk.xa0.dsh.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The honesty table.
 *
 * Every one of these is a statement about what the widget is allowed to *say*, and
 * they are the tests that matter most in this feature: a widget is read out of context
 * and out of time by somebody who has not just opened the app, so a line it should not
 * have drawn is worse than a line it forgot to draw.
 *
 * Pure JVM — no Context, no launcher, no clock. [WidgetCopy.of] takes a snapshot and
 * returns three strings, which is the whole point of separating it from the renderer.
 */
class WidgetTextTest {

    private val at = 1_700_000_000_000L

    private fun snapshot(
        needsKnown: Boolean = true,
        needs: Int = 0,
        kind: String? = null,
        workingKnown: Boolean = true,
        working: Int = 0,
        subagents: Int = 0,
        asOf: Long = at,
    ) = WidgetSnapshot(
        needsYou = WidgetReading(known = needsKnown, value = needs, asOf = asOf),
        working = WidgetReading(known = workingKnown, value = working, asOf = asOf),
        subagents = WidgetReading(known = workingKnown, value = subagents, asOf = asOf),
        kind = kind,
    )

    @Test
    fun `no file at all says so rather than claiming an empty world`() {
        val text = WidgetCopy.of(null)
        assertEquals("Needs you: unknown", text.headline)
        assertEquals("Running: unknown", text.detail)
        assertEquals(WidgetCopy.STAMP_OPEN, text.stampLabel)
        assertNull("there is no age because nothing was ever true", text.ageBase)
        assertEquals(WidgetLevel.UNKNOWN, text.level)
    }

    @Test
    fun `a confirmed empty world is said plainly`() {
        val text = WidgetCopy.of(snapshot())
        assertEquals("Nothing needs you", text.headline)
        assertEquals("Nothing running", text.detail)
        assertEquals(WidgetCopy.STAMP_CHECKED, text.stampLabel)
        assertEquals(at, text.ageBase)
        assertEquals(WidgetLevel.QUIET, text.level)
    }

    /**
     * The Router tab's rule, on a homescreen: an app that cannot hear the host has not
     * learned that nothing is waiting, and must not say that it has.
     */
    @Test
    fun `a count of zero nobody could confirm is never drawn as nothing needs you`() {
        val text = WidgetCopy.of(snapshot(needsKnown = false, workingKnown = false))
        assertEquals("Needs you: unknown", text.headline)
        assertEquals("Running: unknown", text.detail)
        // It was confirmed once, so the instant is still worth printing: the card says
        // when it last knew rather than pretending it never did.
        assertEquals(at, text.ageBase)
        assertEquals(WidgetLevel.UNKNOWN, text.level)
    }

    @Test
    fun `a count nobody has ever established has no age to print`() {
        val text = WidgetCopy.of(
            WidgetSnapshot(
                needsYou = WidgetReading(known = false, value = 0, asOf = 0L),
                working = WidgetReading(known = false, value = 0, asOf = 0L),
                subagents = WidgetReading(known = false, value = 0, asOf = 0L),
            ),
        )
        assertEquals("Needs you: unknown", text.headline)
        assertEquals("Running: unknown", text.detail)
        assertNull(text.ageBase)
        assertEquals(WidgetCopy.STAMP_OPEN, text.stampLabel)
    }

    @Test
    fun `one waiting thing is named by what it is`() {
        assertEquals("Approval waiting", WidgetCopy.of(snapshot(needs = 1, kind = "APPROVAL")).headline)
        assertEquals("Question waiting", WidgetCopy.of(snapshot(needs = 1, kind = "QUESTION")).headline)
        assertEquals("Plan to review", WidgetCopy.of(snapshot(needs = 1, kind = "PLAN_REVIEW")).headline)
        // A waterfall that carried no session still counts; it just cannot be named.
        assertEquals("Someone needs you", WidgetCopy.of(snapshot(needs = 1, kind = null)).headline)
    }

    @Test
    fun `more than one waiting thing is a count, because the kinds can differ`() {
        val text = WidgetCopy.of(snapshot(needs = 3, kind = "APPROVAL"))
        assertEquals("3 need you", text.headline)
        assertEquals(WidgetLevel.ATTENTION, text.level)
    }

    @Test
    fun `anything waiting is the loud level and nothing waiting is not`() {
        assertEquals(WidgetLevel.ATTENTION, WidgetCopy.of(snapshot(needs = 1, kind = "APPROVAL")).level)
        assertEquals(WidgetLevel.QUIET, WidgetCopy.of(snapshot()).level)
    }

    @Test
    fun `subagents qualify the running count and never stand alone`() {
        assertEquals("4 running · 1 subagent", WidgetCopy.of(snapshot(working = 4, subagents = 1)).detail)
        assertEquals("4 running · 2 subagents", WidgetCopy.of(snapshot(working = 4, subagents = 2)).detail)
        assertEquals("4 running", WidgetCopy.of(snapshot(working = 4, subagents = 0)).detail)
        assertEquals("Nothing running", WidgetCopy.of(snapshot(working = 0, subagents = 0)).detail)
    }

    @Test
    fun `the stamp is the weakest reading on the card`() {
        val text = WidgetCopy.of(
            WidgetSnapshot(
                needsYou = WidgetReading(known = true, value = 1, asOf = at),
                working = WidgetReading(known = true, value = 0, asOf = at - 600_000),
                subagents = WidgetReading(known = true, value = 0, asOf = at),
            ),
        )
        assertEquals("the running reading is the older of the two, so it stamps", at - 600_000, text.ageBase)
        assertEquals("Approval waiting", text.headline)
    }
}
