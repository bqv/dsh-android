package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The wire vocabulary for *addressing* a continuable subagent.
 *
 * A prompt to a child is not `session/prompt`. The web routes every addressed
 * session through a different RPC (`api/session-controller/src/client/sessions/
 * session.ts`: `prompt` sends `subagents/prompt`, `cancel` sends
 * `subagents/interruptByParent`), and it is the durable parent address — not the
 * child's own session id — that authorizes the call. Keeping that routing here
 * leaves the ViewModel only the decision of *when* to use it, and makes the
 * read-only gate readable as the four `if`s the web's `selectReadOnlySubagent`
 * (`client/ui-subagent/src/client/index.ts`) is.
 */

/**
 * `subagents/prompt`'s required discriminator. The wire marker stays
 * `continuable` even for a one-shot address: the host reads the durable
 * descriptor itself, and a one-shot child fails with `subagent/not-resumable`.
 */
const val SUBAGENT_CONTINUABLE = "continuable"

/** The catalogue mode of a terminal delegated task, and the read-only reason. */
const val SUBAGENT_MODE_ONE_SHOT = "one-shot"

/** Host code for a prompt that carried a staged file to a child. */
const val SUBAGENT_ATTACHMENT_INVALID = "subagent/attachment-invalid"

/** `details.reason` on [SUBAGENT_ATTACHMENT_INVALID]. */
const val SUBAGENT_FILE_UNSUPPORTED = "SUBAGENT_FILE_UNSUPPORTED"

/**
 * The refusal's own copy, verbatim from the web, because the app shows the
 * host's message shape for this code: the failure a host-side rejection of the
 * same kind would produce.
 */
const val SUBAGENT_ATTACHMENT_REFUSAL = "subagent continuation does not accept files"

/**
 * One subagent session the app can address: its durable direct parent plus the
 * catalog mode the address form needs. `null` (see [subagentTargetOf]) for an
 * ordinary session, which keeps every pre-existing path untouched.
 */
data class SubagentTarget(
    val parentSessionId: String,
    val childSessionId: String,
    val mode: String,
)

/**
 * The addressed child one session row is, or null when it is not one.
 *
 * Both halves are required, exactly as in the app's own `sessionAddress`: a
 * child whose catalog mode has not landed cannot be addressed on the wire
 * either, so treating it as a subagent here would send its prompt to a route the
 * host cannot resolve.
 */
fun subagentTargetOf(sessionId: String, parentSessionId: String?, mode: String?): SubagentTarget? {
    if (parentSessionId.isNullOrEmpty() || mode.isNullOrEmpty()) return null
    return SubagentTarget(parentSessionId, sessionId, mode)
}

/**
 * `subagents/prompt`'s request for one addressed child.
 *
 * `delivery` is the app's ordinary send mode (`queue`/`steer`) — on the session
 * route the same value is called `mode`, which is why the two are easy to
 * confuse. [clientTimeZone] is omitted rather than sent empty: the host accepts
 * only `UTC` or an Area/Location name, and a device pinned to a fixed offset
 * reports "GMT+02:00" (the same guard the ordinary prompt needs).
 */
fun subagentPromptRequest(
    requestId: String,
    target: SubagentTarget,
    delivery: String,
    content: JSONArray,
    clientTimeZone: String? = null,
): JSONObject {
    val request = JSONObject()
        .put("requestId", requestId)
        .put("parentSessionId", target.parentSessionId)
        .put("childSessionId", target.childSessionId)
        .put("mode", SUBAGENT_CONTINUABLE)
        .put("delivery", delivery)
        .put("content", content)
    if (clientTimeZone != null) request.put("clientTimeZone", clientTimeZone)
    return request
}

/**
 * `subagents/interruptByParent`'s args. The two ids are flat parameters on the
 * host (probed live), not a nested `request`, and the mode is required.
 *
 * Its authority is the durable parent address, which is what keeps a live child
 * interruptible while its parent Agent is offline — exactly the state the
 * read-only gate cares about.
 */
fun subagentInterruptArgs(target: SubagentTarget): JSONObject = JSONObject()
    .put("childSessionId", target.childSessionId)
    .put("parentSessionId", target.parentSessionId)
    .put("mode", SUBAGENT_CONTINUABLE)

/**
 * `parentAvailable` from a `subagents/list` value, or null for "unknown".
 *
 * Unknown is the safe answer, not a default: the gate's third rule keeps the
 * normal composer whenever availability is not exactly `false`, so a host that
 * never sent the field — or a read that has not landed — must not be able to
 * claim the parent is offline.
 */
fun parseSubagentParentAvailable(value: JSONObject?): Boolean? {
    if (value == null || value.isNull("parentAvailable")) return null
    return value.optBoolean("parentAvailable")
}

/**
 * Whether any wire content part is a staged file.
 *
 * A child refuses file parts *client-side*, before the call (`session.ts`:
 * `content.some(part => part.type === 'file')`), so the part has to be caught
 * here rather than handed to a route that would drop it. Images are not files:
 * they travel inline and a continuation accepts them.
 */
fun hasFileContentPart(content: JSONArray): Boolean {
    for (i in 0 until content.length()) {
        if (content.optJSONObject(i)?.str("type") == "file") return true
    }
    return false
}

/**
 * Why an addressed child cannot take input, with the frame's copy verbatim from
 * `client/ui-subagent/src/client/locales.ts` (en). Both the title and the body
 * are shown, as a status frame.
 */
enum class SubagentReadOnlyReason(val title: String, val body: String) {
    /** The record of a terminal delegated task: reviewable, never resumable. */
    ONE_SHOT(
        title = "One-shot subagent record",
        body = "One-shot tasks do not accept follow-ups; review the full execution record here.",
    ),

    /** A continuable child whose parent Agent is gone for now. */
    PARENT_UNAVAILABLE(
        title = "This subagent is read-only for now",
        body = "The parent session is offline; reopen it to continue sending messages.",
    ),
}

/** What the composer's seat renders for the session on screen. */
sealed interface SubagentComposerState {
    /** The normal composer. */
    data object Normal : SubagentComposerState

    /** A frame explaining why there is no input, in the composer's place. */
    data class ReadOnly(val reason: SubagentReadOnlyReason) : SubagentComposerState
}

/**
 * The web's `selectReadOnlySubagent`, ported in its own order:
 *
 *  1. not an addressed child → the normal composer;
 *  2. `one-shot` → read-only, whatever the parent is doing (the address can
 *     never accept a follow-up);
 *  3. `parentAvailable !== false` → the normal composer, so *unknown*
 *     availability never flickers the composer into a frame it may have to take
 *     back;
 *  4. parent offline → read-only, EXCEPT while the child is still `running`:
 *     there the default composer's input is disabled anyway, but its Stop is
 *     the only way to interrupt the child, so the seat has to stay. Once the
 *     child stops, the takeover returns.
 */
fun subagentComposerState(
    target: SubagentTarget?,
    parentAvailable: Boolean?,
    running: Boolean,
): SubagentComposerState {
    if (target == null) return SubagentComposerState.Normal
    if (target.mode == SUBAGENT_MODE_ONE_SHOT) {
        return SubagentComposerState.ReadOnly(SubagentReadOnlyReason.ONE_SHOT)
    }
    if (parentAvailable != false) return SubagentComposerState.Normal
    if (running) return SubagentComposerState.Normal
    return SubagentComposerState.ReadOnly(SubagentReadOnlyReason.PARENT_UNAVAILABLE)
}
