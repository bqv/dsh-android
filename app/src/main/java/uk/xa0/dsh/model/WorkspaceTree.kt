package uk.xa0.dsh.model

import uk.xa0.dsh.WorkspaceItem

/**
 * The drawer's three browsing modes, in the web's own order and vocabulary.
 *
 * The web does not offer a hierarchy of *sessions*: "Workspace Tree" nests each
 * **Workspace** under its nearest registered ancestor directory, and each Workspace
 * keeps its own sessions. Read from `dsh-client-ui-workspace` (host bundle at
 * `/usr/lib64/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/`):
 *
 *  * `lib/client.js` — the View options menu's `Group by` items are
 *    `group-by` (label), `workspace` ("WorkSpace"), `workspace-tree`
 *    ("Workspace Tree") and `flat` ("In one list"), in that order, each with its
 *    own icon. The mode selects `nestWorkspaces: groupBy === "workspace-tree"`
 *    on the browser, so the other two modes share one flat-group derivation.
 *  * `lib/types/client/tree.d.ts` — `deriveGroups` and the `GroupNode` shape, plus
 *    `owningParentFolder`.
 *  * `lib/client.js` — the renderer builds the parent map with
 *    `owningParentFolder`, draws `childRows` inside `renderGroup`, and indents with
 *    `--dsh-workspace-indent: ${depth * 12}px`.
 *  * `README.md`, "Workspace hierarchy" — the prose contract this file follows:
 *    nesting by registered canonical paths only, child Workspaces before the
 *    parent's own sessions, ancestors start expanded, ancestor folders stay
 *    highlighted, and only the contents indent.
 *
 * The mode is persisted under the web's own id ([stored]) rather than under a
 * private enum name, so a stored value can be read against the web's
 * `dsh.workspace.view.v5` store. The app used to persist a boolean
 * (`drawer_group_by_workspace`); `ConfigStore` still reads that key when the
 * new one is absent, so an upgrade keeps the reader's last choice.
 */
enum class DrawerGrouping(val stored: String) {
    /** Sibling Workspace sections, the web's "WorkSpace". */
    WORKSPACE("workspace"),

    /** Workspaces nested under their nearest registered ancestor, "Workspace Tree". */
    TREE("workspace-tree"),

    /** One flat list with no Workspace sections, the web's "In one list". */
    FLAT("flat");

    /** Whether this mode draws Workspace sections at all. */
    val groupsByWorkspace: Boolean
        get() = this != FLAT

    /** Whether Workspaces nest, which only the web's tree mode does. */
    val nestsWorkspaces: Boolean
        get() = this == TREE

    companion object {
        /**
         * Parse a persisted id.
         *
         * @param value the stored [stored] string, or null when nothing was saved.
         * @return the mode, or null for a missing or unrecognised value — the caller
         *   keeps its own default rather than guessing.
         */
        fun fromStored(value: String?): DrawerGrouping? =
            values().firstOrNull { it.stored == value }
    }
}

/**
 * One Workspace's seat in the Workspace Tree: itself, how deep it sits, and the
 * registered Workspaces nested directly beneath it.
 *
 * [depth] is a count of registered ancestors, not of path segments. A Workspace at
 * `/home/user/var/work/app` is a root when no registered Workspace is an ancestor,
 * even though its path is six segments deep; the tree the web draws is a hierarchy
 * of *registered directories*, and a folder nobody registered is not a node.
 *
 * A node's sessions are not carried here: they remain on [WorkspaceItem.sessionIds]
 * in the host's manual order, and the drawer resolves them against its roster. This
 * data type answers only "which Workspace sits under which, at what depth".
 */
data class WorkspaceTreeNode(
    val workspace: WorkspaceItem,
    val depth: Int,
    val children: List<WorkspaceTreeNode>,
)

/**
 * Normalise a directory path the way the web's `folderPath` does before comparing it.
 *
 * Windows paths (a drive prefix, or a UNC path beginning with two backslashes) have
 * backslashes rewritten to forward slashes; POSIX paths keep their backslashes,
 * because there a backslash is an ordinary file-name character. Trailing separators
 * are then dropped, so `/work/app/` and `/work/app` name the same directory.
 *
 * @param path the host's spelling of a directory.
 * @return the comparable form of [path].
 */
private fun folderPath(path: String): String {
    val slashed = if (isWindowsPath(path)) path.replace('\\', '/') else path
    return slashed.trimEnd('/')
}

/** The web's `^[A-Za-z]:[/\\]` or `\\\\` test, written without a regex. */
private fun isWindowsPath(path: String): Boolean {
    if (path.startsWith("\\\\")) return true
    if (path.length < 3) return false
    val drive = path[0]
    val asciiLetter = drive in 'A'..'Z' || drive in 'a'..'z'
    return asciiLetter && path[1] == ':' && (path[2] == '/' || path[2] == '\\')
}

/**
 * Find the nearest registered ancestor directory, excluding the directory itself.
 *
 * This is the web's `owningParentFolder(path, parents)` from
 * `dsh-client-ui-workspace/lib/types/client/tree.d.ts`, ported line for line:
 * the longest registered path that is a strict textual prefix ending on a
 * separator wins, so `/work/app` nests under `/work` and not under `/wo`.
 * Matching is case-sensitive, like Workspace identity, and no symlink or
 * project-root resolution happens — the web's README says hierarchy uses
 * "registered canonical paths only".
 *
 * @param path the Workspace directory being placed.
 * @param paths every registered Workspace directory path, in registry order.
 * @return the nearest ancestor in its original spelling, or null when no
 *   registered Workspace contains [path].
 */
fun owningParentFolder(path: String, paths: Collection<String>): String? {
    val child = folderPath(path)
    var owner: String? = null
    var longest = -1
    for (parent in paths) {
        val root = folderPath(parent)
        if (root.length > longest && child != root && child.startsWith("$root/")) {
            owner = parent
            longest = root.length
        }
    }
    return owner
}

/**
 * Resolve every Workspace's direct parent by id, mirroring the web's `parents` map.
 *
 * The web keys registered paths by their raw spelling (`new Map(workspaces.map(...))`,
 * so a duplicate path's last id wins) and then resolves the nearest ancestor's id
 * through that map. This function does the same, so a path that differs only by a
 * trailing slash still nests correctly.
 *
 * @param workspaces the registry in Host group order.
 * @return Workspace id to parent Workspace id; null marks a root.
 */
fun workspaceParentIds(workspaces: List<WorkspaceItem>): Map<String, String?> {
    val idByPath = LinkedHashMap<String, String>(workspaces.size)
    for (workspace in workspaces) idByPath[workspace.path] = workspace.id
    val paths = idByPath.keys.toList()
    val parents = LinkedHashMap<String, String?>(workspaces.size)
    for (workspace in workspaces) {
        parents[workspace.id] = owningParentFolder(workspace.path, paths)?.let(idByPath::get)
    }
    return parents
}

/**
 * Build the Workspace Tree, in the order the web's renderer draws it.
 *
 * Roots are the Workspaces with no registered ancestor, in registry order; each
 * root's children follow the same rule, and the recursion carries [WorkspaceTreeNode.depth]
 * so the drawer can indent by registered ancestry rather than by path depth. The
 * renderer draws a group's children **before** the group's own sessions, which is
 * why the drawer walks this forest depth-first with the child sections first; this
 * function returns the shape, not the row order.
 *
 * Only registered paths decide the shape. A directory that contains a Workspace but
 * is not itself registered is not a node, and nesting changes neither a session's
 * working directory nor its Workspace membership.
 *
 * A workspace visited twice (only possible with malformed registry data — strict
 * path prefixes cannot form a cycle) is drawn once, and any Workspace the parent
 * map leaves unreachable is appended at the root rather than dropped, because a
 * missing folder would look like a registry that lost it.
 *
 * @param workspaces the registry in Host group order.
 * @return the forest's roots in registry order.
 */
fun workspaceForest(workspaces: List<WorkspaceItem>): List<WorkspaceTreeNode> {
    if (workspaces.isEmpty()) return emptyList()
    val parents = workspaceParentIds(workspaces)
    val children = LinkedHashMap<String?, MutableList<WorkspaceItem>>()
    for (workspace in workspaces) {
        children.getOrPut(parents[workspace.id]) { ArrayList() } += workspace
    }
    val placed = HashSet<String>(workspaces.size)

    fun build(workspace: WorkspaceItem, depth: Int): WorkspaceTreeNode? {
        if (!placed.add(workspace.id)) return null
        val nested = children[workspace.id].orEmpty().mapNotNull { build(it, depth + 1) }
        return WorkspaceTreeNode(workspace, depth, nested)
    }

    val roots = ArrayList<WorkspaceTreeNode>(workspaces.size)
    for (workspace in children[null].orEmpty()) {
        build(workspace, 0)?.let(roots::add)
    }
    if (placed.size < workspaces.size) {
        for (workspace in workspaces) {
            if (workspace.id !in placed) build(workspace, 0)?.let(roots::add)
        }
    }
    return roots
}
