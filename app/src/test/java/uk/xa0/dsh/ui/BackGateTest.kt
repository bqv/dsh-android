package uk.xa0.dsh.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The exit gate: a single Back must not leave the app.
 *
 * An accidental Back closing the app is the kind of bug that is only ever noticed
 * by losing your place, so the arithmetic is pinned here rather than found again on
 * a phone.
 */
class BackGateTest {

    @Test
    fun `the first press only arms`() {
        assertFalse(backGateLeaves(armedAtMs = null, nowMs = 1_000))
    }

    @Test
    fun `a second press inside the window leaves`() {
        assertTrue(backGateLeaves(armedAtMs = 1_000, nowMs = 1_100))
        assertTrue(backGateLeaves(armedAtMs = 1_000, nowMs = 3_000))
    }

    /** Deliberate: a press a minute later is a fresh first press, not half of one. */
    @Test
    fun `a press after the window is a fresh first press`() {
        assertFalse(backGateLeaves(armedAtMs = 1_000, nowMs = 3_001))
        assertFalse(backGateLeaves(armedAtMs = 1_000, nowMs = 60_000))
    }

    /** A clock that jumps backwards must not turn into a free exit. */
    @Test
    fun `a press stamped before the arming does not leave`() {
        assertFalse(backGateLeaves(armedAtMs = 5_000, nowMs = 1_000))
    }
}
