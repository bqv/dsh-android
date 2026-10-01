package uk.xa0.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ids a session's alerts are posted under, and withdrawn by.
 *
 * Opening a session has to be able to silence *its* notifications and nothing else,
 * so the property that matters is scoping: every id carries the session, and two
 * sessions never share one. A collision would show up as an alert for one session
 * vanishing when an unrelated one was opened — which is exactly the kind of bug that
 * looks like "the app forgot to tell me" rather than like a bug.
 */
class SessionNotificationIdsTest {

    @Test
    fun `one id per alert kind, per session`() {
        val ids = Attention.sessionNotificationIds("session-abc")
        assertEquals(4, ids.size)
        assertEquals("the four kinds are distinct", 4, ids.toSet().size)
    }

    @Test
    fun `two sessions never share an id`() {
        val a = Attention.sessionNotificationIds("session-a").toSet()
        val b = Attention.sessionNotificationIds("session-b").toSet()
        assertEquals("no overlap at all", emptySet<Int>(), a intersect b)
    }

    @Test
    fun `the same session always asks for the same ids`() {
        assertEquals(
            Attention.sessionNotificationIds("session-x"),
            Attention.sessionNotificationIds("session-x"),
        )
    }

    /** The cancelled set must include the kinds the app actually posts per session. */
    @Test
    fun `the list covers the per-session kinds the app posts`() {
        val session = "session-abc"
        val ids = Attention.sessionNotificationIds(session)
        assertTrue("finished-session dot", Attention.idleId(session) in ids)
        assertTrue("approval", Attention.approvalId(session) in ids)
        assertTrue("question", Attention.questionId(session) in ids)
        assertTrue("terminal bell", Attention.bellId(session) in ids)
    }
}
