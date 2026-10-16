package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.xa0.dsh.SessionItem

/**
 * Where a fresh open lands.
 *
 * The roster is in `updatedAt` descending order, and a session an active schedule
 * delivers into is updated by that schedule, so "the first row with content" is such a
 * session on any host that has one firing — the reported bug. These pin the replacement
 * rule, and the properties that keep it narrow: a host with no scheduled tasks behaves
 * exactly as before, the signal is the host's task catalog and **not** the shape of the
 * id, and a session with a task is not removed from the roster, only passed over while a
 * conversation exists.
 */
class LandingSessionTest {

    private fun session(
        id: String,
        updatedAt: Long = 0L,
        blank: Boolean = false,
        subagent: Boolean = false,
        tasks: Int = 0,
    ) = SessionItem(
        id = id,
        title = id,
        cwd = "/tmp",
        updatedAt = updatedAt,
        running = false,
        isSubagent = subagent,
        blank = blank,
        scheduledTaskCount = tasks,
        parentSessionId = if (subagent) "session-parent" else null,
    )

    /**
     * A session a schedule delivers into. The id is deliberately an ordinary one: the
     * host's Schedule subsystem reuses the session the task was created in, so there is
     * no id prefix left to recognise and the mark comes from the task catalog alone.
     */
    private fun scheduledSession(id: String, updatedAt: Long = 0L) =
        session(id, updatedAt, tasks = 1)

    @Test
    fun `an empty roster lands nowhere`() {
        assertNull(landingSessionId(emptyList()))
    }

    /**
     * The property the rest of the app leans on: with no scheduled tasks to consider, the
     * landing is exactly the first row with content, as it always was.
     */
    @Test
    fun `with no scheduled tasks the first non-blank row lands`() {
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
     * The reported defect. The scheduled session is the newest row on the host, which is
     * what the schedule guarantees; the landing must be the newest *conversation*.
     */
    @Test
    fun `a scheduled session newer than every conversation is not the landing`() {
        val roster = listOf(
            scheduledSession("session-1759600000-abc", 100),
            session("session-worked-in", 40),
            session("session-old", 10),
        )
        assertEquals("session-worked-in", landingSessionId(roster))
    }

    @Test
    fun `a scheduled session does not mask a conversation behind a blank row either`() {
        val roster = listOf(
            scheduledSession("session-1759600000-abc", 100),
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
    fun `a subagent of a scheduled session is not a landing either`() {
        val roster = listOf(
            session("session-child", 100, subagent = true),
            scheduledSession("session-1759600000-abc", 90),
            session("session-worked-in", 40),
        )
        assertEquals("session-worked-in", landingSessionId(roster))
    }

    /**
     * Nothing is hidden by the rule: on a host holding only scheduled sessions, one is
     * still the landing, and a blank provisional session still loses to it. Passing over
     * a scheduled session is a preference, not a removal from the roster.
     */
    @Test
    fun `a scheduled session still lands when the host holds nothing else with content`() {
        val roster = listOf(
            scheduledSession("session-1759600000-abc", 100),
            session("session-blank", 90, blank = true),
        )
        assertEquals("session-1759600000-abc", landingSessionId(roster))
    }

    /**
     * The prefix the old rule keyed on is dead, and this is the test that says so: a
     * session whose id still carries the retired `dsh-automation-session-` shape, but
     * which the host's task catalog does not bind a task to, is an ordinary conversation
     * and lands like any other. Under the old predicate this row would have been passed
     * over for no reason at all.
     */
    @Test
    fun `the retired id prefix no longer marks a session`() {
        val roster = listOf(
            session("dsh-automation-session-7eaa1963", 100),
            session("session-worked-in", 40),
        )
        assertEquals("dsh-automation-session-7eaa1963", landingSessionId(roster))
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
