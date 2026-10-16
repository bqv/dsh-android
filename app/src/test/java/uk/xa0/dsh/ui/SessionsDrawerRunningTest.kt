package uk.xa0.dsh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.WorkspaceItem
import uk.xa0.dsh.model.WorkspaceCopy
import uk.xa0.dsh.model.WorkspaceListPhase
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The Workspace header's live-work signal, from the roster to the drawn header.
 *
 * `WorkspaceGroupTest` pins the rule; this pins the *wiring*, which is the half a pure test
 * cannot see: that the drawer reads `SessionItem.running` (and a running descendant's rollup)
 * for the group it holds, that a folded group still says so, and that a group whose members
 * never arrived is not drawn as one that has nothing running.
 *
 * The blue itself cannot be measured here — the JVM harness has no text metrics and no pixels
 * — so the assertion is the same fact spoken: the header's `stateDescription`, which is what a
 * screen reader announces in place of the colour. A header that stops speaking its run state
 * therefore fails even though a colour change alone would pass unnoticed. The other half of the
 * rule is words rather than colour, so it *is* measurable here: a Workspace whose registry row
 * names a member the roster never delivered draws "shown of accounted" in its count column, and
 * does not dim its title or speak an "unknown" state at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class SessionsDrawerRunningTest {

    @get:Rule
    val rule = createComposeRule()

    private fun session(
        id: String,
        running: Boolean = false,
        parentSessionId: String? = null,
        subagent: Boolean = false,
        tasks: Int = 0,
    ) = SessionItem(
        id = id,
        title = id,
        cwd = "/home/user/var/work/dsh-android",
        updatedAt = 1_000L,
        running = running,
        isSubagent = subagent,
        scheduledTaskCount = tasks,
        parentSessionId = parentSessionId,
    )

    private fun workspace(vararg sessionIds: String) = WorkspaceItem(
        id = "ws1",
        path = "/home/user/var/work/dsh-android",
        title = "dsh-android",
        sessionIds = sessionIds.toList(),
    )

    /** The header's spoken run state, which is the blue's accessible twin. */
    private fun hasState(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    @Composable
    private fun drawer(sessions: List<SessionItem>, collapsed: Set<String>) {
        SessionsDrawer(
            sessions = sessions,
            workspaces = listOf(workspace(*sessions.filterNot { it.isSubagent }.map { it.id }.toTypedArray())),
            workspacesPhase = WorkspaceListPhase.Ready,
            archivedSessionIds = emptySet(),
            completedSessionIds = emptySet(),
            currentId = null,
            drawerOpen = true,
            loading = false,
            onSelect = {},
            onNew = {},
            onRefresh = {},
            onSettings = {},
            onAbout = {},
            groupByWorkspace = true,
            orderByUpdated = false,
            showArchived = false,
            collapsedSections = collapsed,
            onGroupByWorkspace = {},
            onOrderByUpdated = {},
            onShowArchived = {},
            onCollapsedSections = {},
        )
    }

    @Test
    fun `a folded Workspace holding a working session reads as running`() {
        rule.setContent {
            DshTheme {
                drawer(listOf(session("s1", running = true)), collapsed = setOf("ws1"))
            }
        }
        rule.waitForIdle()

        rule.onNode(hasState(WorkspaceCopy.RUNNING)).assertExists()
    }

    @Test
    fun `a folded Workspace whose only live work is a delegated subagent reads as running`() {
        // The subagent is never a member — the group holds the idle parent — so this passes
        // only if the rollup half of the derivation reaches the header. `subagentTiming.active`
        // is not read anywhere: this child is running because `SessionItem.running` says so.
        rule.setContent {
            DshTheme {
                drawer(
                    listOf(
                        session("parent"),
                        session("child", running = true, parentSessionId = "parent", subagent = true),
                    ),
                    collapsed = setOf("ws1"),
                )
            }
        }
        rule.waitForIdle()

        rule.onNode(hasState(WorkspaceCopy.RUNNING)).assertExists()
    }

    @Test
    fun `an open Workspace holding a working session reads as running too`() {
        // Folded is the case that was reported, but an open group is capped at six rows and the
        // drawer holds the roster's order still while it is open, so the working row can be
        // below the fold of an expanded group. The header is sticky, so the tint is the one part
        // of the group still on screen.
        rule.setContent {
            DshTheme {
                drawer(listOf(session("s1", running = true)), collapsed = emptySet())
            }
        }
        rule.waitForIdle()

        rule.onNode(hasState(WorkspaceCopy.RUNNING)).assertExists()
    }

    @Test
    fun `a folded Workspace with nothing running makes no running claim at all`() {
        // Not "not running": the header says nothing, exactly as a session row draws no dot for
        // an idle turn. There is no "Running: unknown" left to say either: an idle group is a
        // confirmed nothing for the work that is on screen, and the only other fact — the
        // completeness of the roster — is said in the count, not in the spoken state.
        rule.setContent {
            DshTheme {
                drawer(listOf(session("s1")), collapsed = setOf("ws1"))
            }
        }
        rule.waitForIdle()

        rule.onNode(hasState(WorkspaceCopy.RUNNING)).assertDoesNotExist()
    }

    @Test
    fun `a folded Workspace whose members never arrived states the gap in its count`() {
        // The registry accounts a member the roster has not delivered, so "nothing is running
        // in here" is a statement about the work the client can see, and the header makes it
        // without apology (no spoken "unknown", no dimmed-title rule to speak of). The fact the
        // reader needs — that this directory is not showing everything — is the count: "0 of 1",
        // which is visible folded, where the "Sessions not loaded" note under the header is not.
        rule.setContent {
            DshTheme {
                SessionsDrawer(
                    sessions = emptyList(),
                    workspaces = listOf(workspace("never-arrived")),
                    workspacesPhase = WorkspaceListPhase.Ready,
                    archivedSessionIds = emptySet(),
                    completedSessionIds = emptySet(),
                    currentId = null,
                    drawerOpen = true,
                    loading = false,
                    onSelect = {},
                    onNew = {},
                    onRefresh = {},
                    onSettings = {},
                    onAbout = {},
                    groupByWorkspace = true,
                    orderByUpdated = false,
                    showArchived = false,
                    collapsedSections = setOf("ws1"),
                    onGroupByWorkspace = {},
                    onOrderByUpdated = {},
                    onShowArchived = {},
                    onCollapsedSections = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNode(hasState(WorkspaceCopy.RUNNING)).assertDoesNotExist()
        rule.onNodeWithText("0 of 1").assertExists()
        rule.onNodeWithText(WorkspaceCopy.UNKNOWN).assertDoesNotExist()
    }

    @Test
    fun `a Workspace with a missing member still reads running when a delivered one works`() {
        // The positive fact does not depend on the whole membership, and the gap does not
        // suppress it: the header goes blue and speaks "Running" while the count states
        // "1 of 2". This is the split the rule exists for — colour for what is running here,
        // words for what the roster left out.
        rule.setContent {
            DshTheme {
                SessionsDrawer(
                    sessions = listOf(session("s1", running = true)),
                    workspaces = listOf(workspace("s1", "gone")),
                    workspacesPhase = WorkspaceListPhase.Ready,
                    archivedSessionIds = emptySet(),
                    completedSessionIds = emptySet(),
                    currentId = null,
                    drawerOpen = true,
                    loading = false,
                    onSelect = {},
                    onNew = {},
                    onRefresh = {},
                    onSettings = {},
                    onAbout = {},
                    groupByWorkspace = true,
                    orderByUpdated = false,
                    showArchived = false,
                    collapsedSections = setOf("ws1"),
                    onGroupByWorkspace = {},
                    onOrderByUpdated = {},
                    onShowArchived = {},
                    onCollapsedSections = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNode(hasState(WorkspaceCopy.RUNNING)).assertExists()
        rule.onNodeWithText("1 of 2").assertExists()
    }

    /**
     * The clock mark, which `AutomationsTest` cannot see: the pure test pins the parse and
     * the count, this pins that the drawer *draws* it on a row whose host task count is
     * non-zero. The pixels are not measurable here, so the assertion is the mark's spoken
     * label — the same fact a screen reader announces in place of the glyph.
     */
    @Test
    fun `a session an active task delivers into wears the clock mark`() {
        rule.setContent {
            DshTheme {
                drawer(listOf(session("s1", tasks = 1)), collapsed = emptySet())
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("1 scheduled task").assertExists()
    }

    @Test
    fun `a session with no task wears no mark`() {
        rule.setContent {
            DshTheme {
                drawer(listOf(session("s1")), collapsed = emptySet())
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("1 scheduled task").assertDoesNotExist()
        rule.onNodeWithContentDescription("2 scheduled tasks").assertDoesNotExist()
    }

    /**
     * The mark and the status dot share the row's one leading cell, and the web offers it
     * to the mark only while the row's own primary state is idle. A live turn must keep
     * the dot there; the count is not lost, it is simply not drawn until the row rests,
     * which is also when a clock is worth reading.
     */
    @Test
    fun `a running session keeps its status dot rather than the mark`() {
        rule.setContent {
            DshTheme {
                drawer(listOf(session("s1", running = true, tasks = 1)), collapsed = emptySet())
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription("1 scheduled task").assertDoesNotExist()
    }
}
