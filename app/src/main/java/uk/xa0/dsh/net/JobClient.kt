package uk.xa0.dsh.net

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

/**
 * The host's native `job` Remote namespace, as 0.2.0-rc.2 actually serves it.
 *
 * This is the replacement for the roster this app used to read out of
 * `session/control`: the running host's control baseline carries `projections`
 * only, so background jobs had nowhere to arrive from and stopped appearing at
 * all. Probed against the live host, the namespace is:
 *
 * | method       | carrier | args                                    |
 * |--------------|---------|-----------------------------------------|
 * | `job/list`   | mux     | `{"request":{"sessionId"}}`             |
 * | `job/follow` | mux     | `{"request":{"sessionId?","jobId","from?"}}` |
 * | `job/kill`   | unary   | `{"request":{"sessionId","jobId"}}`     |
 *
 * Two details are worth stating because they are *not* what the shipped
 * `typert.remote-client.js` says, and guessing either one wrong is silent:
 *
 *  * the parameter's wire name is **`request`** — `session/list` on the same host
 *    really does want `_request`, so the two must be read separately, not
 *    generalised;
 *  * both `list` and `follow` are **stream** methods, so a plain
 *    `POST /api/job/list` is refused with
 *    `gateway/signature-invalid: stream Remote methods must be opened through the
 *    stream carrier`.
 */
class JobClient(private val client: DshClient) {

    /**
     * `job/list` as a mux stream: the session's whole visible set, re-sent after
     * every lifecycle change. There is no polling anywhere in this feature — a job
     * that starts, stops or fails is a push, which is why the roster can be trusted
     * to be current while a panel is open.
     */
    fun list(sessionId: String): Flow<StreamEvent> =
        client.mux().openStream("job/list", args("request" to JSONObject().put("sessionId", sessionId)))

    /**
     * `job/follow` as a mux stream.
     *
     * [from] is the previous generation's `next` offset; the host answers with the
     * batch that *contains* that offset rather than starting exactly at it, so a
     * reconnect can repeat up to one chunk. That is the host's behaviour and the
     * web's model appends the same way, so it is mirrored rather than "fixed" here.
     */
    fun follow(sessionId: String?, jobId: String, from: Int?): Flow<StreamEvent> {
        val body = JSONObject().put("jobId", jobId)
        if (sessionId != null) body.put("sessionId", sessionId)
        if (from != null) body.put("from", from)
        return client.mux().openStream("job/follow", args("request" to body))
    }

    /**
     * `job/kill` — the human kill.
     *
     * The admission is all this returns ("requested" or "already-finished"); the row
     * itself converges through the roster stream, so the caller must not paint a
     * killed state on the strength of this answer alone.
     */
    suspend fun kill(sessionId: String, jobId: String) {
        val body = JSONObject().put("sessionId", sessionId).put("jobId", jobId)
        // Throws DshRpcException for `job/not-found`, which is a real answer: the
        // session's list no longer carries a killable row under that id.
        client.rpc("job/kill", args("request" to body))
    }

    private fun args(vararg pairs: Pair<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in pairs) json.put(key, value)
        return json
    }
}
