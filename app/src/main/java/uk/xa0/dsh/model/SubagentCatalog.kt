package uk.xa0.dsh.model

import org.json.JSONArray

/**
 * The wire vocabulary of a parent's subagent catalog.
 *
 * A parent's children reach this client as the *parent's* `subagentCatalog`
 * projection on `session/list` / `session/control`: identity only (`id`,
 * `createdAt`, `mode`, `label` — `subagent/projection-types.ts`).
 *
 * **The catalog says nothing about whether a child is running, and must never be
 * read as if it did.** The projection has no activity field at all, and the
 * `subagents/list` reply that once carried one (`activity`) is not a host method
 * on 0.2.0 — it answered `http/404` on every session open. This file used to keep
 * both, and the client folded the activity-less projection into a running set
 * anyway: every rebuild stamped each child `inactive`, which wiped the row of a
 * child that was working. A child's activity has exactly one home now —
 * [RunningBook], fed by the roster's `subagentTiming` and by `api-session/status`
 * frames.
 */

/** One `entries[]` row. Mirrors the wire union in `subagent/control-types.ts`. */
sealed interface SubagentCatalogEntry {
    /** The durable child session id. */
    val id: String

    /**
     * A descriptor-backed child. `mode` decides the address form and, with
     * `one-shot`, the read-only composer; `label` is the durable creation label
     * (always present for a continuable child, optional for a terminal one).
     */
    data class Child(
        override val id: String,
        val mode: String,
        val label: String?,
        val hasChildren: Boolean,
    ) : SubagentCatalogEntry

    /**
     * A candidate the host could not describe: a settled child whose descriptor
     * fold served no identity, or one whose session was transiently unreadable.
     * It has no mode, so it is not addressable — carried so the reply is not
     * silently truncated, not rendered.
     */
    data class Diagnostic(
        override val id: String,
        val reason: String,
    ) : SubagentCatalogEntry
}

/** One parent's child catalog: its direct children, identity only. */
data class SubagentCatalog(
    val entries: List<SubagentCatalogEntry>,
    /**
     * `parentAvailable`, kept exactly as [parseSubagentParentAvailable] reads it:
     * null means *unknown*, which the read-only gate treats as available so the
     * composer cannot flicker into a frame it would have to take back.
     */
    val parentAvailable: Boolean?,
) {
    /** The descriptor-backed rows, in the host's own order. */
    val children: List<SubagentCatalogEntry.Child>
        get() = entries.filterIsInstance<SubagentCatalogEntry.Child>()

    /** One child row, or null when this catalog does not describe it. */
    fun child(id: String): SubagentCatalogEntry.Child? {
        for (entry in entries) {
            if (entry is SubagentCatalogEntry.Child && entry.id == id) return entry
        }
        return null
    }
}

/**
 * Parses the `subagentCatalog` session projection.
 *
 * 0.2.0 publishes a parent's children as a projection on the parent's own session
 * rather than answering `subagents/list`, which is not a host method any more and
 * answered `http/404` on every session open. A row carries an id, a creation time, a
 * mode and an optional label — and nothing about whether the child is working, which
 * is why no reader may infer activity from this value.
 *
 * `hasChildren` comes off the roster, because that is where the lineage actually is.
 *
 * `parentAvailable` has no equivalent here and stays null, which the read-only gate
 * treats as *available*: unknown must not be able to claim the parent is offline.
 */
fun parseSubagentCatalogProjection(
    rows: JSONArray?,
    hasChildren: (String) -> Boolean = { false },
): SubagentCatalog {
    if (rows == null) return SubagentCatalog(emptyList(), null)
    val entries = ArrayList<SubagentCatalogEntry>(rows.length())
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val id = row.str("id")
        if (id.isEmpty()) continue
        entries += SubagentCatalogEntry.Child(
            id = id,
            mode = row.str("mode"),
            label = row.str("label").takeIf { it.isNotBlank() },
            hasChildren = hasChildren(id),
        )
    }
    return SubagentCatalog(entries, null)
}
