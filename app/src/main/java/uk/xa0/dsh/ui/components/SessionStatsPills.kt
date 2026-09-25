package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import uk.xa0.dsh.model.SessionStats
import uk.xa0.dsh.model.formatCompactDuration
import uk.xa0.dsh.model.formatCompactTokens
import uk.xa0.dsh.model.formatExactTokens
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/** The two readings in the row, and which one currently owns the open panel. */
private enum class StatPill { TIME, USAGE }

/**
 * The web's session-stat pills (`ui-chat/src/client/chat/StatsPills.tsx`):
 * a gauge pill carrying turn/step counts and output speed, and a database pill
 * carrying the whole-log token total and cache-hit share.
 *
 * **Placement.** The web mounts this on `conversation.composer.dock`, so it
 * lives *inside the conversation scrollport directly under the composer* and
 * scrolls with it — not in the header, and not in the trajectory view. Hosts
 * call it right below their composer row.
 *
 * Deviations from the web, all forced by touch:
 *  - the portal panel becomes a [Popup] anchored above its pill (the web's
 *    panel is a fixed-position portal with an anchored clamp),
 *  - a pill whose reading opens a panel is always styled as interactive rather
 *    than only on hover, since there is no hover to reveal the affordance.
 *
 * @param stats the parsed projections, or null while the host has not sent them
 * @param modifier slot for the host's own padding. The web's `.root` pads
 *   `4px [composer-side-clearance + 16px] 0`; this row adds only the 4dp top
 *   gap, so a host that gives its composer `16dp` of side padding gives this
 *   row the same and lets the host decide whether the extra 16px is wanted.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SessionStatsPills(stats: SessionStats?, modifier: Modifier = Modifier) {
    if (stats == null || !stats.visible) return
    // One exclusive slot for both panels: opening either closes the other, as
    // the web's single `openPill` state does.
    var open by remember { mutableStateOf<StatPill?>(null) }

    // A `FlowRow`, not a `Row`: the web lays these two chips out on one line
    // because a desktop dock is wide enough for "661M tok · Cache hit 97%", and a
    // phone is not — as a `Row` the second chip was simply clipped at the screen
    // edge and its cache-hit figure was unreachable. Wrapping keeps both whole.
    // Start-aligned, not centred: centring the wrapped lines left them floating
    // with about 103dp of dead space on each side while sitting 12-16dp from the
    // composer above and the navigation bar below — measured on a 411dp screen, so
    // the block read as unbalanced rather than as part of the composer's stack.
    // Aligned to the composer card's own edge, all four margins are of a size.
    FlowRow(
        modifier
            .fillMaxWidth()
            .padding(top = DshSpacing.xs),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.lg),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        if (stats.steps > 0) {
            val counts = "${stats.turns} turns ${stats.steps} steps"
            val speed = stats.tokensPerSecondText
            StatPillButton(
                icon = Icons.Rounded.Speed,
                label = counts,
                suffix = speed?.let { "$it tok/s" },
                expandable = stats.hasTiming,
                expanded = open == StatPill.TIME,
                onToggle = { open = if (open == StatPill.TIME) null else StatPill.TIME },
            ) {
                StatPanel(title = "Session statistics") {
                    // A row is absent, not zeroed: an untimed figure is not the
                    // same fact as a figure that measured zero.
                    if (stats.llmMs > 0) StatRow("LLM time", formatCompactDuration(stats.llmMs))
                    if (stats.toolMs > 0) StatRow("Tool time", formatCompactDuration(stats.toolMs))
                    stats.averageTtftMs?.let {
                        StatRow("Avg time to first token (TTFT)", formatCompactDuration(it))
                    }
                    if (stats.decodeMs > 0) {
                        StatRow("Tokens per second (TPS)", "${speed.orEmpty()} tok/s")
                    }
                }
            }
        }
        if (stats.hasTokenActivity) {
            val cacheHit = stats.cacheHitPercent
            StatPillButton(
                icon = Icons.Rounded.Storage,
                label = "${formatCompactTokens(stats.totalTokens)} tok",
                suffix = cacheHit?.let { "Cache hit $it%" },
                expandable = true,
                expanded = open == StatPill.USAGE,
                onToggle = { open = if (open == StatPill.USAGE) null else StatPill.USAGE },
            ) {
                StatPanel(
                    title = "Token usage",
                    headline = "${formatExactTokens(stats.totalTokens)} tok",
                ) {
                    cacheHit?.let { StatRow("Cache hit", "$it%") }
                    StatRow("Uncached input", exactCount(stats.uncachedInputTokens))
                    StatRow("Cached input", exactCount(stats.cacheReadTokens))
                    // A session that never wrote cache drops the row.
                    if (stats.cacheWriteTokens != 0L) {
                        StatRow("Cache write", exactCount(stats.cacheWriteTokens))
                    }
                    StatRow("Output", exactCount(stats.outputTokens))
                }
            }
        }
    }
}

/** Web `message.turnUsage.count`: `{count} tok`. */
private fun exactCount(value: Long): String = "${formatExactTokens(value)} tok"

/**
 * One 24dp-radius chip: a 14dp glyph, the reading, and — when the reading has a
 * second figure — its separated suffix. A chip with no panel to open stays a
 * plain reading instead of a button, matching the web's static-span form.
 */
@Composable
private fun StatPillButton(
    icon: ImageVector,
    label: String,
    suffix: String?,
    expandable: Boolean,
    expanded: Boolean,
    onToggle: () -> Unit,
    panel: @Composable () -> Unit,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    val pill: @Composable () -> Unit = {
        Row(
            Modifier
                .clip(shape)
                .then(if (expandable || expanded) Modifier.clickableNoRipple(onClick = onToggle) else Modifier)
                .background(if (expanded) colors.hover else Color.Transparent)
                .padding(horizontal = DshSpacing.md, vertical = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (expanded) colors.labelSecondary else colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = label,
                style = DshType.bodyMedium,
                color = if (expanded) colors.labelSecondary else colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (suffix != null) {
                Text(
                    text = "·",
                    style = DshType.bodyMedium,
                    color = colors.borderL3,
                    modifier = Modifier.padding(horizontal = DshSpacing.sm),
                )
                Text(
                    text = suffix,
                    style = DshType.bodyMedium,
                    color = if (expanded) colors.labelSecondary else colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    if (!expandable) {
        pill()
        return
    }
    Box {
        pill()
        if (expanded) {
            Popup(
                onDismissRequest = onToggle,
                popupPositionProvider = remember {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset = IntOffset(
                            // Centred on the pill and sitting just above it, clamped
                            // so the panel keeps a viewport margin at either edge.
                            x = (anchorBounds.center.x - popupContentSize.width / 2)
                                .coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)),
                            y = (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(8),
                        )
                    }
                },
            ) { panel() }
        }
    }
}

/**
 * The shared stat-panel skin (`stat-dialog.module.css`): menu surface, 12dp
 * radius, prominent shadow, a heading with an optional right-hand headline
 * value, a hairline rule, then the label/value rows.
 */
@Composable
private fun StatPanel(
    title: String,
    headline: String? = null,
    rows: @Composable () -> Unit,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        Modifier
            .widthIn(min = 232.dp, max = 320.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(colors.menu)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(DshSpacing.xl),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = DshType.bodySmall.copy(fontWeight = FontWeight.Medium),
                color = colors.labelPrimary,
                modifier = Modifier.weight(1f),
            )
            if (headline != null) {
                // No fixed gap is needed: the title takes the slack, so a wide
                // panel keeps the two apart without a hard column split.
                Text(
                    text = headline,
                    style = DshType.bodySmall.copy(fontWeight = FontWeight.Medium),
                    color = colors.labelPrimary,
                    maxLines = 1,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = DshSpacing.md, bottom = 10.dp)
                .height(0.5.dp)
                .background(colors.borderL2),
        )
        rows()
    }
}

/** One `dt`/`dd` pair: tertiary label left, tabular value right. */
@Composable
private fun StatRow(label: String, value: String) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(DshSpacing.xl))
        Text(
            text = value,
            style = DshType.bodySmall,
            color = colors.labelSecondary,
            maxLines = 1,
        )
    }
}
