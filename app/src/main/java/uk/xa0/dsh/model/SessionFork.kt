package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * The `session/fork` request body.
 *
 * [atSeq] is the **inclusive** event seq the child inherits through, and it is
 * omitted — never sent as null — when there is no boundary to name: the host's
 * schema is `atSeq?: number`, and an omitted boundary is exactly what selects the
 * session's latest completed-turn prefix. The host refuses a boundary that names no
 * event (`session/fork-unavailable`), but accepts one that names the *wrong* event
 * without a word, so the value passed here is always a seq this client has read off
 * the wire — [TranscriptReducer.closingBranchSeqs] is the only producer.
 *
 * Kept out of the ViewModel so the wire shape is pinned by a test that needs no
 * socket, the same reason [SessionTargets] holds the session-start decisions.
 */
fun sessionForkRequest(sessionId: String, atSeq: Int? = null): JSONObject =
    JSONObject()
        .put("sessionId", sessionId)
        .apply { if (atSeq != null) put("atSeq", atSeq) }
