package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.xa0.dsh.SessionItem

/**
 * Where a fresh open lands.
 *
 * The roster is in `updatedAt` descending order, and an automation run is updated
 * by its own schedule, so "the first row with content" is a run on any host that
 * has one firing — the reported bug. These pin the replacement rule, and the two
 * properties that keep it narrow: a host with no runs behaves exactly as before,
 * and a run is not removed from the roster, only passed over while a conversation
 * exists.
 */
class LandingSessionTest {

    private fun session(
        id: String,
        updatedAt: Long = 0L,
        blank: Boolean = false,
        subagent: Boolean = false,
    ) = SessionItem(
        id = id,
        title = id,
        cwd = "/tmp",
        updatedAt = updatedAt,
        running = false,
        isSubagent = subagent,
        blank = blank,
        parentSessionId = if (subagent) "session-parent" else null,
    )

    private fun automationRun(id: String, updatedAt: Long = 0L) =
        session("$AUTOMATION_SESSION_PREFIX-$id", updatedAt)

    @Test
    fun `an empty roster lands nowhere`() {
        assertNull(landingSessionId(emptyList()))
    }

    /**
     * The property the rest of the app leans on: with no runs to consider, the
     * landing is exactly the first row with content, as it always was.
     */
    @Test
    fun `with no automations the first non-blank row lands`() {
        val roster = listOf(
            session("session-newest", 9),
            session("session-older", 5),
        )
        assertEquals("session-newest", landingSessionId(roster))
    }

    @Test
    fun `a blank provisional row does not beat a real conversation`() {
        val roster = listOf(
            session("session-blank", 9, blank = true),
            session("session-real", 5),
        )
        assertEquals("session-real", landingSessionId(roster))
    }

    /**
     * The reported defect. The run is the newest row on the host, which is what
     * the schedule guarantees; the landing must be the newest *conversation*.
     */
    @Test
    fun `a scheduled run newer than every conversation is not the landing`() {
        val roster = listOf(
            automationRun("1759600000-abc", 100),
            session("session-worked-in", 40),
            session("session-old", 10),
        )
        assertEquals("session-worked-in", landingSessionId(roster))
    }

    @Test
    fun `a run does not mask a conversation behind a blank row either`() {
        val roster = listOf(
            automationRun("1759600000-abc", 100),
            session("session-blank", 90, blank = true),
            session("session-worked-in", 40),
        )
        assertEquals("session-worked-in", landingSessionId(roster))
    }

    @Test
    fun `a subagent is never the landing, however new it is`() {
        val roster = listOf(
            session("session-child", 100, subagent = true),
            session("session-worked-in", 40),
        )
        assertEquals("session-worked-in", landingSessionId(roster))
    }

    @Test
    fun `a run's subagent is not a landing either`() {
        val roster = listOf(
            session("session-child", 100, subagent = true),
            automationRun("1759600000-abc", 90),
            session("session-worked-in", 40),
        )
        assertEquals("session-worked-in", landingSessionId(roster))
    }

    /**
     * Nothing is hidden by the rule: on a host holding only runs, a run is still
     * the landing, and a blank provisional session still loses to it. Passing over
     * a run is a preference, not a removal from the roster.
     */
    @Test
    fun `a run still lands when the host holds nothing else with content`() {
        val roster = listOf(
            automationRun("1759600000-abc", 100),
            session("session-blank", 90, blank = true),
        )
        assertEquals("$AUTOMATION_SESSION_PREFIX-1759600000-abc", landingSessionId(roster))
    }

    @Test
    fun `with only blank provisional rows the newest still lands`() {
        val roster = listOf(
            session("session-blank-new", 9, blank = true),
            session("session-blank-old", 5, blank = true),
        )
        assertEquals("session-blank-new", landingSessionId(roster))
    }

    /**
     * The fold takes the caller's order and does not re-sort, so it cannot disagree
     * with the roster order the drawer is already reading. Pinned here because a
     * later "improvement" to sort by `updatedAt` inside the fold would be a silent
     * second ordering rule.
     */
    @Test
    fun `the caller's roster order decides, not updatedAt`() {
        val roster = listOf(
            session("session-first", 1),
            session("session-second", 999),
        )
        assertEquals("session-first", landingSessionId(roster))
    }
}
