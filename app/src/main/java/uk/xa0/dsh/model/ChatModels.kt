package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * Null-safe readers for the loosely-typed JSON coming off the DSH wire.
 *
 * These are top-level (not members of an object) on purpose: a member extension
 * function can only be called with its dispatch receiver in scope, so the
 * `Json.str(...)` form would not resolve at any call site.
 */
fun JSONObject?.str(key: String): String {
    val source = this ?: return ""
    // An explicit JSON null must read as empty. org.json's optString returns the
    // *string* "null" for JSONObject.NULL, which leaked straight into the UI as
    // session rows literally titled "null".
    if (source.isNull(key)) return ""
    return source.optString(key).orEmpty()
}

fun JSONObject?.obj(key: String): JSONObject? = this?.optJSONObject(key)

fun JSONObject?.arr(key: String): JSONArray? = this?.optJSONArray(key)

fun JSONObject?.int(key: String, fallback: Int = 0): Int = this?.optInt(key, fallback) ?: fallback

fun JSONObject?.long(key: String, fallback: Long = 0L): Long = this?.optLong(key, fallback) ?: fallback

fun JSONObject?.bool(key: String, fallback: Boolean = false): Boolean =
    this?.optBoolean(key, fallback) ?: fallback

/** One rendered row in the conversation. */
sealed interface ChatEntry {
    val seq: Int

    data class UserMessage(
        override val seq: Int,
        val id: String,
        val text: String,
        val time: Long,
        /** Notices injected by plugins/jobs rather than typed by the human. */
        val fromPlugin: Boolean,
        val summary: String?,
        /**
         * File/image blocks the message carried. They used to be dropped, which
         * made an attached screenshot look like a message with no attachment at
         * all — the bubble is the only place a user message shows its own
         * attachments.
         */
        val attachments: List<MessageAttachment> = emptyList(),
        /**
         * The prompt RPC this message answers, when this client sent it. Used to
         * retire the optimistic row once the durable echo lands.
         */
        val rpcId: String? = null,
        /** Sent but not yet durable: rendered dimmed with a "Sending…" status. */
        val pending: Boolean = false,
    ) : ChatEntry

    data class AssistantMessage(
        override val seq: Int,
        val turn: Int,
        val step: Int,
        val reasoning: String,
        val text: String,
        val time: Long,
    ) : ChatEntry

    data class ToolCall(
        override val seq: Int,
        val callId: String,
        val name: String,
        val arguments: String,
        /** Null until the matching tool/result arrives. */
        val result: String?,
        val isError: Boolean,
        val time: Long,
        /** Owning turn, used to fold a finished turn's process behind a summary. */
        val turn: Int = 0,
        /**
         * The host's structured error code when the call failed (`ASK_CANCELLED`,
         * `ASK_ABORTED`, …). A tool result's prose is not a contract; the code is,
         * and the UI's verdicts key off it.
         */
        val errorCode: String? = null,
        /**
         * The result's structured side-channel (`data.meta`), which is where the
         * host puts what the prose only describes: the read window (`lines`,
         * `offset`, `totalLines`), a search's `files`/`shape`/`total`, and an
         * edit's `diffs`. Null when the call has not answered yet.
         */
        val meta: JSONObject? = null,
        /**
         * Image blocks the tool returned, if any. A tool that answers with a
         * picture (`read` on an image) sends image content rather than text, so
         * this is the only handle the row has for drawing it.
         */
        val images: List<MessageAttachment> = emptyList(),
    ) : ChatEntry

    data class Todos(
        override val seq: Int,
        val items: List<TodoItem>,
    ) : ChatEntry

    data class Notice(
        override val seq: Int,
        val kind: String,
        val text: String,
        val time: Long,
        val severity: NoticeSeverity = NoticeSeverity.INFO,
        /** Body shown only when the row is expanded; null when there is nothing more. */
        val detail: String? = null,
    ) : ChatEntry
}

/** Info notices are inline tags (context compaction); errors get the warning card. */
enum class NoticeSeverity { INFO, ERROR }

/**
 * One attachment block on a message. Images carry a media type rather than a
 * name, and files carry a name and size; both are addressed by `attachmentId`
 * for the `session/attachment` read that fetches the bytes.
 */
data class MessageAttachment(
    val kind: String,
    val attachmentId: String,
    val name: String,
    val bytes: Int,
    val mediaType: String?,
    /**
     * Base64 bytes carried on the row itself, for a picture that only exists on
     * this device so far.
     *
     * The optimistic "Sending…" row is built before the host has echoed the
     * message, so it has no [attachmentId] for `session/attachment` to resolve —
     * but staging already held the exact bytes inline. Keeping them here is what
     * lets that row draw the picture immediately instead of waiting on a round
     * trip that would return nothing.
     */
    val localData: String? = null,
)

data class TodoItem(val content: String, val status: String) {
    val isDone: Boolean get() = status == "completed"
    val isActive: Boolean get() = status == "in_progress"
}

/** A block inside the assistant attempt that is currently streaming. */
data class LiveBlock(
    val index: Int,
    val type: String,
    val reasoning: String = "",
    val text: String = "",
    val toolName: String? = null,
    val arguments: String = "",
)

/** The in-flight assistant attempt, rendered after the durable transcript. */
data class LiveAttempt(
    val attemptId: String,
    val turn: Int,
    val step: Int,
    val blocks: List<LiveBlock>,
    /** True once the host sent the terminal `end` frame; text stays, the cursor goes. */
    val finished: Boolean = false,
) {
    val reasoning: String get() = blocks.filter { it.type == "reasoning" }.joinToString("") { it.reasoning }
    val text: String get() = blocks.filter { it.type == "text" }.joinToString("") { it.text }
    val toolNames: List<String> get() = blocks.mapNotNull { it.toolName }
    val isEmpty: Boolean get() = reasoning.isBlank() && text.isBlank() && toolNames.isEmpty()
}

/** Header facts for the open session. */
data class SessionHeader(
    val id: String,
    val cwd: String?,
    val createdAt: Long,
    val agentPreset: String?,
    val parentSession: String?,
)
