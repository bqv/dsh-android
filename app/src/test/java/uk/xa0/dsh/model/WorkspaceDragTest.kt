package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import uk.xa0.dsh.WorkspaceItem

/**
 * The pure half of dragging a Workspace header.
 *
 * The gesture, the lift, the auto-scroll and the insertion line need a device; what
 * a JVM can pin is every decision that turns a finger position into a request: the
 * sibling scope Tree mode enforces, the slot a midpoint crossing names, the order
 * the drawer previews, and the `workspace/insertBefore` anchor a release sends.
 *
 * What this cannot prove: that a running host accepts or refuses a move, that the
 * drawer's measured header midpoints line up with a real finger, or that a real
 * drag gesture starts at all.
 */
class WorkspaceDragTest {

    private fun workspace(id: String, path: String = "/work/$id") = WorkspaceItem(
        id = id,
        path = path,
        title = id,
        sessionIds = emptyList(),
    )

    private fun ids(items: List<WorkspaceItem>): List<String> = items.map { it.id }

    // ----------------------------------------------------------- sibling scope

    @Test
    fun `flat mode treats the whole registry as one sibling list`() {
        val list = listOf(workspace("a"), workspace("b"), workspace("c"))

        assertEquals(listOf("a", "b", "c"), workspaceSiblingOrder(list, false, "b"))
    }

    @Test
    fun `tree mode scopes the drag to the parent the path names`() {
        val list = listOf(
            workspace("outer", "/work"),
            workspace("child1", "/work/one"),
            workspace("other", "/other"),
            workspace("child2", "/work/two"),
        )

        // The children are siblings even though a root sits between them in the
        // registry: a drag may reorder child1 and child2, and may never take either
        // out from under `outer`.
        assertEquals(listOf("child1", "child2"), workspaceSiblingOrder(list, true, "child1"))
        assertEquals(listOf("outer", "other"), workspaceSiblingOrder(list, true, "outer"))
        // One root, one child: neither has anything to drag against.
        val single = listOf(workspace("outer", "/work"), workspace("only", "/work/only"))
        assertEquals(listOf("outer"), workspaceSiblingOrder(single, true, "outer"))
        assertEquals(listOf("only"), workspaceSiblingOrder(single, true, "only"))
    }

    @Test
    fun `an unknown Workspace has no sibling list`() {
        assertEquals(
            emptyList<String>(),
            workspaceSiblingOrder(listOf(workspace("a")), false, "missing"),
        )
    }

    // --------------------------------------------------------------- drop slot

    @Test
    fun `crossing a header's midpoint moves the dragged id into that slot`() {
        // Midpoints of a, b and c as a drawer might measure them, in pixels.
        val centres = mapOf("a" to 17f, "b" to 51f, "c" to 85f)
        val siblings = listOf("a", "b", "c")

        // Below b's midpoint: the slot is still between a and b.
        assertEquals(0, workspaceDropSlot(siblings, "b", 20f, centres))
        // Past b's midpoint and before c's: b now sits after a.
        assertEquals(1, workspaceDropSlot(siblings, "c", 40f, centres))
        // Past every midpoint: the end of the sibling list.
        assertEquals(2, workspaceDropSlot(siblings, "a", 120f, centres))
        // Dragging b up: above a's midpoint puts it first.
        assertEquals(0, workspaceDropSlot(siblings, "b", 5f, centres))
    }

    @Test
    fun `a midpoint exactly under the finger has not been crossed`() {
        val centres = mapOf("a" to 17f, "b" to 51f, "c" to 85f)

        // c is dragged, so only a and b are candidates. At b's midpoint the finger
        // has not crossed it: c stays after b, and the next sample past it moves c
        // before b.
        assertEquals(1, workspaceDropSlot(listOf("a", "b", "c"), "c", 51f, centres))
        assertEquals(0, workspaceDropSlot(listOf("a", "b", "c"), "c", 50.9f, centres))
    }

    @Test
    fun `headers with no measurement are ignored rather than guessed`() {
        // Only b is visible; a and c are scrolled out. A finger above b belongs
        // between a and b (slot 1 in the list without the dragged c), not at 0.
        assertEquals(
            1,
            workspaceDropSlot(listOf("a", "b", "c"), "c", 10f, mapOf("b" to 51f)),
        )
    }

    @Test
    fun `a slot is clamped when it is carried past the end`() {
        val siblings = listOf("a", "b", "c")

        assertEquals(listOf("b", "c", "a"), reorderWorkspaceSiblings(siblings, "a", 50))
        assertEquals(listOf("a", "b", "c"), reorderWorkspaceSiblings(siblings, "a", -4))
        // A row that is not in the list cannot be inserted into it.
        assertEquals(siblings, reorderWorkspaceSiblings(siblings, "missing", 1))
    }

    // ------------------------------------------------------------ preview list

    @Test
    fun `a preview moves sibling seats and leaves a non-sibling where it was`() {
        val list = listOf(
            workspace("rootA", "/a"),
            workspace("childA", "/a/one"),
            workspace("rootB", "/b"),
            workspace("childB", "/a/two"),
        )

        // Only the roots are being rearranged here. Their seats are the first and
        // third entries; childA must stay between them and childB must stay last.
        val preview = applySiblingOrder(list, listOf("rootB", "rootA"))
        assertEquals(listOf("rootB", "childA", "rootA", "childB"), ids(preview))
    }

    @Test
    fun `a preview that is not a permutation of rows that exist changes nothing`() {
        val list = listOf(workspace("a"), workspace("b"))

        assertEquals(list, applySiblingOrder(list, listOf("a", "ghost")))
        assertEquals(list, applySiblingOrder(list, listOf("a")))
        assertEquals(list, applySiblingOrder(list, emptyList()))
    }

    // ------------------------------------------------------------ release move

    @Test
    fun `a drop names the sibling that follows the slot, and the end appends`() {
        val list = listOf(workspace("a"), workspace("b"), workspace("c"), workspace("d"))

        // a dragged past c: the new order is b, c, a, d, so a lands in front of d.
        assertEquals(
            WorkspaceMove("a", "d"),
            workspaceDragMove(list, false, "a", listOf("b", "c", "a", "d")),
        )
        // a dragged to the end: there is no following sibling, so the host appends.
        assertEquals(
            WorkspaceMove("a", null),
            workspaceDragMove(list, false, "a", listOf("b", "c", "d", "a")),
        )
        // A drag that ends where it began sends no request at all.
        assertNull(workspaceDragMove(list, false, "a", listOf("a", "b", "c", "d")))
    }

    @Test
    fun `a tree-mode drop stays inside the sibling list it began in`() {
        val list = listOf(
            workspace("outer", "/work"),
            workspace("child1", "/work/one"),
            workspace("other", "/other"),
            workspace("child2", "/work/two"),
        )

        // child2 moved above child1 names child1 as the anchor. The unrelated root
        // that sits between them in the registry is never named.
        assertEquals(
            WorkspaceMove("child2", "child1"),
            workspaceDragMove(list, true, "child2", listOf("child2", "child1")),
        )
        // child1 moved below child2 is the end of that sibling list: append.
        assertEquals(
            WorkspaceMove("child1", null),
            workspaceDragMove(list, true, "child1", listOf("child2", "child1")),
        )
        // A preview that names a Workspace outside the sibling list is refused, so
        // a stale gesture cannot re-parent a child.
        assertNull(
            workspaceDragMove(list, true, "child1", listOf("child1", "other")),
        )
    }

    @Test
    fun `an only child has no drop to commit`() {
        val list = listOf(workspace("outer", "/work"), workspace("only", "/work/only"))

        // There is no other seat, and a preview that claimed one is not a
        // permutation of the sibling list, so nothing is sent either way.
        assertNull(workspaceDragMove(list, true, "only", listOf("only")))
        assertNull(workspaceDragMove(list, true, "only", listOf("outer", "only")))
    }

    @Test
    fun `a flat-mode drop through the whole registry keeps the order a release commits`() {
        val list = listOf(workspace("a"), workspace("b"), workspace("c"), workspace("d"))
        val preview = reorderWorkspaceSiblings(listOf("a", "b", "c", "d"), "d", 0)

        assertEquals(listOf("d", "a", "b", "c"), preview)
        val move = workspaceDragMove(list, false, "d", preview)
        assertEquals(WorkspaceMove("d", "a"), move)
        // The optimistic path the ViewModel already applies turns the same anchor
        // into the same order the drawer was showing.
        assertEquals(preview, ids(applyWorkspaceMove(list, move!!)))
    }
}
