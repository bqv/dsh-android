package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The automation split, and the property that matters most about it: **with no runs,
 * nothing changes**.
 *
 * The drawer lifts these sessions into a section of their own, and the requirement is
 * that a host with no automations renders exactly as it did before the feature existed
 * — no empty header, no disabled row, no hint that the app knows the word. That is a
 * property of the split (an empty set partitions to identity) plus the section being
 * built only when there is something to put in it, and the first half is testable here.
 */
class AutomationsTest {

    private fun id(name: String) = name

    @Test
    fun `no automation sessions means no change at all`() {
        val roster = listOf("session-a", "session-b", "session-c")
        val (runs, rest) = partitionAutomations(roster, ::id)
        assertEquals("nothing is lifted out", emptyList<String>(), runs)
        assertEquals("and the order is untouched", roster, rest)
    }

    @Test
    fun `runs are lifted out, and both sides keep their order`() {
        val roster = listOf(
            "session-a",
            "$AUTOMATION_SESSION_PREFIX-1",
            "session-b",
            "$AUTOMATION_SESSION_PREFIX-2",
        )
        val (runs, rest) = partitionAutomations(roster, ::id)
        assertEquals(listOf("$AUTOMATION_SESSION_PREFIX-1", "$AUTOMATION_SESSION_PREFIX-2"), runs)
        assertEquals(listOf("session-a", "session-b"), rest)
    }

    /**
     * A run's own subagent is not a run. Its id is a plain `session-…`, so the prefix
     * test already says so — this pins it, because pulling a run's subagent into the
     * section on its own would list the work twice, once without its parent.
     */
    @Test
    fun `a subagent of a run is not itself a run`() {
        assertFalse(isAutomationSessionId("session-1234-5678"))
        assertFalse(isAutomationSessionId("session-deadbeef"))
    }

    @Test
    fun `only the prefix counts, and only at the start`() {
        assertTrue(isAutomationSessionId("$AUTOMATION_SESSION_PREFIX-7eaa1963"))
        // A name that merely contains it is not a run: this is a prefix test, not a
        // search, and the host's ids are the thing being recognised.
        assertFalse(isAutomationSessionId("session-x-$AUTOMATION_SESSION_PREFIX-1"))
        assertFalse(isAutomationSessionId(""))
        assertFalse(isAutomationSessionId("dsh-automation"))
    }
}
