package uk.xa0.dsh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.WorkspaceItem
import uk.xa0.dsh.model.DrawerGrouping
import uk.xa0.dsh.model.WorkspaceCopy
import uk.xa0.dsh.model.WorkspaceListPhase
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The drawer's Workspace Tree, wired end to end.
 *
 * The web's tree is a hierarchy of **Workspaces** — each registered directory nested
 * under its nearest registered ancestor, its own sessions beneath it — not a hierarchy
 * of sessions. `WorkspaceTreeTest` pins the shape; this pins what the drawer does with
 * it: nesting, the children-before-own-sessions order, indentation, folding, the
 * Ungrouped bucket and the archived filter.
 *
 * The JVM harness has no text metrics and no pixels. What it *does* have is layout
 * bounds and the semantics tree, so the indentation and ordering assertions read
 * `boundsInRoot` rather than a colour or a glyph. The exact 12dp step is not measured:
 * the test demands only that a nested header sits right of its parent, which is the
 * fact a broken depth argument would lose.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class SessionsDrawerTreeTest {

    @get:Rule
    val rule = createComposeRule()

    private fun session(id: String, cwd: String, running: Boolean = false) = SessionItem(
        id = id,
        title = id,
        cwd = cwd,
        updatedAt = 1_000L,
        running = running,
        isSubagent = false,
    )

    private fun workspace(id: String, path: String, vararg sessionIds: String) = WorkspaceItem(
        id = id,
        path = path,
        title = id,
        sessionIds = sessionIds.toList(),
    )

    @Composable
    private fun drawer(
        sessions: List<SessionItem>,
        workspaces: List<WorkspaceItem>,
        grouping: DrawerGrouping,
        collapsed: Set<String> = emptySet(),
        archived: Set<String> = emptySet(),
        showArchived: Boolean = false,
        currentId: String? = null,
        onGrouping: (DrawerGrouping) -> Unit = {},
    ) {
        SessionsDrawer(
            sessions = sessions,
            workspaces = workspaces,
            workspacesPhase = WorkspaceListPhase.Ready,
            archivedSessionIds = archived,
            completedSessionIds = emptySet(),
            currentId = currentId,
            drawerOpen = true,
            loading = false,
            onSelect = {},
            onNew = {},
            onRefresh = {},
            onSettings = {},
            onAbout = {},
            grouping = grouping,
            orderByUpdated = false,
            showArchived = showArchived,
            collapsedSections = collapsed,
            onGrouping = onGrouping,
            onOrderByUpdated = {},
            onShowArchived = {},
            onCollapsedSections = {},
        )
    }

    private fun top(text: String): Float =
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

    private fun left(text: String): Float =
        rule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.left

    private fun hasState(value: String) =
        SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, value)

    private val nestedWorkspaces = listOf(
        workspace("outer", "/work", "s1"),
        workspace("inner", "/work/app", "s2"),
    )

    private val nestedSessions = listOf(
        session("s1", "/work"),
        session("s2", "/work/app"),
    )

    // ------------------------------------------------------------------ control

    @Test
    fun `the Group row offers all three of the web's modes`() {
        rule.setContent {
            DshTheme {
                drawer(nestedSessions, nestedWorkspaces, DrawerGrouping.WORKSPACE)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(GROUP_WORKSPACE_TAG).assertExists()
        rule.onNodeWithTag(GROUP_TREE_TAG).assertExists()
        rule.onNodeWithTag(GROUP_LIST_TAG).assertExists()
    }

    @Test
    fun `the middle chip reports the web's Workspace Tree and is named in full for a screen reader`() {
        var picked: DrawerGrouping? = null
        rule.setContent {
            DshTheme {
                drawer(
                    nestedSessions,
                    nestedWorkspaces,
                    DrawerGrouping.WORKSPACE,
                    onGrouping = { picked = it },
                )
            }
        }
        rule.waitForIdle()

        // "Tree" is the shortened visible label; the full web name is its spoken one.
        rule.onNodeWithContentDescription("Workspace Tree").assertExists()
        rule.onNodeWithTag(GROUP_TREE_TAG).performClick()
        assertEquals(DrawerGrouping.TREE, picked)
    }

    // ---------------------------------------------------------------- hierarchy

    @Test
    fun `tree mode nests a Workspace under its registered ancestor and indents its contents`() {
        rule.setContent {
            DshTheme {
                drawer(nestedSessions, nestedWorkspaces, DrawerGrouping.TREE)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("outer", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("inner", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("s1", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("s2", useUnmergedTree = true).assertExists()

        assertTrue(
            "a nested header's contents sit right of its parent's",
            left("inner") > left("outer") + 4f,
        )
    }

    @Test
    fun `sibling mode draws the same two Workspaces at the same indent`() {
        rule.setContent {
            DshTheme {
                drawer(nestedSessions, nestedWorkspaces, DrawerGrouping.WORKSPACE)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("inner", useUnmergedTree = true).assertExists()
        assertEquals(
            "in sibling mode neither Workspace has registered ancestry to indent for",
            left("outer"),
            left("inner"),
            1f,
        )
    }

    @Test
    fun `a nested Workspace's sessions are drawn before the parent's own sessions`() {
        rule.setContent {
            DshTheme {
                drawer(nestedSessions, nestedWorkspaces, DrawerGrouping.TREE)
            }
        }
        rule.waitForIdle()

        // The web's renderGroup emits header, childRows, then the parent's sessions.
        assertTrue(
            "the child header follows the parent header",
            top("inner") > top("outer"),
        )
        assertTrue(
            "the child's session row precedes the parent's own row",
            top("s2") < top("s1"),
        )
    }

    @Test
    fun `a folded ancestor hides its nested Workspace`() {
        rule.setContent {
            DshTheme {
                drawer(
                    nestedSessions,
                    nestedWorkspaces,
                    DrawerGrouping.TREE,
                    collapsed = setOf("outer"),
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("outer", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("inner", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithText("s2", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun `a folded ancestor reads as running when the live work is a nested Workspace's`() {
        // The ancestor has no members of its own. Without descending, folding it would
        // hide the only running session and leave a grey header over live work; the
        // blue is extended down the tree for the same reason it already covers a
        // running subagent.
        val sessions = listOf(session("s2", "/work/app", running = true))
        rule.setContent {
            DshTheme {
                drawer(
                    sessions = sessions,
                    workspaces = listOf(
                        workspace("outer", "/work"),
                        workspace("inner", "/work/app", "s2"),
                    ),
                    grouping = DrawerGrouping.TREE,
                    collapsed = setOf("outer"),
                )
            }
        }
        rule.waitForIdle()

        rule.onAllNodes(hasState(WorkspaceCopy.RUNNING)).assertCountEquals(1)
    }

    // ------------------------------------------------------------------ buckets

    @Test
    fun `a session in no Workspace is still visible in tree mode`() {
        rule.setContent {
            DshTheme {
                drawer(
                    sessions = listOf(session("s1", "/work"), session("stray", "/elsewhere")),
                    workspaces = listOf(workspace("outer", "/work", "s1")),
                    grouping = DrawerGrouping.TREE,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText(WorkspaceCopy.UNGROUPED, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("stray", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `an empty parent says so, and its nested Workspace still draws`() {
        rule.setContent {
            DshTheme {
                drawer(
                    sessions = listOf(session("s2", "/work/app")),
                    workspaces = listOf(
                        workspace("outer", "/work"),
                        workspace("inner", "/work/app", "s2"),
                    ),
                    grouping = DrawerGrouping.TREE,
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("outer", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(WorkspaceCopy.NO_SESSIONS, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("inner", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the archived filter does not dissolve the tree`() {
        rule.setContent {
            DshTheme {
                drawer(
                    sessions = nestedSessions,
                    workspaces = nestedWorkspaces,
                    grouping = DrawerGrouping.TREE,
                    archived = setOf("s2"),
                    showArchived = false,
                )
            }
        }
        rule.waitForIdle()

        // The child is a registered directory whose only member the filter hid: it
        // draws, with the words that say so, and keeps its place under its parent.
        rule.onNodeWithText("outer", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("inner", useUnmergedTree = true).assertExists()
        rule.onNodeWithText(WorkspaceCopy.SESSIONS_HIDDEN, useUnmergedTree = true).assertExists()
        rule.onNodeWithText("s2", useUnmergedTree = true).assertDoesNotExist()
    }
}
