package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import uk.xa0.dsh.JobItem
import uk.xa0.dsh.model.JobObservation
import uk.xa0.dsh.model.JobsWire
import uk.xa0.dsh.diag.DiagLazyList
import uk.xa0.dsh.diag.ScrollDiag
import uk.xa0.dsh.diag.diagDrag
import uk.xa0.dsh.diag.diagOffset
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.rememberTouchGate
import uk.xa0.dsh.ui.scroll.TailFollow
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import uk.xa0.dsh.ui.touchGate

/**
 * The header's background-jobs seat.
 *
 * The web puts a job count in the conversation header and opens a list from it.
 * The phone's header has one trailing slot, so the count *is* the affordance:
 * tapping it opens the list — and so the seat has to exist for **every** job the
 * session can see, not only for a running one, or the list becomes unreachable the
 * moment the last job settles. That is the web's `visibleCount > 0`.
 *
 * The dot is the activity signal, and it mirrors the session rows' dots because it
 * answers the same question — "did anything happen while I was looking elsewhere?":
 *  * a job is running → the ongoing chase (blue), so a live job is visible at a
 *    glance even though the agent's own turn may be over;
 *  * a job finished since the list was last opened → the green done dot;
 *  * jobs exist but nothing is running and nothing is new → a muted count, no dot.
 */
@Composable
fun JobsSeat(
    jobs: List<JobItem>,
    finishedUnseen: Boolean,
    onClick: () -> Unit,
) {
    val colors = DshTheme.colors
    if (jobs.isEmpty()) return
    val running = jobs.count { it.running }

    Row(
        Modifier
            .clip(RoundedCornerShape(DshRadius.pill))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The dot is the *activity* signal and is absent when there is none, exactly
        // as the web's trigger draws its ongoing dot only while something runs. The
        // green done dot is this app's own reminder that something finished unseen.
        when {
            running > 0 -> StateDot(state = DotState.ONGOING, size = 10.dp)
            finishedUnseen -> StateDot(state = DotState.DONE, size = 10.dp)
        }
        if (running > 0 || finishedUnseen) Spacer(Modifier.width(DshSpacing.sm))
        Text(
            // Deliberately terser than the web's `count.live.*` ("3 background jobs
            // running"): this seat shares the phone's header with the lineage chip and
            // the Files button, and the count is the affordance, not the label. The
            // sheet behind it carries the web's exact vocabulary.
            //
            // The seat itself stays for **any** job the session can see, which is what
            // the web's `visibleCount > 0` does. Hiding it whenever nothing was running
            // and nothing was new meant the sheet — the only route to a session's job
            // history — became unreachable the moment the last job settled, so a
            // session full of finished jobs looked exactly like one that had never run
            // any. That is precisely the "they don't show up at all" report this seat
            // was the visible half of.
            text = when {
                running == 1 -> "1 job"
                running > 1 -> "$running jobs"
                finishedUnseen -> "done"
                jobs.size == 1 -> "1 job"
                else -> "${jobs.size} jobs"
            },
            style = DshType.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = if (running > 0) colors.accent else colors.labelSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The jobs list — the phone's stand-in for the web's header popover.
 *
 * Ported from `dsh-client-ui-jobs/lib/client.js` (`JobListAction` + `JobItem`),
 * because the two now share a transport and it would be perverse for them to
 * disagree about what a row means:
 *
 *  * live rows first, in start order, under a **Running** heading; settled rows
 *    newest-first under a collapsible **Finished N** heading with a client-side
 *    **Clear**;
 *  * a row is expandable exactly when the web's `isObservable` says so — every live
 *    job, plus a settled one that left retained output — and expanding it opens
 *    that job's `job/follow` stream into a terminal panel; collapsing closes it, so
 *    output only flows while someone is watching;
 *  * a live row carries the two-press stop the web has: the first press arms it,
 *    the confirming press within three seconds asks the host to kill.
 */
@Composable
fun JobsSheet(
    jobs: List<JobItem>,
    output: JobObservation?,
    onObserve: (String?) -> Unit,
    onKill: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    // Expanded row, the client-side cleared set, and the settled section's fold —
    // all three are view state the host knows nothing about, exactly as on the web.
    var expandedId by rememberSaveable { mutableStateOf<String?>(null) }
    var cleared by remember { mutableStateOf(emptySet<String>()) }
    var settledOpen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // The armed stop, held here rather than in the row: arming one row must disarm
    // any other, and a row that recycles must not take the arm with it.
    var armedId by remember { mutableStateOf<String?>(null) }

    val ordered = remember(jobs) { JobsWire.ordered(jobs) }
    val liveRows = remember(ordered) { ordered.filter { JobsWire.isLive(it) } }
    val settledRows = remember(ordered, cleared) {
        ordered.filter { !JobsWire.isLive(it) && it.id !in cleared }
    }
    val settledExpanded = settledOpen ?: liveRows.isEmpty()
    val visible = liveRows.size + settledRows.size

    // The roster list, instrumented like every other sheet's list
    // (`docs/SCROLL-DIAG.md`). It is the surface that can steal a drag meant for an
    // expanded row's output panel, and the two records together — this list moved,
    // that one did not — are what name which of them took the gesture.
    val sheetList = rememberLazyListState()
    DiagLazyList(JOB_SHEET_SURFACE, sheetList)

    // A live row's duration is read at composition, not ticked on a timer.
    //
    // The web's popover re-renders every second while something runs, and this did
    // the same — but a 1 Hz state write holds a frame callback open for as long as
    // the sheet is, so the app never goes idle: exactly the cost `LocalAnimatedDots`
    // exists to avoid elsewhere (`ui/components/StateDot.kt`). It buys almost nothing
    // here, because the sheet already recomposes on every `job/output` batch a
    // watched job produces and on every `job/list` roster frame — so a job that is
    // printing refreshes its duration as often as the host says anything. What is
    // lost is a *silent* running job holding a figure that stands still between
    // frames, which is a smaller price than never idling.
    val now = System.currentTimeMillis()

    // A row that leaves the roster cannot stay expanded: its observation stream is
    // gone, and the panel would sit there empty under a heading that no longer has
    // the row.
    LaunchedEffect(jobs.size, expandedId) {
        if (expandedId != null && ordered.none { it.id == expandedId }) {
            expandedId = null
            onObserve(null)
        }
    }

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.menu)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(vertical = DshSpacing.md),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = DshSpacing.xl, end = DshSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Background jobs",
                style = DshType.labelLarge,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickableNoRipple(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Close background jobs",
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Spacer(Modifier.height(DshSpacing.sm))

        if (visible == 0) {
            Text(
                text = "No background jobs in this session.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DshSpacing.xl, vertical = DshSpacing.md),
            )
            return@Column
        }

        LazyColumn(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .diagDrag(JOB_SHEET_SURFACE) { sheetList.diagOffset() },
            state = sheetList,
            verticalArrangement = Arrangement.spacedBy(DshSpacing.xxs),
        ) {
            if (liveRows.isNotEmpty()) {
                item(key = "section-live") { SectionHeading("Running") }
                items(liveRows, key = { it.id }) { job ->
                    JobRow(
                        job = job,
                        output = output,
                        now = now,
                        expanded = expandedId == job.id,
                        onToggle = {
                            val next = if (expandedId == job.id) null else job.id
                            expandedId = next
                            onObserve(next)
                        },
                        armed = armedId == job.id,
                        onArm = { armedId = job.id },
                        onDisarm = { if (armedId == job.id) armedId = null },
                        onConfirm = {
                            armedId = null
                            onKill(job.id)
                        },
                    )
                }
            }
            if (settledRows.isNotEmpty()) {
                item(key = "section-settled") {
                    SectionHeading(
                        text = "Finished ${settledRows.size}",
                        expandable = true,
                        expanded = settledExpanded,
                        onToggle = { settledOpen = !settledExpanded },
                        onClear = {
                            cleared = cleared + settledRows.map { it.id }
                            if (expandedId != null && settledRows.any { it.id == expandedId }) {
                                expandedId = null
                                onObserve(null)
                            }
                        },
                    )
                }
                if (settledExpanded) {
                    items(settledRows, key = { it.id }) { job ->
                        JobRow(
                            job = job,
                            output = output,
                            now = now,
                            expanded = expandedId == job.id,
                            onToggle = {
                                val next = if (expandedId == job.id) null else job.id
                                expandedId = next
                                onObserve(next)
                            },
                            armed = false,
                            onArm = {},
                            onDisarm = {},
                            onConfirm = {},
                        )
                    }
                }
            }
        }
    }
}

/** A list heading: "Running", or the collapsible "Finished N" with its Clear. */
@Composable
private fun SectionHeading(
    text: String,
    expandable: Boolean = false,
    expanded: Boolean = true,
    onToggle: () -> Unit = {},
    onClear: (() -> Unit)? = null,
) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.xl, end = DshSpacing.md, top = DshSpacing.md, bottom = DshSpacing.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier
                .clip(RoundedCornerShape(DshRadius.sm))
                .then(if (expandable) Modifier.clickableNoRipple(onClick = onToggle) else Modifier),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (expandable) {
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                    contentDescription = if (expanded) "Collapse finished jobs" else "Expand finished jobs",
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(DshSpacing.xs))
            }
            Text(text, style = DshType.micro, color = colors.labelTertiary)
        }
        if (onClear != null) {
            Spacer(Modifier.weight(1f))
            Text(
                text = "Clear",
                style = DshType.micro,
                color = colors.labelTertiary,
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .clickableNoRipple(onClick = onClear)
                    .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
            )
        }
    }
}

/** How much of a job is shown before "Show more", for the command and the detail. */
private const val JOB_COLLAPSED_LINES = 4

/**
 * The most of a job's output the panel shows before it scrolls.
 *
 * The web's jobs panel caps its terminal at `--dsl-terminal-output-max-height: 288px`
 * with `overflow: auto`, and the transcript's shell block has used this same 224dp
 * since before this feature existed (`14732a4`: "The 224dp vertical cap and its
 * scroller stay — output is unbounded in that direction"). One cap, one scroller,
 * both places output is read.
 */
private val JOB_OUTPUT_MAX_HEIGHT = 224.dp

/** The scroll surface's name in the latent diagnostics (`docs/SCROLL-DIAG.md`). */
private const val JOB_OUTPUT_SURFACE = "job-output"

/** The roster list's name in the same diagnostics — the other surface a drag can land on. */
private const val JOB_SHEET_SURFACE = "sheet:jobs"

/** How long an armed stop waits for its confirming press (the web's `KILL_ARM_MS`). */
private const val KILL_ARM_MS = 3_000L

/** The wire status → the dot's semantics, exactly as the web's `dotState`. */
private fun dotOf(status: String): DotState = when (JobsWire.dotState(status)) {
    "ongoing" -> DotState.ONGOING
    "warning" -> DotState.WARNING
    "done" -> DotState.DONE
    "error" -> DotState.ERROR
    else -> DotState.IDLE
}

@Composable
private fun JobRow(
    job: JobItem,
    output: JobObservation?,
    now: Long,
    expanded: Boolean,
    onToggle: () -> Unit,
    armed: Boolean,
    onArm: () -> Unit,
    onDisarm: () -> Unit,
    onConfirm: () -> Unit,
) {
    val colors = DshTheme.colors
    // A job is capped and offered rather than clipped, and *one* offer covers the row:
    // two toggles, one under the command and one under the detail, would read as two
    // different things to expand.
    //
    // The overflow flags are only ever written while collapsed: once expanded there is
    // nothing to overflow, and overwriting them there would take the "Show less" away
    // and leave the row stuck open.
    var textExpanded by rememberSaveable(job.id) { mutableStateOf(false) }
    var labelOverflows by remember(job.id) { mutableStateOf(false) }
    var detailOverflows by remember(job.id) { mutableStateOf(false) }
    val live = JobsWire.isLive(job)
    val observable = JobsWire.isObservable(job)
    val detail = JobsWire.detailOf(job)
    val showMore = textExpanded || labelOverflows || detailOverflows
    val duration = JobsWire.duration(
        if (live) now - job.startedAt else (job.finishedAt ?: job.startedAt) - job.startedAt,
    )

    // The armed stop disarms itself, as the web's three-second window does. Keyed on
    // the arm, so the confirming press and the host's own settlement both cancel it.
    LaunchedEffect(armed) {
        if (armed) {
            delay(KILL_ARM_MS)
            onDisarm()
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.lg)
            .clip(RoundedCornerShape(DshRadius.md))
            .then(if (live) Modifier.background(colors.bgLayer2) else Modifier)
            .padding(vertical = DshSpacing.sm),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp)) {
                StateDot(state = dotOf(job.status), size = 10.dp)
            }
            Spacer(Modifier.width(DshSpacing.md))
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .then(if (observable) Modifier.clickableNoRipple(onClick = onToggle) else Modifier),
            ) {
                Text(
                    // Twice what it was. Two lines was enough to read `cd …` and no
                    // further, which is the part of a command that says the least about
                    // what it does — and this row is the only place the command appears,
                    // since the compact seat beside the transcript is one line by design.
                    text = job.label,
                    style = DshType.bodyMedium,
                    color = if (live) colors.labelPrimary else colors.labelSecondary,
                    maxLines = if (textExpanded) Int.MAX_VALUE else JOB_COLLAPSED_LINES,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { layout -> if (!textExpanded) labelOverflows = layout.hasVisualOverflow },
                )
                Text(
                    // Same vocabulary the web's job list uses: the kind, then the live
                    // progress line or the terminal detail, then the duration.
                    text = listOf(JobsWire.statusLabel(job.status), duration)
                        .filter { it.isNotEmpty() }
                        .joinToString(" \u00b7 "),
                    style = DshType.micro,
                    color = if (job.status == "failed") colors.error else colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                detail?.let { text ->
                    Text(
                        text = text,
                        style = DshType.micro,
                        color = colors.labelCaption,
                        maxLines = if (textExpanded) Int.MAX_VALUE else JOB_COLLAPSED_LINES,
                        overflow = TextOverflow.Ellipsis,
                        onTextLayout = { layout -> if (!textExpanded) detailOverflows = layout.hasVisualOverflow },
                    )
                }
                if (showMore) {
                    Text(
                        text = if (textExpanded) "Show less" else "Show more",
                        style = DshType.micro,
                        color = colors.link,
                        modifier = Modifier
                            .padding(top = 2.dp)
                            .clip(RoundedCornerShape(DshRadius.sm))
                            .clickableNoRipple { textExpanded = !textExpanded }
                            // Inside the clickable, so the padding is part of the target:
                            // a bare line of `micro` text is about 15dp tall, which is a
                            // third of a comfortable tap.
                            .padding(vertical = 10.dp, horizontal = 2.dp),
                    )
                }
            }
            if (live) {
                Spacer(Modifier.width(DshSpacing.sm))
                StopButton(armed = armed, label = job.label, onPress = { if (armed) onConfirm() else onArm() })
            } else if (observable) {
                Spacer(Modifier.width(DshSpacing.sm))
                // An indicator only: the whole row is the target, so a 16dp chevron
                // does not need to be one too — and a second, smaller target inside a
                // tappable row is how a tap lands on the wrong thing.
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ExpandLess,
                    contentDescription = if (expanded) {
                        "Hide live output of ${job.label}"
                    } else {
                        "Show live output of ${job.label}"
                    },
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(16.dp),
                )
            }
        }

        if (expanded && observable) {
            Spacer(Modifier.height(DshSpacing.sm))
            JobOutputPanel(
                command = job.label,
                view = output?.takeIf { it.jobId == job.id },
                running = live,
                jobId = job.id,
            )
        }
    }
}

/**
 * The two-press stop: the first press arms it, the confirming press kills.
 *
 * Two presses rather than one is the web's decision and a good one on a phone: this
 * button sits a thumb's width from a row that opens a panel, and killing an agent's
 * work by mistake is not undoable.
 */
@Composable
private fun StopButton(armed: Boolean, label: String, onPress: () -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .border(0.5.dp, if (armed) colors.error else colors.borderL1, RoundedCornerShape(DshRadius.sm))
            .then(if (armed) Modifier.background(colors.warnTertiary) else Modifier)
            .clickableNoRipple(onClick = onPress)
            .padding(horizontal = if (armed) DshSpacing.sm else DshSpacing.xs, vertical = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Stop,
            contentDescription = if (armed) "Confirm stop" else "Stop task $label",
            tint = if (armed) colors.error else colors.labelSecondary,
            modifier = Modifier.size(12.dp),
        )
        if (armed) {
            Spacer(Modifier.width(DshSpacing.xs))
            Text("Confirm stop", style = DshType.micro, color = colors.error, maxLines = 1)
        }
    }
}

/**
 * One job's live output, drawn as a terminal block — the phone's `TerminalBlock`,
 * which is what the web's expanded row mounts.
 *
 * It is the same surface in both places the output appears, the jobs sheet and a
 * chat-log entry, so a job cannot look like two different things depending on
 * where you opened it — including how it scrolls, which is the whole point of
 * asking for it in both places.
 *
 * [view] is null until the first frame lands, and the panel still draws the
 * command: a row that has just been tapped should say what it is watching rather
 * than flashing empty.
 *
 * [jobId] identifies the job whose output this is. It is the tail follow's rearm
 * key, so opening this panel — or swapping it to another job — lands on the newest
 * line. Defaulted so a caller that has no id still gets a following panel.
 */
@Composable
fun JobOutputPanel(
    command: String,
    view: JobObservation?,
    running: Boolean,
    modifier: Modifier = Modifier,
    jobId: String? = null,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.codeBlock)
            .border(0.5.dp, colors.borderL1, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = DshSpacing.md, end = 14.dp, top = 9.dp, bottom = if (running) 9.dp else 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(Modifier.width(22.dp)) {
                StateDot(
                    state = when {
                        running -> DotState.ONGOING
                        view?.error != null -> DotState.ERROR
                        else -> DotState.DONE
                    },
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            Text(
                text = command,
                style = DshType.codeSmall,
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            if (!running) {
                Spacer(Modifier.width(DshSpacing.md))
                JobCopyButton(command)
            }
        }

        // Retention gaps and stream interruptions render as notices above the
        // output, never as output: they are facts about the channel, and running
        // them together with the job's bytes is how a broken stream reads as a
        // job that printed "… earlier output dropped …".
        if (view?.gapBefore == true) {
            Text(
                text = "… earlier output dropped …",
                style = DshType.micro,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
            )
        }
        view?.error?.let { error ->
            Text(
                text = "live output stream interrupted: $error",
                style = DshType.micro,
                color = colors.error,
                modifier = Modifier.padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
            )
        }

        val text = view?.text.orEmpty()
        val lines = remember(text) { linesOf(text) }
        if (lines == null) {
            // A running job that has not printed yet says so; a settled one offers
            // the web's `(no output)` rather than an empty box.
            Text(
                text = if (running) "Waiting for output…" else "(no output)",
                style = DshType.codeSmall,
                color = colors.labelTertiary,
                modifier = Modifier.padding(
                    start = 30.dp,
                    end = 14.dp,
                    top = DshSpacing.sm,
                    bottom = DshSpacing.lg,
                ),
            )
        } else {
            JobOutputStream(lines = lines, jobId = jobId)
        }
    }
}

/** The panel's terminal lines, or null when there is nothing to draw. */
private fun linesOf(text: String): List<String>? {
    if (text.isEmpty()) return null
    // The terminator newline is not an extra blank line to draw.
    val body = if (text.endsWith("\n")) text.dropLast(1) else text
    return body.split('\n')
}

/**
 * A job's output as a scrollable, tail-following terminal.
 *
 * ## Scrolling
 *
 * The panel is a fixed window over an unbounded stream, so it scrolls — vertically,
 * and only vertically. The web wraps rather than panning (`--dsl-terminal-line-whitespace:
 * pre-wrap` on the jobs panel, and its README says the panel "wraps commands and
 * output lines in full"), and this app reached the same conclusion for the
 * transcript's shell block in `14732a4`: a horizontal scroller nested inside a
 * vertically scrolling surface "costs a gesture that has to be told apart from the
 * list's". So lines wrap, and there is deliberately no horizontal scroll state.
 *
 * The viewport is `heightIn(max = …)`, the web's `max-height` + `overflow: auto`,
 * and the same 224dp cap the transcript's terminal block uses (kept there by that
 * same commit: "output is unbounded in that direction").
 *
 * ## Not the Shell tab's cell size
 *
 * The Shell tab's cell size is deliberately not applied here, and this panel is why
 * the distinction is worth writing down: it *reads* like a terminal but is not one.
 * It has no cells — nothing is measured, nothing is addressed by column, and the
 * lines wrap — so a width measured in character cells would have no referent. It
 * also draws in `DshType.codeSmall` (the platform monospace at 11sp) rather than the
 * terminal's Inconsolata at a measured advance, so the two sizes are not the same
 * number and cannot be swapped for one another. And there is no host geometry to
 * keep honest on this side: the PTY's columns and rows are a fact the Shell tab
 * must report, while this window's only dimension is the 224dp cap above.
 *
 * ## Following the tail
 *
 * The newest line is the interesting one, so the panel follows it — and it is
 * [TailFollow], the transcript's own rule, rather than a second implementation of
 * it. That buys the whole behaviour in one call: new lines are followed only while
 * the reader is at the tail, growth inside the last line is corrected by exactly the
 * overflow, a deliberate drag releases the follow, releasing it *at* the tail hands
 * it straight back, and a finger on the list suspends every automatic move. Nothing
 * here is invented; the rules and the reasoning behind each one are in `TailFollow`.
 *
 * [jobId] is the rearm key: opening a job's panel — or switching to another job's —
 * means "follow this again", the same way opening a session does for the chat.
 */
@Composable
private fun JobOutputStream(lines: List<String>, jobId: String?) {
    val colors = DshTheme.colors
    val state = rememberLazyListState()
    val touch = rememberTouchGate()
    TailFollow(
        state = state,
        rearmKey = jobId,
        holding = { touch.isDown },
        onMove = { ScrollDiag.prog(JOB_OUTPUT_SURFACE, it) },
    )
    // Latent, additive instrumentation — one call site per surface, the same way
    // every other scrollable in this app is wired (`docs/SCROLL-DIAG.md`). It draws
    // nothing and consumes no event; it exists so that the next device run can say
    // whether a drag on this panel reached the list at all (`child=1 moved=0` is a
    // gesture something else swallowed; `child=0 moved=0` is one the list never saw),
    // which is the one thing a harness cannot answer.
    DiagLazyList(JOB_OUTPUT_SURFACE, state)
    LazyColumn(
        state = state,
        // The text's gutters are inside the *items*, not on the list, so the whole
        // panel is scrollable: `padding` on the list would put the 30dp gutter
        // outside the scrollable node, and a drag starting in it — which is where a
        // thumb naturally lands on a left-aligned block of mono text — would move
        // nothing. `contentPadding` insets the content while leaving the surface
        // full-width.
        contentPadding = PaddingValues(top = DshSpacing.sm, bottom = DshSpacing.lg),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = JOB_OUTPUT_MAX_HEIGHT)
            .diagDrag(JOB_OUTPUT_SURFACE) { state.diagOffset() }
            .touchGate(touch),
    ) {
        items(lines.size, key = { it }) { index ->
            Text(
                text = lines[index].ifEmpty { " " },
                style = DshType.codeSmall,
                color = colors.labelPrimary,
                modifier = Modifier.padding(start = 30.dp, end = 14.dp),
            )
        }
    }
}

/**
 * Copy the *command*, not the output — the web's `copyText: job.label`.
 *
 * Output is what the panel already shows and what `job_output` hands the model;
 * the command is the one thing about a job that is not otherwise recoverable from
 * a screenshot.
 */
@Composable
private fun JobCopyButton(text: String) {
    val colors = DshTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1_000)
            copied = false
        }
    }
    Text(
        text = if (copied) "Copied" else "Copy",
        style = DshType.micro,
        color = colors.labelSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickable {
                if (copied) return@clickable
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            .padding(vertical = DshSpacing.xs, horizontal = DshSpacing.xs),
    )
}
