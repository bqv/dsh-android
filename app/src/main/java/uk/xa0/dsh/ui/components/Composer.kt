package uk.xa0.dsh.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.delay
import java.util.Locale
import uk.xa0.dsh.ContextBreakdown
import uk.xa0.dsh.PendingAttachment
import uk.xa0.dsh.data.BusyEnter
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The composer card: a 22dp capsule on the `specific-input-major` fill with a
 * hairline stroke. The toolbar mirrors the web layout — attach and the mode chips
 * on the left, context ring, model trigger and the 34dp circular send on the right.
 */
@Composable
fun Composer(
    // The whole `TextFieldValue`, not just its text: the caller derives the `/`
    // and `@` triggers from the caret, which only the selection carries.
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    running: Boolean,
    modelLabel: String?,
    onModelClick: () -> Unit,
    permissionLabel: String?,
    onPermissionClick: () -> Unit,
    planActive: Boolean,
    onExitPlan: () -> Unit,
    contextPercent: Int,
    contextTokens: Int,
    contextWindow: Int,
    contextBreakdown: ContextBreakdown?,
    attachments: List<PendingAttachment>,
    onToggleCommands: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    modifier: Modifier = Modifier,
    showAttach: Boolean = true,
    /** What the plain submit does while running: `queue` or `steer`. */
    busyEnter: String = BusyEnter.QUEUE,
    /** Send with one explicit delivery mode, bypassing the preference. */
    onSendMode: (String) -> Unit = {},
    onToggleBusyEnter: () -> Unit = {},
) {
    val colors = DshTheme.colors
    val focusManager = LocalFocusManager.current
    // An attachment is a message all by itself: a screenshot with no caption is a
    // perfectly ordinary thing to send, and requiring text made the picture
    // unsendable.
    val canSend = value.text.isNotBlank() || running || attachments.isNotEmpty()
    val shape = RoundedCornerShape(DshRadius.bubble)

    Column(modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(colors.inputMajor)
                .border(0.5.dp, colors.borderL2, shape)
                .padding(top = DshSpacing.md),
        ) {
            if (attachments.isNotEmpty()) {
                AttachmentRail(
                    attachments = attachments,
                    onRemove = onRemoveAttachment,
                    // 8dp below the chips: at 4dp the rail and the text read as one
                    // crowded block, which is what "no gap between attachments and
                    // the text" was.
                    modifier = Modifier.padding(
                        start = DshSpacing.lg,
                        end = DshSpacing.lg,
                        top = DshSpacing.xs,
                        bottom = DshSpacing.md,
                    ),
                )
            }

            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = DshType.messageBody.copy(color = colors.labelPrimary),
                cursorBrush = SolidColor(colors.accent),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Default,
                ),
                keyboardActions = KeyboardActions(),
                modifier = Modifier
                    .fillMaxWidth()
                    // No `min` here: a min height centres the field's own text
                    // while the placeholder stays top-aligned, which is why the
                    // ghost text sat off-centre. The card's padding sets the 36dp.
                    .heightIn(max = 160.dp)
                    .padding(start = 14.dp, end = DshSpacing.md, bottom = DshSpacing.xs),
                decorationBox = { inner ->
                    Box {
                        if (value.text.isEmpty()) {
                            Text(
                                text = "Message DSH…",
                                style = DshType.messageBody,
                                color = colors.labelCaption,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        inner()
                    }
                },
            )

            // A single action row, but the two dropdowns sit inline and abbreviated
            // (see `permissionShort`/the model trigger). Both are menus, so their
            // resting label only has to be recognisable — the full host names and
            // descriptions still appear in the sheets. That buys back the vertical
            // space a second row would cost without pushing send off the margin.
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(
                        start = DshSpacing.md,
                        end = DshSpacing.md,
                        bottom = DshSpacing.sm,
                        top = DshSpacing.xxs,
                    ),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (showAttach) {
                    CircleIconButton(
                        icon = Icons.Rounded.Add,
                        // The web's `+` is the command menu, not an attachment
                        // button: `input.commands` = "Add files or run commands".
                        contentDescription = "Add files or run commands",
                        onClick = onToggleCommands,
                        size = 28.dp,
                        background = colors.selector,
                        tint = colors.labelPrimary,
                    )
                    Spacer(Modifier.width(DshSpacing.sm))
                }

                if (permissionLabel != null) {
                    ModeChip(label = permissionLabel, onClick = onPermissionClick)
                    Spacer(Modifier.width(DshSpacing.sm))
                }

                if (planActive) {
                    PlanChip(onExit = onExitPlan)
                    Spacer(Modifier.width(DshSpacing.sm))
                }

                // Left group is attach + modes; the model trigger belongs to the
                // *trailing* group with the context ring and send, right-flushed —
                // as the web's `.row` does with `justify-content: space-between`.
                Spacer(Modifier.weight(1f))

                if (modelLabel != null) {
                    ModelTrigger(label = modelLabel, onClick = onModelClick)
                }

                Spacer(Modifier.width(DshSpacing.md))

                ContextMeter(
                    percent = contextPercent,
                    usedTokens = contextTokens,
                    windowTokens = contextWindow,
                    breakdown = contextBreakdown,
                )
                Spacer(Modifier.width(DshSpacing.lg))

                SendButton(
                    canSend = canSend,
                    running = running,
                    draftBlank = value.text.isBlank(),
                    busyEnter = busyEnter,
                    onSend = {
                        focusManager.clearFocus()
                        onSend()
                    },
                    onStop = onStop,
                    onSendMode = { mode ->
                        focusManager.clearFocus()
                        onSendMode(mode)
                    },
                    onToggleBusyEnter = onToggleBusyEnter,
                )
            }
        }
    }
}

/**
 * The 34dp circular send button, and the delivery-mode menu behind a long press.
 *
 * While the agent runs, the message has to go somewhere: into the turn (steer,
 * which interrupts it) or into the queue (deliver once it ends). The web puts
 * that choice on `busyEnter` and swaps it with Cmd/Ctrl+Enter; a phone has no
 * chord, so the same two modes live on a long-press menu with the preference
 * toggleable in place. Both are real submissions — the menu replaces the tap
 * rather than deferring a decision, so a steer is never silently queued.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SendButton(
    canSend: Boolean,
    running: Boolean,
    draftBlank: Boolean,
    busyEnter: String,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onSendMode: (String) -> Unit,
    onToggleBusyEnter: () -> Unit,
) {
    val colors = DshTheme.colors
    var menuOpen by remember { mutableStateOf(false) }
    val isStop = running && draftBlank
    val sendFill by animateColorAsState(
        targetValue = if (canSend) colors.sendFill else colors.sendFill.copy(alpha = 0.4f),
        label = "sendFill",
    )

    Box {
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(sendFill)
                .combinedClickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = canSend,
                    // Only a running agent has two plausible destinations.
                    onLongClick = { if (running && !draftBlank) menuOpen = true },
                    onClick = { if (isStop) onStop() else onSend() },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (isStop) Icons.Rounded.Stop else Icons.Rounded.Send,
                contentDescription = when {
                    isStop -> "Stop"
                    busyEnter == BusyEnter.STEER -> "Send and steer"
                    else -> "Send and queue"
                },
                tint = colors.sendGlyph,
                modifier = Modifier.size(16.dp),
            )
        }

        if (menuOpen) {
            Popup(
                onDismissRequest = { menuOpen = false },
                popupPositionProvider = remember {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset = IntOffset(
                            x = (anchorBounds.right - popupContentSize.width)
                                .coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)),
                            y = (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(8),
                        )
                    }
                },
            ) {
                SendModeMenu(
                    busyEnter = busyEnter,
                    onPick = { mode ->
                        menuOpen = false
                        onSendMode(mode)
                    },
                    // Deliberately stays open: flipping the default and closing in
                    // the same tap gave no sign that anything happened.
                    onToggleDefault = onToggleBusyEnter,
                )
            }
        }
    }
}

@Composable
private fun SendModeMenu(
    busyEnter: String,
    onPick: (String) -> Unit,
    onToggleDefault: () -> Unit,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        Modifier
            .width(246.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(colors.menu)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(vertical = DshSpacing.xs),
    ) {
        SendModeRow(
            icon = Icons.Rounded.Send,
            title = "Steer now",
            subtitle = "Cut into the current turn",
            selected = busyEnter == BusyEnter.STEER,
            onClick = { onPick(BusyEnter.STEER) },
        )
        SendModeRow(
            icon = DshIconQueue14,
            title = "Queue for later",
            subtitle = "Deliver when this turn ends",
            selected = busyEnter == BusyEnter.QUEUE,
            onClick = { onPick(BusyEnter.QUEUE) },
        )

        Box(
            Modifier
                .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs)
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.borderL1),
        )

        SendModeRow(
            icon = Icons.Rounded.Tune,
            title = "Default while busy",
            subtitle = "What Send does while the agent runs",
            // The current value rides on the row itself, so the toggle's effect is
            // visible while the menu stays open.
            trailing = if (busyEnter == BusyEnter.STEER) "Steer" else "Queue",
            selected = false,
            onClick = onToggleDefault,
        )
    }
}

@Composable
private fun SendModeRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    trailing: String? = null,
) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (selected || trailing != null) colors.accent else colors.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(DshSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(title, style = DshType.bodyMedium, color = colors.labelPrimary, maxLines = 1)
            Text(subtitle, style = DshType.micro, color = colors.labelTertiary, maxLines = 1)
        }
        if (trailing != null) {
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = trailing,
                style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.accent,
                maxLines = 1,
            )
            Spacer(Modifier.width(DshSpacing.xs))
            Icon(
                Icons.Rounded.SwapVert,
                contentDescription = "Switch default",
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
        }
        if (selected) {
            Icon(
                Icons.Rounded.Check,
                contentDescription = "Current default",
                tint = colors.accent,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Model trigger. The web trigger shows the model name (the provider appears only
 * in a degenerate fallback). The label is width-capped so a long name ellipsizes
 * rather than pushing the trailing group off the card's margin.
 */
@Composable
private fun ModelTrigger(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(DshRadius.pill))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 132.dp),
        )
        Spacer(Modifier.width(DshSpacing.xxs))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(14.dp),
        )
    }
}

/**
 * A mode selector: 28dp tall, r8, 13/20/500, with a 12dp chevron trailing it —
 * matching the web UI's Plan / Read-only selects.
 */
@Composable
private fun ModeChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Row(
        modifier
            .height(28.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(onClick = onClick)
            .padding(start = DshSpacing.md, end = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A shield marks this as a permissions control, so the label cannot be
        // mistaken for the workspace *directory* picker that sits on the hero.
        Icon(
            Icons.Rounded.Security,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(12.dp),
        )
        Spacer(Modifier.width(DshSpacing.xs))
        Text(
            text = label,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(DshSpacing.xxs))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(12.dp),
        )
    }
}

/** Plan mode is on: the chip exists only to switch it back off (`/plan off`). */
@Composable
private fun PlanChip(onExit: () -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .background(colors.accentTertiary)
            .clickableNoRipple(onClick = onExit)
            .padding(horizontal = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Plan",
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.accent,
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Icon(
            Icons.Rounded.Close,
            contentDescription = "Leave plan mode",
            tint = colors.accent,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * Context-window gauge and its popover.
 *
 * The web meter opens a small **anchored, ephemeral popover** — no scrim, no flow
 * interruption, dismissed by an outside tap. A modal dialog was the wrong shape for
 * something this glanceable, so this is a real [Popup] pinned above the ring that
 * also fades itself out.
 */
@Composable
private fun ContextMeter(
    percent: Int,
    usedTokens: Int,
    windowTokens: Int,
    breakdown: ContextBreakdown?,
) {
    val colors = DshTheme.colors
    val tint = when {
        percent >= 90 -> colors.error
        percent >= 75 -> colors.warn
        else -> colors.accent
    }
    var open by remember { mutableStateOf(false) }

    // Ephemeral: it closes itself, so a glance never leaves a thing to dismiss.
    LaunchedEffect(open) {
        if (open) {
            delay(4000)
            open = false
        }
    }

    Box {
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickableNoRipple { open = !open },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                val inset = stroke.width / 2f
                val arcSize = Size(size.width - stroke.width, size.height - stroke.width)
                drawArc(
                    color = colors.borderL3,
                    startAngle = 0f,
                    sweepAngle = 360f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = stroke,
                )
                drawArc(
                    color = tint,
                    startAngle = -90f,
                    sweepAngle = 360f * (percent.coerceIn(0, 100) / 100f),
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = stroke,
                )
            }
        }

        if (open) {
            Popup(
                onDismissRequest = { open = false },
                popupPositionProvider = remember {
                    object : PopupPositionProvider {
                        override fun calculatePosition(
                            anchorBounds: IntRect,
                            windowSize: IntSize,
                            layoutDirection: LayoutDirection,
                            popupContentSize: IntSize,
                        ): IntOffset = IntOffset(
                            // Right-aligned to the ring and sitting just above it,
                            // clamped so it never leaves the window.
                            x = (anchorBounds.right - popupContentSize.width)
                                .coerceIn(8, (windowSize.width - popupContentSize.width - 8).coerceAtLeast(8)),
                            y = (anchorBounds.top - popupContentSize.height - 8).coerceAtLeast(8),
                        )
                    }
                },
            ) {
                ContextCard(
                    percent = percent,
                    usedTokens = usedTokens,
                    windowTokens = windowTokens,
                    breakdown = breakdown,
                )
            }
        }
    }
}

@Composable
private fun ContextCard(
    percent: Int,
    usedTokens: Int,
    windowTokens: Int,
    breakdown: ContextBreakdown?,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        Modifier
            .width(220.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(colors.menu)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(DshSpacing.lg),
    ) {
        Text(
            text = if (windowTokens > 0) {
                "$percent% · ${compactTokens(usedTokens)} / ${compactTokens(windowTokens)}"
            } else {
                "No context window reported"
            },
            style = DshType.bodySmall,
            color = colors.labelPrimary,
        )
        if (breakdown != null && windowTokens > 0) {
            Spacer(Modifier.height(DshSpacing.md))
            MeterRow("System", breakdown.system, windowTokens, colors.accent)
            MeterRow("Tools", breakdown.tools, windowTokens, colors.synFunction)
            MeterRow("Messages", breakdown.messages, windowTokens, colors.success)
        }
    }
}

@Composable
private fun MeterRow(label: String, tokens: Int, window: Int, tint: Color) {
    val colors = DshTheme.colors
    val fraction = (tokens.toFloat() / window).coerceIn(0f, 1f)
    Column(Modifier.padding(vertical = 3.dp)) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = DshType.micro, color = colors.labelSecondary, modifier = Modifier.weight(1f))
            Text(compactTokens(tokens), style = DshType.micro, color = colors.labelTertiary)
        }
        Spacer(Modifier.height(3.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(colors.borderL1),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(tint),
            )
        }
    }
}

private fun compactTokens(tokens: Int): String = when {
    tokens >= 1_000_000 -> String.format(Locale.US, "%.1fM", tokens / 1_000_000.0)
    tokens >= 1_000 -> String.format(Locale.US, "%.0fk", tokens / 1_000.0)
    else -> tokens.toString()
}

/** Compact attachment chips; the web UI uses 64dp thumbnails, which is too wide here. */
@Composable
private fun AttachmentRail(
    attachments: List<PendingAttachment>,
    onRemove: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.md),
    ) {
        attachments.forEach { attachment ->
            val shape = RoundedCornerShape(DshRadius.md)
            val tint = when {
                attachment.error != null -> colors.error
                attachment.receiptId == null -> colors.labelTertiary
                else -> colors.labelSecondary
            }
            Row(
                Modifier
                    .clip(shape)
                    .background(colors.tip)
                    .border(0.5.dp, colors.borderL2, shape)
                    .padding(start = DshSpacing.md, end = DshSpacing.xs, top = DshSpacing.sm, bottom = DshSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Rounded.Description,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(13.dp),
                )
                Spacer(Modifier.width(DshSpacing.sm))
                Text(
                    text = attachment.error
                        ?: if (attachment.receiptId == null) "${attachment.name} · uploading…" else attachment.name,
                    style = DshType.micro,
                    color = tint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(DshSpacing.sm))
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Remove attachment",
                    tint = colors.labelTertiary,
                    modifier = Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .clickableNoRipple { onRemove(attachment.id) }
                        .padding(3.dp),
                )
            }
        }
    }
}

@Composable
fun CircleIconButton(
    icon: ImageVector,
    contentDescription: String?,
    onClick: () -> Unit,
    size: Dp,
    background: Color,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}
