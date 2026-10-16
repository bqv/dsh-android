package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.SessionItem

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

    // ------------------------------------------------------------------- live work in a group

    @Test
    fun `a group holding a member that is working is running`() {
        val state = workspaceRunState(
            memberIds = listOf("a", "b"),
            liveIds = setOf("a"),
            knownIds = setOf("a", "b"),
        )
        assertEquals(WorkspaceRunState.Running, state)
    }

    @Test
    fun `a group whose members have all arrived and none is working is idle`() {
        val state = workspaceRunState(
            memberIds = listOf("a", "b"),
            liveIds = emptySet(),
            knownIds = setOf("a", "b", "elsewhere"),
        )
        assertEquals(WorkspaceRunState.Idle, state)
    }

    @Test
    fun `a group holding a member the roster never delivered is unknown, not idle`() {
        // The one that matters: "nothing is running in here" is not a fact while a member is
        // missing, so the header must not be drawn as the confirmed-nothing it is not. The
        // drawer reads this state as the untinted title plus the spoken `RUNNING_UNKNOWN`.
        val state = workspaceRunState(
            memberIds = listOf("a", "b"),
            liveIds = emptySet(),
            knownIds = setOf("a"),
        )
        assertEquals(WorkspaceRunState.Unknown, state)
        assertNotEquals(
            "an undelivered member must not be drawn as a quieter idle",
            WorkspaceRunState.Idle,
            state,
        )
        assertNotEquals(WorkspaceRunState.Running, state)
    }

    @Test
    fun `work already seen outranks the members that have not arrived`() {
        // "Something is running" stays true however much of the membership is still in flight —
        // only the negative needs the whole list. A header that went quiet while a pull lagged
        // would hide the one live row it had just been told about.
        val state = workspaceRunState(
            memberIds = listOf("a", "b"),
            liveIds = setOf("a"),
            knownIds = setOf("a"),
        )
        assertEquals(WorkspaceRunState.Running, state)
    }

    @Test
    fun `a workspace the registry accounts no member is idle rather than unknown`() {
        // Nothing is missing, because nothing was named: an empty directory is a fact the
        // registry supplied, and its header is not "Running: unknown".
        assertEquals(
            WorkspaceRunState.Idle,
            workspaceRunState(memberIds = emptyList(), liveIds = emptySet(), knownIds = emptySet()),
        )
    }

    @Test
    fun `a running descendant makes its ancestor's group live`() {
        // A subagent is never a Workspace member (measured on the running host), so the group
        // holds the parent and the parent is not itself working. Without the rollup half this
        // group would read idle and a collapsed folder would hide a delegated run entirely.
        val sessions = listOf(
            SessionItem("parent", "Parent", "/w", 3L, running = false, isSubagent = false),
            SessionItem(
                "child",
                "Child",
                "/w",
                2L,
                running = true,
                isSubagent = true,
                parentSessionId = "parent",
            ),
            SessionItem("other", "Other", "/w", 1L, running = false, isSubagent = false),
        )
        val rollupRunningParents = setOf("parent")
        val live = liveWorkIds(sessions, rollupRunningParents)

        assertEquals(
            "the working child and the ancestor it is credited to are both live",
            setOf("child", "parent"),
            live,
        )
        assertEquals(
            WorkspaceRunState.Running,
            workspaceRunState(listOf("parent", "other"), live, sessions.map { it.id }.toSet()),
        )
    }

    // ------------------------------------------------- no rows drawn vs a dimmed title

    @Test
    fun `no rows drawn and a dimmed title are independent facts, not one condition`() {
        // The two are easy to conflate because one cause — a member the roster never
        // delivered — can produce both, but they are separate derivations. A group can
        // have no rows and still be a confirmed idle one, and a group can be drawing rows
        // and still be unable to claim that nothing is running.
        //
        // No rows, every member known: the reader's Archived switch hid the group's only
        // member. No rows are drawn, and yet "nothing is running in here" is a fact, so the
        // header title keeps its ordinary colour — it is not dimmed.
        val allHidden = workspaceGroupState(
            memberIds = listOf("archived"),
            visibleIds = emptyList(),
            knownIds = setOf("archived"),
        )
        assertEquals(WorkspaceGroupState.Filtered, allHidden)
        assertEquals(
            "no rows drawn, and still a confirmed idle group",
            WorkspaceRunState.Idle,
            workspaceRunState(listOf("archived"), emptySet(), setOf("archived")),
        )

        // A row on screen *and* a member the roster never delivered: rows are drawn, and the
        // header is still dimmed, because the missing member leaves "nothing is running"
        // unconfirmed. On screen this is a blue folder (the open session is a member) under a
        // dimmed title.
        val partlyDelivered = workspaceGroupState(
            memberIds = listOf("here", "missing"),
            visibleIds = listOf("here"),
            knownIds = setOf("here"),
        )
        assertEquals(WorkspaceGroupState.Members, partlyDelivered)
        assertEquals(
            "rows drawn, and still not a confirmed idle group",
            WorkspaceRunState.Unknown,
            workspaceRunState(listOf("here", "missing"), emptySet(), setOf("here")),
        )
    }

    @Test
    fun `the header speaks its run state, and idle says nothing`() {
        // The blue is invisible to a screen reader, so the same fact is spoken. Idle has no
        // sentence on purpose: the drawer's rows draw no "not running" either, and a header that
        // announced it would be making the claim the whole rule exists to refuse.
        assertEquals("Running", WorkspaceCopy.RUNNING)
        assertEquals("Running: unknown", WorkspaceCopy.RUNNING_UNKNOWN)
        assertNotEquals(WorkspaceCopy.RUNNING, WorkspaceCopy.RUNNING_UNKNOWN)
    }
}
