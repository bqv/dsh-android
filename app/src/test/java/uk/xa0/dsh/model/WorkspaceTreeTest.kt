package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.WorkspaceItem

/**
 * The Workspace Tree's shape.
 *
 * The web's tree is a hierarchy of **Workspaces**, not of sessions: a Workspace nests
 * under the nearest *registered* ancestor directory, and its own sessions stay under
 * its own header. These tests pin the part that decides the shape —
 * `owningParentFolder`, the parent map and the forest — because the drawer's
 * `remember` block cannot be reached from the JVM harness and an indent is not a
 * hierarchy: a bug that placed every Workspace at depth 0 would still draw them all.
 *
 * Read from `dsh-client-ui-workspace` (`lib/types/client/tree.d.ts`,
 * `lib/client.js`) on 2026-10-22; the function names here are the web's own.
 */
class WorkspaceTreeTest {

    private fun workspace(id: String, path: String) = WorkspaceItem(
        id = id,
        path = path,
        title = id,
        sessionIds = emptyList(),
    )

    private fun flatten(nodes: List<WorkspaceTreeNode>): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        fun walk(node: WorkspaceTreeNode) {
            out += node.workspace.id to node.depth
            node.children.forEach(::walk)
        }
        nodes.forEach(::walk)
        return out
    }

    // ------------------------------------------------------------- the mode

    @Test
    fun `the three modes use the web's ids and only tree nests`() {
        assertEquals("workspace", DrawerGrouping.WORKSPACE.stored)
        assertEquals("workspace-tree", DrawerGrouping.TREE.stored)
        assertEquals("flat", DrawerGrouping.FLAT.stored)
        assertEquals(DrawerGrouping.TREE, DrawerGrouping.fromStored("workspace-tree"))
        assertNull(DrawerGrouping.fromStored("workspaces"))
        assertNull(DrawerGrouping.fromStored(null))
        assertTrue(DrawerGrouping.WORKSPACE.groupsByWorkspace)
        assertTrue(DrawerGrouping.TREE.groupsByWorkspace)
        assertFalse(DrawerGrouping.FLAT.groupsByWorkspace)
        assertTrue(DrawerGrouping.TREE.nestsWorkspaces)
        assertFalse(DrawerGrouping.WORKSPACE.nestsWorkspaces)
        assertFalse(DrawerGrouping.FLAT.nestsWorkspaces)
    }

    // ------------------------------------------------- nearest registered ancestor

    @Test
    fun `a Workspace nests under the nearest registered ancestor, not the longest path it can find`() {
        val paths = listOf("/work", "/work/app", "/work/app/ui")
        assertEquals("/work/app", owningParentFolder("/work/app/ui", paths))
        assertEquals("/work", owningParentFolder("/work/app", paths))
        assertNull(owningParentFolder("/work", paths))
    }

    @Test
    fun `a name prefix is not a folder prefix`() {
        // `/work/apple` must not become a child of `/work/app`: the separator is the
        // whole difference between a sibling and a descendant.
        assertNull(owningParentFolder("/work/apple", listOf("/work/app")))
    }

    @Test
    fun `a registered ancestor that is not adjacent in path segments still wins`() {
        // Only registered directories are nodes. `/work` contains `/work/a/b/c`, so it
        // is that Workspace's parent even though `/work/a` and `/work/a/b` (unregistered)
        // are skipped entirely.
        assertEquals("/work", owningParentFolder("/work/a/b/c", listOf("/work")))
    }

    @Test
    fun `trailing separators do not stop a match, and a Workspace is not its own parent`() {
        assertEquals("/work/", owningParentFolder("/work/app/", listOf("/work/")))
        assertNull(owningParentFolder("/work/", listOf("/work/")))
        assertNull(owningParentFolder("/work", listOf("/work/")))
    }

    @Test
    fun `matching is case-sensitive, like Workspace identity`() {
        assertNull(owningParentFolder("/Work/app", listOf("/work")))
        assertEquals("/work", owningParentFolder("/work/App", listOf("/work")))
    }

    @Test
    fun `Windows spellings are compared with one separator`() {
        // The web rewrites backslashes only for a drive-qualified or UNC path; a POSIX
        // path's backslash is an ordinary file-name character and must stay distinct.
        assertEquals("C:\\work", owningParentFolder("C:\\work\\app", listOf("C:\\work")))
        assertEquals("C:/work", owningParentFolder("C:/work/app", listOf("C:/work")))
        assertNull(owningParentFolder("/work/app", listOf("/work\\")))
    }

    // ------------------------------------------------------------------ forest

    @Test
    fun `the forest keeps registry order and carries depth`() {
        val workspaces = listOf(
            workspace("app", "/work/app"),
            workspace("ref", "/ref"),
            workspace("ui", "/work/app/ui"),
            workspace("work", "/work"),
        )
        val forest = workspaceForest(workspaces)
        // Roots and siblings keep the registry's order: `ref` was registered before
        // `work`, so it is drawn first, with its own subtree nowhere to go.
        assertEquals(
            listOf("ref" to 0, "work" to 0, "app" to 1, "ui" to 2),
            flatten(forest),
        )
    }

    @Test
    fun `a sibling stays at the root and a child renders before the parent's own row`() {
        val workspaces = listOf(
            workspace("outer", "/work"),
            workspace("inner", "/work/app"),
            workspace("other", "/work-other"),
        )
        val forest = workspaceForest(workspaces)
        assertEquals(listOf("outer", "other"), forest.map { it.workspace.id })
        assertEquals(listOf("inner"), forest.first().children.map { it.workspace.id })
    }

    @Test
    fun `sessions do not affect the shape`() {
        // Membership and hierarchy are independent: a Workspace with no sessions is
        // still a node, and nesting never moves a session between Workspaces.
        val workspaces = listOf(
            WorkspaceItem("outer", "/work", "outer", listOf("s1")),
            WorkspaceItem("inner", "/work/app", "inner", emptyList()),
        )
        val parents = workspaceParentIds(workspaces)
        assertEquals("outer", parents["inner"])
        assertNull(parents["outer"])
        assertEquals(listOf("s1"), workspaces.first().sessionIds)
    }

    @Test
    fun `an empty registry is an empty forest`() {
        assertTrue(workspaceForest(emptyList()).isEmpty())
    }

    @Test
    fun `a lone Workspace is a root`() {
        val forest = workspaceForest(listOf(workspace("only", "/only")))
        assertEquals(listOf("only" to 0), flatten(forest))
    }
}
