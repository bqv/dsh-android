package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sidebar's Workspace groups are allowed to say.
 *
 * The whole point of these rules is that groups which *look* alike on screen must not be
 * drawn alike when they mean different things: a directory the host says accounts for no
 * session, a directory whose sessions this client has not been handed, and a directory
 * whose sessions this drawer's own view rules have all hidden. The drawer's own `remember`
 * block cannot be reached from the JVM harness, so the rules live in `WorkspaceGroups.kt`
 * and are pinned here.
 */
class WorkspaceGroupTest {

    // ------------------------------------------------- empty vs loading vs filtered

    @Test
    fun `a Workspace the host accounts no sessions to is empty, and says so`() {
        val state = workspaceGroupState(
            memberIds = emptyList(),
            visibleIds = emptyList(),
            knownIds = emptySet(),
        )
        assertEquals(WorkspaceGroupState.Empty, state)
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
        assertEquals(WorkspaceCopy.SESSIONS_NOT_LOADED, state.placeholder)
        assertNull(
            "a group that does not know its membership must not print a count",
            state.headerCount(0),
        )
    }

    @Test
    fun `no two zero-row states say the same thing`() {
        val empty = workspaceGroupState(emptyList(), emptyList(), emptySet())
        val loading = workspaceGroupState(listOf("a"), emptyList(), emptySet())
        val filtered = workspaceGroupState(listOf("a"), emptyList(), setOf("a"))

        // The words, and the count. If any pair of these ever coincides the sidebar is
        // back to claiming one thing while meaning another — "this directory has nothing"
        // when it has merely not been told, or when its reader's own filter is on.
        val words = listOf(empty.placeholder, loading.placeholder, filtered.placeholder)
        assertTrue("every zero-row state has words, none is silent", words.all { it != null })
        assertEquals(
            "each zero-row state has a sentence of its own",
            3,
            words.toSet().size,
        )
        assertNotEquals(empty.headerCount(0), loading.headerCount(0))
        // Empty and Filtered both draw a zero, and that zero is true of both — what is
        // drawn under the header is what tells them apart.
        assertEquals(empty.headerCount(0), filtered.headerCount(0))
    }

    @Test
    fun `every zero-row state has words to draw`() {
        // A placeholder that silently went missing would leave an empty header looking
        // exactly like an empty directory.
        assertEquals("No sessions", WorkspaceGroupState.Empty.placeholder)
        assertEquals("Sessions not loaded", WorkspaceGroupState.Loading.placeholder)
        assertEquals("Sessions hidden by filters", WorkspaceGroupState.Filtered.placeholder)
    }

    // ------------------------------------------------------------------------ filtered

    @Test
    fun `a Workspace whose only sessions are archived is drawn, and says the filters hide them`() {
        // The reader's own report: a Workspace holds only archived sessions, the Archived
        // switch is off, and the drawer dropped the whole group — so the Workspace looked
        // like it did not exist. Archived sessions and subagents are *known* members (the
        // drawer hands this the unfiltered roster), so the group is Filtered; Filtered now
        // draws, because the registry always outranks the reader's persisted filter.
        val state = workspaceGroupState(
            memberIds = listOf("archived", "subagent"),
            visibleIds = emptyList(),
            knownIds = setOf("archived", "subagent", "unrelated"),
        )
        assertEquals(WorkspaceGroupState.Filtered, state)
        assertEquals(WorkspaceCopy.SESSIONS_HIDDEN, state.placeholder)
        assertEquals(
            "nothing is drawn under the header, so its figure is a fact",
            0,
            state.headerCount(0),
        )
    }

    @Test
    fun `an archived member is known, so its group is filtered rather than loading`() {
        // The drawer hands this the *unfiltered* roster: archived sessions and subagents
        // are delivered rows, not missing ones. Calling them missing would put a
        // permanent "Sessions not loaded" under every Workspace whose sessions are all
        // archived, when the truth is that the reader's own switch is hiding them.
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
