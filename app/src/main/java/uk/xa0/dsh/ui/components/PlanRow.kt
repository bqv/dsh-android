package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The `todo_write` row's summary: "N/M completed · <first active task>", with
 * the parallel-active count kept out of the ellipsized text. Ports
 * `plan-summary.ts` + `todo-row.tsx`: several items may be in progress at once,
 * so only the first active item is named and the rest are counted as `+n`.
 */
internal data class PlanRowModel(val text: String, val extra: Int, val isError: Boolean, val stopped: Boolean)

internal fun planRowModel(arguments: String, isError: Boolean, errorCode: String?): PlanRowModel? {
    val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    val todos = args.optJSONArray("todos") ?: return null
    var done = 0
    var activeCount = 0
    var activeContent: String? = null
    for (i in 0 until todos.length()) {
        val item = todos.optJSONObject(i) ?: return null
        when (item.optString("status")) {
            "completed" -> done++
            "in_progress" -> {
                activeCount++
                if (activeContent == null) {
                    val content = item.opt("content")
                    if (content is String && content.trim().isNotEmpty()) activeContent = content
                }
            }
        }
    }
    val head = "$done/${todos.length()} completed"
    return PlanRowModel(
        text = if (activeContent == null) head else "$head · $activeContent",
        // The active clause is the only part an unusable name costs; the counts
        // alone are still good, so the suffix stays zero when nothing is named.
        extra = if (activeContent == null) 0 else activeCount - 1,
        isError = isError,
        stopped = errorCode == "interrupted",
    )
}

@Composable
internal fun PlanCallRow(entry: ChatEntry.ToolCall, model: PlanRowModel) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickableNoRipple { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    expanded -> Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    model.isError -> StateDot(DotState.ERROR)
                    model.stopped -> StateDot(DotState.WARNING)
                    else -> Icon(
                        Icons.Rounded.Checklist,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(DshSpacing.sm))
            Text("Update to-do list", style = DshType.rowTitle, color = colors.labelSecondary, maxLines = 1)
            Spacer(Modifier.width(DshSpacing.md))
            Box(
                Modifier
                    .size(2.dp)
                    .clip(CircleShape)
                    .background(colors.labelCaption),
            )
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = model.text,
                style = DshType.rowSummary,
                color = if (model.isError) colors.error else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (model.extra > 0 && !model.isError) {
                Spacer(Modifier.width(DshSpacing.xs))
                Text(
                    text = "+${model.extra}",
                    style = DshType.rowSummary,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(DshSpacing.xs))
            ToolIoCard(
                sections = buildList {
                    add("IN" to entry.arguments)
                    entry.result?.let { add("OUT" to it) }
                },
                isError = entry.isError,
            )
            Spacer(Modifier.height(DshSpacing.xs))
        }
    }
}
