package uk.xa0.dsh.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import java.util.TimeZone

/**
 * What the footer prints, pinned with the locale and the zone supplied.
 *
 * Both have to be given: `SimpleDateFormat` reads the machine's own settings otherwise,
 * and an assertion whose answer moves with the machine is not an assertion. Pure JVM —
 * no Context, no launcher, no clock — because the whole of what this replaces (the
 * `Chronometer`'s base conversion, and the two clocks that had to be threaded through it)
 * is gone: there is no longer a timebase to get wrong.
 *
 * The date is the load-bearing part of the format and these tests are written so that
 * dropping it fails them. An age was unambiguous because it was a span; an instant is
 * unambiguous only inside its own day, and the card is drawn once and then read at a
 * moment it cannot know and cannot be woken for.
 */
class WidgetStampTest {

    private val uk = Locale.UK
    private val utc = TimeZone.getTimeZone("UTC")

    private fun stamp(instant: Long): String = WidgetCopy.stampTime(instant, uk, utc)

    /** 2023-11-14T22:13:20Z. */
    private val at = 1_700_000_000_000L

    @Test
    fun `an instant is written as a day, a month and a time`() {
        assertEquals("14 Nov 22:13", stamp(at))
    }

    @Test
    fun `an hour apart differs only in the time`() {
        assertEquals("14 Nov 23:13", stamp(at + 3_600_000L))
    }

    /**
     * The failure the date exists to prevent. A bare `22:13` drawn yesterday reads as
     * seven minutes old to somebody glancing at the card at twenty past ten tonight, and
     * nothing on the card would ever correct it.
     */
    @Test
    fun `a day apart differs in the date, so a stale reading cannot read as today's`() {
        assertEquals("13 Nov 22:13", stamp(at - 86_400_000L))
        assertNotEquals(
            "the same clock time on two different days must not print the same stamp",
            stamp(at - 86_400_000L),
            stamp(at),
        )
    }

    /**
     * A stamp is a wall-clock reading for the reader, not a UTC instant: somebody in Tokyo
     * looking at a card stamped `14 Nov 22:13` would be reading a time that never happened
     * on their phone.
     */
    @Test
    fun `the reader's own zone is the zone the stamp is written in`() {
        assertEquals("15 Nov 07:13", WidgetCopy.stampTime(at, uk, TimeZone.getTimeZone("Asia/Tokyo")))
    }

    /**
     * Locale decides the month's name and the digits; it may not decide the order, because
     * the order is what says which number is the day. Asserted as a shape rather than as a
     * French literal: the month's own spelling is the reader's, and pinning an exact
     * abbreviation would make this test about ICU's data instead of about the format.
     */
    @Test
    fun `the order is the app's even where the locale would write it differently`() {
        assertEquals("14 Nov 22:13", WidgetCopy.stampTime(at, Locale.US, utc))

        val french = WidgetCopy.stampTime(at, Locale.FRANCE, utc)
        assertTrue("the day must lead in every locale, not the month", french.startsWith("14"))
        assertTrue("the time must close it in every locale", french.endsWith("22:13"))
        assertNotEquals("the month is the reader's own word, so it does move", stamp(at), french)
    }
}
