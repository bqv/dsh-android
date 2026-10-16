package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The omitted-workspace-member log line: what it says, and how often.
 *
 * This is the diagnostic side of the roster/registry divergence the drawer renders as
 * "[shown] of [accounted]" (`WorkspaceGroupTest`). The line has to exist — a member the pull
 * never returns leaves a Workspace looking short, and which id vanished is the whole diagnosis
 * — but the first version listed every id, roughly 4 KB on the user's phone, on every roster
 * pull. `MissingMembersLatch` is the same one-field, no-timer shape as `ScheduleSignalLatch`,
 * and `omittedMembersWarning` bounds the line. Both are pure, so the JVM harness can pin them
 * even though the view model's call sites cannot be reached from here.
 */
class MissingMembersTest {

    @Test
    fun `the omitted-member warning counts and samples, and never lists every id`() {
        val missing = (1..70).map { "dsh-automation-session-$it" }
        val line = omittedMembersWarning(missing)
        assertTrue("the count is the fact", line.contains("70 workspace member(s)"))
        assertTrue("a few ids are still there to match by hand", line.contains("... and 67 more"))
        assertTrue(
            "the line must not carry every id, which was the whole of the flooding",
            line.length < 300,
        )
    }

    @Test
    fun `a short omission lists its ids without a remainder`() {
        val line = omittedMembersWarning(listOf("b", "a"))
        assertEquals(
            "2 workspace member(s) the registry accounts are missing from session/list: [a, b]",
            line,
        )
    }

    @Test
    fun `the missing-member latch reports a set once and is quiet until it changes`() {
        val latch = MissingMembersLatch()
        val seventy = (1..70).map { "id-$it" }.toSet()

        assertEquals("the first omission of an episode is written", seventy, latch.accept(seventy))
        assertNull("the same omission on the next pull is quiet", latch.accept(seventy))
        assertNull("and the one after that", latch.accept(seventy))

        val smaller = seventy - "id-1"
        assertEquals("a changed omission is a new episode", smaller, latch.accept(smaller))

        assertNull(
            "the latch compares sets, so the registry's ordering cannot make it speak",
            latch.accept(smaller.toList().reversed().toSet()),
        )

        assertNull("a whole pull clears it", latch.accept(emptySet()))
        assertEquals(
            "so the same omission recurring after a healthy pull is written again",
            seventy,
            latch.accept(seventy),
        )
    }

    @Test
    fun `a fresh latch has reported nothing`() {
        // The state at startup: the first omission of the process must be written, not
        // swallowed by a latch that thinks it already spoke.
        assertNull(MissingMembersLatch().accept(emptySet()))
        assertEquals(setOf("x"), MissingMembersLatch().accept(setOf("x")))
    }
}
