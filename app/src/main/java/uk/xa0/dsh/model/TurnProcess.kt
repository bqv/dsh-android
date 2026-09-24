package uk.xa0.dsh.model

import android.util.Log

/**
 * One row of the transcript after turn-process folding.
 *
 * The web client keeps a flat, seq-sorted node list and treats "grouping" as a
 * purely presentational **visibility projection**, not nesting: collapsing hides
 * member rows and splices a synthetic summary row in immediately after the turn's
 * opening user message. This mirrors that shape.
 */
sealed interface DisplayRow {
    val key: String

    data class Single(val entry: ChatEntry) : DisplayRow {
        override val key: String get() = "e${entry.seq}"
    }

    data class TurnProcess(
        val turn: Int,
        val toolCalls: Int,
        val messages: Int,
        val subagents: Int,
        val expanded: Boolean,
    ) : DisplayRow {
        override val key: String get() = "tp$turn"

        /**
         * Counts only. The host's copy is `{n} tool calls · {n} messages ·
         * {n} subagents`, falling back to "Thought for a while" when a turn had
         * neither tool calls nor interim messages.
         */
        val label: String
            get() {
                val parts = buildList {
                    if (toolCalls > 0) add(plural(toolCalls, "tool call"))
                    if (messages > 0) add(plural(messages, "message"))
                    if (subagents > 0) add(plural(subagents, "subagent"))
                }
                return if (parts.isEmpty()) "Thought for a while" else parts.joinToString(" · ")
            }

        private fun plural(count: Int, noun: String): String =
            if (count == 1) "1 $noun" else "$count ${noun}s"
    }

    data class Live(val attempt: LiveAttempt) : DisplayRow {
        override val key: String get() = "live"
    }
}

/**
 * Folds finished turns behind a process summary.
 *
 * Folding requires **both** that the host ended the turn (`turn/end`) and that the
 * turn produced a finalised answer. A turn still in flight renders every row
 * inline — there is no group and no summary row while a turn runs, which is the
 * detail a naive port gets wrong.
 *
 * Ownership is resolved in two passes before grouping, because a turn's rows are
 * *not* always contiguous in seq order: plugin and background-job notices arrive
 * as `user/message` events in the middle of a running turn. Splitting a turn into
 * two spans produced two summary rows for the same turn, and therefore a duplicate
 * LazyColumn key, which throws. Assigning every row to exactly one turn keeps a
 * turn to a single span and a single summary.
 *
 * `entries` must be seq-ascending. [DisplayRow.Live] is appended last because the
 * transcript renders with `reverseLayout`, where last means the visual bottom.
 */
fun buildDisplayRows(
    entries: List<ChatEntry>,
    endedTurns: Set<Int>,
    expandedTurns: Set<Int>,
    live: LiveAttempt?,
): List<DisplayRow> {
    val rows = ArrayList<DisplayRow>(entries.size + 2)
    if (entries.isEmpty()) {
        if (live != null) rows += DisplayRow.Live(live)
        return rows
    }

    val owners = IntArray(entries.size)
    for (index in entries.indices) {
        owners[index] = when (val entry = entries[index]) {
            is ChatEntry.AssistantMessage -> entry.turn
            is ChatEntry.ToolCall -> entry.turn
            else -> 0
        }
    }

    // A human message opens the *following* turn, so it looks forward. Plugin
    // notices are process members of the turn already running, so they look back.
    var following = 0
    for (index in entries.indices.reversed()) {
        if (owners[index] != 0) {
            following = owners[index]
        } else {
            val entry = entries[index]
            if (entry is ChatEntry.UserMessage && !entry.fromPlugin) owners[index] = following
        }
    }

    // Everything still unowned is a member of the turn already running, so it
    // looks back — except a human message, which never belongs to the turn that
    // already ran before it.
    //
    // The reverse pass above leaves a just-sent message unowned on purpose when
    // the turn it opens has no rows yet (the host writes `turn/start`, then
    // `user/message`, and only then the assistant's first row). This pass used to
    // hand it `preceding` — the *previous* turn — so the message was absorbed into
    // that turn's group and rendered above its summary and answer. Because the list
    // sits at the newest content, that put the user's own message off-screen: you
    // watched a turn's results with no sign of the prompt that caused them, until
    // the new turn's first assistant row arrived and re-owned the message to the
    // bottom. Leaving it at 0 renders it standalone at its own seq, which is the
    // bottom of the transcript.
    var preceding = 0
    for (index in entries.indices) {
        if (owners[index] != 0) {
            preceding = owners[index]
            continue
        }
        val entry = entries[index]
        if (entry is ChatEntry.UserMessage && !entry.fromPlugin) continue
        owners[index] = preceding
    }

    var index = 0
    while (index < entries.size) {
        val turn = owners[index]
        if (turn == 0) {
            rows += DisplayRow.Single(entries[index])
            index++
            continue
        }
        val group = ArrayList<ChatEntry>()
        while (index < entries.size && owners[index] == turn) {
            group += entries[index]
            index++
        }
        rows += foldTurn(turn, group, endedTurns, expandedTurns)
    }

    if (live != null) rows += DisplayRow.Live(live)

    // Defensive: a duplicate LazyColumn key is a hard crash on device, so a
    // grouping mistake must never reach the list. Dropping a row is survivable
    // and gets logged; taking the app down is not.
    //
    // Blank rows are dropped for a layout reason, not a tidy one: a row that
    // renders nothing still occupies a list slot, and the column's item spacing
    // applies on *both* sides of it, so an empty assistant message (a step that
    // only called tools) silently doubles the gap around it. That is what made
    // the transcript's rhythm look uneven.
    val seen = HashSet<String>(rows.size)
    val unique = ArrayList<DisplayRow>(rows.size)
    for (row in rows) {
        if (!row.renders) continue
        if (seen.add(row.key)) {
            unique += row
        } else {
            Log.w(TAG, "dropped duplicate transcript row key=${row.key}")
        }
    }
    return unique
}

/** False for rows whose composable would draw nothing at all. */
private val DisplayRow.renders: Boolean
    get() = when (this) {
        is DisplayRow.Single -> when (val entry = entry) {
            is ChatEntry.AssistantMessage -> entry.text.isNotBlank() || entry.reasoning.isNotBlank()
            // Always drawn: NoticeRow falls back to `kind` as its label, so a
            // prose-less row (a context injection, a plugin event) is still a
            // labelled row. Dropping these is what made whole classes of host
            // events invisible in the transcript.
            is ChatEntry.Notice -> true
            is ChatEntry.UserMessage -> entry.text.isNotBlank() || entry.attachments.isNotEmpty()
            is ChatEntry.ToolCall -> true
            is ChatEntry.Todos -> entry.items.isNotEmpty()
        }

        is DisplayRow.TurnProcess -> true
        is DisplayRow.Live -> true
    }

/**
 * Tool names that open a child session. The host's own tool is `subagent`
 * (configurable); the other two are older aliases the app already draws with the
 * same glyph.
 */
private val SUBAGENT_TOOLS = setOf("subagent", "task", "delegate")

private const val TAG = "DshTranscript"

private fun foldTurn(
    turn: Int,
    group: List<ChatEntry>,
    endedTurns: Set<Int>,
    expandedTurns: Set<Int>,
): List<DisplayRow> {
    val answer = group.filterIsInstance<ChatEntry.AssistantMessage>().lastOrNull { it.text.isNotBlank() }
    if (turn !in endedTurns || answer == null) return group.map { DisplayRow.Single(it) }

    val expanded = turn in expandedTurns
    // Rows the host keeps *outside* the fold: human messages and every
    // non-conversational notice (compaction, plugin/context rows, errors). Only
    // the assistant's own process — tool calls and interim messages — is a fold
    // member. Folding notices hid `compaction/*` entirely, so a context
    // compaction looked like the model silently forgetting things.
    val alwaysVisible = group.filter { candidate ->
        candidate is ChatEntry.Notice || (candidate is ChatEntry.UserMessage && !candidate.fromPlugin)
    }
    // Everything that is not the closing prose and not kept outside the fold: the
    // assistant's own process, which the summary stands in for.
    val members = group.filter { candidate ->
        candidate !== answer && alwaysVisible.none { it === candidate }
    }

    val rows = ArrayList<DisplayRow>(group.size + 3)
    // The group is walked in seq order and the summary stands in for the process
    // members *at the position of the first one*, rather than all the outside rows
    // being emitted up front.
    //
    // Emitting them up front put every notice above the turn's summary and answer,
    // even when it happened after them: a compaction lands at the end of the range
    // it compacted, so its marker was hoisted above the very turns it followed —
    // "compaction showing above turns it's after". It also lost the intra-turn
    // order of notices relative to each other.
    var summaryEmitted = false
    fun emitSummary() {
        if (summaryEmitted) return
        summaryEmitted = true
        rows += DisplayRow.TurnProcess(
            turn = turn,
            toolCalls = members.count { it is ChatEntry.ToolCall },
            messages = members.count { it is ChatEntry.AssistantMessage || it is ChatEntry.UserMessage },
            // A delegation is an ordinary tool call here. The web counts the child
            // nodes of its call tree; until this client builds that tree, counting
            // the delegating calls themselves is the honest version of the same
            // number — the host's tool is `subagent`, with `task`/`delegate` as
            // older aliases.
            subagents = members.count {
                it is ChatEntry.ToolCall && it.name.lowercase() in SUBAGENT_TOOLS
            },
            expanded = expanded,
        )
    }

    for (candidate in group) {
        when {
            alwaysVisible.any { it === candidate } -> rows += DisplayRow.Single(candidate)
            candidate === answer -> {
                // The process precedes the prose that closed the turn.
                emitSummary()
                rows += DisplayRow.Single(if (expanded) candidate else candidate.copy(reasoning = ""))
            }

            else -> {
                // A fold member: collapsed it is represented by the summary, and
                // expanded it is drawn beneath it.
                emitSummary()
                if (expanded) rows += DisplayRow.Single(candidate)
            }
        }
    }
    return rows
}
