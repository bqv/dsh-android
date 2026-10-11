package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import uk.xa0.dsh.QueueAttachment
import uk.xa0.dsh.QueuedMessage

/**
 * The web client's queue-preview cap (`QUEUE_PREVIEW_CHARS` in
 * `ui-conversation`'s queue mirror). Shared by the reader below and by the local
 * submission echo, so a row's preview cannot depend on which shape it came from.
 */
const val QUEUE_PREVIEW_CHARS = 200

/**
 * The pending queue as the host actually publishes it, restated as the item shape
 * [parseQueueItems] reads.
 *
 * 0.2.0 does not put the queue on the control stream: its baseline carries
 * `projections` and nothing else, and there is no `queue` frame anywhere in the
 * host. The pending queue is the **`inbox` session projection**
 * (`@deepseek-ai/dsh-agent-loop`'s `inboxProjectionDefinition`, key `inbox`),
 * whose value is `{"next-turn": Message[], "next-step": Message[]}`. The host's
 * own web client reads exactly that key (`dsh-client-ui-conversation`:
 * `useProjection("inbox")`, `inbox?.["next-turn"] ?? EMPTY_QUEUE`).
 *
 * Two shape differences from the old control-stream item, both handled here: the
 * content is the message's own top-level field rather than nested under `message`,
 * and the request id sits on the message's `source` rather than on the item.
 * `next-step` is deliberately not read: the web dock offers `next-turn` only, and
 * a row this client offered but the web did not would be a queue entry the host
 * never put in front of the reader.
 */
fun inboxQueueItems(inbox: JSONObject?): JSONArray {
    val items = JSONArray()
    val next = inbox?.optJSONArray("next-turn") ?: return items
    for (i in 0 until next.length()) {
        val message = next.optJSONObject(i) ?: continue
        val item = JSONObject()
            .put("id", message.str("id"))
            .put("placement", "queued")
            .put(
                "message",
                JSONObject().put("content", message.optJSONArray("content") ?: JSONArray()),
            )
        val rpcId = message.obj("source")?.takeIf { it.str("kind") == "user" }?.str("rpcId")
        if (!rpcId.isNullOrEmpty()) item.put("rpcId", rpcId)
        items.put(item)
    }
    return items
}

/**
 * One queue row per wire item, in the shape the dock consumes.
 *
 * Both client-side derivations are ported verbatim from the host's client store
 * (`queue-mirror.ts`), because the dock's behaviour depends on them: the preview
 * is the *non-attachment* content and `text` is null the moment any block is not
 * text, which is exactly when the web disables Edit.
 */
fun parseQueueItems(items: JSONArray?): List<QueuedMessage> {
    if (items == null) return emptyList()
    val result = ArrayList<QueuedMessage>(items.length())
    for (i in 0 until items.length()) {
        val item = items.optJSONObject(i) ?: continue
        val content = item.obj("message").arr("content") ?: JSONArray()
        val attachments = ArrayList<QueueAttachment>()
        val flat = StringBuilder()
        var allText = true
        for (j in 0 until content.length()) {
            val block = content.optJSONObject(j) ?: continue
            when (val type = block.str("type")) {
                "text" -> {
                    if (flat.isNotEmpty()) flat.append(' ')
                    flat.append(block.str("text"))
                }

                "image", "file" -> {
                    allText = false
                    val ref = block.obj("attachment")
                    attachments += QueueAttachment(
                        kind = type,
                        name = ref.str("name").ifEmpty { if (type == "image") "Image" else "File" },
                        bytes = ref.int("bytes"),
                    )
                }

                else -> {
                    allText = false
                    if (flat.isNotEmpty()) flat.append(' ')
                    flat.append("[$type]")
                }
            }
        }
        val collapsed = flat.toString().replace(Regex("\\s+"), " ").trim()
        val preview = if (collapsed.length > QUEUE_PREVIEW_CHARS) {
            collapsed.take(QUEUE_PREVIEW_CHARS) + "…"
        } else {
            collapsed
        }
        result += QueuedMessage(
            id = item.str("id"),
            placement = item.str("placement").ifEmpty { "queued" },
            preview = preview,
            // `textOf` joins with NO separator, unlike the preview.
            text = if (allText) content.let { blocks ->
                (0 until blocks.length()).joinToString("") { blocks.optJSONObject(it)?.str("text").orEmpty() }
            } else {
                null
            },
            attachments = attachments,
            // The item's `rpcId` is the only identity shared with a local
            // submission echo; see [uk.xa0.dsh.QueuedMessage.rpcId].
            rpcId = item.str("rpcId").takeIf { it.isNotEmpty() },
        )
    }
    return result
}
