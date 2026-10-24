package uk.xa0.dsh.model

import uk.xa0.dsh.WorkspaceItem

/**
 * The arithmetic behind dragging a Workspace header into a new seat.
 *
 * The gesture itself belongs to the drawer, but everything that decides *where* a
 * drop lands is here: which Workspaces may move against each other, which slot the
 * finger's midpoint crossings name, the order shown while the finger is down, and
 * the one `workspace/insertBefore` anchor that order commits.
 *
 * ## Siblings, not the whole registry
 *
 * Workspace Tree mode derives nesting from **paths** ([workspaceParentIds]), so a
 * drag may never re-parent anything: moving a Workspace changes the order of the
 * children under one registered parent, and nothing else. In the two flat modes
 * every Workspace is a root, so the whole registry is one sibling list. This is the
 * same scoping [workspaceMoves] applies to a "Move up"/"Move down" pair; a drag is
 * only a longer jump through the same list.
 *
 * ## The drop slot
 *
 * The drawer reports where each visible sibling header's midpoint is, in one
 * consistent coordinate space. A finger below a header's midpoint has not crossed
 * it; a finger above it has. That gives the slot between two siblings, and the
 * preview order is the siblings with the dragged one lifted out and put back at
 * that slot. Session rows are never consulted: only headers have a midpoint here,
 * so an expanded section's sessions cannot become a target.
 *
 * ## The anchor
 *
 * `workspace/insertBefore` names the Workspace the moved one lands **in front of**,
 * so the anchor is the sibling that follows the drop slot, or null when the slot is
 * after the last sibling, which is the host's own "append". Naming the following
 * sibling rather than the preceding one matters in Tree mode: the registry order may
 * interleave another parent's Workspaces between two siblings, and inserting in
 * front of the *next sibling* puts the moved one between the right two entries of
 * that sibling list without touching any other parent's children.
 *
 * Everything in this file is pure, so a JVM test can pin it without a device.
 */

/**
 * The Workspaces [workspaceId] may move against, in registry order.
 *
 * @param workspaces the registry in Host order.
 * @param siblingsOnly true in Workspace Tree mode, where path-derived nesting fixes
 *   each Workspace's parent; false in the flat modes, where every row is a root.
 * @param workspaceId the Workspace being dragged.
 * @return the sibling ids including [workspaceId], or an empty list when the id is
 *   not in the registry. One element means there is nothing to drag against.
 */
fun workspaceSiblingOrder(
    workspaces: List<WorkspaceItem>,
    siblingsOnly: Boolean,
    workspaceId: String,
): List<String> {
    if (workspaces.none { it.id == workspaceId }) return emptyList()
    if (!siblingsOnly) return workspaces.map { it.id }
    val parents = workspaceParentIds(workspaces)
    val parent = parents[workspaceId]
    return workspaces.filter { parents[it.id] == parent }.map { it.id }
}

/**
 * Splice a new sibling order back into the registry, leaving every other seat alone.
 *
 * The drawer draws the returned list while the finger is down, so a drag previews
 * exactly the sibling movement it would request. A non-sibling never changes seat:
 * re-parenting is impossible in Tree mode, and in the flat modes there are no
 * non-siblings to leave behind.
 *
 * @param workspaces the registry in Host order.
 * @param siblingOrder a permutation of a subset of [workspaces]' ids.
 * @return the same rows with the sibling seats filled from [siblingOrder], or
 *   [workspaces] unchanged when the order is not a permutation of rows that exist.
 */
fun applySiblingOrder(
    workspaces: List<WorkspaceItem>,
    siblingOrder: List<String>,
): List<WorkspaceItem> {
    if (siblingOrder.isEmpty()) return workspaces
    val byId = workspaces.associateBy { it.id }
    val ordered = siblingOrder.mapNotNull { byId[it] }
    if (ordered.size != siblingOrder.size) return workspaces
    val seats = workspaces.withIndex()
        .filter { it.value.id in siblingOrder }
        .map { it.index }
    if (seats.size != siblingOrder.size) return workspaces
    val result = workspaces.toMutableList()
    seats.forEachIndexed { position, seat -> result[seat] = ordered[position] }
    return result
}

/**
 * The slot a drag at [pointerY] names among the dragged Workspace's siblings.
 *
 * [centres] carries the midpoint of each *visible* sibling header, in whatever one
 * coordinate space the drawer measured them (window pixels, here). A header with no
 * entry is above or below the viewport, so it is simply not a crossing candidate;
 * the returned slot is still an index into [siblings] with the dragged id removed.
 *
 * The comparison is strict: a finger exactly on a midpoint has not crossed it, so
 * a stationary finger cannot make the preview oscillate around the boundary.
 *
 * @param siblings the preview order, including [draggedId].
 * @param draggedId the Workspace being dragged.
 * @param pointerY the finger's position on the same axis as [centres].
 * @param centres sibling id to the y coordinate of its header's midpoint.
 * @return the insertion index among the siblings other than [draggedId], from 0
 *   (before the first) to one past the last.
 */
fun workspaceDropSlot(
    siblings: List<String>,
    draggedId: String,
    pointerY: Float,
    centres: Map<String, Float>,
): Int {
    val others = siblings.filterNot { it == draggedId }
    others.forEachIndexed { index, id ->
        val centre = centres[id] ?: return@forEachIndexed
        if (pointerY < centre) return index
    }
    return others.size
}

/**
 * Lift [draggedId] out of [siblings] and put it back at [slot].
 *
 * [slot] is clamped to the list, so a caller that carries a slot across a changed
 * roster cannot drop the row out of the list. A non-member is returned unchanged.
 *
 * @param siblings the current sibling order, including [draggedId].
 * @param draggedId the Workspace being dragged.
 * @param slot an insertion index among the siblings other than [draggedId].
 * @return the reordered sibling ids.
 */
fun reorderWorkspaceSiblings(
    siblings: List<String>,
    draggedId: String,
    slot: Int,
): List<String> {
    if (draggedId !in siblings) return siblings
    val others = siblings.filterNot { it == draggedId }
    val at = slot.coerceIn(0, others.size)
    return ArrayList<String>(siblings.size).apply {
        addAll(others.subList(0, at))
        add(draggedId)
        addAll(others.subList(at, others.size))
    }
}

/**
 * The request a released drag sends, or null when there is nothing to send.
 *
 * A preview that is the registry's own order is a drag that ended where it began:
 * releasing it must not ask the host for a move that changes nothing. So must a
 * preview that is not a permutation of the current sibling list — a stale gesture
 * racing a registry push — and an only child, which has no other seat to take.
 *
 * @param workspaces the registry in Host order.
 * @param siblingsOnly true in Workspace Tree mode.
 * @param workspaceId the Workspace that was dragged.
 * @param previewSiblings the sibling order the drawer is showing.
 * @return the move to pass to `DshViewModel.moveWorkspace`, or null.
 */
fun workspaceDragMove(
    workspaces: List<WorkspaceItem>,
    siblingsOnly: Boolean,
    workspaceId: String,
    previewSiblings: List<String>,
): WorkspaceMove? {
    val start = workspaceSiblingOrder(workspaces, siblingsOnly, workspaceId)
    if (start.size < 2) return null
    if (previewSiblings.size != start.size) return null
    if (previewSiblings.toSet() != start.toSet()) return null
    if (previewSiblings == start) return null
    val seat = previewSiblings.indexOf(workspaceId)
    if (seat < 0) return null
    return WorkspaceMove(workspaceId, previewSiblings.getOrNull(seat + 1))
}
