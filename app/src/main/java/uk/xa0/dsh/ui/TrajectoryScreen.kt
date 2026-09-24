package uk.xa0.dsh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.xa0.dsh.model.TrajectoryKind
import uk.xa0.dsh.model.TrajectoryModel
import uk.xa0.dsh.model.TrajectoryRow
import uk.xa0.dsh.model.TrajectorySpan
import uk.xa0.dsh.model.TrajectoryTimeline
import uk.xa0.dsh.model.TrajectoryTimelineSpan
import uk.xa0.dsh.ui.theme.DshColors
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The Trajectory view: the conversation as the model's input/output ledger.
 *
 * Standalone on purpose — it takes the folded [TrajectoryModel] and a title,
 * owns its own scroll and fold state, and has no dependency on the chat screen.
 * The intended host is the conversation header's second view tab (the web's
 * `conversation.view` slot, entry id `trajectory`, order 10); the call site in
 * `docs/research/trajectory.md` is the contract.
 *
 * @param showHeader whether to draw this screen's own title/back row. The web's
 *   view tab swaps only the content area and leaves the conversation header
 *   (title, drawer control, tab strip) in place, so a host that already shows
 *   one passes `false`; the default keeps the screen standalone.
 *
 * What the web draws and this does not is listed in that document; the short
 * version is that the overview is read-only (no drag-zoom or selection), the
 * per-record inspector trades its tabbed panel for an inline expand, and rows
 * that the app's reducer cannot distinguish (`system`, `subtool`) are absent
 * rather than guessed at.
 */
@Composable
fun TrajectoryScreen(
    model: TrajectoryModel,
    title: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    showHeader: Boolean = true,
) {
    val colors = DshTheme.colors
    var collapsedTurns by remember { mutableStateOf(emptySet<Int>()) }

    Column(
        modifier
            .fillMaxSize()
            .background(colors.bgBase),
    ) {
        if (showHeader) TrajectoryHeader(title = title, onClose = onClose)

        if (model.isEmpty) {
            TrajectoryEmptyState(Modifier.weight(1f))
        } else {
            model.timeline?.let { TrajectoryTimelineStrip(it) }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.borderL2),
            )

            val rows = remember(model, collapsedTurns) { model.rows(collapsedTurns) }
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(rows, key = { it.key }) { row ->
                    when (row) {
                        is TrajectoryRow.Header -> TurnHeaderRow(row) {
                            row.turn?.let { turn ->
                                collapsedTurns = if (turn in collapsedTurns) {
                                    collapsedTurns - turn
                                } else {
                                    collapsedTurns + turn
                                }
                            }
                        }

                        is TrajectoryRow.Request -> RequestBoundaryRow(row)
                        is TrajectoryRow.Span -> TrajectorySpanRow(row.span)
                        is TrajectoryRow.Collapsed -> CollapsedSummaryRow(row.label)
                    }
                }
                item { Spacer(Modifier.height(DshSpacing.xxl)) }
            }
        }
    }
}

@Composable
private fun TrajectoryHeader(title: String, onClose: () -> Unit) {
    val colors = DshTheme.colors
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.ArrowBack,
                contentDescription = "Back to chat",
                tint = colors.labelPrimary,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickableNoRipple(onClick = onClose)
                    .padding(DshSpacing.md),
            )
            Spacer(Modifier.width(DshSpacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = DshType.messageBody.copy(fontWeight = FontWeight.Medium),
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The view name, mirroring the web's view-tab label.
                Text(
                    text = "Trajectory",
                    style = DshType.bodySmall,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(DshSpacing.lg))
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.borderL3),
        )
    }
}

@Composable
private fun TrajectoryEmptyState(modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Column(
        modifier
            .fillMaxSize()
            .padding(DshSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No trajectory records yet",
            style = DshType.titleSmall,
            color = colors.labelPrimary,
        )
        Spacer(Modifier.height(DshSpacing.sm))
        Text(
            text = "Messages, tool calls, context and compactions appear here as the " +
                "model's input/output ledger once this session has history.",
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * The web's three-lane overview (`TrajectoryTimeline`, `timeline.ts:75-118`).
 *
 * Geometry from `TrajectoryTimeline.module.css`: a 44px caption column with a
 * hairline right border, 14px of lane pitch, 8px blocks with a 1px radius and a
 * one-pixel gap, and the labels pinned to the top of each lane band.
 *
 * Read-only here: the web's drag-to-focus range, wheel zoom and click-to-select
 * all presuppose a mouse and a virtualized table that can scroll to a record.
 * The lanes, their order, their colours, the turn boundaries and the sequence
 * projection are the parts that survive on a phone, so those are what is drawn.
 */
@Composable
private fun TrajectoryTimelineStrip(timeline: TrajectoryTimeline, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    val laneHeight = 14.dp
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.bgLayer2)
            // Web `.plot`: the lanes and the captions both start 7px down.
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.width(44.dp)) {
            for (label in TIMELINE_LANES) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(laneHeight),
                    contentAlignment = Alignment.TopEnd,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .padding(end = 3.dp),
                        contentAlignment = Alignment.CenterEnd,
                    ) {
                        Text(label, style = TIMELINE_LABEL_TYPE, color = colors.labelCaption, maxLines = 1)
                    }
                }
            }
        }
        // Web `.labels { border-right }`.
        Box(
            Modifier
                .width(0.5.dp)
                .height(laneHeight * TIMELINE_LANES.size)
                .background(colors.borderL1),
        )
        Canvas(
            Modifier
                .weight(1f)
                .height(laneHeight * TIMELINE_LANES.size),
        ) {
            val total = (timeline.end - timeline.start).coerceAtLeast(1).toFloat()
            val width = size.width
            val gap = 1.dp.toPx()
            val spanHeight = 8.dp.toPx()
            val lanePx = laneHeight.toPx()

            // Turn boundaries first, so a span always paints over its marker.
            // Web filters out the boundary at the domain start.
            for (boundary in timeline.turnBoundaries) {
                if (boundary.at <= timeline.start) continue
                val x = ((boundary.at - timeline.start) / total) * width
                drawRect(
                    color = colors.borderL2,
                    topLeft = Offset(x, 0f),
                    size = Size(0.5.dp.toPx(), size.height),
                )
            }
            for (span in timeline.spans) {
                val left = ((span.start - timeline.start) / total) * width + gap / 2f
                val rawWidth = ((span.end - span.start) / total) * width - gap
                // Web `.span { top: calc(var(--lane) * 14px) }`: blocks hug the
                // top of their band rather than being centred in it.
                val top = span.lane.index * lanePx
                drawRoundRect(
                    color = timelineSpanColor(span, colors).copy(alpha = timelineSpanAlpha(span)),
                    topLeft = Offset(left, top),
                    size = Size(rawWidth.coerceAtLeast(2.dp.toPx()), spanHeight),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
            }
        }
    }
}

/** The lane captions, in lane order (`column.input`/`model`/`tools`). */
private val TIMELINE_LANES = listOf("Input", "Model", "Tools")

/** Web `.labels`: 10px on a unit line-height, caption colour. */
private val TIMELINE_LABEL_TYPE = DshType.micro.copy(fontSize = 10.sp, lineHeight = 10.sp)

/** Web `.turnLabel`: 8px/10px code, tertiary on the platform-module fill. */
private val TURN_LABEL_TYPE = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontFamily = FontFamily.Monospace,
)

/** Web `.requestBoundaryControl::after`: 9px/12px code. */
private val REQUEST_LABEL_TYPE = TextStyle(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontFamily = FontFamily.Monospace,
)

/** Web `.kindTag`: 10px/16px, weight 650, 0.035em tracking, radius 4. */
private val KIND_TAG_TYPE = TextStyle(
    fontSize = 10.sp,
    lineHeight = 16.sp,
    fontWeight = FontWeight(650),
    letterSpacing = 0.35.sp,
)

/**
 * Span colours from `TrajectoryTimeline.module.css:172-218`. The web mixes
 * against two aliases this app has no token for (`state-error-secondary`, which
 * equals [DshColors.error], and a success tertiary), so the closest app tokens
 * are used and the mixes are reproduced with [lerp].
 */
private fun timelineSpanColor(span: TrajectoryTimelineSpan, colors: DshColors): Color = when {
    span.isError -> colors.error
    span.kind == TrajectoryKind.USER -> colors.accent
    span.kind == TrajectoryKind.CONTEXT -> lerp(colors.labelSecondary, colors.success, 0.68f)
    span.kind == TrajectoryKind.MESSAGE -> lerp(colors.error, colors.accent, 0.6f)
    span.kind == TrajectoryKind.TOOL || span.kind == TrajectoryKind.SUBTOOL -> colors.warn
    span.kind == TrajectoryKind.ERROR -> colors.error
    else -> colors.labelSecondary
}

/** Web: the default block sits at 0.78; message and tool blocks are opaque. */
private fun timelineSpanAlpha(span: TrajectoryTimelineSpan): Float = when {
    span.isError -> 0.78f
    span.kind == TrajectoryKind.MESSAGE -> 1f
    span.kind == TrajectoryKind.TOOL || span.kind == TrajectoryKind.SUBTOOL -> 1f
    else -> 0.78f
}

/**
 * The web's turn chip (`TrajectoryTable.module.css:396-436`). The web pins it to
 * the first row of the turn and tints it while that turn is active; here it gets
 * its own row so the fold control has a stable touch target, and an open
 * (not-yet-ended) turn uses the web's own active treatment — the app has no
 * live trajectory projection, so "host has not closed this turn" is the honest
 * signal.
 */
@Composable
private fun TurnHeaderRow(row: TrajectoryRow.Header, onToggle: () -> Unit) {
    val colors = DshTheme.colors
    val chipColor = if (row.open) {
        lerp(colors.labelTertiary, colors.accentDeep, 0.55f)
    } else {
        colors.labelTertiary
    }
    val chipBackground = if (row.open) {
        lerp(colors.bgLayer1, colors.accentDeep, 0.22f)
    } else {
        colors.tip
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(enabled = row.expandable, onClick = onToggle)
            .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(2.dp))
                .background(chipBackground)
                .padding(horizontal = 5.dp, vertical = 1.dp),
        ) {
            Text(row.label, style = TURN_LABEL_TYPE, color = chipColor, maxLines = 1)
        }
        Spacer(Modifier.weight(1f))
        if (row.expandable) {
            Spacer(Modifier.width(DshSpacing.sm))
            Icon(
                imageVector = if (row.collapsed) Icons.Rounded.ChevronRight else Icons.Rounded.ExpandMore,
                contentDescription = if (row.collapsed) "Expand turn" else "Collapse turn",
                tint = colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * One request boundary — the web's 5px `requestBoundaryControl` dot
 * (`TrajectoryTable.module.css:237-323`) whose `Request #N` (or
 * `Request #N · Compaction`) label the web reveals on hover/focus. Touch has no
 * hover, so the label is always shown.
 */
@Composable
private fun RequestBoundaryRow(row: TrajectoryRow.Request) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(5.dp)
                .clip(CircleShape)
                .background(if (row.isError) colors.error else colors.labelCaption),
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = if (row.compaction) {
                "Request #${row.number} · Compaction"
            } else {
                "Request #${row.number}"
            },
            style = REQUEST_LABEL_TYPE,
            color = colors.labelSecondary,
            maxLines = 1,
        )
    }
}

@Composable
private fun CollapsedSummaryRow(label: String) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("…", style = DshType.micro, color = colors.labelCaption)
        Spacer(Modifier.width(DshSpacing.sm))
        Text(label, style = DshType.micro, color = colors.labelTertiary)
    }
}

/**
 * One ledger record, i.e. one web table row: a kind tag and the record's
 * display text (with the tool result after an arrow), plus an inline disclosure
 * for the retained source that the web puts in its separate details panel.
 */
@Composable
private fun TrajectorySpanRow(span: TrajectorySpan, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    var expanded by remember(span.recordId) { mutableStateOf(false) }
    val hasDetail = span.inputDetail != null || span.outputDetail != null || span.thinkingDetail != null

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickableNoRipple(enabled = hasDetail) { expanded = !expanded }
                .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.md),
            verticalAlignment = Alignment.Top,
        ) {
            // Web `.kindSlot` is a fixed-width slot with the tags pushed to its
            // trailing edge (`justify-content: flex-end`), so the content column
            // starts at one x for every row; the phone keeps both properties.
            Box(Modifier.width(88.dp), contentAlignment = Alignment.TopEnd) {
                KindTag(span.kind)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    text = span.displayText.ifBlank { "No content" },
                    style = DshType.bodyMedium,
                    // Web `.toolCallOnly` recedes a step with the kind tag icon
                    // alone: an assistant step that was only tool calls.
                    color = if (span.toolCallOnly) colors.labelTertiary else colors.labelPrimary,
                    maxLines = if (expanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                span.resultText?.let { result ->
                    Spacer(Modifier.height(DshSpacing.xxs))
                    Text(
                        text = "→ $result",
                        style = DshType.bodyMedium,
                        color = if (span.isError) colors.error else colors.labelSecondary,
                        maxLines = if (expanded) Int.MAX_VALUE else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (expanded) {
                    span.thinkingDetail?.let { DetailBlock("Thinking", it) }
                    span.inputDetail?.let { DetailBlock("Input", it) }
                    span.outputDetail?.let { DetailBlock("Output", it) }
                }
            }
            if (hasDetail) {
                Spacer(Modifier.width(DshSpacing.sm))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .size(14.dp)
                        .padding(top = 3.dp),
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .padding(start = DshSpacing.xl)
                .background(colors.borderL1),
        )
    }
}

@Composable
private fun KindTag(kind: TrajectoryKind) {
    val colors = DshTheme.colors
    val (foreground, background) = kindTagColors(kind, colors)
    Box(
        Modifier
            .height(19.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = kind.label,
            style = KIND_TAG_TYPE,
            color = foreground,
            maxLines = 1,
        )
    }
}

/**
 * Tag colours from `TrajectoryTable.module.css:602-724`.
 *
 * The web's neutral fill is `bg-module-platform`, which the app's `tip` token
 * carries at the same dark value; the two tertiary tints the web has no app
 * twin for (`state-success-tertiary`, and the assistant blend over
 * `bg-layer-1`) are reproduced as a bounded mix over [DshColors.bgLayer1].
 */
private fun kindTagColors(kind: TrajectoryKind, colors: DshColors): Pair<Color, Color> = when (kind) {
    TrajectoryKind.USER -> colors.accent to colors.accentTertiary
    TrajectoryKind.CONTEXT ->
        lerp(colors.labelSecondary, colors.success, 0.68f) to
            lerp(colors.bgLayer1, colors.success, 0.14f)

    TrajectoryKind.MESSAGE ->
        lerp(colors.error, colors.accent, 0.6f) to
            lerp(colors.bgLayer1, lerp(colors.error, colors.accent, 0.55f), 0.15f)

    TrajectoryKind.TOOL -> colors.warn to colors.warnTertiary
    TrajectoryKind.SUBTOOL -> lerp(colors.warn, colors.labelTertiary, 0.38f) to colors.warnTertiary
    TrajectoryKind.ERROR -> colors.error to lerp(colors.bgLayer1, colors.error, 0.12f)
    else -> colors.labelSecondary to colors.tip
}

@Composable
private fun DetailBlock(label: String, text: String) {
    val colors = DshTheme.colors
    val body = if (text.length > DETAIL_CAP) text.take(DETAIL_CAP) + "…" else text
    Spacer(Modifier.height(DshSpacing.lg))
    Text(
        text = label.uppercase(),
        style = DshType.micro,
        color = colors.labelCaption,
    )
    Spacer(Modifier.height(DshSpacing.xxs))
    Text(
        text = body,
        style = DshType.codeSmall,
        color = colors.labelSecondary,
    )
}

/**
 * Detail bodies are capped because the reducer retains whole records: a
 * compaction summary or a tool result can run to tens of kilobytes, and a
 * single `Text` that large is a measurable layout cost on a phone.
 */
private const val DETAIL_CAP = 4000
