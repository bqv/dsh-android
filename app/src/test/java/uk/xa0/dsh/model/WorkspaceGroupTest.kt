package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sidebar's Workspace groups are allowed to say.
 *
 * The whole point of these rules is that two groups which *look* alike on screen must not
 * be drawn alike when they mean different things: a directory the host says accounts for
 * no session, and a directory whose sessions this client has simply not been handed. The
 * drawer's own `remember` block cannot be reached from the JVM harness, so the rules live
 * in `WorkspaceGroups.kt` and are pinned here.
 */
class WorkspaceGroupTest {

    // ---------------------------------------------------------------- empty vs loading

    @Test
    fun `a Workspace the host accounts no sessions to is empty, and says so`() {
        val state = workspaceGroupState(
            memberIds = emptyList(),
            visibleIds = emptyList(),
            knownIds = emptySet(),
        )
        assertEquals(WorkspaceGroupState.Empty, state)
        assertTrue("an empty Workspace is a group the sidebar draws", state.showsHeader)
        assertEquals(WorkspaceCopy.NO_SESSIONS, state.placeholder)
        assertEquals("zero visible sessions is a fact here", 0, state.headerCount(0))
    }

    @Test
    fun `a Workspace whose members have not arrived is loading, and says so`() {
        val state = workspaceGroupState(
            memberIds = listOf("a", "b"),
            visibleIds = emptyList(),
            knownIds = setOf("a"),
        )
        assertEquals(WorkspaceGroupState.Loading, state)
        assertTrue("the group is still drawn — its absence is the point", state.showsHeader)
        assertEquals(WorkspaceCopy.SESSIONS_NOT_LOADED, state.placeholder)
        assertNull(
            "a group that does not know its membership must not print a count",
            state.headerCount(0),
        )
    }

    @Test
    fun `empty and loading are drawn differently in both places the group has`() {
        val empty = workspaceGroupState(emptyList(), emptyList(), emptySet())
        val loading = workspaceGroupState(listOf("a"), emptyList(), emptySet())

        // The words, and the count. If either pair ever coincides, the sidebar is back
        // to claiming "this directory has nothing" when it has merely not been told.
        assertNotEquals(empty.placeholder, loading.placeholder)
        assertNotEquals(empty.headerCount(0), loading.headerCount(0))
    }

    @Test
    fun `both zero-row states have words to draw`() {
        // A placeholder that silently went missing would leave an empty header looking
        // exactly like an empty directory.
        assertEquals("No sessions", WorkspaceGroupState.Empty.placeholder)
        assertEquals("Sessions not loaded", WorkspaceGroupState.Loading.placeholder)
    }

    // ------------------------------------------------------------------------ filtered

    @Test
    fun `members the filters hid are not a group at all`() {
        // Every member is known — archived, a subagent, or simply not matching the
        // query. The reader did that and can undo it, so an empty header would be the
        // drawer reporting their own filter back as a fact about the directory.
        val state = workspaceGroupState(
            memberIds = listOf("a"),
            visibleIds = emptyList(),
            knownIds = setOf("a"),
        )
        assertEquals(WorkspaceGroupState.Filtered, state)
        assertFalse(state.showsHeader)
        assertNull(state.placeholder)
    }

    @Test
    fun `an archived member is known, so its group is filtered rather than loading`() {
        // The drawer hands this the *unfiltered* roster: archived sessions and subagents
        // are delivered rows, not missing ones. Calling them missing would put a
        // permanent "Sessions not loaded" under every Workspace whose sessions are all
        // archived.
        val state = workspaceGroupState(
            memberIds = listOf("archived", "subagent"),
            visibleIds = emptyList(),
            knownIds = setOf("archived", "subagent", "unrelated"),
        )
        assertEquals(WorkspaceGroupState.Filtered, state)
    }

    // ------------------------------------------------------------------------- members

    @Test
    fun `one visible member makes the group ordinary, however many are still missing`() {
        val state = workspaceGroupState(
            memberIds = listOf("a", "b"),
            visibleIds = listOf("a"),
            knownIds = setOf("a"),
        )
        assertEquals(WorkspaceGroupState.Members, state)
        assertNull("a group with rows draws no placeholder", state.placeholder)
        assertEquals("the count is the visible rows the web counts too", 1, state.headerCount(1))
    }

    @Test
    fun `a group with rows is never called empty even when its membership is unknown`() {
        // The registry named two members and the roster delivered neither, yet a row is
        // on screen — from a membership frame, say. What is drawn wins over what is.
        val state = workspaceGroupState(
            memberIds = listOf("a", "b"),
            visibleIds = listOf("a", "b"),
            knownIds = emptySet(),
        )
        assertEquals(WorkspaceGroupState.Members, state)
        assertEquals(2, state.headerCount(2))
    }

    // --------------------------------------------------------------- the registry itself

    @Test
    fun `the catch-all refuses the host's label until the registry has answered`() {
        // "Ungrouped" is a claim about membership; before the baseline lands, every
        // session on screen might belong to a Workspace this client has not been told
        // about.
        assertEquals("Sessions", ungroupedTitle(WorkspaceListPhase.Pending))
        assertEquals("Ungrouped", ungroupedTitle(WorkspaceListPhase.Ready))
    }

    @Test
    fun `the registry's own figure is unknown, not a count`() {
        // The placeholder row has no count to print: it stands where the group headers
        // will be, and "0 Workspaces" would be the one thing the pending phase denies.
        assertEquals("unknown", WorkspaceCopy.UNKNOWN)
        assertNotEquals(WorkspaceCopy.UNKNOWN, WorkspaceGroupState.Empty.placeholder)
    }
}
