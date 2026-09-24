package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire vocabulary of `subagents/list`, the parent-scoped catalog of direct
 * subagent children.
 *
 * The same durable rows reach this client from the other end as the *parent's*
 * `subagentCatalog` projection on `session/list` / `session/control`. That
 * projection carries identity only (`id`, `createdAt`, `mode`, `label` —
 * `subagent/projection-types.ts`); this reply is the only source of the two
 * fields that describe the child's present life: `activity`, the host's own
 * sample of whether the child's Agent is running, and `hasChildren`, whether a
 * deeper level exists.
 *
 * It matters because `session/list` reports every subagent as not running — the
 * child runs inside its parent's Agent, so the host's list summary has nothing
 * to go on — which left this client inferring child activity from the live
 * status frames alone, where one missed frame stranded a child as forever idle
 * or forever running.
 */

/** `activity` for a child whose Agent is live right now. */
const val SUBAGENT_ACTIVITY_RUNNING = "running"

/** `activity` for a child that exists only in persistence. */
const val SUBAGENT_ACTIVITY_INACTIVE = "inactive"

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
        val activity: String,
        val hasChildren: Boolean,
    ) : SubagentCatalogEntry {
        val running: Boolean get() = activity == SUBAGENT_ACTIVITY_RUNNING
    }

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

/** One `subagents/list` reply: the direct children of one parent. */
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
 * Parses one `subagents/list` value.
 *
 * A row without an id is dropped rather than addressed by an empty string, and
 * an unrecognized `kind` is dropped rather than guessed at: the union is
 * open-ended on the host (`unsupported` is reserved, `reason` is expected to
 * grow), and inventing a child from an unknown row would put a fabricated
 * address on the wire.
 *
 * `activity` is compared against [SUBAGENT_ACTIVITY_RUNNING] rather than against
 * its opposite, so a row that omits the field — impossible in the declared
 * union, but not in a hand-rolled reply — cannot claim to be running.
 */
fun parseSubagentCatalog(value: JSONObject?): SubagentCatalog {
    if (value == null) return SubagentCatalog(emptyList(), null)
    val rows = value.arr("entries") ?: JSONArray()
    val entries = ArrayList<SubagentCatalogEntry>(rows.length())
    for (i in 0 until rows.length()) {
        val row = rows.optJSONObject(i) ?: continue
        val id = row.str("id")
        if (id.isEmpty()) continue
        when (row.str("kind")) {
            "child" -> entries += SubagentCatalogEntry.Child(
                id = id,
                mode = row.str("mode"),
                label = row.str("label").takeIf { it.isNotBlank() },
                activity = row.str("activity"),
                hasChildren = row.optBoolean("hasChildren"),
            )

            "diagnostic" -> entries += SubagentCatalogEntry.Diagnostic(id, row.str("reason"))
        }
    }
    return SubagentCatalog(entries, parseSubagentParentAvailable(value))
}
