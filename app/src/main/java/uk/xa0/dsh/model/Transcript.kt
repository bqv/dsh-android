package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.TreeMap

/**
 * Folds DSH's session journal into renderable rows.
 *
 * Two sources feed it, and they overlap on purpose:
 *  - *durable* events (`user/message`, `assistant/message`, `tool/call`,
 *    `tool/result`, `todo/write`) are the committed truth, and
 *  - *live* `assistant-stream` chunk frames are token deltas for the attempt in
 *    flight.
 *
 * The live attempt is kept until the durable `assistant/message` for the same
 * (turn, step) lands, so text never blinks out between "stream finished" and
 * "step committed".
 */
class TranscriptReducer {

    private val entries = TreeMap<Int, ChatEntry>()
    private val liveBlocks = TreeMap<Int, LiveBlock>()

    /**
     * Prompts this client has admitted but the host has not yet echoed durably,
     * keyed by the `rpcId` the host copies onto the message's source.
     *
     * A *steered* message is written to the journal only when the agent reaches a
     * step boundary and claims it from the next-step inbox — which can be many
     * minutes into a long tool call. Without this row the message appears to have
     * been swallowed: the composer clears, the dock shows nothing (steering rows
     * are not queued rows), and the transcript has no trace of it.
     */
    private val pending = LinkedHashMap<String, ChatEntry.UserMessage>()

    /** Synthetic keys for optimistic rows: above every real seq, so they sort last. */
    private var pendingSeq = Int.MAX_VALUE - 1024

    /** Turns the host has closed with `turn/end`; only these may fold. */
    private val endedTurns = mutableSetOf<Int>()

    /** `turn/start` / `turn/end` wall-clock times, for the "Ran for …" footer pill. */
    private val turnStarts = mutableMapOf<Int, Long>()
    private val turnEnds = mutableMapOf<Int, Long>()

    /**
     * Compaction attempts in flight, mapped to the seq of their
     * "Compacting context…" row.
     *
     * The web shows that row only for a *command-driven* compaction, and replaces
     * it with the summary marker once the summary lands — or with nothing at all
     * when the attempt aborts, because `compaction/end`'s error is deliberately
     * never surfaced.
     */
    private val compactionRows = mutableMapOf<String, Int>()

    /**
     * `compaction/summary` evidence, keyed by compaction id.
     *
     * The marker is anchored on the **checkpoint** — a replacement `user/message`
     * carrying `source.plugin = "compact"` — exactly as the web's
     * `compactionDefinition` does: it builds its node from the checkpoint and only
     * *enriches* it from the summary. The summary event is log-only, so it can sit
     * outside the loaded window while the checkpoint is inside it; anchoring on
     * the summary instead made such a compaction draw nothing at all, where the
     * web still shows "Compaction summary unavailable". The facts are buffered
     * here and folded in when the checkpoint lands.
     */
    private data class CompactionFacts(
        val items: Int?,
        val tokens: Int?,
        val summary: String?,
    )

    private val compactionFacts = mutableMapOf<String, CompactionFacts>()

    /** Marker seq and title per compaction id, so a replayed checkpoint moves rather than duplicates. */
    private data class CompactionMarker(val seq: Int, val title: String)

    private val compactionMarkers = mutableMapOf<String, CompactionMarker>()

    /** One tool result held until its call shows up; see [orphanResults]. */
    private data class OrphanResult(
        val text: String,
        val isError: Boolean,
        val errorCode: String?,
        val meta: JSONObject?,
        val images: List<MessageAttachment>,
    )

    /**
     * The seq of each retry chain's "waiting" row, so the row can be retired when
     * the attempt actually starts instead of leaving two rows for one retry.
     */
    private val retryRows = mutableMapOf<String, Int>()

    /**
     * Results that arrived before their call, keyed by `callId`.
     *
     * A `tool/result` with no matching call is not a row of its own: the web hangs
     * every result on its call node, so an orphan shows nothing. Inventing a row
     * for it produced a nameless "Tool ·" line in the transcript.
     */
    private val orphanResults = mutableMapOf<String, OrphanResult>()

    /**
     * PTC parent links, which are the only source of tool-call nesting — see
     * [ToolCallParents]. Collected as events stream past rather than parsed out of
     * the kept rows, because the dispatch record can arrive for a sub-call whose
     * own row is still outside the loaded window.
     */
    private val ptcParents = ToolCallParents()

    private var header: SessionHeader? = null
    private var live: LiveAttempt? = null
    private var cursor: Int = 0
    private var hasMore: Boolean = false

    /**
     * The newest turn *any* event named, maintained as events stream past.
     *
     * [ownTurnClosed] runs on every transcript publish — 16 times a second while a
     * turn streams — so it cannot scan the kept rows, and `turnStarts` alone is not
     * enough: a long turn pushes its own `turn/start` out of the loaded window while
     * its step boundaries and row events stay in it.
     */
    private var newestTurnSeen: Int = 0

    fun reset() {
        entries.clear()
        liveBlocks.clear()
        pending.clear()
        pendingSeq = Int.MAX_VALUE - 1024
        endedTurns.clear()
        turnStarts.clear()
        turnEnds.clear()
        compactionRows.clear()
        compactionFacts.clear()
        compactionMarkers.clear()
        retryRows.clear()
        orphanResults.clear()
        ptcParents.clear()
        header = null
        live = null
        cursor = 0
        hasMore = false
        newestTurnSeen = 0
    }

    /** Turns already closed by the host, for [buildDisplayRows]. */
    fun endedTurns(): Set<Int> = endedTurns.toSet()

    /** Elapsed time per completed turn, for the assistant turn-tail pill. */
    fun turnDurations(): Map<Int, Long> = buildMap {
        turnStarts.forEach { (turn, start) ->
            val end = turnEnds[turn] ?: return@forEach
            if (end > start) put(turn, end - start)
        }
    }

    /** When the newest turn began, so a live clock can run while it is in flight. */
    fun latestTurnStart(): Long? = turnStarts.maxByOrNull { it.key }?.value

    /**
     * Whether the journal proves this session's **own** turn has closed.
     *
     * The host's `running` is not a safe sole answer to that question in this
     * client: the flag it holds is the last `session/list` sample (plus the
     * quiet-window hold), and nothing here re-reads the list after `turn/end`, so a
     * sample taken while the agent was still closing outlives the turn while the
     * journal has already closed it. A turn the journal has seen `turn/end` for is
     * closed whatever the agent registry still reports — which is the whole of the
     * reported "idle parent with a live subagent still reads as Deep diving".
     *
     * Closure therefore needs positive evidence on all three counts, because each
     * has a window where the journal is merely *silent*, and silence must never
     * hide a live turn:
     *  - the newest turn any event named has a `turn/end` (a turn whose own
     *    `turn/start` scrolled out of the loaded window is still named by its
     *    step boundaries and row events),
     *  - no assistant attempt is still open (`start` seen, its durable
     *    `assistant/message` not yet), and
     *  - no optimistic prompt of this client's is unclaimed: sending into an idle
     *    session closes nothing until the host writes the new `turn/start`, and a
     *    status row must not blink out in that gap.
     */
    fun ownTurnClosed(): Boolean {
        if (pending.isNotEmpty()) return false
        if (live?.finished == false) return false
        val newest = maxOf(newestTurnSeen, live?.turn ?: 0)
        if (newest <= 0) return false
        return newest in endedTurns
    }

    /**
     * The seq of each completed turn's closing text answer.
     *
     * The web UI hangs the assistant action row on a separate `turn-tail` node and
     * omits it entirely when a turn has no closing text assistant (tool-only or
     * interrupted), so the footer needs to know exactly which row closes a turn.
     */
    fun closingAssistantSeqs(): Set<Int> = buildSet {
        endedTurns.forEach { turn ->
            entries.values
                .filterIsInstance<ChatEntry.AssistantMessage>()
                .lastOrNull { it.turn == turn && it.text.isNotBlank() }
                ?.let { add(it.seq) }
        }
    }

    fun snapshot(): List<ChatEntry> = entries.values.toList() + pending.values

    fun header(): SessionHeader? = header

    fun liveAttempt(): LiveAttempt? = live?.takeIf { it.blocks.isNotEmpty() }

    fun cursor(): Int = cursor

    fun hasMore(): Boolean = hasMore

    /**
     * The inclusive log cut of the window this reducer holds, straight from the
     * follow opening frame.
     *
     * `session/page` wants it back as `throughSeq`: history is only reachable up to
     * the cut the stream was opened at, so a page can never pull in records this
     * client was not entitled to see.
     */
    fun throughSeq(): Int = cursor

    /** Oldest durable seq held; the `beforeSeq` of the next back-fill. */
    fun oldestSeq(): Int? = entries.firstKey()

    /** The PTC parent links seen so far, for `buildToolCallTree`. */
    fun toolParents(): Map<String, String> = ptcParents.links()

    /**
     * Prepends one `session/page` back-fill.
     *
     * The records take the same [applyEvent] path as the live stream, which is what
     * makes an overlapping page harmless: entries are keyed by seq and an existing
     * seq is never rewritten. The return value is how many *new* rows arrived, so
     * the caller can stop when a page adds nothing — the host answers with an empty
     * delta once a surface replacement has retired the range being asked for.
     */
    fun applyOlderPage(page: JSONObject): Int {
        val sizeBefore = entries.size
        page.arr("records")?.let { records ->
            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: continue
                record.obj("event")?.let { applyEvent(it) }
            }
        }
        // The page is authoritative about whether anything older exists; the
        // follow snapshot's own `hasMore` only describes the opening window.
        if (page.has("hasMore")) hasMore = page.bool("hasMore")
        return entries.size - sizeBefore
    }

    /** Newest `todo/write` state, used for the plan chip above the composer. */
    fun latestTodos(): List<TodoItem> =
        entries.values.filterIsInstance<ChatEntry.Todos>().lastOrNull()?.items.orEmpty()

    // ---------------------------------------------------- optimistic sends

    /**
     * Shows a prompt the moment the host admits it, before any durable echo.
     * @param rpcId - the request id minted for `session/prompt`.
     * @param text - the message body as typed.
     * @param attachments - file/image blocks riding along, for the chips.
     */
    fun addPending(rpcId: String, text: String, attachments: List<MessageAttachment>) {
        pendingSeq -= 1
        pending[rpcId] = ChatEntry.UserMessage(
            seq = pendingSeq,
            id = rpcId,
            text = text,
            time = System.currentTimeMillis(),
            fromPlugin = false,
            summary = null,
            attachments = attachments,
            rpcId = rpcId,
            pending = true,
        )
    }

    /** The host refused the prompt (or the call failed): drop the row. */
    fun failPending(rpcId: String) {
        pending.remove(rpcId)
    }

    // ---------------------------------------------------------------- frames

    fun applySnapshot(value: JSONObject) {
        value.obj("header")?.let { raw ->
            header = SessionHeader(
                id = raw.str("id"),
                cwd = raw.optString("cwd").takeIf { it.isNotEmpty() },
                createdAt = raw.long("createdAt"),
                agentPreset = raw.optString("agentPreset").takeIf { it.isNotEmpty() },
                parentSession = raw.optString("parentSession").takeIf { it.isNotEmpty() },
            )
        }
        cursor = value.int("cursor")
        hasMore = value.bool("hasMore")

        value.arr("records")?.let { records ->
            for (i in 0 until records.length()) {
                val record = records.optJSONObject(i) ?: continue
                record.obj("event")?.let { applyEvent(it) }
            }
        }

        // A reconnect can land mid-attempt; replay the detached stream we missed.
        value.obj("assistantStream")?.obj("activeAttempt")?.let { attempt ->
            liveBlocks.clear()
            live = LiveAttempt(
                attemptId = attempt.str("attemptId"),
                turn = attempt.int("turn"),
                step = attempt.int("step"),
                blocks = emptyList(),
                finished = false,
            )
            attempt.arr("stream")?.let { stream ->
                for (i in 0 until stream.length()) {
                    stream.optJSONObject(i)?.let { applyChunk(it) }
                }
            }
        }
    }

    /** One durable `SessionWireEvent`. */
    fun applyEvent(event: JSONObject) {
        val seq = event.int("seq", -1)
        if (seq < 0) return
        // Before the duplicate-seq return: a re-applied snapshot must not be the
        // reason a link is missing, and the collector ignores repeats anyway.
        ptcParents.record(event)

        // A surface *replacement* is a model-visible shadow, not a transcript row:
        // every web node definition requires `surfaceOp === 'append'`, and the
        // host's own docs say a landed replacement "would erase conversation the
        // user already saw". Clearing the shadowed range here did exactly that,
        // and it took earlier compaction markers with it (a later checkpoint's
        // range starts at the previous checkpoint's own seq). The replacement copy
        // is skipped at the row level instead, below.
        val surfaceOp = event.opt("surfaceOp")
        val isReplacement = surfaceOp is JSONObject && surfaceOp.optString("op") == "replace"

        if (entries.containsKey(seq)) return

        val type = event.str("type")
        val time = event.long("time")
        val data = event.obj("data") ?: JSONObject()

        // Recorded before the dispatch below so a turn boundary, a step, and a row
        // all count: `turn/start` is the first event of a turn, but it is also the
        // first thing a bounded window drops.
        val namedTurn = data.int("turn")
        if (namedTurn > newestTurnSeen) newestTurnSeen = namedTurn

        when (type) {
            "user/message" -> {
                val source = data.obj("source")
                val kind = source.str("kind")
                val content = data.arr("content")
                val text = textOf(content)
                val summary = source.str("summary")
                // The durable echo of a prompt this client sent retires its
                // optimistic row, matched on the RPC id the host copies through.
                source.str("rpcId").takeIf { it.isNotEmpty() }?.let { pending.remove(it) }
                val attachments = attachmentsOf(content)
                // The `compact` checkpoint carries the whole compaction summary as
                // its content. It is not a message row on the web either — it is
                // the marker's anchor — and drawing it here would duplicate the
                // summary: its prose made a 14 KB notice out of a one-line event.
                if (source.str("plugin") == "compact") {
                    applyCompactionCheckpoint(seq, time, source)
                    return
                }
                // Any other replacement copy stays model-only.
                if (isReplacement) return
                if (kind != "user" && kind.isNotEmpty()) {
                    // Plugin and background-job notices are `context` rows on the
                    // web, not chat bubbles: one compact label line, with the body
                    // available on demand. The label follows the web's own rules
                    // (plugin id, the paths an instruction update touched, the raw
                    // kind); falling back to the first line of the prose produced a
                    // wall of run-together text instead of a label.
                    entries[seq] = ChatEntry.Notice(
                        seq = seq,
                        kind = source.str("plugin").ifEmpty { kind },
                        text = source?.let { jobNoticeLabel(it, text) }
                            ?: summary.ifBlank { contextLabel(kind, source ?: JSONObject()) },
                        detail = text.takeIf { it.isNotBlank() && it != summary },
                        time = time,
                        severity = NoticeSeverity.INFO,
                    )
                } else {
                    entries[seq] = ChatEntry.UserMessage(
                        seq = seq,
                        id = data.str("id"),
                        text = text.ifBlank { if (attachments.isEmpty()) "[attachment]" else "" },
                        time = time,
                        fromPlugin = false,
                        summary = null,
                        attachments = attachments,
                        rpcId = source.str("rpcId").takeIf { it.isNotEmpty() },
                    )
                }
            }

            "assistant/message" -> {
                val message = data.obj("message") ?: JSONObject()
                val content = message.arr("content")
                val turn = data.int("turn")
                val step = data.int("step")
                entries[seq] = ChatEntry.AssistantMessage(
                    seq = seq,
                    turn = turn,
                    step = step,
                    reasoning = reasoningOf(content),
                    text = textOf(content),
                    time = time,
                )
                // The committed message supersedes the live buffer for this step.
                if (live?.turn == turn && live?.step == step) {
                    live = null
                    liveBlocks.clear()
                }
            }

            "tool/call" -> {
                val callId = data.str("callId")
                // A result that beat its call to this client is attached here
                // instead of being rendered as a nameless row of its own.
                val late = orphanResults.remove(callId)
                entries[seq] = ChatEntry.ToolCall(
                    seq = seq,
                    callId = callId,
                    name = data.str("name"),
                    arguments = data.str("arguments"),
                    result = late?.text,
                    isError = late?.isError ?: false,
                    errorCode = late?.errorCode,
                    meta = late?.meta,
                    images = late?.images.orEmpty(),
                    time = time,
                    turn = data.int("turn"),
                )
            }

            "tool/result" -> {
                val callId = data.obj("message")?.obj("source").str("callId")
                val (text, isError) = toolResultOf(data)
                // The structured code is the contract; the prose is not. An
                // `ask_user_question` cancellation says only "the user cancelled
                // ask_user_question" in text while `error.code` names the verdict.
                val errorCode = data.obj("error")?.str("code")?.takeIf { it.isNotEmpty() }
                // The structured side-channel: the read window, a search's file
                // list, an edit's diffs. The prose only describes it.
                val meta = data.obj("meta")
                val images = toolImagesOf(data)
                val existing = entries.values
                    .filterIsInstance<ChatEntry.ToolCall>()
                    .lastOrNull { it.callId == callId && it.result == null }
                if (existing != null) {
                    entries[existing.seq] = existing.copy(
                        result = text,
                        isError = isError,
                        errorCode = errorCode,
                        meta = meta,
                        images = images,
                    )
                } else if (callId.isNotEmpty()) {
                    // No call to hang this on: the web renders nothing here, so
                    // hold the result in case the call is still coming and never
                    // invent a nameless "Tool" row for it.
                    orphanResults[callId] = OrphanResult(text, isError, errorCode, meta, images)
                }
            }

            // A PTC (`run_code`) program dispatching another tool from inside
            // itself. The web nests these under the dispatching call
            // (`conversation-nodes/tool.ts` `updateDispatch`), and their events are
            // the *only* thing that says so: `tool/call` and `tool/result` carry no
            // parent id. Without a row here the child would have nothing to nest
            // into, so a start materialises one and the settle fills it in — a
            // settle whose start is outside the loaded window still gets its row.
            "tool/ptc-dispatch-start" -> {
                val subCallId = data.str("subCallId")
                if (subCallId.isNotEmpty()) {
                    entries[seq] = ChatEntry.ToolCall(
                        seq = seq,
                        callId = subCallId,
                        name = data.str("name"),
                        arguments = argumentsOf(data.opt("arguments")),
                        result = null,
                        isError = false,
                        time = time,
                        turn = dispatchTurn(data),
                    )
                }
            }

            "tool/ptc-dispatch" -> {
                val subCallId = data.str("subCallId")
                val text = textOf(data.arr("content"))
                val settled = entries.values
                    .filterIsInstance<ChatEntry.ToolCall>()
                    .lastOrNull { it.callId == subCallId }
                if (settled != null) {
                    entries[settled.seq] = settled.copy(
                        result = text,
                        isError = data.bool("isError"),
                        errorCode = data.obj("error")?.str("code")?.takeIf { it.isNotEmpty() },
                    )
                } else if (subCallId.isNotEmpty()) {
                    entries[seq] = ChatEntry.ToolCall(
                        seq = seq,
                        callId = subCallId,
                        name = data.str("name"),
                        arguments = argumentsOf(data.opt("arguments")),
                        result = text,
                        isError = data.bool("isError"),
                        time = time,
                        turn = dispatchTurn(data),
                    )
                }
            }

            // Turn boundaries: `turn/end` is what permits folding, and the pair of
            // times gives the turn's elapsed duration.
            "turn/start" -> data.int("turn").takeIf { it > 0 }?.let { turnStarts[it] = time }

            "turn/end" -> data.int("turn").takeIf { it > 0 }?.let { turn ->
                endedTurns += turn
                turnEnds[turn] = time
                // Why a turn stopped is worth a row: a reply cut off by the output
                // budget and a reply that failed both otherwise look like the model
                // simply trailing off mid-thought.
                val reason = data.obj("reason")
                when (reason?.str("kind")) {
                    "max-tokens" -> entries[seq] = ChatEntry.Notice(
                        seq = seq,
                        kind = "turn-max-tokens",
                        text = "Output token limit reached",
                        detail = "The reply was cut off; earlier output is preserved in " +
                            "the conversation. Send \"continue\" to let the model resume.",
                        time = time,
                    )

                    "error" -> entries[seq] = ChatEntry.Notice(
                        seq = seq,
                        kind = "turn-error",
                        text = reason.obj("error")?.str("message")
                            .orEmpty()
                            .ifEmpty { "The turn failed." },
                        time = time,
                        severity = NoticeSeverity.ERROR,
                    )
                }
            }

            "todo/write" -> {
                val todos = data.arr("todos") ?: JSONArray()
                val items = (0 until todos.length()).mapNotNull { i ->
                    todos.optJSONObject(i)?.let { TodoItem(it.str("content"), it.str("status")) }
                }
                entries[seq] = ChatEntry.Todos(seq, items)
            }

            "agent/error", "session/error" -> {
                entries[seq] = ChatEntry.Notice(
                    seq = seq,
                    kind = type,
                    text = data.str("message"),
                    time = time,
                    severity = NoticeSeverity.ERROR,
                )
            }

            // Context compaction. None of these three is a row of its own, and
            // neither is the checkpoint that carries them: the marker is drawn
            // from the checkpoint and *enriched* from the summary here, the way
            // the web's `compactionDefinition` builds its node (its
            // `buildViewNode` returns null until a checkpoint has landed).
            "compaction/start" -> {
                val id = data.str("compactionId")
                // Only a command-driven compaction announces itself; an automatic
                // one is an implementation detail of the turn it runs inside.
                if (id.isNotEmpty() && data.str("sourceCommandId").isNotEmpty()) {
                    compactionRows[id]?.let { entries.remove(it) }
                    compactionRows[id] = seq
                    entries[seq] = ChatEntry.Notice(
                        seq = seq,
                        kind = "compaction",
                        text = "Compacting context…",
                        time = time,
                    )
                }
            }

            "compaction/summary" -> {
                val id = data.str("compactionId")
                if (id.isNotEmpty()) {
                    val facts = CompactionFacts(
                        items = data.arr("shadowedSeqs")?.length()?.takeIf { it > 0 },
                        tokens = data.int("shadowedTokenCount")
                            .takeIf { data.has("shadowedTokenCount") && it >= 0 },
                        summary = textOf(data.arr("summary")).takeIf { it.isNotBlank() },
                    )
                    compactionFacts[id] = facts
                    // A replay can deliver the checkpoint first; refresh the row
                    // it already drew instead of leaving it without its facts.
                    compactionMarkers[id]?.let { marker -> refreshCompactionMarker(marker, id) }
                }
            }

            "compaction/end" -> {
                // Retired either way: an aborted attempt must not leave a
                // permanent "Compacting context…" behind. The marker itself, when
                // one landed, is not touched — the web never surfaces this
                // event's error.
                compactionRows.remove(data.str("compactionId"))?.let { entries.remove(it) }
            }

            // The host retries a model request on its own. The web shows one row per
            // retry chain and moves it from "waiting" to "retried", so the waiting row
            // is retired when the attempt starts rather than doubled up.
            "llm/retry" -> {
                val id = data.str("retryId")
                if (id.isNotEmpty()) {
                    retryRows[id] = seq
                    entries[seq] = ChatEntry.Notice(
                        seq = seq,
                        kind = "model-retry",
                        text = "Waiting to retry model request",
                        detail = data.str("failure").takeIf { it.isNotBlank() },
                        time = time,
                    )
                }
            }

            "llm/retry-started" -> {
                val id = data.str("retryId")
                if (id.isNotEmpty()) {
                    retryRows.remove(id)?.let { entries.remove(it) }
                    entries[seq] = ChatEntry.Notice(
                        seq = seq,
                        kind = "model-retry",
                        text = "Retried model request",
                        time = time,
                    )
                }
            }
        }
    }

    /** One `assistant-stream` frame: start / chunk / end. */
    fun applyAssistantFrame(frame: JSONObject) {
        when (frame.str("type")) {
            "start" -> {
                liveBlocks.clear()
                live = LiveAttempt(
                    attemptId = frame.str("attemptId"),
                    turn = frame.int("turn"),
                    step = frame.int("step"),
                    blocks = emptyList(),
                    finished = false,
                )
            }

            "chunk" -> {
                val attemptId = frame.str("attemptId")
                if (live == null) {
                    live = LiveAttempt(
                        attemptId = attemptId,
                        turn = 0,
                        step = 0,
                        blocks = emptyList(),
                        finished = false,
                    )
                }
                frame.obj("chunk")?.let { applyChunk(it) }
            }

            "end" -> {
                // Keep the rendered text; only drop the "streaming" affordance.
                live = live?.copy(finished = true)
            }
        }
    }

    private fun applyChunk(chunk: JSONObject) {
        val index = chunk.int("index", -1)
        if (index < 0) return

        when (chunk.str("type")) {
            "block-start" -> liveBlocks[index] = LiveBlock(index, chunk.str("blockType"))

            "block-end" -> {
                val block = chunk.obj("block")
                if (block != null) {
                    val type = block.str("type")
                    liveBlocks[index] = LiveBlock(
                        index = index,
                        type = type.ifEmpty { liveBlocks[index]?.type ?: "text" },
                        toolName = block.optString("name").takeIf { it.isNotEmpty() },
                        arguments = block.optString("arguments"),
                    )
                }
            }

            "reasoning-delta" -> {
                val current = liveBlocks[index] ?: LiveBlock(index, "reasoning")
                liveBlocks[index] = current.copy(reasoning = current.reasoning + chunk.str("text"))
            }

            "text-delta", "text" -> {
                val current = liveBlocks[index] ?: LiveBlock(index, "text")
                liveBlocks[index] = current.copy(text = current.text + chunk.str("text"), type = "text")
            }

            "tool-call-delta" -> {
                val current = liveBlocks[index] ?: LiveBlock(index, "tool-call")
                liveBlocks[index] = current.copy(
                    type = "tool-call",
                    toolName = chunk.optString("name").takeIf { it.isNotEmpty() } ?: current.toolName,
                    arguments = current.arguments + chunk.str("argumentsDelta"),
                )
            }
        }

        live = live?.copy(blocks = liveBlocks.values.toList())
    }

    // ---------------------------------------------------------------- helpers

    /**
     * `arguments` is a JSON object on a dispatch event and an already-encoded
     * string on `tool/call`, so both land in the row's one string field.
     */
    private fun argumentsOf(value: Any?): String = when (value) {
        null -> ""
        is String -> value
        else -> value.toString()
    }

    /**
     * A dispatch event carries no `turn`, so the sub-call inherits the turn of the
     * call that dispatched it: the deliverables view groups by turn, and a
     * sub-call's artifacts belong with the turn that asked for them.
     */
    private fun dispatchTurn(data: JSONObject): Int =
        entries.values.filterIsInstance<ChatEntry.ToolCall>()
            .lastOrNull { it.callId == data.str("parentCallId") }?.turn
            ?: data.int("turn")

    private fun textOf(content: JSONArray?): String {
        if (content == null) return ""
        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            when (part.str("type")) {
                // Parts are separate blocks, so they must not be concatenated:
                // without a separator, adjacent blocks ran together mid-word
                // ("…send it more.Its closing message:Wrote …").
                "text" -> {
                    if (builder.isNotEmpty()) builder.append('\n')
                    builder.append(part.str("text"))
                }

                "image", "file" -> {
                    // Attachments are rendered from their own blocks now, so they
                    // contribute no text. The old "[image]" placeholder made an
                    // attached screenshot read as prose.
                }
            }
        }
        return builder.toString()
    }

    /**
     * File and image blocks of one message, in block order.
     *
     * `attachmentId` is the address the `session/attachment` read needs, so it is
     * kept even though the row itself only shows the name and size.
     */
    private fun attachmentsOf(content: JSONArray?): List<MessageAttachment> {
        if (content == null) return emptyList()
        val result = ArrayList<MessageAttachment>()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            val kind = part.str("type")
            if (kind != "image" && kind != "file") continue
            val ref = part.obj("attachment") ?: continue
            result += MessageAttachment(
                kind = kind,
                attachmentId = ref.str("attachmentId"),
                name = ref.str("name").ifEmpty { if (kind == "image") "Image" else "File" },
                bytes = ref.int("bytes"),
                mediaType = ref.str("mediaType").takeIf { it.isNotEmpty() },
            )
        }
        return result
    }

    /**
     * Image blocks a tool result carried, in order.
     *
     * A tool that answers with a picture (`read` on an image path) sends image
     * content instead of text, and the text extractor deliberately ignores it.
     */
    private fun toolImagesOf(data: JSONObject): List<MessageAttachment> {
        val content = data.obj("message")?.arr("content") ?: return emptyList()
        val images = ArrayList<MessageAttachment>()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.str("type") != "tool-result") continue
            images += attachmentsOf(part.arr("content")).filter { it.kind == "image" }
        }
        return images
    }

    private fun reasoningOf(content: JSONArray?): String {
        if (content == null) return ""
        val builder = StringBuilder()
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.str("type") == "reasoning") builder.append(part.str("text"))
        }
        return builder.toString()
    }

    private fun toolResultOf(data: JSONObject): Pair<String, Boolean> {
        val content = data.obj("message")?.arr("content") ?: return "" to false
        val builder = StringBuilder()
        var isError = false
        for (i in 0 until content.length()) {
            val part = content.optJSONObject(i) ?: continue
            if (part.str("type") != "tool-result") continue
            if (part.bool("isError")) isError = true
            val inner = part.arr("content")
            if (inner != null) {
                for (j in 0 until inner.length()) {
                    val node = inner.optJSONObject(j) ?: continue
                    if (node.str("type") == "text") {
                        if (builder.isNotEmpty()) builder.append('\n')
                        builder.append(node.str("text"))
                    }
                }
            }
        }
        return builder.toString() to isError
    }

    /**
     * A background-job notice reads "background job bash-27 (bash: <command>)…"
     * and repeats the command as its source summary. The job's own identifier is
     * what names the row — at launch and at its conclusion alike — so it leads and
     * the command becomes the expandable body.
     */
    private fun jobNoticeLabel(source: JSONObject, text: String): String? {
        if (source.str("plugin") != "tool-jobs") return null
        val name = JOB_NAME.find(text)?.groupValues?.get(1) ?: return null
        val exit = JOB_EXIT.find(text)?.groupValues?.get(1)
        return when {
            text.contains("signal:") -> "$name · killed"
            exit != null -> "$name · exit $exit"
            text.contains("finished") -> "$name · finished"
            else -> name
        }
    }

    /**
     * The one-line label of a context row, following the web's projection rules:
     * a plugin id, the paths an instruction update touched, a skill's name, the
     * goal's round — and the raw `kind` when the payload carries nothing better.
     * The prose of these rows runs to thousands of characters, so it is the body,
     * never the label.
     */
    private fun contextLabel(kind: String, source: JSONObject): String = when (kind) {
        "agent-instructions" -> source.arr("changes")
            ?.let { changes ->
                (0 until changes.length())
                    .mapNotNull { changes.optJSONObject(it)?.str("path")?.takeIf { path -> path.isNotEmpty() } }
                    .take(2)
                    .joinToString(", ")
                    .ifEmpty { null }
            }
            ?: kind

        "skill-invocation" -> source.str("name").ifEmpty { kind }
        "skill-catalog" -> source.arr("entries")?.length()?.let { "Skill catalog · $it" } ?: kind
        "goal" -> source.int("round").takeIf { it > 0 }?.let { "Goal · round $it" } ?: "Goal"
        "agent-message" -> source.str("senderSessionId")
            .takeIf { it.isNotEmpty() }
            ?.let { "Relay from ${it.take(8)}…" }
            ?: kind

        else -> kind
    }

    /**
     * Draw (or move) one compaction marker from its checkpoint.
     *
     * The checkpoint is the replacement `user/message` the host writes when a
     * compaction lands, so it is the only compaction evidence guaranteed to be in
     * a loaded window. `sourceCommandId` decides how the web titles it: a manual
     * `/compact` is folded into its command row and titled `compact`, while an
     * automatic compaction is the plain "Context compacted" marker.
     */
    private fun applyCompactionCheckpoint(seq: Int, time: Long, source: JSONObject?) {
        val id = source.str("compactionId")
        if (id.isEmpty()) return
        compactionRows.remove(id)?.let { entries.remove(it) }
        compactionMarkers.remove(id)?.let { entries.remove(it.seq) }
        val title = if (source.str("sourceCommandId").isNotEmpty()) "compact" else "Context compacted"
        compactionMarkers[id] = CompactionMarker(seq, title)
        entries[seq] = compactionRow(seq, time, title, compactionFacts[id])
    }

    /** Rebuild a marker already on screen once its summary facts arrive. */
    private fun refreshCompactionMarker(marker: CompactionMarker, id: String) {
        val existing = entries[marker.seq] as? ChatEntry.Notice ?: return
        entries[marker.seq] = compactionRow(marker.seq, existing.time, marker.title, compactionFacts[id])
    }
    private fun compactionRow(
        seq: Int,
        time: Long,
        title: String,
        facts: CompactionFacts?,
    ): ChatEntry.Notice = ChatEntry.Notice(
        seq = seq,
        kind = "compaction",
        text = compactionLabel(title, facts),
        detail = facts?.summary,
        time = time,
    )

    /**
     * The compaction marker's label, as the web words it: how much history went
     * away and roughly how many tokens it held, or the disclosure prompts when the
     * summary event itself is outside the loaded window ("View compaction
     * summary"), or nothing was reported at all ("Compaction summary
     * unavailable"). `shadowedSeqs` is the list of retired records and
     * `shadowedTokenCount` is the count *removed* — the host reports no
     * after-the-fact total. The web requires *both* counts before it shows the
     * count line, so a half-reported pair degrades instead of guessing.
     */
    private fun compactionLabel(title: String, facts: CompactionFacts?): String {
        val items = facts?.items
        val tokens = facts?.tokens
        val detail = when {
            items != null && tokens != null -> {
                val count = if (items == 1) "1 history item" else "$items history items"
                "$count (~${formatTokens(tokens)} tokens)"
            }

            facts?.summary != null -> "View compaction summary"
            else -> "Compaction summary unavailable"
        }
        return "$title · $detail"
    }

    private fun formatTokens(tokens: Int): String = when {
        tokens >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", tokens / 1_000_000.0)
        tokens >= 1_000 -> "${tokens / 1_000}k"
        else -> tokens.toString()
    }
}

/**
 * A background-job notice's own identifier (`bash-27`) and, when the host reports
 * one, how the job ended. The prose is the only place the name appears — the
 * structured source carries just the command.
 */
private val JOB_NAME = Regex("background job\\s+([A-Za-z0-9_-]+)")
private val JOB_EXIT = Regex("exit code:\\s*(\\d+)")
