package uk.xa0.dsh.model

import uk.xa0.dsh.SessionItem

/**
 * Every subagent at or below [parentId], including [parentId] itself.
 *
 * Used to answer "is the reader looking at this" for notifications: opening a session
 * is how you look at the work its subagents did, so their alerts are settled by the
 * same visit. A parent and its children are one thing the reader attends to, even
 * though the host models them as separate sessions.
 *
 * Breadth first and cycle-safe: a roster that names a parent twice, or a lineage that
 * somehow loops, terminates rather than spinning.
 */
fun subagentTreeIds(sessions: List<SessionItem>, parentId: String): Set<String> {
    val children = sessions
        .filter { it.isSubagent && it.parentSessionId != null }
        .groupBy { it.parentSessionId }
    val out = LinkedHashSet<String>()
    out += parentId
    val queue = ArrayDeque<String>()
    queue += parentId
    while (queue.isNotEmpty()) {
        for (child in children[queue.removeFirst()].orEmpty()) {
            if (out.add(child.id)) queue += child.id
        }
    }
    return out
}
