package uk.xa0.dsh.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.xa0.dsh.SessionItem

/**
 * The way up from a subagent, against the roster shapes that actually occur.
 *
 * The roster is flat and each subagent names its own parent, so this is a lookup —
 * but every way it can fail is silent on screen (no affordance drawn), which is
 * exactly the kind of thing that is worth pinning down here rather than finding by
 * tapping a phone.
 */
class SubagentParentTest {

    private fun session(
        id: String,
        isSubagent: Boolean = false,
        parent: String? = null,
        title: String = "",
    ) = SessionItem(
        id = id,
        title = title,
        cwd = "/home/user/var",
        updatedAt = 0L,
        running = false,
        isSubagent = isSubagent,
        parentSessionId = parent,
    )

    @Test
    fun `a subagent finds the session that spawned it`() {
        val roster = listOf(
            session("parent", title = "Native Android app"),
            session("child", isSubagent = true, parent = "parent", title = "You are researching…"),
        )
        val parent = subagentParentOf(roster, "child")
        assertEquals("parent", parent?.sessionId)
        assertEquals("Native Android app", parent?.title)
    }

    /** A chain, which is what makes the affordance repeatable up the stack. */
    @Test
    fun `a nested subagent finds its own parent, not the root`() {
        val roster = listOf(
            session("root", title = "Root"),
            session("mid", isSubagent = true, parent = "root", title = "Mid"),
            session("leaf", isSubagent = true, parent = "mid", title = "Leaf"),
        )
        assertEquals("mid", subagentParentOf(roster, "leaf")?.sessionId)
        assertEquals("root", subagentParentOf(roster, "mid")?.sessionId)
        assertNull(subagentParentOf(roster, "root"))
    }

    @Test
    fun `a top-level session has nothing above it`() {
        val roster = listOf(session("top", title = "Top"))
        assertNull(subagentParentOf(roster, "top"))
    }

    /**
     * A parent that is not in the roster draws no affordance: the roster is where its
     * title comes from, so the tap would open a session the client cannot even name.
     * The drawer lists archived sessions, so that is the honest route.
     */
    @Test
    fun `a parent missing from the roster is not offered`() {
        val roster = listOf(session("child", isSubagent = true, parent = "gone", title = "Child"))
        assertNull(subagentParentOf(roster, "child"))
    }

    @Test
    fun `a subagent with no parent id, or one pointing at itself, is not offered`() {
        val orphan = listOf(session("a", isSubagent = true, title = "A"))
        assertNull(subagentParentOf(orphan, "a"))
        val selfParent = listOf(session("b", isSubagent = true, parent = "b", title = "B"))
        assertNull(subagentParentOf(selfParent, "b"))
    }

    /** An untitled parent still goes somewhere, so the id stands in for its name. */
    @Test
    fun `an untitled parent is named by its id`() {
        val roster = listOf(
            session("parent-1", title = ""),
            session("child", isSubagent = true, parent = "parent-1", title = "Child"),
        )
        assertEquals("parent-1", subagentParentOf(roster, "child")?.title)
    }

    @Test
    fun `no session open means no parent to offer`() {
        val roster = listOf(session("child", isSubagent = true, parent = "p", title = "Child"))
        assertNull(subagentParentOf(roster, null))
        assertNull(subagentParentOf(roster, ""))
        assertNull(subagentParentOf(roster, "not-in-the-roster"))
    }
}
