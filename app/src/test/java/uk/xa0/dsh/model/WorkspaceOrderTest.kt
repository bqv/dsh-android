package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.WorkspaceItem

/**
 * The rules behind the Workspace row's explicit move actions.
 *
 * The list these pin is the **registry** order — the one `workspace/insertBefore`
 * stores — not the `sessionIds` order inside one Workspace. Tree mode derives its
 * nesting from paths, so a move is sibling-scoped there: it can change the order of
 * children under one registered parent, but not which parent a child nests under.
 *
 * What a JVM cannot prove: that the host accepts a move, or that the drawer's
 * `remember` block passes the anchor this file computes. The pure rules and the
 * drawer's wiring are tested separately for exactly that reason.
 */
class WorkspaceOrderTest {

    private fun workspace(id: String, path: String = "/work/$id") = WorkspaceItem(
        id = id,
        path = path,
        title = id,
        sessionIds = emptyList(),
    )

    private fun ids(items: List<WorkspaceItem>): List<String> = items.map { it.id }

    // ------------------------------------------------------------ move targets

    @Test
    fun `flat mode steps through the whole registry and stops at both ends`() {
        val list = listOf(workspace("a"), workspace("b"), workspace("c"))

        // Down is expressed as "before whatever follows the next sibling", so moving
        // `a` down swaps it with `b` without taking it past `c`; moving the
        // second-to-last row down has no follower and therefore appends.
        assertEquals(WorkspaceMove("a", "c"), workspaceMoves(list, false, "a", WorkspaceMoveDirection.Down))
        assertEquals(WorkspaceMove("b", "a"), workspaceMoves(list, false, "b", WorkspaceMoveDirection.Up))
        assertEquals(WorkspaceMove("b", null), workspaceMoves(list, false, "b", WorkspaceMoveDirection.Down))
        assertNull(workspaceMoves(list, false, "a", WorkspaceMoveDirection.Up))
        assertNull(workspaceMoves(list, false, "c", WorkspaceMoveDirection.Down))
    }

    @Test
    fun `tree mode scopes the pair to siblings, so a child cannot leave its parent`() {
        // Two roots and two children of `outer`. The registry order interleaves them,
        // which is what makes the sibling scoping visible: `child2`'s move up must
        // pass its sibling `child1`, not the unrelated root that sits between them.
        val list = listOf(
            workspace("outer", "/work"),
            workspace("child1", "/work/one"),
            workspace("sibling", "/other/sibling"),
            workspace("child2", "/work/two"),
        )

        // The child's next sibling is the last registry row, so the move appends;
        // what matters for the tree is that it lands after its sibling and never
        // before a root.
        assertEquals(
            WorkspaceMove("child1", null),
            workspaceMoves(list, true, "child1", WorkspaceMoveDirection.Down),
        )
        // Up names the previous sibling as the anchor. The sibling it must pass is
        // `child1`, never the unrelated `sibling` that sits between them.
        assertEquals(
            WorkspaceMove("child2", "child1"),
            workspaceMoves(list, true, "child2", WorkspaceMoveDirection.Up),
        )
        assertNull(workspaceMoves(list, true, "child1", WorkspaceMoveDirection.Up))
        assertNull(workspaceMoves(list, true, "child2", WorkspaceMoveDirection.Down))
        // Roots are siblings of each other and of nothing else.
        assertEquals(
            WorkspaceMove("outer", "child2"),
            workspaceMoves(list, true, "outer", WorkspaceMoveDirection.Down),
        )
    }

    @Test
    fun `a last sibling's move down keeps the registry displacement to one step`() {
        // `b` is the last child, followed in the flat registry by `r` and `tail`. The
        // move must name the workspace after `b`'s next sibling — or append when that
        // sibling is already last — never jump the moved row past unrelated roots
        // further than the sibling swap requires.
        val lastSibling = listOf(
            workspace("p", "/work/p"),
            workspace("a", "/work/p/a"),
            workspace("b", "/work/p/b"),
        )
        assertNull(workspaceMoves(lastSibling, true, "b", WorkspaceMoveDirection.Down))

        val followed = listOf(
            workspace("p", "/work/p"),
            workspace("a", "/work/p/a"),
            workspace("b", "/work/p/b"),
            workspace("r", "/other/r"),
        )
        assertEquals(
            WorkspaceMove("a", "r"),
            workspaceMoves(followed, true, "a", WorkspaceMoveDirection.Down),
        )
    }

    // ---------------------------------------------------------------- applying

    @Test
    fun `a move lands the source immediately before its anchor`() {
        val list = listOf(workspace("a"), workspace("b"), workspace("c"), workspace("d"))
        val moved = applyWorkspaceMove(list, WorkspaceMove("d", "b"))
        assertEquals(listOf("a", "d", "b", "c"), ids(moved))
    }

    @Test
    fun `a null anchor appends, which is the host's own reading`() {
        val list = listOf(workspace("a"), workspace("b"), workspace("c"))
        assertEquals(listOf("b", "c", "a"), ids(applyWorkspaceMove(list, WorkspaceMove("a", null))))
    }

    @Test
    fun `an unknown source or anchor changes nothing, so a stale row sends no request`() {
        val list = listOf(workspace("a"), workspace("b"))
        val same = applyWorkspaceMove(list, WorkspaceMove("ghost", "b"))
        assertEquals(list, same)
        assertEquals(list, applyWorkspaceMove(list, WorkspaceMove("a", "ghost")))
        assertEquals(list, applyWorkspaceMove(list, WorkspaceMove("a", "a")))
    }

    @Test
    fun `the reply's complete order wins and leaves rows it does not name at the end`() {
        val list = listOf(workspace("a"), workspace("b"), workspace("c"))
        assertEquals(
            listOf("c", "a", "b"),
            ids(applyWorkspaceOrder(list, listOf("c", "a", "b"))),
        )
        // An id the host names but this client has not received yet sorts last; a row
        // the reply omits keeps its place at the end rather than vanishing.
        assertEquals(
            listOf("b", "a", "c"),
            ids(applyWorkspaceOrder(list, listOf("b", "x"))),
        )
    }

    @Test
    fun `a refused move's sentence names the Workspace and the host's reason`() {
        assertEquals(
            "Could not move “Work”: workspace/not-found: Workspace \"w\" not found",
            workspaceMoveRefusal("Work", "workspace/not-found: Workspace \"w\" not found"),
        )
    }

    // -------------------------------------------------------------- the forest

    @Test
    fun `applying a sibling move changes sibling order without re-parenting anything`() {
        val list = listOf(
            workspace("outer", "/work"),
            workspace("first", "/work/first"),
            workspace("second", "/work/second"),
        )
        val move = workspaceMoves(list, true, "second", WorkspaceMoveDirection.Up)!!
        val moved = applyWorkspaceMove(list, move)

        assertEquals(listOf("outer", "second", "first"), ids(moved))
        assertTrue(
            "a sibling move must not change the derived tree's shape",
            workspaceParentIds(moved) == workspaceParentIds(list),
        )
    }
}
