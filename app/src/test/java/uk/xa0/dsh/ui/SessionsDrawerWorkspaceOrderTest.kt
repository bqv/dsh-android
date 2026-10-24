package uk.xa0.dsh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import uk.xa0.dsh.WorkspaceItem
import uk.xa0.dsh.model.DrawerGrouping
import uk.xa0.dsh.model.WorkspaceCopy
import uk.xa0.dsh.model.WorkspaceListPhase
import uk.xa0.dsh.model.workspaceMoveRefusal
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The Workspace row's explicit reorder actions, wired end to end.
 *
 * The move itself is a host call (`workspace/insertBefore`) and cannot be made from
 * the JVM; what this pins is the part the drawer owns: which anchor a "Move down" tap
 * names, that the end of the list is offered as a disabled item rather than a move
 * that does nothing, that the refusal line is drawn, and — the regression that matters
 * most — that the row still folds on tap while carrying a menu.
 *
 * The pure semantics (sibling scoping in Tree mode, the reply order, snap-back) are
 * pinned in `model/WorkspaceOrderTest`. What neither test can prove is that a running
 * host accepts or refuses a particular move.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class SessionsDrawerWorkspaceOrderTest {

    @get:Rule
    val rule = createComposeRule()

    private fun workspace(id: String, path: String = "/work/$id") = WorkspaceItem(
        id = id,
        path = path,
        title = id,
        sessionIds = emptyList(),
    )

    @Composable
    private fun drawer(
        workspaces: List<WorkspaceItem>,
        grouping: DrawerGrouping = DrawerGrouping.WORKSPACE,
        workspaceOrderError: String? = null,
        onCollapsedSections: (Set<String>) -> Unit = {},
        onMoveWorkspace: (String, String?) -> Unit = { _, _ -> },
    ) {
        SessionsDrawer(
            sessions = emptyList(),
            workspaces = workspaces,
            workspacesPhase = WorkspaceListPhase.Ready,
            archivedSessionIds = emptySet(),
            completedSessionIds = emptySet(),
            currentId = null,
            drawerOpen = true,
            loading = false,
            workspaceOrderError = workspaceOrderError,
            onSelect = {},
            onNew = {},
            onRefresh = {},
            onSettings = {},
            onAbout = {},
            grouping = grouping,
            orderByUpdated = false,
            showArchived = false,
            collapsedSections = emptySet(),
            onGrouping = {},
            onOrderByUpdated = {},
            onShowArchived = {},
            onCollapsedSections = onCollapsedSections,
            onMoveWorkspace = onMoveWorkspace,
        )
    }

    private val three = listOf(workspace("a"), workspace("b"), workspace("c"))

    @Test
    fun `the first Workspace offers Move up disabled and Move down names the anchor the host takes`() {
        var moved: Pair<String, String?>? = null
        rule.setContent {
            DshTheme {
                drawer(three, onMoveWorkspace = { id, before -> moved = id to before })
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription(WorkspaceCopy.actions("a"), useUnmergedTree = true).performClick()
        rule.onNodeWithText(WorkspaceCopy.MOVE_UP).assertIsNotEnabled()
        rule.onNodeWithText(WorkspaceCopy.MOVE_DOWN).assertIsEnabled().performClick()
        rule.waitForIdle()

        // Down is expressed as "before whatever followed the next Workspace", so the
        // one tap swaps `a` with `b` and leaves `c` where it was.
        assertEquals("a" to "c", moved)
    }

    @Test
    fun `the registry's last Workspace cannot move down`() {
        rule.setContent {
            DshTheme {
                drawer(listOf(workspace("a"), workspace("b")))
            }
        }
        rule.waitForIdle()

        rule.onNodeWithContentDescription(WorkspaceCopy.actions("b"), useUnmergedTree = true).performClick()
        rule.onNodeWithText(WorkspaceCopy.MOVE_UP).assertIsEnabled()
        rule.onNodeWithText(WorkspaceCopy.MOVE_DOWN).assertIsNotEnabled()
    }

    @Test
    fun `a refused move's reason is drawn beside the list it snapped back into`() {
        val note = workspaceMoveRefusal("a", "workspace/not-found: Workspace \"a\" not found")
        rule.setContent {
            DshTheme {
                drawer(three, workspaceOrderError = note)
            }
        }
        rule.waitForIdle()

        rule.onNodeWithTag(WORKSPACE_ORDER_ERROR_TAG).assertExists()
        rule.onNodeWithText(note, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a header carrying the menu still folds on tap`() {
        var collapsed: Set<String>? = null
        rule.setContent {
            DshTheme {
                drawer(three, onCollapsedSections = { collapsed = it })
            }
        }
        rule.waitForIdle()

        // The menu is one 28dp target on the same row: tapping the title must still
        // reach the header's own click, not be swallowed by the new control.
        rule.onNodeWithText("a", useUnmergedTree = true).performClick()
        rule.waitForIdle()

        assertEquals(setOf("a"), collapsed)
    }

    @Test
    fun `no move menu is offered where there is no registry row behind the header`() {
        rule.setContent {
            DshTheme {
                drawer(three, grouping = DrawerGrouping.FLAT)
            }
        }
        rule.waitForIdle()

        // The flat list is not a Workspace, so it must never grow a Workspace menu.
        assertTrue(
            "the flat list must not carry a Workspace reorder menu",
            rule.onAllNodesWithText(WorkspaceCopy.MOVE_UP).fetchSemanticsNodes().isEmpty(),
        )
    }
}
