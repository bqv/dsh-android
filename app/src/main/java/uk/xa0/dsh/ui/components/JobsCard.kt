package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.JobItem
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.util.Locale

/**
 * The header's background-jobs seat.
 *
 * The web puts a job count in the conversation header and opens a list from it.
 * The phone's header has one trailing slot, so the count *is* the affordance:
 * tapping it opens the list.
 *
 * Its states mirror the session rows' dots, because they answer the same
 * question — "did anything happen while I was looking elsewhere?":
 *  * a job is running → the ongoing chase (blue), so a live job is visible at a
 *    glance even though the agent's own turn may be over;
 *  * a job finished since the list was last opened → the green done dot;
 *  * jobs exist but nothing is running and nothing is new → a muted count.
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
    // A live-activity seat, not an inventory: a session that has accumulated a dozen
    // finished jobs should not advertise "12 jobs" in the header. Nothing running and
    // nothing unseen means nothing to say, so the seat disappears rather than
    // reporting history - the sheet behind it is where the history lives.
    if (running == 0 && !finishedUnseen) return

    Row(
        Modifier
            .clip(RoundedCornerShape(DshRadius.pill))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(
            state = if (running > 0) DotState.ONGOING else DotState.DONE,
            size = 10.dp,
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = when {
                running == 1 -> "1 job"
                running > 1 -> "$running jobs"
                else -> "done"
            },
            style = DshType.bodySmall.copy(fontWeight = FontWeight.Medium),
            color = if (running > 0) colors.accent else colors.labelSecondary,
            maxLines = 1,
        )
    }
}

/**
 * The jobs list itself.
 *
 * Live jobs first, then history newest-first. The list used to render in arrival
 * order despite this docstring claiming otherwise, so in a long session the job you
 * actually care about - the running one - sat at the very bottom of a scrollable
 * list.
 */
@Composable
fun JobsSheet(
    jobs: List<JobItem>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
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

        if (jobs.isEmpty()) {
            Text(
                text = "No background jobs in this session.",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = DshSpacing.xl, vertical = DshSpacing.md),
            )
            return@Column
        }

        // Running first so the live one is under the finger, then newest first
        // within each group. `startedAt` can be 0 on a job whose frame predates the
        // field, which sorts it last rather than first. Computed here rather than in
        // the `LazyColumn` body: that body is a `LazyListScope`, not a composable
        // scope, so `remember` cannot be called inside it.
        val ordered = remember(jobs) {
            jobs.sortedWith(
                compareByDescending<JobItem> { it.running }
                    .thenByDescending { it.startedAt },
            )
        }
        LazyColumn(
            Modifier.heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(DshSpacing.xxs),
        ) {
            items(ordered, key = { it.id }) { job ->
                JobRow(job)
            }
        }
    }
}

@Composable
private fun JobRow(job: JobItem) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.sm),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.padding(top = 5.dp)) {
            when {
                job.running -> StateDot(state = DotState.ONGOING, size = 10.dp)
                job.status == "failed" -> StateDot(state = DotState.ERROR, size = 10.dp)
                job.status == "completed" -> StateDot(state = DotState.DONE, size = 10.dp)
                else -> StateDot(state = DotState.IDLE, size = 10.dp)
            }
        }
        Spacer(Modifier.width(DshSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = job.label,
                style = DshType.bodyMedium,
                color = colors.labelPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                // Same vocabulary the web's job list uses: a status, then the
                // duration it has been (or was) alive.
                text = listOf(jobStatusLabel(job.status), jobDuration(job))
                    .filter { it.isNotEmpty() }
                    .joinToString(" \u00b7 "),
                style = DshType.micro,
                color = if (job.status == "failed") colors.error else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            job.detail?.let {
                Text(
                    text = it,
                    style = DshType.micro,
                    color = colors.labelCaption,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun jobStatusLabel(status: String): String = when (status) {
    "running" -> "running"
    "stopping" -> "stopping"
    "completed" -> "completed"
    "killed" -> "cancelled"
    "failed" -> "failed"
    else -> status
}

/** "12s" / "3m 12s" / "1h 3m", exactly as `job.duration.*` formats it. */
private fun jobDuration(job: JobItem): String {
    val end = job.finishedAt ?: System.currentTimeMillis()
    val seconds = ((end - job.startedAt) / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "${seconds}s"
        seconds < 3600 -> String.format(Locale.US, "%dm %ds", seconds / 60, seconds % 60)
        else -> String.format(Locale.US, "%dh %dm", seconds / 3600, (seconds % 3600) / 60)
    }
}
