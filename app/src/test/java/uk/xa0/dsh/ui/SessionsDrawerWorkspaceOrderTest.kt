package uk.xa0.dsh.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * The Workspace header's reorder path without a menu.
 *
 * Touch reorders by dragging the header, which a Robolectric JVM cannot perform (the
 * long-press timing, the measured header midpoints, the frame clock and the pointer
 * stream are all device behaviour). What this pins is the path that must survive
 * beside it: the header's custom accessibility actions, which are what TalkBack
 * offers because it cannot drag. It also pins that no visual menu is left, that the
 * actions name the same anchors `model/WorkspaceOrder.kt` derives, that an only child
 * gets no actions, and that the header still folds on a tap.
 *
 * The pure drag arithmetic (sibling scoping, the drop slot, the anchor) is
 * `model/WorkspaceDragTest`. What neither test can prove is that a real drag starts,
 * auto-scrolls, or is accepted by a running host.
 */
@RunWith(RobolectricTestRunner::class)
// A phone-sized surface, not the harness's 320x470 default: the drawer's chrome
// already uses most of 470dp, and `performClick` injects a real touch that a control
// below the fold would never receive.
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w411dp-h891dp")
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

    /** The custom actions the header for [id] exposes to a screen reader, in order. */
    private fun headerActions(id: String): List<CustomAccessibilityAction> =
        rule.onNodeWithTag(WORKSPACE_HEADER_TAG_PREFIX + id)
            .fetchSemanticsNode()
            .config[SemanticsActions.CustomActions]

    private val three = listOf(workspace("a"), workspace("b"), workspace("c"))

    @Test
    fun `the first Workspace offers only Move down, which names the anchor the host takes`() {
        var moved: Pair<String, String?>? = null
        rule.setContent {
            DshTheme {
                drawer(three, onMoveWorkspace = { id, before -> moved = id to before })
            }
        }
        rule.waitForIdle()

        // The first sibling has no "up" seat, so it is absent rather than offered
        // and refused; the same choice the old menu made by disabling the item.
        val actions = headerActions("a")
        assertEquals(listOf(WorkspaceCopy.MOVE_DOWN), actions.map { it.label })
        assertTrue(actions.single().action())

        // Down is expressed as "before whatever followed the next Workspace", so the
        // one action swaps `a` with `b` and leaves `c` where it was.
        assertEquals("a" to "c", moved)
    }

    @Test
    fun `the registry's last Workspace offers only Move up`() {
        var moved: Pair<String, String?>? = null
        rule.setContent {
            DshTheme {
                drawer(three, onMoveWorkspace = { id, before -> moved = id to before })
            }
        }
        rule.waitForIdle()

        val actions = headerActions("c")
        assertEquals(listOf(WorkspaceCopy.MOVE_UP), actions.map { it.label })
        assertTrue(actions.single().action())
        assertEquals("c" to "b", moved)
    }

    @Test
    fun `there is no visible reorder menu left on a Workspace header`() {
        rule.setContent {
            DshTheme {
                drawer(three)
            }
        }
        rule.waitForIdle()

        // The pair now exists only as semantics: a visual menu would have these as
        // Text nodes, and there is no `⋮` target either.
        assertTrue(
            "a Workspace header must not draw a Move up/Move down menu",
            rule.onAllNodesWithText(WorkspaceCopy.MOVE_UP).fetchSemanticsNodes().isEmpty() &&
                rule.onAllNodesWithText(WorkspaceCopy.MOVE_DOWN).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `an only child in Tree mode gets no reorder actions at all`() {
        val nested = listOf(
            workspace("outer", "/work"),
            workspace("only", "/work/only"),
        )
        rule.setContent {
            DshTheme {
                drawer(nested, grouping = DrawerGrouping.TREE)
            }
        }
        rule.waitForIdle()

        // Path-derived nesting fixes `only` under `outer`, and it is the only child:
        // there is no other seat for either the drag or the semantic pair.
        assertTrue(headerActions("only").isEmpty())
        assertTrue(headerActions("outer").isEmpty())
    }

    @Test
    fun `the flat list's header has no Workspace reorder actions`() {
        rule.setContent {
            DshTheme {
                drawer(three, grouping = DrawerGrouping.FLAT)
            }
        }
        rule.waitForIdle()

        // The flat list is not a Workspace, so it must never grow a Workspace action.
        val flat = rule.onNodeWithText("Sessions").fetchSemanticsNode()
        assertTrue(flat.config[SemanticsActions.CustomActions].isEmpty())
    }

    @Test
    fun `a header carrying the drag handle and the actions still folds on tap`() {
        var collapsed: Set<String>? = null
        rule.setContent {
            DshTheme {
                drawer(three, onCollapsedSections = { collapsed = it })
            }
        }
        rule.waitForIdle()

        // A real touch at the header's centre: the drag detector only claims the
        // gesture after long press plus touch slop, so a tap keeps its meaning.
        rule.onNodeWithTag(WORKSPACE_HEADER_TAG_PREFIX + "a").performClick()
        rule.waitForIdle()

        assertEquals(setOf("a"), collapsed)
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
}
