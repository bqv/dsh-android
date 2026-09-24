package uk.xa0.dsh.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.HelpOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import uk.xa0.dsh.GoalState
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.DisplayRow
import uk.xa0.dsh.model.LiveAttempt
import uk.xa0.dsh.model.MessageAttachment
import uk.xa0.dsh.model.NoticeSeverity
import uk.xa0.dsh.model.TodoItem
import uk.xa0.dsh.model.ToolCallNode
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.shortenToolPath
import uk.xa0.dsh.ui.markdown.MarkdownText
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * User turns are right-aligned capsules in `--dsw-specific-bubble`; plugin-injected
 * notices (background-job results, hook output) reuse the row but read as system
 * text rather than something the human typed.
 */
@Composable
fun UserMessageRow(
    entry: ChatEntry.UserMessage,
    /**
     * Resolves an image attachment to a drawable. Passed in rather than wrapped in
     * a CompositionLocal so the row keeps no dependency on the RPC client.
     */
    loadImage: suspend (String) -> ImageBitmap? = { null },
) {
    val colors = DshTheme.colors
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End,
    ) {
        Box(
            Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(DshRadius.bubble))
                .background(if (entry.fromPlugin) colors.tip else colors.bubble)
                // Sent-but-unclaimed: the host has not echoed it into the journal
                // yet, so it reads as in-flight rather than as a settled turn.
                .alpha(if (entry.pending) 0.7f else 1f)
                .padding(horizontal = DshSpacing.xl, vertical = 10.dp),
        ) {
            Column {
                if (entry.fromPlugin && entry.summary != null) {
                    Text(
                        text = entry.summary,
                        style = DshType.micro,
                        color = colors.labelTertiary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(DshSpacing.xs))
                }
                // Web order is attachments *then* text: the picture is the first
                // thing you see, and the caption reads under it.
                if (entry.attachments.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.sm)) {
                        entry.attachments.forEach { attachment ->
                            MessageAttachmentBlock(attachment, loadImage)
                        }
                    }
                    if (entry.text.isNotBlank()) Spacer(Modifier.height(DshSpacing.md))
                }
                if (entry.text.isNotBlank()) {
                    Text(
                        text = entry.text,
                        style = DshType.userBubble,
                        color = if (entry.fromPlugin) colors.labelSecondary else colors.labelPrimary,
                    )
                }
                if (entry.pending) {
                    if (entry.text.isNotBlank() || entry.attachments.isNotEmpty()) {
                        Spacer(Modifier.height(DshSpacing.sm))
                    }
                    Text(text = "Sending…", style = DshType.micro, color = colors.labelTertiary)
                }
            }
        }
        // A pending row has no host timestamp yet; its actions would be pointless.
        val stamp = if (entry.pending) "" else formatClock(entry.time)
        if (stamp.isNotEmpty()) {
            Spacer(Modifier.height(DshSpacing.xs))
            // The turn rail runs down the right edge, so the action row is pulled in
            // clear of it. Reserving the same space in the transcript's padding
            // instead would make the content column asymmetric against the composer.
            MessageActions(
                text = entry.text,
                time = entry.time,
                clockFirst = true,
                modifier = Modifier.padding(end = 34.dp),
            )
        }
    }
}

/**
 * One attachment on a user message.
 *
 * An `image` block draws the picture itself — that is the whole point of
 * attaching one, and a filename chip is not "seeing" it. A `file` block is a chip:
 * the host keeps the two kinds apart (only images are readable back as bytes), so
 * a screenshot sent as a file is a file.
 */
@Composable
private fun MessageAttachmentBlock(
    attachment: MessageAttachment,
    loadImage: suspend (String) -> ImageBitmap?,
) {
    if (attachment.kind != "image") {
        MessageAttachmentChip(attachment)
        return
    }

    val colors = DshTheme.colors
    val local = attachment.localData
    // The row rebuilds under the same empty id when a message is edited, so the
    // payload is a key as well: same id, different bytes must not reuse a decode.
    val state by produceState<AttachmentImage>(
        initialValue = AttachmentImage.Loading,
        key1 = attachment.attachmentId,
        key2 = local,
    ) {
        val staged = local
        value = when {
            // A staged picture is already on this device. Decoding a full photo is
            // CPU work on the critical path of the send animation, so it runs off
            // the frame's dispatcher; nothing is cached, since the composition owns
            // the only reference it needs.
            !staged.isNullOrEmpty() ->
                withContext(Dispatchers.Default) { decodeStagedImage(staged) }
                    ?.let { AttachmentImage.Loaded(it) }
                    ?: AttachmentImage.Failed

            attachment.attachmentId.isNotEmpty() ->
                loadImage(attachment.attachmentId)
                    ?.let { AttachmentImage.Loaded(it) }
                    ?: AttachmentImage.Failed

            // Neither the bytes nor an address to read them from: this block can
            // never resolve, and the old icon made that look like loading forever.
            else -> AttachmentImage.Failed
        }
    }

    val shape = RoundedCornerShape(DshRadius.md)
    Box(
        Modifier
            .widthIn(max = 220.dp)
            .heightIn(max = 220.dp)
            .clip(shape)
            .background(colors.bgBase)
            .border(0.5.dp, colors.borderL1, shape)
            // The placeholder is a deliberately sized block rather than an icon, so
            // the bubble already has its final shape; animating the size carries it
            // the rest of the way when a photo of a different shape lands.
            .animateContentSize(tween(durationMillis = 180)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(
            targetState = state,
            animationSpec = tween(durationMillis = 160),
            label = "attachment-image",
        ) { current ->
            when (current) {
                is AttachmentImage.Loaded -> Image(
                    bitmap = current.bitmap,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    // Matching the picture's own ratio keeps a 220dp width cap
                    // without letterboxing a portrait into a landscape slot.
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .aspectRatio(aspectRatioOf(current.bitmap)),
                )

                AttachmentImage.Failed -> AttachmentLoadFailed()
                AttachmentImage.Loading -> AttachmentSkeleton(attachment.name)
            }
        }
    }
}

/** What an image block is showing: bytes still arriving, the picture, or a dead end. */
private sealed interface AttachmentImage {
    data object Loading : AttachmentImage
    data class Loaded(val bitmap: ImageBitmap) : AttachmentImage
    data object Failed : AttachmentImage
}

/**
 * The loading fill: the same 180×120 footprint as the failure state, breathing
 * slowly between two surface tones. It is quiet on purpose — a placeholder that
 * moves enough to notice competes with the text it sits above.
 */
@Composable
private fun AttachmentSkeleton(name: String) {
    val colors = DshTheme.colors
    val transition = rememberInfiniteTransition(label = "attachment-skeleton")
    val breath by transition.animateFloat(
        initialValue = 0.35f,
        targetValue = 0.7f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "breath",
    )
    Box(
        Modifier
            .size(width = 180.dp, height = 120.dp)
            .background(colors.bgLayer2.copy(alpha = breath)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Image,
            contentDescription = "Loading $name",
            tint = colors.labelTertiary,
            modifier = Modifier
                .size(20.dp)
                .alpha(0.6f),
        )
    }
}

/**
 * The failure state, deliberately the same footprint as the skeleton so nothing
 * reflows, but static and captioned: a decode that failed must not keep wearing
 * the loading costume.
 */
@Composable
private fun AttachmentLoadFailed() {
    val colors = DshTheme.colors
    Column(
        Modifier.size(width = 180.dp, height = 120.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.BrokenImage,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.height(DshSpacing.sm))
        Text(
            text = "Couldn't load picture",
            style = DshType.micro,
            color = colors.labelTertiary,
        )
    }
}

/** The picture's own width/height, with a square fallback for a degenerate decode. */
private fun aspectRatioOf(bitmap: ImageBitmap): Float =
    if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 1f

/**
 * Decodes the inline base64 a staged attachment carries. Null covers both a
 * malformed payload and bytes `BitmapFactory` refuses, which the caller renders
 * identically.
 */
private fun decodeStagedImage(encoded: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

@Composable
private fun MessageAttachmentChip(attachment: MessageAttachment) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.md)
    Row(
        Modifier
            .clip(shape)
            .background(colors.bgBase)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (attachment.kind == "image") Icons.Rounded.Image else Icons.Rounded.Description,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = attachment.name,
            style = DshType.bodyMedium,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 176.dp),
        )
        Spacer(Modifier.width(DshSpacing.md))
        Text(
            text = fileSizeText(attachment.bytes),
            style = DshType.micro,
            color = colors.labelTertiary,
        )
    }
}

/**
 * The message action row. The web component's render order is
 * `[clock] [copy] [extras] [branch] [usage] [clock]`, with the clock appearing
 * before copy for user/steering rows and at the end for the assistant turn tail.
 *
 * Copy swaps to a check for 1000 ms with no toast, and repeat clicks inside that
 * window are ignored — both as in the web client.
 */
@Composable
fun MessageActions(
    text: String,
    time: Long,
    modifier: Modifier = Modifier,
    clockFirst: Boolean = false,
    durationMs: Long? = null,
) {
    val colors = DshTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember(text) { mutableStateOf(false) }

    LaunchedEffect(copied) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }

    val stamp = formatClock(time)

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (clockFirst && stamp.isNotEmpty()) {
            Text(stamp, style = DshType.micro, color = colors.labelTertiary)
            Spacer(Modifier.width(DshSpacing.xs))
        }

        if (durationMs != null && durationMs > 0) {
            Icon(
                Icons.Rounded.Schedule,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(DshSpacing.xs))
            Text(
                "Ran for ${formatDuration(durationMs)}",
                style = DshType.micro,
                color = colors.labelTertiary,
            )
            Spacer(Modifier.width(DshSpacing.md))
        }

        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .clickableNoRipple {
                    if (copied) return@clickableNoRipple
                    clipboard.setText(AnnotatedString(text))
                    copied = true
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy,
                contentDescription = if (copied) "Copied" else "Copy",
                tint = if (copied) colors.success else colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }

        if (!clockFirst && stamp.isNotEmpty()) {
            Spacer(Modifier.width(DshSpacing.xs))
            Text(stamp, style = DshType.micro, color = colors.labelTertiary)
        }
    }
}

private val clockHm = SimpleDateFormat("HH:mm", Locale.getDefault())
private val clockMd = SimpleDateFormat("M/d HH:mm", Locale.getDefault())
private val clockYmd = SimpleDateFormat("yyyy-M-d HH:mm", Locale.getDefault())

/**
 * `formatMessageClock`: `HH:mm` for today, `M/D HH:mm` earlier this year, and
 * `Y-M-D HH:mm` beyond it.
 */
internal fun formatClock(time: Long): String {
    if (time <= 0L) return ""
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = time }
    val sameDay = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) &&
        now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> clockHm.format(Date(time))
        now.get(Calendar.YEAR) == then.get(Calendar.YEAR) -> clockMd.format(Date(time))
        else -> clockYmd.format(Date(time))
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val hours = total / 3600
    val minutes = (total / 60) % 60
    val seconds = total % 60
    // Matches the host's `formatRunDuration`: minutes and seconds are zero-padded
    // whenever a larger unit is present ("2m 09s"), bare seconds are not.
    return when {
        hours > 0 -> "${hours}h ${pad2(minutes)}m ${pad2(seconds)}s"
        minutes > 0 -> "${minutes}m ${pad2(seconds)}s"
        else -> "${seconds}s"
    }
}

private fun pad2(value: Long): String = if (value < 10) "0$value" else value.toString()

/**
 * The running-turn status line: **"Deep diving..."** in a brand-blue text shimmer,
 * with a clock that only appears once the turn has clearly been running a while.
 *
 * Mirrors `ChatView.tsx#TurnStatus`: the row is turn-scoped (anchored to
 * `turn/start`) so it rides the whole turn — first-token wait, tool execution and
 * streaming alike — and never flickers per step. There is no streaming caret in
 * the web UI; this row is the live signal.
 */
@Composable
fun TurnStatusRow(elapsedMs: Long?, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    val transition = rememberInfiniteTransition(label = "turn-status")
    val sweep by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "sweep",
    )

    // The web paints this as `background-size: 250% 100%` with the position sliding
    // from 100% to 0, so the bright segment covers 40-60% of a gradient 2.5x the
    // text width. That makes the wash wide and gentle; a narrow band with a strong
    // colour step (the first attempt) reads as a streak instead.
    var textWidth by remember { mutableStateOf(0f) }
    val measured = if (textWidth > 0f) textWidth else 320f
    val span = measured * 2.5f
    val origin = -(span - measured) * (1f - sweep)
    val shimmer = Brush.linearGradient(
        colorStops = arrayOf(
            0f to colors.accentDeep,
            0.4f to colors.accentDeep,
            0.5f to colors.accentLight,
            0.6f to colors.accentDeep,
            1f to colors.accentDeep,
        ),
        start = Offset(origin, 0f),
        end = Offset(origin + span, 0f),
    )

    Row(
        modifier.height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Deep diving...",
            style = DshType.messageBody.copy(fontWeight = FontWeight.SemiBold, brush = shimmer),
            maxLines = 1,
            modifier = Modifier.onSizeChanged { textWidth = it.width.toFloat() },
        )
        if (elapsedMs != null && elapsedMs >= 15_000) {
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = formatDuration(elapsedMs),
                style = DshType.bodyMedium.copy(
                    fontWeight = FontWeight.Normal,
                    fontFeatureSettings = "tnum",
                ),
                color = colors.labelCaption,
                maxLines = 1,
            )
        }
    }
}

/**
 * Assistant turns are plain full-width markdown — no bubble, no avatar. Reasoning
 * is folded into a collapsed disclosure above the answer, exactly as the web UI does.
 *
 * [showFooter] marks the row that closes a completed turn: in the web UI the
 * assistant action row hangs off a separate `turn-tail` node and is omitted
 * entirely when a turn has no closing text answer.
 */
@Composable
fun AssistantMessageRow(
    entry: ChatEntry.AssistantMessage,
    showFooter: Boolean = false,
    durationMs: Long? = null,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DshSpacing.xl)) {
        if (entry.reasoning.isNotBlank()) {
            ReasoningRow(entry.reasoning, running = false)
        }
        if (entry.text.isNotBlank()) {
            MarkdownText(entry.text)
        }
        if (showFooter) {
            // margin-left: -6px, so the 28dp icon target optically aligns with the
            // text column it belongs to.
            MessageActions(
                text = entry.text,
                time = entry.time,
                durationMs = durationMs,
                modifier = Modifier.offset(x = (-6).dp),
            )
        }
    }
}

/** The in-flight attempt: live reasoning, live answer text, plus a streaming dot. */
@Composable
fun LiveAttemptRow(attempt: LiveAttempt) {
    val colors = DshTheme.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(DshSpacing.xl)) {
        val reasoning = attempt.reasoning
        if (reasoning.isNotBlank()) {
            ReasoningRow(reasoning, running = !attempt.finished)
        }
        val text = attempt.text
        if (text.isNotBlank()) {
            MarkdownText(text)
        }
        val pending = attempt.toolNames
        if (pending.isNotEmpty() && text.isBlank() && reasoning.isBlank()) {
            Text(
                text = "Calling ${pending.last()}…",
                style = DshType.bodyMedium,
                color = colors.labelTertiary,
            )
        }
        // No streaming caret: the web UI's live signal is the turn-scoped
        // "Deep diving..." row at the bottom of the column (see TurnStatusRow).
    }
}

/**
 * "Think" disclosure: one 24dp header row collapsing to a summary, expanding to
 * pre-wrapped secondary text.
 */
@Composable
fun ReasoningRow(reasoning: String, running: Boolean) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(reasoning.take(32)) { mutableStateOf(false) }
    val summary = remember(reasoning) {
        reasoning.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    }

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
                if (expanded) {
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Icon(
                        Icons.Rounded.Lightbulb,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = if (running) "Thinking…" else "Think",
                style = DshType.rowTitle,
                color = colors.labelSecondary,
                maxLines = 1,
            )
            if (!expanded && summary.isNotEmpty()) {
                Spacer(Modifier.width(DshSpacing.md))
                Box(
                    Modifier
                        .size(2.dp)
                        .clip(CircleShape)
                        .background(colors.labelCaption),
                )
                Spacer(Modifier.width(DshSpacing.md))
                Text(
                    text = summary,
                    style = DshType.rowSummary,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = if (running) Modifier.alpha(0.8f) else Modifier,
                )
            }
        }

        if (expanded) {
            Text(
                text = reasoning.trim(),
                style = DshType.bodyMedium,
                color = colors.labelTertiary,
                modifier = Modifier.padding(start = 22.dp, top = DshSpacing.xs, bottom = DshSpacing.xs),
            )
        }
    }
}

/** Tool calls: collapsed summary row, expanding to a mono IN/OUT card. */
@Composable
fun ToolCallRow(
    entry: ChatEntry.ToolCall,
    /**
     * Resolves a host-held attachment id to a drawable. Only the read-image
     * card consumes it; defaulted so every other caller (and preview) needs no
     * client dependency.
     */
    loadImage: suspend (String) -> ImageBitmap? = { null },
) {
    val colors = DshTheme.colors
    // The ask-question call owns a transcript row of its own: its args and result
    // pair into a question/answer card rather than an IN/OUT dump.
    val askModel = remember(entry.callId, entry.arguments, entry.result, entry.isError, entry.errorCode) {
        if (entry.name == "ask_user_question") {
            askQuestionRowModel(entry.arguments, entry.result, entry.isError, entry.errorCode)
        } else {
            null
        }
    }
    if (askModel != null) {
        AskQuestionCallRow(entry, askModel)
        return
    }
    // A standard shell call draws the terminal card; anything the card model
    // declines (errors, background or persistent shells) keeps the generic row.
    val bashModel = remember(entry.callId, entry.arguments, entry.result, entry.isError) {
        if (entry.name == "bash" || entry.name == "pwsh") {
            bashTerminalModel(entry.arguments, entry.result, entry.isError)
        } else {
            null
        }
    }
    if (bashModel != null) {
        BashCallRow(entry, bashModel)
        return
    }
    val readModel = remember(entry.callId, entry.arguments, entry.result, entry.isError, entry.meta) {
        if (entry.name == "read" || entry.name == "read_file") {
            readCardModel(entry.arguments, entry.result, entry.isError, entry.meta)
        } else {
            null
        }
    }
    if (readModel != null) {
        ReadCallRow(entry, readModel)
        return
    }
    // A `read` on a picture, or a `read_image` call: the result carries image
    // blocks rather than a file envelope, so the read card above declined.
    val imageModel = remember(entry.callId, entry.arguments, entry.result, entry.isError, entry.meta, entry.images) {
        if (entry.name == "read" || entry.name == "read_image") {
            readImageCardModel(entry.arguments, entry.result, entry.isError, entry.meta, entry.images)
        } else {
            null
        }
    }
    if (imageModel != null) {
        ReadImageCallRow(entry, imageModel, loadImage)
        return
    }
    val searchModel = remember(entry.callId, entry.arguments, entry.result, entry.isError, entry.meta) {
        if (entry.name == "grep" || entry.name == "glob") {
            searchCardModel(entry.name, entry.arguments, entry.result, entry.isError, entry.meta)
        } else {
            null
        }
    }
    if (searchModel != null) {
        SearchCallRow(entry, searchModel)
        return
    }
    val webModel = remember(entry.callId, entry.isError, entry.meta) {
        if (entry.name == "web_search" || entry.name == "web_fetch") {
            webCardModel(entry.name, entry.isError, entry.meta)
        } else {
            null
        }
    }
    if (webModel != null) {
        WebCallRow(entry, webModel)
        return
    }
    val diffModel = remember(entry.callId, entry.arguments, entry.result, entry.isError, entry.meta) {
        if (entry.name == "write" || entry.name == "edit") {
            diffCardModel(entry.name, entry.arguments, entry.result, entry.isError, entry.meta)
        } else {
            null
        }
    }
    if (diffModel != null) {
        DiffCallRow(entry, diffModel)
        return
    }
    val planModel = remember(entry.arguments, entry.isError, entry.errorCode) {
        if (entry.name == "todo_write") {
            planRowModel(entry.arguments, entry.isError, entry.errorCode)
        } else {
            null
        }
    }
    if (planModel != null) {
        PlanCallRow(entry, planModel)
        return
    }
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }
    val summary = remember(entry.name, entry.arguments) { toolSummary(entry.name, entry.arguments) }
    val title = remember(entry.name) { toolTitle(entry.name) }

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
                if (expanded) {
                    Icon(
                        Icons.Rounded.ExpandMore,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    Icon(
                        imageVector = toolIcon(entry.name),
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = title,
                style = DshType.rowTitle,
                color = colors.labelSecondary,
                maxLines = 1,
            )
            // An empty summary drops the separator with it (ToolRow.tsx:212), so a
            // row that is only its title shows no trailing dot. A blank summary on
            // an in-flight call still says "running", since the row has no other
            // signal that the call has started.
            val rowSummary = when {
                summary.isNotBlank() && entry.result == null -> "$summary · running"
                summary.isNotBlank() -> summary
                entry.result == null -> "running"
                else -> ""
            }
            if (rowSummary.isNotBlank()) {
                Spacer(Modifier.width(DshSpacing.md))
                Box(
                    Modifier
                        .size(2.dp)
                        .clip(CircleShape)
                        .background(colors.labelCaption),
                )
                Spacer(Modifier.width(DshSpacing.md))
                Text(
                    text = rowSummary,
                    style = DshType.rowSummary,
                    color = if (entry.isError) colors.error else colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
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

/**
 * The IN/OUT card: 0.5dp hairline, 12dp radius, code surface, 11/16 mono, each
 * section independently capped and scrollable.
 */
@Composable
internal fun ToolIoCard(sections: List<Pair<String, String>>, isError: Boolean) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.xs)
            .clip(shape)
            .background(colors.codeBlock)
            .border(0.5.dp, colors.borderL1, shape),
    ) {
        sections.forEachIndexed { index, (label, body) ->
            if (index > 0) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.borderL2),
                )
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 150.dp)
                    // The cap without a scroller hid everything past 150dp with
                    // no way to reach it; the web's `.ioSection` scrolls.
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.lg),
            ) {
                Text(
                    text = label,
                    style = DshType.micro,
                    color = colors.labelCaption,
                    modifier = Modifier.width(30.dp),
                )
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    Text(
                        text = body.ifEmpty { "—" },
                        style = DshType.codeSmall,
                        color = if (isError && label == "OUT") colors.error else colors.labelSecondary,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

/**
 * One tool call and whatever the host nested under it (`ToolCallTree.tsx`).
 *
 * The web paints a root call and its recursive sub-calls through the same row
 * component, indenting the children behind a 0.5px connector rail
 * (`ToolCallTree.module.css` `.subCalls`: `margin-left: 22px`, `padding-left:
 * 8px`, `border-left: 0.5px`). It has no caret — a mouse user sees the whole
 * subtree at once. A phone cannot afford that: a `run_code` program can nest
 * hundreds of dispatches, and the transcript has no hover to preview them, so
 * the subtree starts folded behind a caret that carries the count.
 *
 * A node with no children renders exactly the flat [ToolCallRow] it replaced, so
 * non-PTC calls are untouched.
 */
@Composable
fun ToolCallTreeRow(
    node: ToolCallNode,
    /**
     * Resolves a host-held attachment id to a drawable; forwarded unchanged to
     * every row in the subtree.
     */
    loadImage: suspend (String) -> ImageBitmap? = { null },
) {
    if (node.children.isEmpty()) {
        ToolCallRow(node.entry, loadImage)
        return
    }
    var expanded by rememberSaveable(node.entry.callId) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        ToolCallRow(node.entry, loadImage)
        NestedCallDisclosure(
            count = node.descendantCount,
            expanded = expanded,
            onToggle = { expanded = !expanded },
        )
        if (expanded) NestedCalls(node.children, loadImage)
    }
}

/**
 * The connector rail and the nested rows it hangs: web `.subCalls`' geometry,
 * with the rail drawn behind instead of as a border so the column keeps its
 * intrinsic height.
 */
@Composable
private fun NestedCalls(
    children: List<ToolCallNode>,
    loadImage: suspend (String) -> ImageBitmap?,
) {
    val colors = DshTheme.colors
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 22.dp)
            .drawBehind {
                drawRect(
                    color = colors.borderL2,
                    topLeft = Offset.Zero,
                    size = Size(0.5.dp.toPx(), size.height),
                )
            }
            .padding(start = DshSpacing.md, top = DshSpacing.xs, bottom = DshSpacing.xxs),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        for (child in children) {
            key(child.entry.callId) { ToolCallTreeRow(child, loadImage) }
        }
    }
}

/**
 * The collapsed-subtree affordance: a 24dp disclosure line seated under the
 * parent's own 16dp leading slot, labelled with the web's subtool vocabulary
 * (`ui-trajectory/locales.ts` `kind.subtool` / `details.subtoolCalls`).
 */
@Composable
private fun NestedCallDisclosure(count: Int, expanded: Boolean, onToggle: () -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickableNoRipple(onClick = onToggle)
            .padding(start = 22.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
            contentDescription = if (expanded) "Collapse subtool calls" else "Expand subtool calls",
            tint = colors.labelTertiary,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = if (count == 1) "1 subtool call" else "$count subtool calls",
            style = DshType.rowSummary,
            color = colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The to-do dock, mirroring `TodoPanel.tsx`.
 *
 * Starts **collapsed** (the web panel's `useState(true)`) and its chevron points
 * *up* while collapsed, because the panel grows upward from the composer. The
 * header carries the checklist glyph, the title "To-dos", and a done/active/pending
 * tally; the list only exists while expanded.
 */
@Composable
fun TodoCard(items: List<TodoItem>, modifier: Modifier = Modifier) {
    if (items.isEmpty()) return
    val colors = DshTheme.colors
    var collapsed by rememberSaveable { mutableStateOf(true) }
    val shape = RoundedCornerShape(DshRadius.card)

    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.tip)
            .border(0.5.dp, colors.borderL1, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickableNoRipple { collapsed = !collapsed }
                // Extra trailing inset so the chevron clears the turn rail's
                // gesture column, which overlays the transcript's right edge.
                .padding(start = DshSpacing.lg, end = 30.dp, top = DshSpacing.md, bottom = DshSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Checklist,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = "To-dos",
                style = DshType.rowTitle.copy(fontWeight = FontWeight.Medium),
                color = colors.labelPrimary,
                maxLines = 1,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = todoProgressLabel(items),
                style = DshType.micro,
                color = colors.labelTertiary,
                maxLines = 1,
            )
            Spacer(Modifier.width(DshSpacing.md))
            Icon(
                imageVector = if (collapsed) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (collapsed) "Expand to-dos" else "Collapse to-dos",
                tint = colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }

        if (!collapsed) {
            Column(
                Modifier
                    .padding(
                        start = DshSpacing.lg,
                        end = DshSpacing.lg,
                        bottom = DshSpacing.md,
                    )
                    // The composer below cannot shrink, so an unbounded list here
                    // pushes it off the screen once the keyboard is up. The web
                    // caps its panel lists the same way.
                    .heightIn(max = 168.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                items.forEach { item ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            TodoGlyph(isDone = item.isDone, isActive = item.isActive)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = item.content,
                            style = DshType.bodySmall,
                            color = colors.labelSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/**
 * `progressLabel`: only non-zero buckets, joined with en-space · en-space because
 * HTML/Compose both collapse runs of ASCII spaces.
 */
private fun todoProgressLabel(items: List<TodoItem>): String {
    val done = items.count { it.isDone }
    val active = items.count { it.isActive }
    val pending = items.size - done - active
    return buildList {
        if (done > 0) add("$done completed")
        if (active > 0) add("$active in progress")
        if (pending > 0) add("$pending pending")
    }.joinToString("\u2002·\u2002")
}

/**
 * Non-conversational rows: plugin/job notices, context compaction, and errors.
 *
 * Notices are compact `context` rows like the web UI's, not chat bubbles — one
 * summary line with an icon, and the body only when the row is expanded. Errors
 * keep the warning card treatment.
 */
@Composable
fun NoticeRow(entry: ChatEntry.Notice) {
    val colors = DshTheme.colors

    if (entry.severity == NoticeSeverity.ERROR) {
        Text(
            text = entry.text.ifBlank { entry.kind },
            style = DshType.bodySmall,
            color = colors.error,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.md))
                .background(colors.warnTertiary)
                .padding(DshSpacing.lg),
        )
        return
    }

    var expanded by rememberSaveable(entry.seq) { mutableStateOf(false) }
    val isCompaction = entry.kind.startsWith("compaction")

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickableNoRipple(enabled = entry.detail != null) { expanded = !expanded },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isCompaction) Icons.Rounded.Info else Icons.Rounded.SmartToy,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = entry.text.ifBlank { entry.kind },
                style = DshType.micro,
                color = colors.labelTertiary,
                maxLines = if (expanded) 4 else 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (entry.detail != null) {
                Spacer(Modifier.width(DshSpacing.sm))
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
        }

        if (expanded) {
            entry.detail?.let { detail ->
                Spacer(Modifier.height(DshSpacing.xs))
                Text(
                    text = detail,
                    style = DshType.bodySmall,
                    color = colors.labelSecondary,
                    modifier = Modifier.padding(start = 21.dp),
                )
            }
        }
    }
}

/**
 * Approval prompt for a host `approval/request` waterfall.
 *
 * This is a hard requirement for a usable client: while a waterfall is pending
 * the agent is blocked, so the answer has to come from a human tap.
 */
@Composable
fun ApprovalCard(
    toolName: String,
    reason: String?,
    onAllow: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.inputMajor)
            .border(1.dp, colors.warn, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.warnTertiary)
                .padding(horizontal = DshSpacing.xl, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Warning,
                contentDescription = null,
                tint = colors.warn,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(DshSpacing.md))
            Text("Approval required", style = DshType.meta, color = colors.warn)
        }

        Column(Modifier.padding(DshSpacing.xl)) {
            Text(
                text = "$toolName wants to run",
                style = DshType.titleMedium,
                color = colors.labelPrimary,
            )
            if (!reason.isNullOrBlank()) {
                Spacer(Modifier.height(DshSpacing.sm))
                Text(reason, style = DshType.meta, color = colors.labelSecondary)
            }
            Spacer(Modifier.height(DshSpacing.xl))
            Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.lg)) {
                PillButton("Reject", primary = false, onClick = onReject)
                PillButton("Allow once", primary = true, onClick = onAllow)
            }
        }
    }
}

@Composable
private fun PillButton(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.pill)
    Box(
        Modifier
            .clip(shape)
            .background(if (primary) colors.sendFill else colors.tip)
            .border(0.5.dp, if (primary) colors.sendFill else colors.borderL3, shape)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.xxl, vertical = DshSpacing.md),
    ) {
        Text(
            text = label,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (primary) colors.sendGlyph else colors.labelPrimary,
        )
    }
}

/**
 * Goal bar: the docked strip above the composer.
 *
 * Like the to-do dock it starts collapsed, so the composer area stays compact on a
 * phone; the objective is still legible in the header. Expanding reveals the full
 * objective and the lifecycle actions, and `complete` goals render nothing at all.
 */
@Composable
fun GoalBar(
    goal: GoalState,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onEdit: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    var collapsed by rememberSaveable(goal.id) { mutableStateOf(true) }
    var editing by remember(goal.id) { mutableStateOf(false) }
    var draft by remember(goal.id) { mutableStateOf(goal.objective) }
    // Edit swaps a plain Text for a plain BasicTextField, which is invisible
    // without the caret and the keyboard: tapping Edit looked like it did
    // nothing. The field takes focus, so the caret and the keyboard appear.
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(editing) {
        if (editing) runCatching { focusRequester.requestFocus() }
    }

    val label = when (goal.phase) {
        "paused" -> "Paused Goal"
        "blocked" -> "Blocked Goal"
        "active" -> "Ongoing Goal"
        else -> "Goal"
    }
    val accent = when (goal.phase) {
        "paused", "blocked" -> colors.warn
        else -> colors.accent
    }

    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.tip)
            .border(0.5.dp, colors.borderL1, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickableNoRipple { collapsed = !collapsed }
                // Trailing inset clears the turn rail's gesture column, exactly as
                // the to-do card's does. The goal bar is dock-only today, so this is
                // for consistency and for the day goal updates render inline.
                .padding(start = DshSpacing.lg, end = 30.dp, top = DshSpacing.md, bottom = DshSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Flag,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(DshSpacing.sm))
            Text(label, style = DshType.rowTitle, color = accent, maxLines = 1)
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = goal.objective,
                style = DshType.bodySmall,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // `revision` is the host's CAS revision and bumps on every mutation
            // (edit/pause/resume), so it is not a round counter; the budget counts
            // `roundsStarted`, which the host advances to the admitted round when
            // that round's `user/message` folds — so while round N runs it is N.
            goal.maxRounds?.let { max ->
                Spacer(Modifier.width(DshSpacing.sm))
                Text(
                    text = "${goal.roundsStarted}/$max",
                    style = DshType.micro,
                    color = colors.labelCaption,
                )
            }
            Spacer(Modifier.width(DshSpacing.md))
            Icon(
                imageVector = if (collapsed) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (collapsed) "Expand goal" else "Collapse goal",
                tint = colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }

        if (!collapsed) {
            Column(
                Modifier
                    .padding(
                        start = DshSpacing.lg,
                        end = DshSpacing.lg,
                        bottom = DshSpacing.md,
                    )
                    // Same reason as the to-do list: a long objective must not
                    // push the composer off the screen.
                    .heightIn(max = 168.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                if (editing) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        textStyle = DshType.bodySmall.copy(color = colors.labelPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester)
                            .clip(RoundedCornerShape(DshRadius.md))
                            .background(colors.inputMajor)
                            .border(0.5.dp, colors.borderL2, RoundedCornerShape(DshRadius.md))
                            .padding(DshSpacing.md),
                    )
                    Spacer(Modifier.height(DshSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.lg)) {
                        GoalAction("Save", primary = true) {
                            editing = false
                            onEdit(draft)
                        }
                        GoalAction("Cancel", primary = false) { editing = false }
                    }
                } else {
                    Text(
                        text = goal.objective,
                        style = DshType.bodySmall,
                        color = colors.labelSecondary,
                    )
                    Spacer(Modifier.height(DshSpacing.md))
                    Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.lg)) {
                        if (goal.phase == "active") {
                            GoalAction("Pause", primary = false, onClick = onPause)
                        } else {
                            GoalAction("Resume", primary = false, onClick = onResume)
                        }
                        GoalAction("Edit", primary = false) {
                            draft = goal.objective
                            editing = true
                        }
                        GoalAction("Clear", primary = false, onClick = onClear)
                    }
                }
            }
        }
    }
}

@Composable
private fun GoalAction(label: String, primary: Boolean, onClick: () -> Unit) {
    val colors = DshTheme.colors
    Text(
        text = label,
        style = DshType.micro,
        color = if (primary) colors.accent else colors.labelTertiary,
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xxs),
    )
}

/**
 * The folded summary row for a finished turn: one 24dp disclosure line reading
 * "N tool calls · N messages". The web UI crossfades a tool glyph into a chevron
 * on hover; touch has no hover, so the chevron is always shown.
 */
@Composable
fun TurnProcessRow(row: DisplayRow.TurnProcess, onToggle: () -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(24.dp)
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickableNoRipple(onClick = onToggle),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = if (row.expanded) Icons.Rounded.ExpandMore else Icons.Rounded.ChevronRight,
                contentDescription = if (row.expanded) "Collapse process" else "Expand process",
                tint = colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
        }
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = row.label,
            style = DshType.rowTitle,
            color = colors.labelSecondary,
            maxLines = 1,
        )
    }
}

// ------------------------------------------------------------------ helpers

private fun toolIcon(name: String): ImageVector = when (name.lowercase()) {
    "bash", "shell", "terminal", "run_command" -> Icons.Rounded.Terminal
    "read", "read_file" -> Icons.Rounded.Description
    "write", "write_file" -> Icons.Rounded.Edit
    "edit", "str_replace", "apply_patch" -> Icons.Rounded.Edit
    "grep", "search" -> Icons.Rounded.Search
    "glob", "ls", "list" -> Icons.Rounded.FolderOpen
    "web_search" -> Icons.Rounded.Language
    "web_fetch" -> Icons.Rounded.Download
    "task", "subagent", "delegate" -> Icons.Rounded.SmartToy
    "todo_write", "todos" -> Icons.Rounded.Checklist
    "present" -> Icons.Rounded.Visibility
    "ask_user_question", "ask" -> Icons.Rounded.HelpOutline
    else -> Icons.Rounded.Build
}

private fun toolTitle(name: String): String = when (name.lowercase()) {
    "bash" -> "Bash"
    "read", "read_file" -> "Read"
    "write", "write_file" -> "Write"
    "edit" -> "Edit"
    "grep" -> "Grep"
    "glob" -> "Glob"
    "web_search" -> "Web Search"
    "web_fetch" -> "Web Fetch"
    "ask_user_question" -> "Question"
    "todo_write" -> "Plan"
    else -> name.replaceFirstChar { it.uppercase() }.replace('_', ' ')
}

/** Best-effort one-line summary of a tool call, preferring the model's own description. */
internal fun toolSummary(name: String, arguments: String): String {
    if (arguments.isBlank()) return ""
    val json = runCatching { JSONObject(arguments) }.getOrNull() ?: return arguments.take(120)
    val preferred = listOf(
        "description", "file_path", "path", "command", "pattern", "query", "url", "prompt", "name",
    )
    val pathKeys = setOf("file_path", "path", "cwd", "directory", "notebook_path")
    for (key in preferred) {
        val value = json.optString(key)
        if (value.isEmpty()) continue
        val first = value.lineSequence().first()
        // A full workspace path eats the whole row and pushes the tool's own name
        // off screen, so path-bearing keys are abbreviated before the length cap.
        return if (key in pathKeys) shortenToolPath(first) else first.take(160)
    }
    val first = json.keys().asSequence().firstOrNull() ?: return ""
    return json.optString(first).take(160)
}
