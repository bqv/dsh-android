package uk.xa0.dsh.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.model.ScheduleDelivery
import uk.xa0.dsh.model.ScheduleEntry
import uk.xa0.dsh.model.ScheduleStatus
import uk.xa0.dsh.model.scheduleDisplayTitle
import uk.xa0.dsh.model.scheduleFrequencyLabel
import uk.xa0.dsh.model.scheduleFrequencyLabelWithZone
import uk.xa0.dsh.model.scheduleRelativeLabel
import uk.xa0.dsh.model.scheduleStamp
import uk.xa0.dsh.model.scheduleStatusLabel
import uk.xa0.dsh.model.parseScheduleInstant
import uk.xa0.dsh.ScheduleHistoryState
import uk.xa0.dsh.SchedulePageState
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.util.Locale
import java.util.TimeZone

/**
 * The host's Automation tasks, as a sheet: every schedule the host holds, what it will
 * do, where it will do it, and the one verb this client has over it.
 *
 * ## Why here, and not About or Settings
 *
 * An automation is host state, not an app fact and not a preference. About already
 * exists to describe *this client* and its connection; Settings holds choices that
 * change how the app talks to the host. The web puts Automation tasks in its own
 * sidebar panel, at the same level as a session, because it is a place you go to look
 * at work that runs without you. The drawer's footer is this app's nearest equivalent:
 * one row, below the session list, reachable in one tap and never in the composer's
 * way. It is deliberately not a session and never appears in the roster.
 *
 * ## What this sheet is allowed to do
 *
 * It reads `schedule/catalog` and one task's `schedule/history`, and it deletes a task
 * after a confirmation that names it. It does **not** enable or disable a task and does
 * not create one, because the host offers neither to a client: `schedule/update`
 * carries only a name, an instruction and a timing, it refuses an ended task with
 * `schedule_ended`, and no `schedule/create` remote exists at all — the web's own
 * "New" button starts a session, where the agent's `schedule_create` tool does the
 * work. The copy says so plainly rather than growing a control that cannot work.
 *
 * ## What it must never do
 *
 * A schedule is a standing instruction that acts on its own, so nothing here describes
 * one as a note or a reminder card: every row says what it does, and every row says
 * which session it delivers into. An inactive task is drawn as inactive and is never
 * counted as running. A failure is printed where it happened and logged by the view
 * model; silence is the defect this app keeps re-learning.
 */

/** The sheet's container: a full-height modal around [AutomationContent]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AutomationSheet(
    page: SchedulePageState,
    historyTaskId: String?,
    history: ScheduleHistoryState,
    deletingId: String?,
    actionError: String?,
    notice: String?,
    sessionTitle: (String) -> String?,
    onOpen: () -> Unit,
    onRetry: () -> Unit,
    onHistory: (ScheduleEntry) -> Unit,
    onLoadMoreHistory: (ScheduleEntry) -> Unit,
    onDelete: (ScheduleEntry) -> Unit,
    onDismissAction: () -> Unit,
    onOpenSession: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val colors = DshTheme.colors
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        // One read per open: the sheet is composed while it is shown, so this is the
        // moment the list can be stale, and the moment it is cheapest to replace.
        LaunchedEffect(Unit) { onOpen() }
        AutomationContent(
            page = page,
            historyTaskId = historyTaskId,
            history = history,
            deletingId = deletingId,
            actionError = actionError,
            notice = notice,
            sessionTitle = sessionTitle,
            onRetry = onRetry,
            onHistory = onHistory,
            onLoadMoreHistory = onLoadMoreHistory,
            onDelete = onDelete,
            onDismissAction = onDismissAction,
            onOpenSession = onOpenSession,
        )
    }
}

/**
 * The sheet's body, split out so it can be composed without a bottom sheet.
 *
 * [now] and [systemTimeZone] are parameters for the same reason the model's stamp
 * functions take them: a list whose "in 3 hours" moves with the build machine is not
 * something a test can assert, and a rule's zone suffix depends on the device's zone.
 */
@Composable
fun AutomationContent(
    page: SchedulePageState,
    historyTaskId: String?,
    history: ScheduleHistoryState,
    deletingId: String?,
    actionError: String?,
    notice: String?,
    sessionTitle: (String) -> String?,
    onRetry: () -> Unit,
    onHistory: (ScheduleEntry) -> Unit,
    onLoadMoreHistory: (ScheduleEntry) -> Unit,
    onDelete: (ScheduleEntry) -> Unit,
    onDismissAction: () -> Unit,
    onOpenSession: (String) -> Unit,
    now: Long = System.currentTimeMillis(),
    systemTimeZone: String = TimeZone.getDefault().id,
) {
    val colors = DshTheme.colors
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var confirming by rememberSaveable { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf(ScheduleFilter.ALL) }

    val rows = page.rows
    val selected = rows.firstOrNull { it.id == selectedId }
    val zone = remember(systemTimeZone) { TimeZone.getTimeZone(systemTimeZone) }
    val locale = Locale.getDefault()

    // A row that has left the catalogue (deleted here or elsewhere) takes its detail
    // with it; leaving the detail up would describe a task that no longer exists.
    LaunchedEffect(page, selectedId) {
        if (selectedId != null && page is SchedulePageState.Ready && selected == null) {
            selectedId = null
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = DshSpacing.xxl)
            .padding(bottom = DshSpacing.xxl)
            .testTag("automation-tasks"),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.md),
    ) {
        if (selected == null) {
            ListHeader(
                page = page,
                onRetry = onRetry,
                onRefresh = onRetry,
            )
            ActionLine(
                error = actionError,
                notice = notice,
                onDismiss = onDismissAction,
            )
            when (page) {
                SchedulePageState.Idle, SchedulePageState.Loading -> Text(
                    "Loading tasks…",
                    style = DshType.bodySmall,
                    color = colors.labelTertiary,
                )

                is SchedulePageState.Failed -> Text(
                    page.message,
                    style = DshType.bodyMedium,
                    color = colors.warn,
                )

                is SchedulePageState.Ready -> {
                    LossNote(page)
                    val visible = rows.filter { filter.accepts(it) }
                    Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs)) {
                        ScheduleFilter.entries.forEach { option ->
                            FilterTab(
                                label = option.label,
                                isSelected = filter == option,
                                testTag = "schedule-filter-${option.tag}",
                                onClick = { filter = option },
                            )
                        }
                    }
                    val empty = when {
                        rows.isEmpty() -> "No tasks yet. Tasks created in your sessions appear here."
                        visible.isEmpty() && filter == ScheduleFilter.INACTIVE -> "No inactive automation tasks"
                        visible.isEmpty() -> "No matching tasks"
                        else -> null
                    }
                    if (empty != null) {
                        Text(
                            empty,
                            style = DshType.bodySmall,
                            color = colors.labelTertiary,
                        )
                    }
                    visible.forEach { entry ->
                        TaskRow(
                            entry = entry,
                            now = now,
                            zone = zone,
                            locale = locale,
                            systemTimeZone = systemTimeZone,
                            sessionTitle = sessionTitle,
                            onClick = { selectedId = entry.id },
                            onOpenSession = onOpenSession,
                        )
                    }
                }
            }
            Spacer(Modifier.height(DshSpacing.sm))
            Text(
                "These are standing instructions: each one delivers a turn of its own into " +
                    "the session it was created in, whether or not you are looking. A task is " +
                    "created by asking an agent in a session, so this screen lists and manages " +
                    "what already exists.",
                style = DshType.micro,
                color = colors.labelTertiary,
            )
        } else {
            TaskDetail(
                entry = selected,
                now = now,
                zone = zone,
                locale = locale,
                sessionTitle = sessionTitle,
                history = if (historyTaskId == selected.id) history else ScheduleHistoryState.Idle,
                historySelected = historyTaskId == selected.id,
                deleting = deletingId == selected.id,
                actionError = actionError,
                notice = notice,
                onBack = { selectedId = null },
                onHistory = { onHistory(selected) },
                onLoadMoreHistory = { onLoadMoreHistory(selected) },
                onOpenSession = { onOpenSession(selected.sessionId) },
                onDismissAction = onDismissAction,
                onDelete = { confirming = selected.id },
            )
        }
    }

    val confirmingEntry = rows.firstOrNull { it.id == confirming }
    if (confirmingEntry != null) {
        DeleteTaskDialog(
            entry = confirmingEntry,
            deleting = deletingId == confirmingEntry.id,
            onDismiss = { confirming = null },
            onConfirm = {
                // The detail would otherwise describe a row this very tap removes.
                if (selectedId == confirmingEntry.id) selectedId = null
                confirming = null
                onDelete(confirmingEntry)
            },
        )
    }
}

/** The web page's three status tabs, in its own words. */
private enum class ScheduleFilter(val label: String, val tag: String) {
    ALL("All", "all"),
    ENABLED("Enabled", "enabled"),
    INACTIVE("Inactive", "inactive");

    /**
     * Whether this tab shows a row.
     *
     * `Enabled` is exactly [ScheduleStatus.ACTIVE]. A status this build does not know
     * appears under `All` only: calling it inactive would be a claim about the host,
     * and the row already carries an explicit "Unknown status" chip.
     */
    fun accepts(entry: ScheduleEntry): Boolean = when (this) {
        ALL -> true
        ENABLED -> entry.status == ScheduleStatus.ACTIVE
        INACTIVE -> entry.status == ScheduleStatus.INACTIVE
    }
}

/** One status tab: a small pill, with the selected state exposed to accessibility. */
@Composable
private fun FilterTab(
    label: String,
    isSelected: Boolean,
    testTag: String,
    onClick: () -> Unit,
) {
    val colors = DshTheme.colors
    Text(
        text = label,
        style = DshType.micro,
        color = if (isSelected) colors.labelPrimary else colors.labelTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(DshSpacing.xs))
            .background(if (isSelected) colors.active else Color.Transparent)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs)
            .testTag(testTag)
            // A named `isSelected`, because a bare `selected` inside this receiver
            // would resolve to the semantics property being written, not the argument.
            .semantics { selected = isSelected },
    )
}

/** Heading, the honest one-sentence frame, and the refresh control. */
@Composable
private fun ListHeader(
    page: SchedulePageState,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
) {
    val colors = DshTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Automation tasks", style = DshType.titleMedium, color = colors.labelPrimary)
            val ready = page as? SchedulePageState.Ready
            val summary = when {
                ready == null -> "Schedules that deliver into a session, on their own."
                ready.entries.isEmpty() -> "Schedules that deliver into a session, on their own."
                else -> {
                    val active = ready.entries.count { it.status == ScheduleStatus.ACTIVE }
                    val total = ready.entries.size
                    "$total task${if (total == 1) "" else "s"} · $active enabled"
                }
            }
            Text(summary, style = DshType.micro, color = colors.labelTertiary)
        }
        Spacer(Modifier.width(DshSpacing.md))
        if (page is SchedulePageState.Failed) {
            SheetAction("Retry", onRetry)
        } else if (page is SchedulePageState.Ready) {
            SheetAction("Refresh", onRefresh)
        }
    }
}

/** The reasons this list may be missing rows, said in the place the rows are missing. */
@Composable
private fun LossNote(page: SchedulePageState.Ready) {
    val colors = DshTheme.colors
    val clauses = ArrayList<String>(3)
    if (page.droppedRows > 0) {
        clauses += "${page.droppedRows} row${if (page.droppedRows == 1) "" else "s"} named no task id or " +
            "no session and cannot be listed"
    }
    if (page.unknownStatusRows > 0) {
        clauses += "${page.unknownStatusRows} task${if (page.unknownStatusRows == 1) "" else "s"} " +
            "carried a status this build does not know, so ${if (page.unknownStatusRows == 1) "it is" else "they are"} " +
            "not counted as running"
    }
    if (page.unknownKindRows > 0) {
        clauses += "${page.unknownKindRows} task${if (page.unknownKindRows == 1) "" else "s"} " +
            "carry a rule kind this build does not know, so their timing is not rendered"
    }
    if (clauses.isEmpty()) return
    Text(
        text = "This list is incomplete: ${clauses.joinToString("; ")}.",
        style = DshType.micro,
        color = colors.warn,
    )
}

/** The sheet's error or notice, with a way to dismiss it. */
@Composable
private fun ActionLine(error: String?, notice: String?, onDismiss: () -> Unit) {
    val colors = DshTheme.colors
    if (error == null && notice == null) return
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = error ?: notice.orEmpty(),
            style = DshType.bodySmall,
            color = if (error != null) colors.warn else colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
        SheetAction("Dismiss", onDismiss)
    }
}

/** One row: what it does, when, which session, and when it last delivered there. */
@Composable
private fun TaskRow(
    entry: ScheduleEntry,
    now: Long,
    zone: TimeZone,
    locale: Locale,
    systemTimeZone: String,
    sessionTitle: (String) -> String?,
    onClick: () -> Unit,
    onOpenSession: (String) -> Unit,
) {
    val colors = DshTheme.colors
    val title = scheduleDisplayTitle(entry)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshSpacing.md))
            .clickableNoRipple(onClick = onClick)
            .padding(vertical = DshSpacing.md, horizontal = DshSpacing.md)
            .testTag("schedule-row-${entry.id}"),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xxs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Schedule,
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.width(16.dp).height(16.dp),
            )
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = title,
                style = DshType.titleSmall,
                color = if (entry.status == ScheduleStatus.INACTIVE) {
                    colors.labelTertiary
                } else {
                    colors.labelPrimary
                },
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            StatusChip(entry.status)
        }
        Text(
            text = scheduleFrequencyLabel(entry.spec, systemTimeZone),
            style = DshType.bodySmall,
            color = colors.labelSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = DshSpacing.xl),
        )
        Text(
            text = targetLine(entry, now, zone, locale),
            style = DshType.bodySmall,
            color = if (entry.status == ScheduleStatus.ACTIVE) colors.labelPrimaryDimmed else colors.labelTertiary,
            modifier = Modifier.padding(start = DshSpacing.xl),
        )
        // The session is its own tap target, and it says "Into" rather than naming a
        // destination: a schedule delivers into a session, it does not create one.
        val session = sessionLabel(entry.sessionId, sessionTitle)
        Row(
            Modifier
                .padding(start = DshSpacing.xl)
                .clip(RoundedCornerShape(DshSpacing.xs))
                .clickableNoRipple { onOpenSession(entry.sessionId) }
                .padding(vertical = DshSpacing.xxs, horizontal = DshSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Into $session",
                style = DshType.bodySmall,
                color = colors.link,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .semantics { contentDescription = "Open session $session" },
            )
        }
        Text(
            text = deliveryLine(entry, now, zone, locale),
            style = DshType.micro,
            color = colors.labelTertiary,
            modifier = Modifier.padding(start = DshSpacing.xl),
        )
    }
}

/** `Enabled`, `Inactive`, or an explicit unknown. Nothing is drawn for an enabled row. */
@Composable
private fun StatusChip(status: ScheduleStatus) {
    val colors = DshTheme.colors
    if (status == ScheduleStatus.ACTIVE) return
    val text = scheduleStatusLabel(status)
    val tint = if (status == ScheduleStatus.UNKNOWN) colors.warn else colors.labelTertiary
    Text(
        text = text,
        style = DshType.micro,
        color = tint,
        modifier = Modifier
            .clip(RoundedCornerShape(DshSpacing.xs))
            .background(colors.bgLayer2)
            .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xxs),
    )
}

/**
 * When the task runs: the next committed target while enabled, the final one once it
 * has ended.
 *
 * The stamp is in the *device's* zone — the same choice the web makes for this pair —
 * while the rule above it names its own zone when that differs. Both are shown because
 * "09:00" means two different instants depending on which of the two you read.
 */
private fun targetLabel(entry: ScheduleEntry): String =
    if (entry.status == ScheduleStatus.ACTIVE) "Next run" else "Final target"

private fun targetValue(entry: ScheduleEntry, now: Long, zone: TimeZone, locale: Locale): String {
    val target = entry.scheduledAtMillis
        ?: return "unreadable target \"${entry.scheduledAt}\""
    val stamp = scheduleStamp(target, now, zone, locale)
    return if (entry.status == ScheduleStatus.ACTIVE) {
        "$stamp (${scheduleRelativeLabel(target, now)})"
    } else {
        // The host keeps the final committed target on an ended task. It is a fact
        // about the rule, not a countdown, so no relative label is put on it.
        stamp
    }
}

/** The row's whole line: a label that changes with status, and the value it labels. */
private fun targetLine(entry: ScheduleEntry, now: Long, zone: TimeZone, locale: Locale): String =
    "${targetLabel(entry)} ${targetValue(entry, now, zone, locale)}"

/** When this task last delivered into its session, or that it never has. */
private fun deliveryLine(entry: ScheduleEntry, now: Long, zone: TimeZone, locale: Locale): String {
    val delivery = entry.lastDelivery ?: return "Never delivered"
    val millis = parseScheduleInstant(delivery.deliveredAt)
    val stamp = millis?.let { scheduleStamp(it, now, zone, locale) } ?: delivery.deliveredAt
    return "Last delivered $stamp"
}

/** The session's title when this client holds it, otherwise the id it was stored against. */
private fun sessionLabel(sessionId: String, sessionTitle: (String) -> String?): String {
    val title = sessionTitle(sessionId)?.trim()
    return if (title.isNullOrEmpty()) sessionId else title
}

/** One task, opened: the rule, the session, the history, and the only verb there is. */
@Composable
private fun TaskDetail(
    entry: ScheduleEntry,
    now: Long,
    zone: TimeZone,
    locale: Locale,
    sessionTitle: (String) -> String?,
    history: ScheduleHistoryState,
    historySelected: Boolean,
    deleting: Boolean,
    actionError: String?,
    notice: String?,
    onBack: () -> Unit,
    onHistory: () -> Unit,
    onLoadMoreHistory: () -> Unit,
    onOpenSession: () -> Unit,
    onDismissAction: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = DshTheme.colors

    // The detail is the only place records are read, so opening it is the read. The
    // guard keeps a recomposition or a rotation from issuing the same request again;
    // the view model owns the result, and Retry is explicit.
    LaunchedEffect(entry.id) {
        if (!historySelected) onHistory()
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        TextButton(
            onClick = onBack,
            contentPadding = PaddingValues(horizontal = 0.dp, vertical = DshSpacing.xs),
        ) {
            Icon(Icons.Rounded.ArrowBack, contentDescription = null, tint = colors.labelSecondary)
            Spacer(Modifier.width(DshSpacing.xs))
            Text("All tasks", color = colors.labelSecondary, style = DshType.bodyMedium)
        }
    }

    Text(
        text = scheduleDisplayTitle(entry),
        style = DshType.titleLarge,
        color = colors.labelPrimary,
    )
    Text(
        text = "Status: ${scheduleStatusLabel(entry.status)}",
        style = DshType.bodyMedium,
        color = when (entry.status) {
            ScheduleStatus.ACTIVE -> colors.success
            ScheduleStatus.INACTIVE -> colors.labelTertiary
            ScheduleStatus.UNKNOWN -> colors.warn
        },
    )
    DetailRow("Rule", scheduleFrequencyLabelWithZone(entry.spec))
    DetailRow(targetLabel(entry), targetValue(entry, now, zone, locale))
    DetailRow("Delivers into", sessionLabel(entry.sessionId, sessionTitle))
    DetailRow("Task ID", entry.id, mono = true)

    Text(
        "Every time this runs, its instruction is delivered into that session as a new " +
            "turn, whether or not you are looking. It does not create a session of its own.",
        style = DshType.micro,
        color = colors.labelTertiary,
    )

    SheetAction("Open session", onOpenSession)

    HorizontalDivider(color = colors.borderL1)

    Text("Instruction", style = DshType.titleSmall, color = colors.labelPrimary)
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshSpacing.md))
            .background(colors.bgLayer1)
            .padding(DshSpacing.lg),
    ) {
        Text(
            text = entry.prompt.ifBlank { "No instruction was stored with this task." },
            style = DshType.bodySmall,
            color = colors.labelSecondary,
        )
    }

    HorizontalDivider(color = colors.borderL1)

    Text(
        "Delivery records",
        style = DshType.titleSmall,
        color = colors.labelPrimary,
    )
    ActionLine(error = actionError, notice = notice, onDismiss = onDismissAction)
    HistoryBody(
        state = history,
        now = now,
        zone = zone,
        locale = locale,
        onLoadMore = onLoadMoreHistory,
        onRetry = onHistory,
    )

    HorizontalDivider(color = colors.borderL1)

    Text(
        "This host has no way to enable or disable a task. Status is the host's own: a " +
            "one-shot task ends when it delivers, and a recurring task ends when its rule has " +
            "no next occurrence. Deleting it is the only way to stop it from here.",
        style = DshType.micro,
        color = colors.labelTertiary,
    )
    TextButton(
        onClick = onDelete,
        enabled = !deleting,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = DshSpacing.xs),
    ) {
        Text(
            text = if (deleting) "Deleting…" else "Delete task",
            color = if (deleting) colors.labelTertiary else colors.warn,
            style = DshType.bodyMedium,
        )
    }
}

/** One labelled fact in the detail. */
@Composable
private fun DetailRow(label: String, value: String, mono: Boolean = false) {
    val colors = DshTheme.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = label,
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            modifier = Modifier.width(96.dp),
        )
        Spacer(Modifier.width(DshSpacing.md))
        Text(
            text = value,
            style = if (mono) DshType.codeSmall else DshType.bodySmall,
            color = colors.labelSecondary,
            modifier = Modifier.weight(1f),
        )
    }
}

/** The newest delivery records the host holds, with the retention it applied. */
@Composable
private fun HistoryBody(
    state: ScheduleHistoryState,
    now: Long,
    zone: TimeZone,
    locale: Locale,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val colors = DshTheme.colors
    when (state) {
        ScheduleHistoryState.Idle -> Text(
            "Not loaded yet.",
            style = DshType.bodySmall,
            color = colors.labelTertiary,
        )

        ScheduleHistoryState.Loading -> Text(
            "Loading delivery records…",
            style = DshType.bodySmall,
            color = colors.labelTertiary,
        )

        is ScheduleHistoryState.Unavailable -> Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                state.message,
                style = DshType.bodySmall,
                color = colors.warn,
                modifier = Modifier.weight(1f),
            )
            SheetAction("Retry", onRetry)
        }

        is ScheduleHistoryState.Ready -> {
            val page = state.page
            if (page.earlierRecordsUnavailable) {
                Text(
                    "Older delivery records may be unavailable. They are not reconstructed.",
                    style = DshType.micro,
                    color = colors.labelTertiary,
                )
            }
            if (page.earlierRecordsPruned) {
                Text(
                    "Earlier delivery records have been cleared.",
                    style = DshType.micro,
                    color = colors.labelTertiary,
                )
            }
            if (page.records.isEmpty()) {
                Text(
                    "No delivery record available",
                    style = DshType.bodySmall,
                    color = colors.labelTertiary,
                )
            }
            page.records.forEach { record ->
                DeliveryRow(record, now = now, zone = zone, locale = locale)
            }
            if (page.retentionDays > 0 || page.retentionRecords > 0) {
                Text(
                    text = "Each task keeps up to ${page.retentionRecords} delivery records " +
                        "from the last ${page.retentionDays} days. Clearing records does not " +
                        "stop the task.",
                    style = DshType.micro,
                    color = colors.labelTertiary,
                )
            }
            state.moreError?.let {
                Text(it, style = DshType.bodySmall, color = colors.warn)
            }
            if (page.nextBefore != null) {
                if (state.loadingMore) {
                    Text("Loading…", style = DshType.micro, color = colors.labelTertiary)
                } else {
                    SheetAction("Load more", onLoadMore)
                }
            }
        }
    }
}

/** One saved delivery: the occurrence it was due at, and when it actually landed. */
@Composable
private fun DeliveryRow(record: ScheduleDelivery, now: Long, zone: TimeZone, locale: Locale) {
    val colors = DshTheme.colors
    val due = parseScheduleInstant(record.scheduledAt)
    val delivered = parseScheduleInstant(record.deliveredAt)
    Column(Modifier.fillMaxWidth().padding(vertical = DshSpacing.xxs)) {
        Text(
            text = "Due " + (due?.let { scheduleStamp(it, now, zone, locale) } ?: record.scheduledAt),
            style = DshType.bodySmall,
            color = colors.labelSecondary,
        )
        Text(
            text = "Delivered " + (
                delivered?.let { scheduleStamp(it, now, zone, locale) } ?: record.deliveredAt
                ),
            style = DshType.micro,
            color = colors.labelTertiary,
        )
        record.prompt?.let {
            Text(
                text = it,
                style = DshType.micro,
                color = colors.labelTertiary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The delete confirmation, in the web's own words and the app's own dialog shape.
 *
 * It names the task, states what is removed (the task and its saved delivery records)
 * and what is not (the session and its messages, and any queued message). The confirm
 * button says **Confirm deletion** rather than "Delete", so the second tap is a
 * decision and not a repeat of the first.
 */
@Composable
private fun DeleteTaskDialog(
    entry: ScheduleEntry,
    deleting: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = DshTheme.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.bgBase,
        title = { Text("Delete this task?", color = colors.labelPrimary, style = DshType.titleMedium) },
        text = {
            Text(
                text = "\"${scheduleDisplayTitle(entry)}\" will stop triggering, and this task's " +
                    "saved delivery records will be deleted with it. Its session and every " +
                    "message in it remain, and queued messages are not withdrawn.",
                style = DshType.bodyMedium,
                color = colors.labelSecondary,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !deleting) {
                Text(
                    text = if (deleting) "Deleting…" else "Confirm deletion",
                    color = if (deleting) colors.labelTertiary else colors.warn,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = colors.labelSecondary)
            }
        },
    )
}

/** The sheet's small text action, matching the balance sheet's own. */
@Composable
private fun SheetAction(label: String, onClick: () -> Unit) {
    val colors = DshTheme.colors
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 0.dp, vertical = DshSpacing.xs),
    ) {
        Text(label, color = colors.link, style = DshType.bodyMedium)
    }
}
