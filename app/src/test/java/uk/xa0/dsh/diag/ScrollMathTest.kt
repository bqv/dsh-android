package uk.xa0.dsh.diag

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorder's judgements, which are the only part of the diagnostics that can
 * be wrong in a way nobody would notice: too loose and every fling reads as a
 * jump, too tight and the one move worth finding is the one that is dropped.
 */
class ScrollMathTest {

    @Test
    fun `a whole new set of rows is not shared`() {
        assertEquals(0f, sharedRatio(listOf("a", "b", "c"), listOf("x", "y", "z")), 0.001f)
    }

    @Test
    fun `half the rows surviving is half shared`() {
        assertEquals(0.5f, sharedRatio(listOf("a", "b"), listOf("b", "c")), 0.001f)
    }

    @Test
    fun `an empty side is treated as no evidence rather than as a swap`() {
        assertEquals(1f, sharedRatio(emptyList(), listOf("a")), 0.001f)
        assertEquals(1f, sharedRatio(listOf("a"), emptyList()), 0.001f)
    }

    @Test
    fun `two rows in one step is a jump`() {
        assertTrue(jumped(di = -2, kept = 1f, threshold = 2, pxJump = false))
    }

    @Test
    fun `one row in one step is ordinary`() {
        assertFalse(jumped(di = 1, kept = 1f, threshold = 2, pxJump = false))
    }

    @Test
    fun `a single row is still a jump when almost nothing visible survived`() {
        assertTrue(jumped(di = 1, kept = 0.2f, threshold = 2, pxJump = false))
    }

    @Test
    fun `a pixel surface only ever judges by distance`() {
        assertFalse(jumped(di = 0, kept = 1f, threshold = Int.MAX_VALUE, pxJump = false))
        assertTrue(jumped(di = 0, kept = 1f, threshold = Int.MAX_VALUE, pxJump = true))
    }

    @Test
    fun `a mark explains a sample that follows it inside the window`() {
        assertTrue(fresh(markAt = 1_000, now = 1_200, ttl = 500))
        assertTrue(fresh(markAt = 1_000, now = 1_500, ttl = 500))
    }

    @Test
    fun `a mark stops explaining once the window has passed`() {
        assertFalse(fresh(markAt = 1_000, now = 1_501, ttl = 500))
    }

    @Test
    fun `a sample that predates the mark is not explained by it`() {
        // Clocks are monotone here, but a sample read from a stale snapshot is
        // not, and attributing it would hide exactly the move being hunted.
        assertFalse(fresh(markAt = 2_000, now = 1_000, ttl = 500))
    }

    @Test
    fun `a ratio is reported as whole percent`() {
        assertEquals(100, ratioPercent(1f))
        assertEquals(33, ratioPercent(0.333f))
        assertEquals(0, ratioPercent(0f))
    }

    @Test
    fun `a position is reported as a fraction of its extent`() {
        // The fraction is what identifies a gesture landing in a strip at an
        // edge, so it has to survive the arithmetic.
        assertEquals(0.25f, fraction(25f, 100), 0.001f)
        assertEquals(0.93f, fraction(93f, 100), 0.001f)
        assertEquals(1f, fraction(100f, 100), 0.001f)
    }

    @Test
    fun `an unmeasured extent reads as the start rather than as a crash`() {
        assertEquals(0f, fraction(50f, 0), 0.001f)
    }
}
