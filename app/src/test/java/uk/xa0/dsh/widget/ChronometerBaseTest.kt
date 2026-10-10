package uk.xa0.dsh.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The conversion the Chronometer needs, pinned with both clocks supplied.
 *
 * This exists because the render test could not catch the bug it was written to guard: it
 * asserted the raw wall-clock instant as the base, which is self-consistent and wrong. A
 * Chronometer counts from `SystemClock.elapsedRealtime()`, so a card given the wall clock
 * drew a negative age of about forty-six years.
 */
class ChronometerBaseTest {

    @Test
    fun `a wall instant becomes the uptime instant the Chronometer counts from`() {
        // Five days of uptime; the reading is sixty seconds old by the wall clock.
        val uptime = 5L * 24 * 60 * 60 * 1000
        val now = 1_700_000_000_000L
        val reading = now - 60_000L

        // The launcher computes `elapsedRealtime() - base`, and that must be the age.
        val base = chronometerBase(reading, now = now, uptime = uptime)
        assertEquals("the age the launcher derives must be the age of the reading", 60_000L, uptime - base)
    }

    @Test
    fun `an instant in the future is clamped rather than counting backwards`() {
        val base = chronometerBase(
            wallInstant = 1_700_000_060_000L,
            now = 1_700_000_000_000L,
            uptime = 1_000L,
        )
        assertEquals("a future instant draws 0:00, never a negative age", 0L, base)
    }

    @Test
    fun `an instant older than the uptime clamps too`() {
        // The reading predates boot, which is what the real card hit: a wall instant from
        // 2023 against minutes of uptime.
        val base = chronometerBase(
            wallInstant = 1_700_000_000_000L,
            now = 1_700_000_000_000L,
            uptime = 90_000L,
        )
        assertEquals(0L, base)
    }
}
