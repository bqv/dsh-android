package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * One tool call plus the activity nested under it, mirroring the web's
 * `ToolCallTree.tsx` (`packages/client/ui-tool`).
 *
 * The tree exists because a `run_code` (PTC) program can dispatch other tools
 * from inside itself: those sub-calls are separate durable events, not part of
 * the parent's arguments, so the web hangs them off the parent node instead of
 * drawing them as siblings. A call with no [children] renders exactly the flat
 * row it always did.
 */
data class ToolCallNode(
    val entry: ChatEntry.ToolCall,
    val children: List<ToolCallNode> = emptyList(),
) {
    /** Directly nested calls; the disclosure toggles one level at a time. */
    val childCount: Int get() = children.size

    /** Every call anywhere below this one — what the collapsed row counts. */
    val descendantCount: Int get() = children.sumOf { 1 + it.descendantCount }
}

/**
 * Rebuilds the call forest from the flat, seq-ordered tool rows and the wire's
 * parent links.
 *
 * The links come from `tool/ptc-dispatch-start` / `tool/ptc-dispatch`
 * (`subCallId` → `parentCallId`); [ToolCallParents] collects them. Everything
 * else about the tree is derived here rather than stored, so a partially paged
 * window cannot produce a dangling child.
 *
 * A link is rejected when it would place a call under itself, under a
 * descendant (a cycle), under an unknown call id, deeper than the web's
 * `MAX_DEPTH`, or when the call already has an accepted parent. Every rejected
 * link degrades to the flat rendering instead of dropping the row.
 *
 * @param calls the reducer's tool rows, in seq order
 * @param parents call id → its parent's call id, from [ToolCallParents]
 */
fun buildToolCallTree(
    calls: List<ChatEntry.ToolCall>,
    parents: Map<String, String> = emptyMap(),
): List<ToolCallNode> {
    if (calls.isEmpty()) return emptyList()

    // First row wins for a repeated call id: a re-appended call is the same
    // call, and two nodes would give the tree two homes for one child.
    val byCallId = LinkedHashMap<String, ChatEntry.ToolCall>(calls.size)
    for (call in calls) {
        if (call.callId.isEmpty()) continue
        byCallId.putIfAbsent(call.callId, call)
    }

    val childrenOf = LinkedHashMap<String, MutableList<ChatEntry.ToolCall>>()
    val parentOf = HashMap<String, String>(byCallId.size)
    for (call in byCallId.values) {
        val parent = parents[call.callId] ?: continue
        if (!byCallId.containsKey(parent) || !acceptEdge(parentOf, call.callId, parent)) continue
        parentOf[call.callId] = parent
        childrenOf.getOrPut(parent) { ArrayList() } += call
    }

    fun node(call: ChatEntry.ToolCall): ToolCallNode =
        ToolCallNode(call, childrenOf[call.callId].orEmpty().map(::node))

    return byCallId.values
        .filter { it.callId !in parentOf }
        .map(::node)
}

/**
 * The web's `acceptsEdge` (`conversation-nodes/tool.ts`): the parent chain must
 * terminate, must not pass through the child, and the subtree must not exceed
 * `MAX_DEPTH`. `parentOf` is mutated only by the caller, after this returns true.
 */
private fun acceptEdge(parentOf: Map<String, String>, child: String, parent: String): Boolean {
    if (parent == child) return false
    if (parentOf.containsKey(child)) return false
    var cursor: String? = parent
    var depth = 1
    val seen = HashSet<String>()
    while (cursor != null) {
        if (cursor == child) return false
        if (!seen.add(cursor)) return false
        depth += 1
        if (depth > MAX_TOOL_TREE_DEPTH) return false
        cursor = parentOf[cursor]
    }
    return true
}

/** Web `MAX_DEPTH` (`conversation-nodes/tool.ts`). */
private const val MAX_TOOL_TREE_DEPTH = 256

/**
 * Accumulates the wire's PTC parent links as session events stream past.
 *
 * The links are log-only events that never enter the model's context, so they
 * are the *only* source of nesting: `tool/call` and `tool/result` carry no
 * parent id. A start and its settle repeat the same link, so the first one wins
 * and the map does not churn on every settle.
 *
 * This is a collector rather than a parse-from-list because links can arrive for
 * a call whose own row is still outside the loaded window; [buildToolCallTree]
 * then simply has nothing to attach, and the link attaches once the row pages in.
 */
class ToolCallParents {
    private val links = LinkedHashMap<String, String>()

    /** Reads one event; any other type is ignored. */
    fun record(event: JSONObject) {
        val type = event.optString("type")
        if (type != "tool/ptc-dispatch-start" && type != "tool/ptc-dispatch") return
        val data = event.optJSONObject("data") ?: return
        val subCallId = data.optString("subCallId")
        val parentCallId = data.optString("parentCallId")
        if (subCallId.isEmpty() || parentCallId.isEmpty()) return
        links.putIfAbsent(subCallId, parentCallId)
    }

    fun links(): Map<String, String> = links

    fun clear() = links.clear()
}
