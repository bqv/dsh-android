package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.xa0.dsh.SessionItem

/**
 * Which sessions count as "the one the reader is looking at" for notifications.
 *
 * A parent and its subagents are modelled by the host as separate sessions, but they
 * are one thing the reader attends to: opening the parent is how you look at the work
 * the children did.
 */
class SubagentTreeTest {

    private fun session(id: String, parent: String? = null) = SessionItem(
        id = id,
        title = id,
        cwd = "/tmp",
        updatedAt = 0L,
        running = false,
        isSubagent = parent != null,
        parentSessionId = parent,
    )

    //  a
    //  ├── b
    //  │    └── d
    //  └── c
    private val roster = listOf(
        session("a"),
        session("b", parent = "a"),
        session("c", parent = "a"),
        session("d", parent = "b"),
        session("other"),
    )

    @Test
    fun `a session with no subagents is only itself`() {
        assertEquals(setOf("other"), subagentTreeIds(roster, "other"))
    }

    @Test
    fun `and its direct children`() {
        assertEquals(setOf("a", "b", "c", "d"), subagentTreeIds(roster, "a"))
    }

    @Test
    fun `including grandchildren`() {
        assertEquals(setOf("b", "d"), subagentTreeIds(roster, "b"))
    }

    @Test
    fun `an unknown session is only itself`() {
        assertEquals(setOf("ghost"), subagentTreeIds(roster, "ghost"))
    }

    @Test
    fun `a lineage that loops does not spin`() {
        // Not something the host should send, but a roster is a list of ids and this
        // runs on the main thread behind a notification.
        val looping = listOf(session("x", parent = "y"), session("y", parent = "x"))
        assertEquals(setOf("x", "y"), subagentTreeIds(looping, "x"))
    }
}
