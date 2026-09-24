package uk.xa0.dsh.ui.components

import androidx.compose.animation.Crossfade
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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BrokenImage
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.json.JSONObject
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.MessageAttachment
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.shortenPath
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/** The result envelope `formatImageReadOutput` writes (read-image.ts). */
private val IMAGE_ENVELOPE =
    Regex("^<path>[^\\n]*</path>\\n<type>image</type>\\n<content>\\n[\\s\\S]*\\n</content>$")

/** The media types a durable image block may claim; anything else declines. */
private val IMAGE_MEDIA_TYPES = setOf("image/png", "image/jpeg", "image/webp", "image/gif")

/** The image card's material: the display label, the returned pictures, and the envelope prose. */
internal data class ReadImageCardModel(
    val label: String,
    val images: List<MessageAttachment>,
    val text: String,
)

/**
 * Derive the `read_image` card, porting `image-card-model.ts`. The references
 * come from the result's own image blocks (`ChatEntry.ToolCall.images`); `meta`
 * contributes only the display path the content does not carry. A result whose
 * envelope is missing is not a well-formed image read and declines to the
 * generic row.
 */
internal fun readImageCardModel(
    arguments: String,
    result: String?,
    isError: Boolean,
    meta: JSONObject?,
    images: List<MessageAttachment>,
): ReadImageCardModel? {
    if (isError) return null
    val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    val filePath = args.opt("file_path") as? String ?: return null
    if (filePath.trim().isEmpty()) return null
    val path = (meta?.opt("path") as? String)?.takeIf { it.isNotEmpty() } ?: filePath
    // The wire's bytes/width/height validate on the web; the app's attachment
    // shape carries the id, media type and size, which is what the loader needs.
    val refs = images.filter {
        it.attachmentId.isNotEmpty() &&
            it.mediaType?.let { media -> media in IMAGE_MEDIA_TYPES } == true &&
            it.bytes > 0
    }
    if (refs.isEmpty()) return null
    val text = result?.takeIf { IMAGE_ENVELOPE.matches(it) } ?: return null
    return ReadImageCardModel(shortenPath(path), refs, text)
}

@Composable
internal fun ReadImageCallRow(
    entry: ChatEntry.ToolCall,
    model: ReadImageCardModel,
    loadImage: suspend (String) -> ImageBitmap?,
) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }
    val summary = model.label

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
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.Description,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(DshSpacing.sm))
            Text("Read image", style = DshType.rowTitle, color = colors.labelSecondary, maxLines = 1)
            if (summary.isNotBlank()) {
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
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(DshSpacing.xs))
            // ToolRow's image body: the label, the gallery, then the result's own
            // envelope prose (the only evidence an image came back when the
            // loader can resolve nothing).
            Column(Modifier.fillMaxWidth().padding(start = DshSpacing.xs)) {
                Text(model.label, style = DshType.bodyMedium, color = colors.labelSecondary)
                Spacer(Modifier.height(DshSpacing.xs))
                Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.md)) {
                    model.images.forEach { attachment ->
                        ReadImage(attachment, loadImage)
                    }
                }
                Spacer(Modifier.height(DshSpacing.xs))
                Text(model.text, style = DshType.bodyMedium, color = colors.labelTertiary)
            }
            Spacer(Modifier.height(DshSpacing.xs))
        }
    }
}

/** What an image block is showing: bytes still arriving, the picture, or a dead end. */
private sealed interface ReadImageState {
    data object Loading : ReadImageState
    data class Loaded(val bitmap: ImageBitmap) : ReadImageState
    data object Failed : ReadImageState
}

/** Mirrors `MessageAttachmentBlock`'s decode/placeholder lifecycle for a host-held attachment. */
@Composable
private fun ReadImage(attachment: MessageAttachment, loadImage: suspend (String) -> ImageBitmap?) {
    val colors = DshTheme.colors
    val state by produceState<ReadImageState>(ReadImageState.Loading, attachment.attachmentId) {
        value = loadImage(attachment.attachmentId)
            ?.let { ReadImageState.Loaded(it) }
            ?: ReadImageState.Failed
    }
    val shape = RoundedCornerShape(DshRadius.md)
    Box(
        Modifier
            .widthIn(max = 220.dp)
            .heightIn(max = 220.dp)
            .clip(shape)
            .background(colors.bgBase)
            .border(0.5.dp, colors.borderL1, shape)
            .animateContentSize(tween(durationMillis = 180)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(state, animationSpec = tween(durationMillis = 160), label = "read-image") { current ->
            when (current) {
                is ReadImageState.Loaded -> Image(
                    bitmap = current.bitmap,
                    contentDescription = attachment.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .widthIn(max = 220.dp)
                        .aspectRatio(
                            if (current.bitmap.height > 0) {
                                current.bitmap.width.toFloat() / current.bitmap.height
                            } else {
                                1f
                            },
                        ),
                )

                ReadImageState.Failed -> Column(
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
                    Text("Couldn't load picture", style = DshType.micro, color = colors.labelTertiary)
                }

                ReadImageState.Loading -> ReadImageSkeleton(attachment.name)
            }
        }
    }
}

@Composable
private fun ReadImageSkeleton(name: String) {
    val colors = DshTheme.colors
    val transition = rememberInfiniteTransition(label = "read-image-skeleton")
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
