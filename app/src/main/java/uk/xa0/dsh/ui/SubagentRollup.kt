package uk.xa0.dsh.ui

import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.model.SubagentCatalog

/**
 * How many subagents hang off one session, and how many of them are running.
 *
 * This is the app's port of the web's `indexSubagentDescendants`
 * (`ui-workspace/src/client/subagent-lineage.ts` and, identically,
 * `ui-subagent/src/client/subagent-lineage.ts` — the two packages project their own
 * copies of the same walk).
 *
 * Two surfaces consume it, which is why it lives on its own rather than inside
 * either of them:
 *  - the sidebar row, whose chase dot means the session's *own* turn and whose
 *    descendant report is [running] drawn as a count beside that dot, and
 *  - the session header's lineage chip, which shows the [total] with a running
 *    variant, exactly like `SubagentHeaderLineage`.
 */
data class SubagentRollup(val total: Int, val running: Int) {
    val isEmpty: Boolean get() = total == 0

    /**
     * The compact label the header chip draws.
     *
     * Running agents only. The chip is a live-activity indicator, not an inventory:
     * a session with 37 descendants of which one is working wants to say `+1
     * running`, and the full tree is the drawer's job (the per-session caret there
     * shows every descendant, archived included). Reporting the total here also made
     * the number look alarming and never move as work finished.
     *
     * Terse on purpose - `'{count} subagents running'` is 20 characters and the
     * header has a title, a directory, a files seat and a jobs seat to fit beside
     * it. Liveness is carried by the accent tint and the dot; [description] keeps
     * the web's full sentence for the accessibility label.
     */
    val label: String
        get() = if (running == 1) "+1 running" else "+$running running"

    /** The web's own sentence (`ui-subagent/src/client/locales.ts:76-79`). */
    val description: String
        get() = when {
            running > 0 -> if (running == 1) "1 subagent running" else "$running subagents running"
            total == 1 -> "1 subagent"
            else -> "$total subagents"
        }

    /**
     * The sidebar row's copy. `ui-workspace/src/client/locales.ts:124-125`
     * spells it `'{n} subagent running'` / `'{n} subagents running'`; empty when
     * nothing is running, because the row only marks live activity.
     */
    val runningLabel: String?
        get() = if (running == 0) null else if (running == 1) "1 subagent running" else "$running subagents running"
}

/**
 * Index every session's subagent descendants, walking each subagent up through its
 * parents.
 *
 * The web counts a subagent once per ancestor on its path, so a nested subagent
 * inflates every ancestor's total, not just its immediate parent's — and a subagent
 * parent is itself an ancestor. The `seen` set is the web's own cycle guard; a
 * pathological lineage stops rather than spinning.
 *
 * [sessions] is the flat `session/list` roster, which is what makes this possible
 * at all: subagent records are in it with `parentSessionId` set.
 *
 * [catalogs] are the `subagents/list` reads this client happens to hold, keyed by
 * the parent they describe. Each one floors its parent's *total* at the number of
 * direct children the host itself reports — the web's `Math.max(healthy.length,
 * descendants.count)` on the trigger's own label (`SubagentHeaderLineage.tsx`),
 * which exists because a membership frame can outrun the list snapshot. Only the
 * total is floored: the roster stays the authority for activity, exactly as the
 * web's running count is the summary walk's, and for what can be drawn.
 *
 * Callers that gate a *disclosure* on the rollup rather than print it must
 * therefore pass no catalogs — the tree they expand is the roster, so a floored
 * count there would offer a caret that opens onto nothing.
 */
fun indexSubagentRollups(
    sessions: List<SessionItem>,
    catalogs: Map<String, SubagentCatalog> = emptyMap(),
): Map<String, SubagentRollup> {
    val byId = HashMap<String, SessionItem>(sessions.size)
    for (session in sessions) byId[session.id] = session

    val totals = HashMap<String, Int>()
    val running = HashMap<String, Int>()

    for (descendant in sessions) {
        if (!descendant.isSubagent) continue
        val seen = HashSet<String>()
        var parentId = descendant.parentSessionId
        while (parentId != null) {
            // `seen.add` returning false means this ancestor was already credited
            // for this descendant, which is the web's cycle guard.
            if (!seen.add(parentId)) break
            totals[parentId] = (totals[parentId] ?: 0) + 1
            if (descendant.running) running[parentId] = (running[parentId] ?: 0) + 1
            val parent = byId[parentId] ?: break
            // Only a subagent continues the chain; a top-level session ends it.
            if (!parent.isSubagent) break
            parentId = parent.parentSessionId
        }
    }

    // Diagnostics are excluded, as in the web's `healthy` filter: a row the host
    // could not describe is not evidence of a child.
    for ((parentId, catalog) in catalogs) {
        val known = catalog.children.size
        if (known > (totals[parentId] ?: 0)) totals[parentId] = known
    }

    if (totals.isEmpty()) return emptyMap()
    val out = HashMap<String, SubagentRollup>(totals.size)
    for ((id, total) in totals) out[id] = SubagentRollup(total, running[id] ?: 0)
    return out
}

/**
 * The subagent sessions directly under [parentId], newest first — the list the
 * header's lineage chip opens.
 *
 * Direct children only: the web's dropdown is a tree whose branches expand, and
 * this list is its first level, with each child's own rollup telling the reader
 * whether there is more underneath.
 *
 * Roster-only, deliberately, which is why a catalog's `hasChildren` never
 * fabricates a row or a disclosure here. The web can honour that flag because it
 * renders catalog rows directly, standing in for the summaries it has not
 * received; this client's tree is the roster, so a child the roster has not
 * delivered has nothing to open and a caret for it would be dead until the next
 * pull.
 */
fun subagentChildrenOf(sessions: List<SessionItem>, parentId: String): List<SessionItem> =
    sessions.filter { it.parentSessionId == parentId && it.isSubagent }
        .sortedByDescending { it.updatedAt }

/**
 * The session a subagent was spawned by — the way *up*, where [subagentChildrenOf]
 * is the way down.
 *
 * The field is the roster's own `parentSessionId`, which is exactly what
 * [indexSubagentRollups] already walks upward to credit ancestors. So the walk is
 * not new; what was missing is that nothing let a reader *follow* it, and a nested
 * subagent was a one-way trip: back out to the drawer and find the parent by hand.
 *
 * Null covers every way "up" can be absent, and the caller draws nothing when it is:
 * the session is not a subagent, the host sent no parent id, the id is the session
 * itself (a malformed roster must not turn a tap into a no-op that looks broken), or
 * the parent is not in the roster at all. That last one is deliberate rather than a
 * fallback: the roster is where a parent's *title* comes from, so a parent missing
 * from it could only be opened blind — and the drawer, which lists archived sessions
 * too, is the honest way to reach something this client cannot name.
 */
data class SubagentParent(val sessionId: String, val title: String)

fun subagentParentOf(sessions: List<SessionItem>, sessionId: String?): SubagentParent? {
    if (sessionId.isNullOrEmpty()) return null
    val child = sessions.firstOrNull { it.id == sessionId } ?: return null
    if (!child.isSubagent) return null
    val parentId = child.parentSessionId?.takeIf { it.isNotEmpty() } ?: return null
    if (parentId == sessionId) return null
    val parent = sessions.firstOrNull { it.id == parentId } ?: return null
    return SubagentParent(parentId, parent.title.ifBlank { parent.id })
}
