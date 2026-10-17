package uk.xa0.dsh.ui.components

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uk.xa0.dsh.QueueAttachment
import uk.xa0.dsh.QueuedMessage
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * Every size change in the dock — the panel arriving, a header appearing, a list
 * opening, a row landing — uses this one curve. Sharing it is what keeps the
 * panel from looking like two animations racing: whenever two of them run at
 * once (a row and the header, say) their halves cancel instead of overshooting.
 */
private val DockSizeSpec: FiniteAnimationSpec<IntSize> =
    tween(durationMillis = 220, easing = FastOutSlowInEasing)

/** Rows fade slightly ahead of the size change so they never appear mid-clip. */
private val DockFadeSpec: FiniteAnimationSpec<Float> = tween(durationMillis = 160)

/** The CSS `.5px` hairline every outline in the dock uses. */
private val HAIRLINE = 0.5.dp

/**
 * The queue dock — `QueueDock.tsx` ported.
 *
 * It sits in the input dock slot above the composer (order 20) and is drawn as a
 * panel whose bottom edge is tucked *under* the input card, so the two read as one
 * attached surface: square bottom is not a thing here, because the composer's own
 * rounded top covers it (see the caller's offset).
 *
 * Behaviour kept from the web:
 *  * a `steering` row is already on its way into the turn, so it wears the send
 *    glyph instead of the queue glyph, says "Steering…" and offers no actions;
 *    the host holds it in the queue until the durable message lands;
 *  * a local submission echo (no host row yet) renders in place with a "Sending…"
 *    status and its actions disabled, and is retired by the host row carrying the
 *    same `rpcId` — the web's `admitted` rule;
 *  * one row renders directly and carries the queue glyph itself; two or more
 *    render a count header that defaults to collapsed;
 *  * an in-flight edit or a mutation forces the list open;
 *  * Edit is refused when the content is not all text (the host rejects non-text
 *    edit content), and Steer is refused unless the agent is running.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun QueueDock(
    items: List<QueuedMessage>,
    running: Boolean,
    mutable: Boolean,
    busy: String?,
    onEdit: (String, String) -> Unit,
    onRemove: (String) -> Unit,
    onSteer: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors

    // The queue empties the instant the host admits its last row, but the panel
    // still has to animate out. Holding the rows it was last showing keeps the
    // content measurable for the exit, instead of blanking on the first frame and
    // leaving the shrink nothing to draw.
    var shown by remember { mutableStateOf(items) }
    if (items.isNotEmpty() && items != shown) shown = items

    var collapsed by rememberSaveable { mutableStateOf(true) }
    var editing by remember { mutableStateOf<Pair<String, String>?>(null) }

    // A row that leaves the queue while being edited (the host claimed it, or
    // another client removed it) closes the editor, as the web effect does.
    LaunchedEffect(items.map { it.id }, mutable) {
        val id = editing?.first ?: return@LaunchedEffect
        if (!mutable || items.none { it.id == id }) editing = null
    }
    // Keyed on the transition, not on the emptiness: resetting when the queue
    // drains would collapse the list *while* it is animating away, so a refilled
    // queue waits until it is actually back on screen to start folded.
    LaunchedEffect(items.isNotEmpty()) { if (items.isNotEmpty()) collapsed = true }

    val interactionActive = mutable && (editing != null || busy != null)
    val expanded = !collapsed || interactionActive
    // `shown` rather than `items`: during the exit this is the last real queue, and
    // the panel has to keep its rows until the shrink finishes.
    val listVisible = shown.size == 1 || expanded

    val panelShape = RoundedCornerShape(
        topStart = DshRadius.card,
        topEnd = DshRadius.card,
        bottomStart = 0.dp,
        bottomEnd = 0.dp,
    )

    AnimatedVisibility(
        visible = items.isNotEmpty(),
        modifier = modifier,
        // All four transitions grow and sink from the bottom because the panel's
        // bottom edge is the fixed one: it is pinned above the composer, so the
        // dock has to unfold upward out of it. The size transform clips, which is
        // what turns the old one-frame jump into a reveal.
        enter = expandVertically(animationSpec = DockSizeSpec, expandFrom = Alignment.Bottom) +
            fadeIn(DockFadeSpec),
        exit = shrinkVertically(animationSpec = DockSizeSpec, shrinkTowards = Alignment.Bottom) +
            fadeOut(DockFadeSpec),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(panelShape)
                .background(colors.tip)
                // The web draws this outline with `border-bottom: none`: the
                // composer card's own top edge closes the shape, so a line here
                // would trace the very seam the two are supposed to share. A
                // plain `Modifier.border` draws all four sides, and its bottom
                // stroke pokes out of the tuck beside the card's rounded top
                // corners — the little notch that made the join read as two
                // panels. Hence the open path: top, left and right only.
                .drawWithContent {
                    drawContent()
                    val stroke = HAIRLINE.toPx()
                    val inset = stroke / 2f
                    val radius = DshRadius.card.toPx()
                    val path = Path().apply {
                        moveTo(inset, size.height)
                        lineTo(inset, inset + radius)
                        arcTo(
                            Rect(inset, inset, inset + 2 * radius, inset + 2 * radius),
                            180f,
                            90f,
                            false,
                        )
                        lineTo(size.width - inset - radius, inset)
                        arcTo(
                            Rect(size.width - inset - 2 * radius, inset, size.width - inset, inset + 2 * radius),
                            270f,
                            90f,
                            false,
                        )
                        lineTo(size.width - inset, size.height)
                    }
                    drawPath(path, colors.borderL1, style = Stroke(width = stroke))
                }
                // The bottom padding is the caller's tuck's hidden skirt: the
                // panel's painted bottom edge has to reach past the composer
                // card's 22dp corner arc (see the ChatScreen call site), but its
                // top edge and rows must not move, so the extra 4dp of tuck is
                // taken here rather than by growing the dock upward.
                .padding(top = DshSpacing.xs, bottom = DshSpacing.md),
        ) {
            AnimatedVisibility(
                visible = shown.size > 1,
                enter = expandVertically(animationSpec = DockSizeSpec, expandFrom = Alignment.Bottom) +
                    fadeIn(DockFadeSpec),
                exit = shrinkVertically(animationSpec = DockSizeSpec, shrinkTowards = Alignment.Bottom) +
                    fadeOut(DockFadeSpec),
            ) {
                val chevron by animateFloatAsState(
                    // Collapsed points *up*: the dock grows upward off the composer.
                    targetValue = if (expanded) 0f else 180f,
                    animationSpec = tween(durationMillis = 200, easing = FastOutSlowInEasing),
                    label = "queue-chevron",
                )
                Row(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 40.dp)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .clickableNoRipple(enabled = !interactionActive) { collapsed = !collapsed }
                        .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        DshIconQueue14,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = "${shown.size} queued messages",
                        style = DshType.rowTitle.copy(fontWeight = FontWeight.Medium),
                        color = colors.labelPrimary,
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    // Collapsed, a row that is not simply waiting would otherwise
                    // be invisible: the count alone cannot say that a tap landed.
                    val collapsedStatus = when {
                        shown.any { it.pending } -> "Sending…"
                        shown.any { it.placement == "steering" } -> "Steering…"
                        else -> null
                    }
                    if (!listVisible && collapsedStatus != null) {
                        Text(
                            text = collapsedStatus,
                            style = DshType.micro,
                            color = colors.labelTertiary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(DshSpacing.sm))
                    }
                    Icon(
                        // One glyph rotated, rather than two swapped: the swap was
                        // a hard cut in the middle of an otherwise moving panel.
                        imageVector = Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse queue" else "Expand queue",
                        tint = colors.labelTertiary,
                        modifier = Modifier
                            .size(14.dp)
                            .rotate(chevron),
                    )
                }
            }

            AnimatedVisibility(
                visible = listVisible,
                enter = expandVertically(animationSpec = DockSizeSpec, expandFrom = Alignment.Bottom) +
                    fadeIn(DockFadeSpec),
                exit = shrinkVertically(animationSpec = DockSizeSpec, shrinkTowards = Alignment.Bottom) +
                    fadeOut(DockFadeSpec),
            ) {
                LazyColumn(
                    Modifier
                        // `clipToBounds` before the size animation so the growing
                        // rows are cut to the animated height rather than drawn
                        // over the composer while the panel catches up.
                        .clipToBounds()
                        .animateContentSize(DockSizeSpec)
                        .heightIn(max = 180.dp),
                ) {
                    itemsIndexed(shown, key = { _, row -> row.id }) { index, row ->
                        Column(
                            Modifier
                                .fillMaxWidth()
                                // Rows land and retire with a fade and a slide, so a
                                // queue that is filling while open reads as motion.
                                .animateItemPlacement(),
                        ) {
                            if (index > 0) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(colors.borderL1),
                                )
                            }
                            QueueRow(
                                row = row,
                                showLead = shown.size == 1,
                                mutable = mutable,
                                busy = busy,
                                running = running,
                                editing = editing?.takeIf { it.first == row.id }?.second,
                                onStartEdit = { text -> editing = row.id to text },
                                onCancelEdit = { editing = null },
                                onSubmitEdit = { text ->
                                    editing = null
                                    onEdit(row.id, text)
                                },
                                onRemove = { onRemove(row.id) },
                                onSteer = { onSteer(row.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    row: QueuedMessage,
    showLead: Boolean,
    mutable: Boolean,
    busy: String?,
    running: Boolean,
    editing: String?,
    onStartEdit: (String) -> Unit,
    onCancelEdit: () -> Unit,
    onSubmitEdit: (String) -> Unit,
    onRemove: () -> Unit,
    onSteer: () -> Unit,
) {
    val colors = DshTheme.colors
    val focusManager = LocalFocusManager.current
    var draft by remember(editing) { mutableStateOf(editing.orEmpty()) }
    val steering = row.placement == "steering"
    val pending = row.pending
    val isEditing = editing != null

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .padding(start = DshSpacing.lg, end = DshSpacing.sm, top = DshSpacing.sm, bottom = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showLead) {
            // The queue glyph means "waiting its turn"; a steering row is already
            // going into the turn, so it wears the send arrow instead.
            Icon(
                if (steering) Icons.Rounded.Send else DshIconQueue14,
                contentDescription = if (steering) "Steering" else null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(10.dp))
        }

        Box(Modifier.weight(1f)) {
            // The row's height is fixed by the 44dp minimum whether it is showing
            // the preview or the editor, so a crossfade can carry the swap without
            // the panel resizing around it.
            Crossfade(
                targetState = isEditing,
                animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                label = "queue-row-body",
            ) { editingNow ->
                if (editingNow) {
                    BasicTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        textStyle = DshType.bodyMedium.copy(color = colors.labelPrimary),
                        cursorBrush = SolidColor(colors.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                focusManager.clearFocus()
                                if (draft.isNotBlank()) onSubmitEdit(draft)
                            },
                        ),
                        decorationBox = { inner ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(30.dp)
                                    .clip(RoundedCornerShape(DshSpacing.sm))
                                    .background(colors.bgBase)
                                    .border(0.5.dp, colors.borderL4, RoundedCornerShape(DshSpacing.sm))
                                    .padding(horizontal = DshSpacing.md),
                                contentAlignment = Alignment.CenterStart,
                            ) { inner() }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        row.attachments.forEach { attachment ->
                            QueueAttachmentChip(attachment)
                            Spacer(Modifier.width(DshSpacing.sm))
                        }
                        Text(
                            text = row.preview.ifBlank { "(no text)" },
                            style = DshType.bodyMedium,
                            color = colors.labelPrimaryDimmed,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }

        if (mutable) {
            Spacer(Modifier.width(DshSpacing.sm))
            if (pending) {
                // The web's pending row: the same three verbs, dimmed, with a
                // "Sending…" status. They cannot apply — there is no host item id
                // yet, only the prompt request id the row is keyed by.
                Text(
                    text = "Sending…",
                    style = DshType.micro,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(DshSpacing.xs))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    QueueAction(Icons.Rounded.Edit, "Sending…", enabled = false) {}
                    QueueAction(Icons.Rounded.DeleteOutline, "Sending…", enabled = false) {}
                    QueueAction(Icons.Rounded.Send, "Sending…", enabled = false) {}
                }
            } else if (steering) {
                // Already on its way into the running turn. The row has to say so
                // on its own face: in a list of several rows the lead glyph is the
                // header's, so without this a force-steered item read as just
                // another message waiting its turn.
                Text(
                    text = "Steering…",
                    style = DshType.micro,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
                Spacer(Modifier.width(DshSpacing.xs))
                QueueAction(Icons.Rounded.Send, "Steering…", enabled = false) {}
            } else {
                // Edit mode swaps two icons for three (or back). Rendered as a cut it
                // flickered the whole trailing edge of the row; the crossfade keeps the
                // controls still while their glyphs change over.
                Crossfade(
                    targetState = isEditing,
                    animationSpec = tween(durationMillis = 140, easing = FastOutSlowInEasing),
                    label = "queue-row-actions",
                ) { editingNow ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (editingNow) {
                            QueueAction(
                                icon = Icons.Rounded.Check,
                                label = "Save queued message",
                                enabled = busy == null && draft.isNotBlank(),
                            ) { focusManager.clearFocus(); onSubmitEdit(draft) }
                            QueueAction(Icons.Rounded.Close, "Cancel editing", busy == null, onCancelEdit)
                        } else {
                            QueueAction(
                                icon = Icons.Rounded.Edit,
                                label = if (row.text == null) {
                                    "Contains non-text content; editing is not supported yet"
                                } else {
                                    "Edit queued message"
                                },
                                enabled = busy == null && row.text != null,
                            ) { row.text?.let(onStartEdit) }
                            QueueAction(Icons.Rounded.DeleteOutline, "Remove queued message", busy == null, onRemove)
                            // A row that is already steering has nothing left to promote.
                            if (!steering) {
                                QueueAction(
                                    icon = Icons.Rounded.Send,
                                    label = if (running) {
                                        "Steer queued message"
                                    } else {
                                        "Steering is available only while the agent is running"
                                    },
                                    enabled = busy == null && running,
                                ) { onSteer() }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** One attachment block on a queued row: a 24dp thumb, or a bordered file chip. */
@Composable
private fun QueueAttachmentChip(attachment: QueueAttachment) {
    val colors = DshTheme.colors
    if (attachment.kind == "image") {
        val shape = RoundedCornerShape(DshSpacing.xs)
        // A local submission echo still has the staged bytes, so it can draw the
        // picture itself — that is the whole point of attaching one. A host row
        // carries only an `attachmentId`, which this dock does not resolve, so it
        // keeps the empty thumb.
        val staged = attachment.data
        val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = staged) {
            value = staged?.takeIf { it.isNotEmpty() }?.let {
                withContext(Dispatchers.Default) { decodeQueuedImage(it) }
            }
        }
        Box(
            Modifier
                .size(24.dp)
                .clip(shape)
                .background(colors.bgBase)
                .border(HAIRLINE, colors.borderL1, shape),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        return
    }
    Row(
        Modifier
            .height(24.dp)
            .clip(RoundedCornerShape(DshSpacing.sm))
            .background(colors.bgBase)
            .border(0.5.dp, colors.borderL1, RoundedCornerShape(DshSpacing.sm))
            .padding(horizontal = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Description,
            contentDescription = null,
            tint = colors.labelDimmed,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(DshSpacing.xs))
        Text(
            text = attachment.name,
            style = DshType.bodyMedium,
            color = colors.labelDimmed,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 120.dp),
        )
        Spacer(Modifier.width(DshSpacing.xs))
        Text(text = fileSizeText(attachment.bytes), style = DshType.micro, color = colors.labelTertiary)
    }
}

/** A 28dp circular row action; disabled rows fade rather than change hue. */
@Composable
private fun QueueAction(
    icon: ImageVector,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = DshTheme.colors
    Box(
        Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(DshRadius.pill))
            .clickableNoRipple(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (enabled) colors.labelTertiary else colors.labelTertiary.copy(alpha = 0.45f),
            modifier = Modifier.size(16.dp),
        )
    }
}

/**
 * Decodes one locally staged base64 picture for the dock's 24dp thumb.
 *
 * Nothing is cached: the composition owns the only reference it needs, exactly as
 * the transcript's staged-image path does.
 */
private fun decodeQueuedImage(encoded: String): ImageBitmap? = runCatching {
    val bytes = Base64.decode(encoded, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
}.getOrNull()

/** `fileSizeText` from `ui-primitives/src/file-size.ts`, verbatim. */
internal fun fileSizeText(bytes: Int): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024) return if (kb < 10) String.format(java.util.Locale.US, "%.1fKB", kb) else "${Math.round(kb)}KB"
    val mb = kb / 1024
    if (mb < 1024) return if (mb < 10) String.format(java.util.Locale.US, "%.1fMB", mb) else "${Math.round(mb)}MB"
    val gb = mb / 1024
    return if (gb < 10) String.format(java.util.Locale.US, "%.1fGB", gb) else "${Math.round(gb)}GB"
}
