package uk.xa0.dsh.model

import kotlin.math.roundToLong

/**
 * The Trajectory view's model: the conversation journal seen as the *model's*
 * input/output ledger rather than as chat bubbles.
 *
 * The web target is `packages/client/ui-trajectory`. Its ledger is built by two
 * stages that this file folds into one pure pass:
 *  - `layout.ts` turns `ConversationViewNode`s into `Turn -> Group -> cell`
 *    rows (`TrajectoryTurnModel` / `TrajectoryGroupModel` /
 *    `TrajectoryCellProps`), and
 *  - `timeline.ts` projects the visible cells into a three-lane overview.
 *
 * This app has no `ConversationViewNode` layer: its [TranscriptReducer] has
 * already folded the durable journal into [ChatEntry] rows, so the mapping here
 * is `ChatEntry -> TrajectoryCell -> turn/group/timeline`. Keeping it a pure
 * function of the rows is what lets the screen stay a rendering shell, and it is
 * the only place that knows how an app row becomes a web cell.
 *
 * ## What maps, and what cannot
 *  - web `user`        <- [ChatEntry.UserMessage] (`source.kind == 'user'`)
 *  - web `context`     <- [ChatEntry.Notice] (plugin/context/relay rows)
 *  - web `compacted`   <- [ChatEntry.Notice] with `kind == "compaction"`
 *  - web `message`     <- [ChatEntry.AssistantMessage]
 *  - web `tool`        <- [ChatEntry.ToolCall]
 *  - web `subtool`     <- [ChatEntry.ToolCall] whose call id carries a
 *    `tool/ptc-dispatch-start` parent link (see [buildTrajectory]'s `toolParents`)
 *  - web `system` has no source row here: the reducer keeps no
 *    `system/message`/`request/header` prompt changes, so no cell is invented
 *    for them.
 *  - `retry`/`error` are *not* Trajectory cell kinds in the web either — its
 *    Assistant definition folds `llm/retry` into the assistant request and
 *    `turn/end` failures into that request's status. This app's reducer emits
 *    them as [ChatEntry.Notice] rows, so they are carried as first-class span
 *    kinds ([TrajectoryKind.RETRY]/[TrajectoryKind.ERROR]) rather than dropped.
 *
 * Durations are deliberately absent: the reducer stores only the call
 * timestamp, never the result/step-start timestamp, so `timeSeconds` is `null`
 * on every derived span and the timeline therefore renders its default
 * *sequence* projection (equal-width blocks), which is also the web's default.
 */
enum class TrajectoryKind {
    SYSTEM,
    USER,
    CONTEXT,
    COMPACTED,
    MESSAGE,
    TOOL,
    SUBTOOL,
    RETRY,
    ERROR;

    /** The web's kind tag copy (`ui-trajectory/locales.ts` `kind.*`). */
    val label: String
        get() = when (this) {
            SYSTEM -> "SYSTEM"
            USER -> "USER"
            CONTEXT -> "CONTEXT"
            COMPACTED -> "COMPACTED"
            MESSAGE -> "ASSISTANT"
            TOOL -> "TOOL"
            SUBTOOL -> "SUBTOOL"
            RETRY -> "RETRY"
            ERROR -> "ERROR"
        }
}

/**
 * The web's three timeline lanes (`timeline.ts:51-55`):
 * `Input` (system/user/context), `Model` (assistant/compacted), `Tools`.
 *
 * `RETRY`/`ERROR` are app-only kinds; they are placed on the Model lane because
 * they describe a model request, which is the closest honest home for a fact
 * the web keeps inside the assistant request rather than in a lane.
 */
enum class TrajectoryLane(val index: Int) {
    INPUT(0),
    MODEL(1),
    TOOLS(2),
}

/** The web's per-record state (`TrajectoryTable.tsx:723-731`). */
enum class TrajectoryStatus { RUNNING, COMPLETE, ERROR }

val TrajectoryKind.lane: TrajectoryLane
    get() = when (this) {
        TrajectoryKind.TOOL, TrajectoryKind.SUBTOOL -> TrajectoryLane.TOOLS
        TrajectoryKind.MESSAGE, TrajectoryKind.COMPACTED,
        TrajectoryKind.RETRY, TrajectoryKind.ERROR -> TrajectoryLane.MODEL

        else -> TrajectoryLane.INPUT
    }

/**
 * One ledger record — the web's `TrajectoryCellProps`.
 *
 * The raw pieces are kept beside the derived previews for the same reason the
 * web keeps them: the ledger row shows a bounded one-line summary while an
 * expanded row (the web's details panel) shows the retained source.
 */
data class TrajectorySpan(
    /** 1-based record index shown as `#N`; assigned in journal order. */
    val index: Int,
    val kind: TrajectoryKind,
    /**
     * The web's `text`: a short prefix that the preview is appended to. For a
     * message this is empty when prose is present; for a tool it is the name.
     */
    val text: String = "",
    /** The web's `previewMarkdown`: the one-line summary source. */
    val previewMarkdown: String? = null,
    /** The web's `result`: a short tool-result verdict (`error`, `No output`). */
    val result: String? = null,
    val resultPreviewMarkdown: String? = null,
    val inputDetail: String? = null,
    val outputDetail: String? = null,
    val thinkingDetail: String? = null,
    val toolName: String? = null,
    val callId: String? = null,
    /**
     * The wire's PTC parent link (`tool/ptc-dispatch-start`'s `parentCallId`),
     * set only on a dispatched sub-call. The web's ledger keeps such a record
     * flat but tags it `subtool` (`layout.ts:1027`) and carries the link for its
     * details panel (`trajectory-tool-definition.ts:22`); this app does the same,
     * because the trajectory is a ledger, not the chat tree.
     */
    val parentCallId: String? = null,
    val sourceSeq: Int,
    /** Own duration in seconds; always null here — see the file header. */
    val timeSeconds: Double? = null,
    /** Unix epoch milliseconds the operation started, when known. */
    val startedAt: Long? = null,
    val status: TrajectoryStatus = TrajectoryStatus.COMPLETE,
    /** Owning turn, when the row was enclosed by one. */
    val turn: Int? = null,
    val step: Int? = null,
) {
    val lane: TrajectoryLane get() = kind.lane

    val isError: Boolean get() = status == TrajectoryStatus.ERROR

    /** Web `recordDisplayText` (`TrajectoryTable.tsx:1012-1028`). */
    val displayText: String
        get() {
            val preview = previewMarkdown?.let(::trajectoryPreviewText).orEmpty()
            return when {
                previewMarkdown != null && text.isNotEmpty() ->
                    if (preview.isEmpty()) text else "$text · $preview"

                previewMarkdown != null -> preview
                else -> text
            }
        }

    /** Web `recordResultText` (`TrajectoryTable.tsx:1030-1034`). */
    val resultText: String?
        get() = resultPreviewMarkdown?.let(::trajectoryPreviewText) ?: result

    /**
     * Web `isToolCallOnly` (`TrajectoryTable.tsx:1051-1056`): an assistant step
     * whose whole content was tool calls is labelled, not left blank.
     */
    val toolCallOnly: Boolean
        get() = kind == TrajectoryKind.MESSAGE &&
            outputDetail == null &&
            thinkingDetail == null &&
            text == TOOL_CALL_ONLY

    /**
     * Stable identity that survives prepending older records, as the web's
     * `trajectoryRecordId` does — collapse state and list keys hang off it.
     */
    val recordId: String
        get() = callId?.let { "call\u0000$kind\u0000$it" } ?: "seq\u0000$kind\u0000$sourceSeq"
}

/**
 * One web `TrajectoryGroupModel`: the `Message` group and each `Step N` group
 * inside a turn (a standalone compaction is a one-group `turn == null` section).
 *
 * The web's ledger does not print these titles as rows: a group is the
 * *request* unit, and `TrajectoryView.tsx:213-313` numbers every assistant step
 * group and every compaction, which the table paints as a boundary marker
 * labelled `Request #N`. [requestNumber] is that ordinal. [description] is kept
 * because the web computes it (`layout.ts:651-683`) and its own ledger only ever
 * renders it through the unused `TrajectoryGroupHeader`, so the app computes it
 * for parity and does not draw it either.
 */
data class TrajectoryGroup(
    val title: String,
    val description: String? = null,
    val spans: List<TrajectorySpan> = emptyList(),
    /** 1-based request ordinal, or null for a non-request (`Message`) group. */
    val requestNumber: Int? = null,
)

/**
 * One web `TrajectoryTurnModel`. `turn == null` is the web's "Between turns"
 * section: a standalone compaction section that belongs to no turn.
 */
data class TrajectoryTurn(
    val turn: Int?,
    val groups: List<TrajectoryGroup> = emptyList(),
    /** True while the host has not closed this turn with `turn/end`. */
    val open: Boolean = false,
) {
    val spans: List<TrajectorySpan> get() = groups.flatMap { it.spans }

    val firstIndex: Int get() = spans.minOfOrNull { it.index } ?: Int.MAX_VALUE

    /** Unique across the ledger; the standalone case uses its first record. */
    val key: String get() = turn?.let { "t$it" } ?: "c$firstIndex"

    /** Web `collapsibleTurnIds`: more than one non-system content record. */
    val collapsible: Boolean get() = spans.size > 1

    /** Web `summarizeTurn`: distinct `Step N` groups. */
    val stepCount: Int get() = groups.count { it.title.startsWith("Step ") }

    /**
     * Web `summarizeTurn` counts only the records *after* the first one, because
     * the collapsed view keeps that first record visible.
     */
    fun collapsedSummary(skipFirst: Boolean = true): String {
        val considered = if (skipFirst) spans.drop(1) else spans
        val tools = considered.count {
            it.kind == TrajectoryKind.TOOL || it.kind == TrajectoryKind.SUBTOOL
        }
        return listOf(plural(stepCount, "step"), plural(tools, "tool call")).joinToString(" · ")
    }
}

/** One record projected into the sequence-domain timeline (`timeline.ts`). */
data class TrajectoryTimelineSpan(
    val start: Int,
    val end: Int,
    val index: Int,
    val kind: TrajectoryKind,
    val lane: TrajectoryLane,
    val label: String,
    val isError: Boolean,
)

/** One turn's first position in the timeline domain (`TrajectoryTimelineTurnBoundary`). */
data class TrajectoryTurnBoundary(val turn: Int, val at: Int)

/**
 * The web's `deriveTrajectoryTimeline` in its default `sequence` mode: every
 * visible record occupies one unit, so the overview is a stable map of the
 * conversation regardless of how slowly the host recorded its wall clock.
 */
data class TrajectoryTimeline(
    val start: Int,
    val end: Int,
    val spans: List<TrajectoryTimelineSpan> = emptyList(),
    val turnBoundaries: List<TrajectoryTurnBoundary> = emptyList(),
)

/** A flat, key-stable row list for a `LazyColumn`; see [TrajectoryModel.rows]. */
sealed interface TrajectoryRow {
    val key: String

    data class Header(
        val sectionKey: String,
        val turn: Int?,
        val label: String,
        val open: Boolean,
        val collapsed: Boolean,
        val expandable: Boolean,
    ) : TrajectoryRow {
        override val key: String get() = "h\u0000$sectionKey"
    }

    /**
     * The web's request-boundary marker (`TrajectoryTable.tsx:2733-2753`). In the
     * web it is a 5px dot whose `Request #N` label appears on hover; a touch
     * device has no hover, so the app renders the label beside the dot.
     */
    data class Request(
        val sectionKey: String,
        val number: Int,
        val compaction: Boolean,
        val isError: Boolean,
    ) : TrajectoryRow {
        override val key: String get() = "r\u0000$sectionKey\u0000$number"
    }

    data class Span(val span: TrajectorySpan) : TrajectoryRow {
        override val key: String get() = "s\u0000${span.recordId}"
    }

    data class Collapsed(
        val sectionKey: String,
        val label: String,
    ) : TrajectoryRow {
        override val key: String get() = "x\u0000$sectionKey"
    }
}

/** The folded ledger plus its timeline, ready for the screen to draw. */
data class TrajectoryModel(
    val turns: List<TrajectoryTurn> = emptyList(),
    val timeline: TrajectoryTimeline? = null,
    val endedTurns: Set<Int> = emptySet(),
) {
    val isEmpty: Boolean get() = turns.isEmpty()

    val spanCount: Int get() = turns.sumOf { it.spans.size }

    /** Web `sectionLabel` (`TrajectoryTable.tsx:561-563`). */
    fun sectionLabel(turn: Int?): String = if (turn == null) "Between turns" else "Turn $turn"

    /**
     * Flattens the ledger into `LazyColumn` items, in the web's row order:
     * a turn label chip, then for each request group its boundary marker
     * followed by the group's records.
     *
     * The web keeps the marker and the turn label painted inside the first row
     * of the group; hoisting them into their own rows is the phone's substitute
     * for a two-column sticky table. Rows are still one per record, in the same
     * seq order. Keys are namespaced by section, because a request number or a
     * record can only be unique *within* the ledger and `LazyColumn` keys are
     * global to the list.
     */
    fun rows(collapsedTurns: Set<Int> = emptySet()): List<TrajectoryRow> {
        val rows = ArrayList<TrajectoryRow>(spanCount + turns.size * 3)
        for (turn in turns) {
            val collapsed = turn.turn != null && turn.turn in collapsedTurns
            rows += TrajectoryRow.Header(
                sectionKey = turn.key,
                turn = turn.turn,
                label = sectionLabel(turn.turn),
                open = turn.open,
                collapsed = collapsed,
                expandable = turn.collapsible,
            )
            if (!collapsed) {
                for (group in turn.groups) {
                    val number = group.requestNumber
                    if (number != null) {
                        rows += TrajectoryRow.Request(
                            sectionKey = turn.key,
                            number = number,
                            compaction = turn.turn == null,
                            // Web `data-request-status='error'` paints the marker
                            // red; the app's closest evidence is a failed record
                            // in the same request group.
                            isError = group.spans.any { it.isError },
                        )
                    }
                    for (span in group.spans) rows += TrajectoryRow.Span(span)
                }
            } else {
                // Web `collapseTurnRecords`: keep the first content record, then
                // splice a synthetic summary row in its place.
                turn.spans.firstOrNull()?.let { rows += TrajectoryRow.Span(it) }
                rows += TrajectoryRow.Collapsed(turn.key, turn.collapsedSummary())
            }
        }
        return rows
    }
}

private const val TOOL_CALL_ONLY = "Tool call only"

// The web's preview budget (`trajectory-preview.ts`): read 2 048 source
// characters, emit at most 512.
private const val PREVIEW_SOURCE_CHARACTERS = 2048
private const val PREVIEW_OUTPUT_CHARACTERS = 512

/**
 * Bounded one-line preview, the app-side stand-in for the web's
 * `trajectoryPreviewText`.
 *
 * The web additionally runs `extractMarkdownPlainText` first. This app has no
 * plain-text extractor outside the renderer, so markdown syntax survives the
 * summary; that is a deliberate, visible compromise, not an oversight.
 */
fun trajectoryPreviewText(text: String): String {
    if (text.length <= PREVIEW_OUTPUT_CHARACTERS) {
        return text.replace(WHITESPACE, " ").trim()
    }
    val source = text.take(PREVIEW_SOURCE_CHARACTERS)
    val compact = source.replace(WHITESPACE, " ").trim()
    val preview = compact.take(PREVIEW_OUTPUT_CHARACTERS).trimEnd()
    return if (source.length < text.length || preview.length < compact.length) "$preview…" else preview
}

private val WHITESPACE = Regex("\\s+")

/**
 * Folds the transcript rows into the trajectory ledger.
 *
 * @param entries the reducer's seq-ascending snapshot
 * @param endedTurns turns the host has closed; the newest open turn is marked
 *   so the screen can show it as still running
 * @param toolParents the wire's PTC parent links
 *   (`tool/ptc-dispatch-start` / `tool/ptc-dispatch`, `subCallId` → `parentCallId`;
 *   [ToolCallParents] collects them). A call that has one is the web's `subtool`
 *   record. Empty means "no links known", which is the app's flat `tool` rendering.
 */
fun buildTrajectory(
    entries: List<ChatEntry>,
    endedTurns: Set<Int> = emptySet(),
    toolParents: Map<String, String> = emptyMap(),
): TrajectoryModel {
    if (entries.isEmpty()) return TrajectoryModel(endedTurns = endedTurns)
    val ordered = entries.sortedBy { it.seq }
    val owners = resolveTurnOwners(ordered)

    // Sections are created on first touch, and the rows arrive seq-ascending, so
    // encounter order is already the web's `firstCellIndex` order.
    val sections = LinkedHashMap<String, TurnBuilder>()
    var index = 0

    for (i in ordered.indices) {
        val entry = ordered[i]
        index += 1
        if (entry is ChatEntry.Notice && entry.kind == "compaction") {
            val span = compactionSpan(entry, index)
            val section = sections.getOrPut("c${entry.seq}") { TurnBuilder(null) }
            section.groups += GroupBuilder("Compaction ${entry.seq}", arrayListOf(span))
            continue
        }
        // Turn 0 is not a turn: notices before the first assistant belong to the
        // first real turn, which is the web's own "orphan turn-0 folds into 1".
        val turn = owners[i].let { if (it <= 0) 1 else it }
        val section = sections.getOrPut("t$turn") { TurnBuilder(turn) }
        when (entry) {
            is ChatEntry.AssistantMessage -> {
                val span = assistantSpan(entry, index)
                if (entry.step > 0) section.addStep(entry.step, span) else section.addMessage(span)
            }

            is ChatEntry.ToolCall -> section.addTool(
                toolSpan(entry, index, toolParents[entry.callId]),
            )
            is ChatEntry.UserMessage -> section.addMessage(userSpan(entry, index))
            is ChatEntry.Notice -> section.addMessage(noticeSpan(entry, index))
            is ChatEntry.Todos -> section.addMessage(todosSpan(entry, index))
        }
    }

    val turns = sections.values
        .map { it.build(endedTurns) }
        .sortedBy { it.firstIndex }

    // Web `TrajectoryView.tsx:213-313` numbers every assistant step group and
    // every compaction, in start-seq order, as one request ordinal. The app's
    // `Message` groups are not requests, so they carry no number.
    var request = 0
    val numbered = turns.map { turn ->
        turn.copy(
            groups = turn.groups.map { group ->
                if (turn.turn == null || group.title.startsWith("Step ")) {
                    request += 1
                    group.copy(requestNumber = request)
                } else {
                    group
                }
            },
        )
    }
    return TrajectoryModel(
        turns = numbered,
        timeline = deriveTimeline(numbered),
        endedTurns = endedTurns,
    )
}

/**
 * The web's turn-enclosure rules (`layout.ts:876-885` and `TurnProcess.kt`'s
 * two-pass ownership):
 *  - a human message opens the *following* assistant turn (or the turn after
 *    the last assistant, when nothing follows it yet);
 *  - every other untagged row (a plugin notice, a to-do write, a turn-end
 *    notice) is a member of the turn already running, so it looks back.
 */
private fun resolveTurnOwners(entries: List<ChatEntry>): IntArray {
    val size = entries.size
    val owner = IntArray(size)
    for (i in 0 until size) {
        owner[i] = when (val entry = entries[i]) {
            is ChatEntry.AssistantMessage -> entry.turn
            is ChatEntry.ToolCall -> entry.turn
            else -> 0
        }
    }

    // Next assistant turn strictly after each index.
    val nextAssistant = IntArray(size)
    var next = 0
    for (i in size - 1 downTo 0) {
        nextAssistant[i] = next
        val entry = entries[i]
        if (entry is ChatEntry.AssistantMessage && entry.turn > 0) next = entry.turn
    }

    // Nearest resolved turn at or before each index; the anchors are assistant
    // and tool rows, which carry their own turn.
    val preceding = IntArray(size)
    var last = 0
    for (i in 0 until size) {
        preceding[i] = last
        if (owner[i] > 0) last = owner[i]
    }

    for (i in 0 until size) {
        if (owner[i] > 0) continue
        val entry = entries[i]
        owner[i] = when {
            entry is ChatEntry.UserMessage && !entry.fromPlugin ->
                nextAssistant[i].takeIf { it > 0 } ?: (preceding[i] + 1).coerceAtLeast(1)

            else -> preceding[i]
        }
    }
    return owner
}

/** Mutable `Turn -> Group -> spans` while the ledger is being folded. */
private class TurnBuilder(private val turn: Int?) {
    val groups = ArrayList<GroupBuilder>()

    fun addMessage(span: TrajectorySpan) {
        val last = groups.lastOrNull()
        if (last?.title == MESSAGE_GROUP) {
            last.spans += span
        } else {
            groups += GroupBuilder(MESSAGE_GROUP, arrayListOf(span))
        }
    }

    fun addStep(step: Int, span: TrajectorySpan) {
        val title = "Step $step"
        val existing = groups.firstOrNull { it.title == title }
        if (existing != null) existing.spans += span else groups += GroupBuilder(title, arrayListOf(span))
    }

    fun addTool(span: TrajectorySpan) {
        // A `tool/call` carries no step in this app's row model, but it always
        // follows the assistant step that produced it in seq order, so the most
        // recent step group is its owner — exactly where the web's
        // `expandAssistant` puts the tool cell.
        val step = groups.lastOrNull { it.title.startsWith("Step ") }
        if (step != null) step.spans += span else groups += GroupBuilder("Step 1", arrayListOf(span))
    }

    fun build(endedTurns: Set<Int>): TrajectoryTurn = TrajectoryTurn(
        turn = turn,
        groups = groups.map { it.build() },
        open = turn != null && turn !in endedTurns,
    )
}

private class GroupBuilder(val title: String, val spans: ArrayList<TrajectorySpan>) {
    fun build(): TrajectoryGroup = TrajectoryGroup(
        title = title,
        description = groupDescription(spans),
        spans = spans.sortedBy { it.index },
    )
}

private const val MESSAGE_GROUP = "Message"

/** Web `groupDescription` (`layout.ts:651-683`): wall span + tool histogram. */
private fun groupDescription(spans: List<TrajectorySpan>): String? {
    val parts = ArrayList<String>(2)
    val times = ArrayList<Long>()
    for (span in spans) {
        val startedAt = span.startedAt ?: continue
        times += startedAt
        val seconds = span.timeSeconds
        if (span.kind == TrajectoryKind.TOOL && seconds != null && seconds > 0) {
            times += startedAt + (seconds * 1000).roundToLong()
        }
    }
    val spanSeconds = when {
        times.size >= 2 -> ((times.maxOrNull() ?: 0L) - (times.minOrNull() ?: 0L)) / 1000.0
        else -> spans.firstOrNull { it.startedAt == times.firstOrNull() }?.timeSeconds
    }
    if (spanSeconds != null && spanSeconds >= 0) {
        parts += "${formatMilliseconds((spanSeconds * 1000).roundToLong())} ms"
    }
    val tools = LinkedHashMap<String, Int>()
    for (span in spans) {
        if (span.kind != TrajectoryKind.TOOL) continue
        val name = span.toolName ?: continue
        tools[name] = (tools[name] ?: 0) + 1
    }
    for ((name, count) in tools) parts += if (count > 1) "$name×$count" else name
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" ")
}

/** Web `formatDurationMillis` with the same thousands separators. */
private fun formatMilliseconds(value: Long): String =
    String.format(java.util.Locale.US, "%,d", value)

private fun plural(count: Int, noun: String): String =
    if (count == 1) "1 $noun" else "$count ${noun}s"

// ------------------------------------------------------------------ cells

private fun userSpan(entry: ChatEntry.UserMessage, index: Int): TrajectorySpan {
    val images = entry.attachments.count { it.kind == "image" }
    val files = entry.attachments.count { it.kind == "file" }
    // Web `inputCellDetail`: attachments are the `text` prefix, the prose is the
    // markdown preview, so a labelled image-only record never renders blank.
    val attachmentSummary = listOfNotNull(
        if (entry.text.isBlank() && images > 0) "Images ×$images" else null,
        if (files > 0) "Files ×$files" else null,
    ).joinToString(" · ")
    return TrajectorySpan(
        index = index,
        kind = TrajectoryKind.USER,
        text = attachmentSummary,
        previewMarkdown = entry.text.takeIf { it.isNotBlank() },
        inputDetail = entry.text.takeIf { it.isNotBlank() },
        sourceSeq = entry.seq,
        timeSeconds = 0.0,
        startedAt = entry.time,
    )
}

private fun assistantSpan(entry: ChatEntry.AssistantMessage, index: Int): TrajectorySpan {
    val message = entry.text.takeIf { it.isNotBlank() }
    val thinking = entry.reasoning.takeIf { it.isNotBlank() }
    return TrajectorySpan(
        index = index,
        kind = TrajectoryKind.MESSAGE,
        // Web `expandAssistant`: prose and reasoning both belong to the single
        // assistant record; only a step with neither is "Tool call only".
        text = if (message == null && thinking == null) TOOL_CALL_ONLY else "",
        previewMarkdown = message ?: thinking,
        outputDetail = message,
        thinkingDetail = thinking,
        sourceSeq = entry.seq,
        startedAt = entry.time,
        turn = entry.turn,
        step = entry.step,
    )
}

private fun toolSpan(
    entry: ChatEntry.ToolCall,
    index: Int,
    parentCallId: String? = null,
): TrajectorySpan {
    // Web `summarizeResult`: a failure's contract is its code, not its prose; a
    // text result is the preview; an empty one is the "No output" marker.
    val result: String = when {
        entry.isError -> entry.errorCode ?: "error"
        entry.result.isNullOrBlank() -> "No output"
        else -> ""
    }
    val resultPreview = entry.result?.takeIf { it.isNotBlank() && !entry.isError }
    return TrajectorySpan(
        index = index,
        // A dispatched sub-call is the web's own `subtool` cell, not a `tool` one.
        kind = if (parentCallId.isNullOrEmpty()) TrajectoryKind.TOOL else TrajectoryKind.SUBTOOL,
        text = entry.name,
        previewMarkdown = entry.arguments.takeIf { it.isNotBlank() },
        result = result,
        resultPreviewMarkdown = resultPreview,
        inputDetail = entry.arguments.takeIf { it.isNotBlank() },
        outputDetail = entry.result?.takeIf { it.isNotBlank() },
        toolName = entry.name.takeIf { it.isNotBlank() },
        callId = entry.callId.takeIf { it.isNotBlank() },
        parentCallId = parentCallId?.takeIf { it.isNotBlank() },
        sourceSeq = entry.seq,
        startedAt = entry.time,
        status = when {
            entry.isError -> TrajectoryStatus.ERROR
            entry.result == null -> TrajectoryStatus.RUNNING
            else -> TrajectoryStatus.COMPLETE
        },
        turn = entry.turn.takeIf { it > 0 },
    )
}

private fun noticeSpan(entry: ChatEntry.Notice, index: Int): TrajectorySpan {
    val kind = when (entry.kind) {
        "model-retry" -> TrajectoryKind.RETRY
        "turn-error", "turn-max-tokens", "agent/error", "session/error" -> TrajectoryKind.ERROR
        else -> TrajectoryKind.CONTEXT
    }
    val waiting = kind == TrajectoryKind.RETRY && entry.text.startsWith("Waiting")
    return TrajectorySpan(
        index = index,
        kind = kind,
        text = entry.text.ifBlank { entry.kind },
        previewMarkdown = entry.detail?.takeIf { it.isNotBlank() },
        inputDetail = entry.detail?.takeIf { it.isNotBlank() },
        sourceSeq = entry.seq,
        timeSeconds = 0.0,
        startedAt = entry.time,
        status = when {
            entry.severity == NoticeSeverity.ERROR -> TrajectoryStatus.ERROR
            waiting -> TrajectoryStatus.RUNNING
            else -> TrajectoryStatus.COMPLETE
        },
    )
}

private fun compactionSpan(entry: ChatEntry.Notice, index: Int): TrajectorySpan = TrajectorySpan(
    index = index,
    kind = TrajectoryKind.COMPACTED,
    // Web `layout.ts:333-347`: a complete compaction that *has* a summary shows
    // the summary preview and leaves `text` empty; only a summary-less one falls
    // back to the "Context compacted" marker copy this app already carries.
    text = if (entry.detail == null) entry.text else "",
    previewMarkdown = entry.detail?.takeIf { it.isNotBlank() },
    outputDetail = entry.detail?.takeIf { it.isNotBlank() },
    sourceSeq = entry.seq,
    startedAt = entry.time,
)

/**
 * The web has no Trajectory node for `todo/write`; its Chat target draws the
 * plan card. Keeping the write visible as a CONTEXT record is the app's honest
 * alternative to dropping a model-visible event from the ledger.
 */
private fun todosSpan(entry: ChatEntry.Todos, index: Int): TrajectorySpan {
    val preview = entry.items.take(8).joinToString("; ") { it.content }.takeIf { it.isNotBlank() }
    return TrajectorySpan(
        index = index,
        kind = TrajectoryKind.CONTEXT,
        text = "Todos · ${entry.items.size}",
        previewMarkdown = preview,
        inputDetail = preview,
        sourceSeq = entry.seq,
        timeSeconds = 0.0,
    )
}

// --------------------------------------------------------------- timeline

/** Web `deriveTrajectoryTimeline` in sequence mode (`timeline.ts:75-118`). */
private fun deriveTimeline(turns: List<TrajectoryTurn>): TrajectoryTimeline? {
    val spans = ArrayList<TrajectoryTimelineSpan>()
    val boundaries = ArrayList<TrajectoryTurnBoundary>()
    for (turn in turns) {
        val cells = turn.spans
        if (cells.isEmpty()) continue
        if (turn.turn != null) boundaries += TrajectoryTurnBoundary(turn.turn, spans.size)
        for (cell in cells) {
            spans += TrajectoryTimelineSpan(
                start = spans.size,
                end = spans.size + 1,
                index = cell.index,
                kind = cell.kind,
                lane = cell.lane,
                label = cell.displayText,
                isError = cell.isError,
            )
        }
    }
    if (spans.isEmpty()) return null
    return TrajectoryTimeline(start = 0, end = spans.size, spans = spans, turnBoundaries = boundaries)
}
