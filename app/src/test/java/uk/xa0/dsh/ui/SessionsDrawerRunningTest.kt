package uk.xa0.dsh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
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
 * therefore fails even though a colour change alone would pass unnoticed.
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
    ) = SessionItem(
        id = id,
        title = id,
        cwd = "/home/user/var/work/dsh-android",
        updatedAt = 1_000L,
        running = running,
        isSubagent = subagent,
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
        // an idle turn. What matters is that it does not say "Running" and does not say
        // "Running: unknown" either — an idle group is a confirmed nothing, and the two must
        // stay apart.
        rule.setContent {
            DshTheme {
                drawer(listOf(session("s1")), collapsed = setOf("ws1"))
            }
        }
        rule.waitForIdle()

        rule.onNode(hasState(WorkspaceCopy.RUNNING)).assertDoesNotExist()
        rule.onNode(hasState(WorkspaceCopy.RUNNING_UNKNOWN)).assertDoesNotExist()
    }

    @Test
    fun `a folded Workspace whose members never arrived is unknown, and never reads as idle`() {
        // The registry accounts a member the roster has not delivered, so "nothing is running
        // in here" is not a fact. The header must not go blue (it cannot back it) and must not
        // fall silent (that is the confirmed-nothing it is not): it says "Running: unknown", and
        // the figure it cannot supply is drawn as the word rather than left blank — the
        // "Sessions not loaded" note that would otherwise say so only draws when the group is
        // open, and this group is folded.
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
        rule.onNode(hasState(WorkspaceCopy.RUNNING_UNKNOWN)).assertExists()
        rule.onNodeWithText(WorkspaceCopy.UNKNOWN).assertExists()
    }
}
