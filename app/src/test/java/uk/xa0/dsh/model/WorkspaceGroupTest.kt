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
 *
 * The run-state rules have a second job here too: a colour is only allowed to mean something
 * a reader can see. `WorkspaceRunState` therefore has no "cannot tell" member — an undelivered
 * member is absent for the running question — and the fact that membership is incomplete is
 * pinned as *words* ([workspaceCountLabel]) rather than as a tint.
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
        assertEquals(
            "zero visible sessions is a fact here, and the plain figure says it",
            "0",
            workspaceCountLabel(0, emptyList(), emptySet()),
        )
    }

    @Test
    fun `a Workspace whose members have not arrived is loading, and says so in words`() {
        val state = workspaceGroupState(
            memberIds = listOf("a", "b"),
            visibleIds = emptyList(),
            knownIds = setOf("a"),
        )
        assertEquals(WorkspaceGroupState.Loading, state)
        assertEquals(WorkspaceCopy.SESSIONS_NOT_LOADED, state.placeholder)
        assertEquals(
            "the count states what is shown of what the registry accounts, not a blank and not 'unknown'",
            "0 of 2",
            workspaceCountLabel(0, listOf("a", "b"), setOf("a")),
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
        // Empty and Filtered print the plain figure, which is true of both — what is drawn
        // under the header is what tells them apart. Loading prints the accounting instead.
        assertEquals("0", workspaceCountLabel(0, emptyList(), emptySet()))
        assertEquals("0", workspaceCountLabel(0, listOf("a"), setOf("a")))
        assertEquals("0 of 1", workspaceCountLabel(0, listOf("a"), emptySet()))
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
            "every member is delivered, so the hidden rows are the filter's doing and the figure stays plain",
            "0",
            workspaceCountLabel(0, listOf("archived", "subagent"), setOf("archived", "subagent")),
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
        assertEquals(
            "the count is the visible rows, and it says so in the accounting because one member is missing",
            "1 of 2",
            workspaceCountLabel(1, listOf("a", "b"), setOf("a")),
        )
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
        assertEquals(
            "every accounted member is on screen, so there is no gap to report",
            "2",
            workspaceCountLabel(2, listOf("a", "b"), emptySet()),
        )
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
        // This is the word's only remaining caller; a Workspace's missing members now draw
        // a countable "shown of accounted" figure instead.
        assertEquals("unknown", WorkspaceCopy.UNKNOWN)
        assertNotEquals(WorkspaceCopy.UNKNOWN, WorkspaceGroupState.Empty.placeholder)
    }

    // ------------------------------------------------------- the count states the gap

    @Test
    fun `a Workspace with missing members states shown of accounted, in words`() {
        // The measured case on the user's phone: a Workspace whose registry row holds
        // thirteen members, two of which the roster serves. The old header dimmed the title
        // and showed "unknown"; the reader could see neither the number nor the reason.
        assertEquals(
            "2 of 13",
            workspaceCountLabel(2, (1..13).map { "m$it" }, setOf("m1", "m2")),
        )
    }

    @Test
    fun `a Workspace with a complete roster keeps the plain figure`() {
        val members = (1..13).map { "m$it" }
        assertEquals("13", workspaceCountLabel(13, members, members.toSet()))
        // All delivered but some hidden by the filter: still the plain figure, because the
        // missing rows are this drawer's own rule and its placeholder names them.
        assertEquals("3", workspaceCountLabel(3, members, members.toSet()))
    }

    @Test
    fun `the accounting appears only when the roster gap leaves something undrawn`() {
        // A member the roster never delivered makes the label an accounting; a member the
        // roster holds but the drawer filters out does not, because the reader can reach it.
        val members = listOf("shown", "missing", "archived")
        assertEquals("1 of 3", workspaceCountLabel(1, members, setOf("shown", "archived")))
        assertEquals("3", workspaceCountLabel(3, members, setOf("shown", "archived")))
    }

    // ------------------------------------------------------------------- live work in a group

    @Test
    fun `a group holding a member that is working is running`() {
        val state = workspaceRunState(
            memberIds = listOf("a", "b"),
            liveIds = setOf("a"),
        )
        assertEquals(WorkspaceRunState.Running, state)
    }

    @Test
    fun `a group whose members have all arrived and none is working is idle`() {
        val state = workspaceRunState(
            memberIds = listOf("a", "b"),
            liveIds = emptySet(),
        )
        assertEquals(WorkspaceRunState.Idle, state)
    }

    @Test
    fun `a member the roster never delivered is absent for the running question, not an unknown`() {
        // The rule that keeps the colour explicable. The registry accounts "b" and the roster
        // never serves it, so the client cannot observe whether b is working — and it must not
        // turn the reader's glance at the header into a question about a fact the reader cannot
        // see. "Nothing here is running" is a statement about the work that *is* here, and the
        // incomplete membership is stated in the count instead. There is deliberately no
        // `Unknown` run state left to reach.
        val state = workspaceRunState(
            memberIds = listOf("a", "b"),
            liveIds = emptySet(),
        )
        assertEquals(WorkspaceRunState.Idle, state)
        assertEquals(
            "the only run states are running and not-running",
            2,
            WorkspaceRunState.values().size,
        )
    }

    @Test
    fun `work already seen is running however many members have not arrived`() {
        // A running member is a positive fact and does not depend on the rest of the
        // membership: only the negative would, and the negative no longer consults it.
        val state = workspaceRunState(
            memberIds = listOf("a", "b"),
            liveIds = setOf("a"),
        )
        assertEquals(WorkspaceRunState.Running, state)
    }

    @Test
    fun `a workspace the registry accounts no member is idle rather than unknown`() {
        // Nothing is missing, because nothing was named: an empty directory is a fact the
        // registry supplied, and its header makes no running claim.
        assertEquals(
            WorkspaceRunState.Idle,
            workspaceRunState(memberIds = emptyList(), liveIds = emptySet()),
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
            workspaceRunState(listOf("parent", "other"), live),
        )
    }

    // ------------------------------------------- no rows drawn vs a running title

    @Test
    fun `no rows drawn and a running title are independent facts, not one condition`() {
        // The two derivations share an input — a member the roster never delivered can make
        // both the group state Loading and the count an accounting — but neither implies the
        // other, and neither is read off the other. A group can draw no rows and be running
        // (a running member hidden by the Archived switch); a group can draw rows and not be
        // running. The title is decided only by the second half.
        val runningHidden = workspaceRunState(listOf("archived"), setOf("archived"))
        assertEquals(WorkspaceRunState.Running, runningHidden)
        assertEquals(
            WorkspaceGroupState.Filtered,
            workspaceGroupState(listOf("archived"), emptyList(), setOf("archived")),
        )
        assertEquals(
            "a hidden running member still makes the group running, and its count stays plain",
            "0",
            workspaceCountLabel(0, listOf("archived"), setOf("archived")),
        )

        // Rows on screen, a member the roster never delivered, nothing running: the group is
        // not running, and the gap is in the count where it can be read.
        val partlyDelivered = workspaceGroupState(
            memberIds = listOf("here", "missing"),
            visibleIds = listOf("here"),
            knownIds = setOf("here"),
        )
        assertEquals(WorkspaceGroupState.Members, partlyDelivered)
        assertEquals(
            WorkspaceRunState.Idle,
            workspaceRunState(listOf("here", "missing"), emptySet()),
        )
        assertEquals(
            "1 of 2",
            workspaceCountLabel(1, listOf("here", "missing"), setOf("here")),
        )
    }

    // ------------------------------------------------- what the header speaks

    @Test
    fun `the header speaks running, and idle says nothing`() {
        // The blue is invisible to a screen reader, so the same fact is spoken. Idle has no
        // sentence on purpose: the drawer's rows draw no "not running" either, and a header that
        // announced it would be making a claim the reader cannot check.
        assertEquals("Running", WorkspaceCopy.RUNNING)
    }
}
