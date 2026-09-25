package uk.xa0.dsh.net

import kotlinx.coroutines.flow.Flow
import org.json.JSONObject
import uk.xa0.dsh.term.TerminalEnvironmentInfo
import uk.xa0.dsh.term.TerminalFrame
import uk.xa0.dsh.term.TerminalInfo
import uk.xa0.dsh.term.TerminalShellInfo
import uk.xa0.dsh.term.TerminalState

/**
 * The host's native `terminal` Remote namespace.
 *
 * Unary methods ride [DshClient.rpc]; `follow` rides [RemoteMux.openStream] like
 * every other logical stream in this app. `RemoteMux` needed no change for it: it
 * forwards item values as opaque `JSONObject`s and replays the registration after a
 * reconnect, which is exactly what `terminal/follow` requires — its frames
 * (`snapshot`/`output`/`state`) do not collide with the `ready`/`waterfall`/`cancel`
 * values the mux inspects.
 *
 * Argument keys are the generated descriptor's wire names, not the doc-comment
 * parameter names: the session handle is **`agentId`** although the host's
 * `@Remote` method calls it `agent`. A wrong key is rejected outright with
 * `gateway/arguments-invalid`, which is a confusing way to lose an afternoon.
 */
class TerminalClient(private val client: DshClient) {

    /**
     * `terminal/environment` — the session workspace and the grid limits.
     *
     * `maxCols`/`maxRows` are what the panel clamps its measurement to; the host
     * refuses anything larger instead of clipping it.
     */
    suspend fun environment(agentId: String): TerminalEnvironmentInfo {
        val value = client.rpc("terminal/environment", args("agentId" to agentId))
        return TerminalEnvironmentInfo(
            cwd = value.optString("cwd"),
            maxInputBytes = value.optInt("maxInputBytes", 64 * 1024),
            maxCols = value.optInt("maxCols", 500),
            maxRows = value.optInt("maxRows", 200),
            scrollback = value.optInt("scrollback", 1000),
        )
    }

    /** `terminal/shells` — the profiles present in the session's execution environment. */
    suspend fun shells(agentId: String): List<TerminalShellInfo> {
        val value = client.rpcRaw("terminal/shells", args("agentId" to agentId))
        val array = value as? org.json.JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val shell = array.optJSONObject(index) ?: return@mapNotNull null
            TerminalShellInfo(
                path = shell.optString("path"),
                args = shell.optJSONArray("args")?.let { list ->
                    (0 until list.length()).map { list.optString(it) }
                } ?: emptyList(),
                name = shell.optString("name"),
            )
        }
    }

    /**
     * `terminal/list` — the terminals retained for this session.
     *
     * Takes the *displayed* session id, not a live agent: listing is also how the
     * app finds a terminal to reuse, including for a session whose agent is not
     * live. Creation is the call that needs the agent.
     */
    suspend fun list(sessionId: String): List<TerminalInfo> {
        val value = client.rpcRaw("terminal/list", args("sessionId" to sessionId))
        val array = value as? org.json.JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { index -> array.optJSONObject(index)?.let(::infoOf) }
    }

    /**
     * `terminal/create` — idempotent for an identity that is still open.
     *
     * The caller picks the id, so a tab that reconnects mid-allocation adopts the
     * same terminal instead of racing a second shell into existence.
     */
    suspend fun create(
        agentId: String,
        id: String,
        columns: Int,
        rows: Int,
        shellPath: String?,
    ): TerminalInfo {
        val request = JSONObject()
            .put("id", id)
            .put("cols", columns)
            .put("rows", rows)
        if (shellPath != null) request.put("shellPath", shellPath)
        return infoOf(client.rpc("terminal/create", args("agentId" to agentId, "request" to request)))
    }

    suspend fun write(agentId: String, id: String, attachmentId: String, data: String) {
        client.rpcRaw("terminal/write", args(
            "agentId" to agentId, "id" to id, "attachmentId" to attachmentId, "data" to data,
        ))
    }

    suspend fun resize(agentId: String, id: String, attachmentId: String, columns: Int, rows: Int) {
        client.rpcRaw("terminal/resize", args(
            "agentId" to agentId, "id" to id, "attachmentId" to attachmentId,
            "cols" to columns, "rows" to rows,
        ))
    }

    suspend fun rename(agentId: String, id: String, title: String) {
        client.rpcRaw("terminal/rename", args("agentId" to agentId, "id" to id, "title" to title))
    }

    /** `terminal/close` — the only thing that kills the shell; detaching never does. */
    suspend fun close(agentId: String, id: String) {
        client.rpcRaw("terminal/close", args("agentId" to agentId, "id" to id))
    }

    /**
     * `terminal/follow` as a mux stream.
     *
     * A *fresh* `attachmentId` per stream generation is the caller's job, and it is
     * not optional: the newest attachment owns input, so reusing an id after a
     * reconnect would leave the panel convinced it can still type.
     */
    fun follow(agentId: String, id: String, attachmentId: String): Flow<StreamEvent> =
        client.mux().openStream("terminal/follow", args(
            "agentId" to agentId, "id" to id, "attachmentId" to attachmentId,
        ))

    private fun args(vararg pairs: Pair<String, Any?>): JSONObject {
        val json = JSONObject()
        for ((key, value) in pairs) json.put(key, value)
        return json
    }

    companion object {

        private fun infoOf(json: JSONObject): TerminalInfo = TerminalInfo(
            id = json.optString("id"),
            title = json.optString("title"),
            shell = json.optJSONObject("shell")?.optString("path").orEmpty(),
            cwd = json.optString("cwd"),
            cols = json.optInt("cols", 80),
            rows = json.optInt("rows", 24),
            state = when (json.optString("state")) {
                "running" -> TerminalState.RUNNING
                "exited" -> TerminalState.EXITED
                "failed" -> TerminalState.FAILED
                else -> TerminalState.UNKNOWN
            },
            exitCode = if (json.isNull("exitCode")) null else json.optInt("exitCode"),
            error = json.optString("error").takeIf { it.isNotEmpty() },
            controllerId = json.optString("controllerId").takeIf { it.isNotEmpty() },
        )

        /**
         * Decodes one mux item value.
         *
         * `null` for a frame type this build does not know, so a host that adds a
         * fourth frame kind degrades to "ignore it" instead of breaking the stream.
         */
        fun frameOf(value: JSONObject): TerminalFrame? = when (value.optString("type")) {
            "snapshot" -> TerminalFrame.Snapshot(
                sequence = value.optLong("sequence"),
                screen = value.optString("screen"),
                info = infoOf(value.optJSONObject("info") ?: JSONObject()),
            )
            "output" -> TerminalFrame.Output(
                sequence = value.optLong("sequence"),
                data = value.optString("data"),
            )
            "state" -> TerminalFrame.State(infoOf(value.optJSONObject("info") ?: JSONObject()))
            else -> null
        }
    }
}
