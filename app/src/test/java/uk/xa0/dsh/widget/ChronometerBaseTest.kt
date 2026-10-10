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
    fun `an instant in the future is clamped, so the launcher cannot count backwards`() {
        val uptime = 1_000L
        val base = chronometerBase(
            wallInstant = 1_700_000_060_000L,
            now = 1_700_000_000_000L,
            uptime = uptime,
        )
        // The base itself is what must be bounded: the launcher computes `uptime - base`, so a
        // base above the uptime is a negative age however small the overshoot.
        assertEquals("a future instant is clamped to the uptime, giving an age of zero", uptime, base)
        assertEquals("and the launcher's own arithmetic cannot go negative", 0L, uptime - base)
    }

    @Test
    fun `a reading older than the boot clamps to zero rather than an impossible base`() {
        // The snapshot predates a reboot: two minutes of wall age against ninety seconds of
        // uptime. The unclamped base would be negative, and the launcher would draw an age
        // larger than the uptime it is measuring against.
        val uptime = 90_000L
        val now = 1_700_000_000_000L
        val base = chronometerBase(wallInstant = now - 120_000L, now = now, uptime = uptime)
        assertEquals("clamped to zero", 0L, base)
    }
}
