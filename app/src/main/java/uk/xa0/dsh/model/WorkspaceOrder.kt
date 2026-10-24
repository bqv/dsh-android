package uk.xa0.dsh.model

import uk.xa0.dsh.WorkspaceItem

/**
 * Reordering the Workspaces themselves — which is not the drawer's `Order: Manual /
 * Updated` control.
 *
 * ## The host owns this order
 *
 * `Order: Manual / Updated` decides the order of the sessions *inside* one Workspace
 * (the host's `sessionIds`). The order of the Workspaces is a second, registry-wide
 * list, and the host both stores and mutates it:
 *
 *  * `workspace/insertBefore` — `WorkspaceInsertBeforeRequest {workspaceId,
 *    beforeWorkspaceId?}`, answering `WorkspaceOrderValue {workspaceIds}`; declared in
 *    `dsh-api-workspace-controller/lib/types/types.d.ts:95-103` and
 *    `.../lib/types/commands.d.ts:28-33`, and mapped on the wire in
 *    `.../lib/typert.remote-client.d.ts` (`'workspace/insertBefore'`).
 *  * Its implementation is DOM-insertBefore-like: `dsh-workspace/lib/index.js:477-497`
 *    removes the id from the durable `workspaceIds` array, inserts it at the anchor's
 *    index (or appends when no anchor is given), writes the whole array and returns
 *    it. The array lives in the domain's stored state, not in a client preference
 *    (`dsh-workspace/lib/types/spec.d.ts:24-39`), so two devices see the same order and
 *    a reconnect's `baseline` cannot undo a move.
 *  * Every committed change publishes an `order` increment on `workspace/follow`
 *    (`dsh-api-workspace-controller/lib/index.js:99-113`), which is the stream this
 *    app already subscribes to.
 *
 * So there is no client-side order to invent here: a move is a request, and the
 * reply's `workspaceIds` is the order that is actually durable.
 *
 * ## Tree mode
 *
 * `workspace/follow` supplies one flat order. The Workspace Tree is derived from
 * *paths* ([workspaceForest]), so the only thing a move can change there is the order
 * of **siblings**: a child stays under the same registered ancestor because its parent
 * is a fact about its path, not about its seat in the list. "Move up"/"Move down" in
 * Tree mode are therefore scoped to the sibling list, and the first sibling has no
 * "up" and the last has no "down". In the other two modes every Workspace is a root,
 * so the same pair steps through the whole registry.
 *
 * Everything here is pure, so the rules are testable without a device.
 */

/** Which way one explicit move sends a Workspace. */
enum class WorkspaceMoveDirection {
    /** Towards the start of its sibling list. */
    Up,

    /** Towards the end of its sibling list. */
    Down,
}

/**
 * One DOM-insertBefore-like move.
 *
 * [beforeWorkspaceId] is the anchor the moved Workspace lands in front of; null means
 * "after everything", which is the host's own reading of an absent anchor.
 */
data class WorkspaceMove(
    val workspaceId: String,
    val beforeWorkspaceId: String?,
)

/**
 * The two moves a Workspace row offers, either of which may be unavailable.
 *
 * A missing side is a fact about the list, not a disabled control that was merely not
 * wired: the first sibling cannot move up and the last cannot move down, and the host
 * would answer a no-op for either.
 */
data class WorkspaceMoves(
    val up: WorkspaceMove?,
    val down: WorkspaceMove?,
)

/**
 * Work out every Workspace's up/down move for the current registry order.
 *
 * @param workspaces the registry in Host order.
 * @param siblingsOnly true in Workspace Tree mode, where a move is scoped to the
 *   Workspaces sharing one registered parent; false in the flat group modes, where
 *   every Workspace is a root and the whole list is one sibling list.
 * @return Workspace id to its available moves, for every registry row.
 */
fun workspaceMoves(
    workspaces: List<WorkspaceItem>,
    siblingsOnly: Boolean,
): Map<String, WorkspaceMoves> {
    if (workspaces.isEmpty()) return emptyMap()
    val parents: Map<String, String?> = if (siblingsOnly) workspaceParentIds(workspaces) else emptyMap()
    val seats = LinkedHashMap<String?, MutableList<Int>>()
    workspaces.forEachIndexed { index, workspace ->
        val parent = if (siblingsOnly) parents[workspace.id] else null
        seats.getOrPut(parent) { ArrayList() } += index
    }
    val moves = LinkedHashMap<String, WorkspaceMoves>(workspaces.size)
    for (indices in seats.values) {
        indices.forEachIndexed { seat, index ->
            val id = workspaces[index].id
            // Up lands the Workspace before its previous sibling; down lands it after
            // its next sibling, which is "before whatever follows that sibling". That
            // keeps a move to the smallest displacement the flat order allows, so the
            // list a reader sees in WorkSpace mode does not reshuffle more than the
            // one step they asked for. A next sibling at the end of the registry has
            // no follower, so the move appends, the host's own spelling of "last".
            val up = if (seat == 0) null else WorkspaceMove(id, workspaces[indices[seat - 1]].id)
            val down = if (seat == indices.lastIndex) {
                null
            } else {
                val next = indices[seat + 1]
                WorkspaceMove(id, workspaces.getOrNull(next + 1)?.id)
            }
            moves[id] = WorkspaceMoves(up = up, down = down)
        }
    }
    return moves
}

/** The move one direction would make, or null when that end of the list is reached. */
fun workspaceMoves(
    workspaces: List<WorkspaceItem>,
    siblingsOnly: Boolean,
    workspaceId: String,
    direction: WorkspaceMoveDirection,
): WorkspaceMove? {
    val moves = workspaceMoves(workspaces, siblingsOnly)[workspaceId] ?: return null
    return when (direction) {
        WorkspaceMoveDirection.Up -> moves.up
        WorkspaceMoveDirection.Down -> moves.down
    }
}

/**
 * Apply one move to a list, without touching the host.
 *
 * This is the optimistic half: the drawer shows the new order at once, and the unary
 * reply (or a pushed `order` frame) replaces it with the order the host actually
 * holds. An unknown source or anchor changes nothing, because a stale row must not be
 * moved against a list the host has already replaced; the caller reads an unchanged
 * list as "nothing to send".
 *
 * @param workspaces the order on screen.
 * @param move the requested move.
 * @return the reordered list, or [workspaces] unchanged when the move names something
 *   that is not in the list.
 */
fun applyWorkspaceMove(
    workspaces: List<WorkspaceItem>,
    move: WorkspaceMove,
): List<WorkspaceItem> {
    val source = workspaces.firstOrNull { it.id == move.workspaceId } ?: return workspaces
    val without = workspaces.filterNot { it.id == move.workspaceId }
    val anchor = move.beforeWorkspaceId
    val at = if (anchor == null) without.size else without.indexOfFirst { it.id == anchor }
    if (at < 0) return workspaces
    return ArrayList<WorkspaceItem>(workspaces.size).apply {
        addAll(without.subList(0, at))
        add(source)
        addAll(without.subList(at, without.size))
    }
}

/**
 * Install a complete host order over the rows this client holds.
 *
 * Ids the host names but this client has not received yet sort last until their
 * `upsert` arrives, and a row the order omits keeps its place at the end rather than
 * disappearing: the roster is the host's business, and a reply is not a roster.
 *
 * @param workspaces the rows on screen.
 * @param workspaceIds the complete Host order.
 * @return the same rows, ordered by [workspaceIds].
 */
fun applyWorkspaceOrder(
    workspaces: List<WorkspaceItem>,
    workspaceIds: List<String>,
): List<WorkspaceItem> {
    if (workspaceIds.isEmpty()) return workspaces
    val rank = workspaceIds.withIndex().associate { it.value to it.index }
    return workspaces.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
}

/**
 * What the drawer says when the host refuses a move.
 *
 * The order has already snapped back by the time this is drawn, so the sentence exists
 * to answer "why did that row jump back?" with the host's own reason rather than a
 * gesture that silently did nothing.
 *
 * @param title the Workspace's user-visible title.
 * @param reason the host's answer, as the ViewModel's `describe` spells it.
 */
fun workspaceMoveRefusal(title: String, reason: String): String =
    "Could not move “$title”: $reason"
