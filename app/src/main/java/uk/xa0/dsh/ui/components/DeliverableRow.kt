package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Link
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import uk.xa0.dsh.model.PresentedFile
import uk.xa0.dsh.model.ProducedFile
import uk.xa0.dsh.model.TurnDeliverables
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/** Produced chips before the remainder counter (`SHOWN_LIMIT`, `ProducedFiles.tsx:11`). */
private const val PRODUCED_SHOWN_LIMIT = 6

/** Delivery cards shown while collapsed (`COLLAPSED_PRESENTED_COUNT`, `Deliverables.tsx:17`). */
private const val COLLAPSED_PRESENTED_COUNT = 4

/**
 * The turn-tail deliverables row.
 *
 * Registered on the web at `conversation.chat.turnTail`; it claims the turn only
 * when produced + presented > 0, so an empty [TurnDeliverables] renders nothing
 * here too. Two sections, in the web's order:
 *
 *  - **produced** — single-line link chips labelled `Files changed`, from the
 *    turn's successful `write`/`edit`/mutating `str_replace_editor` calls
 *    (`turn-deliverables.ts`); a chip opens the file in the text preview.
 *  - **presented** — cards for the files the agent declared with `present`,
 *    collapsed to four with an `All N files` toggle.
 *
 * The web's "Open in default app" / "Show in Finder" menu is deliberately
 * dropped: it acts on the *serving host's* desktop, which a phone never has
 * (`panels-settings.md` §4.5 says to keep only the preview action).
 */
@Composable
fun DeliverableRow(
    delivered: TurnDeliverables,
    onOpen: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (delivered.isEmpty) return
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = DshSpacing.xs),
        verticalArrangement = Arrangement.spacedBy(DshSpacing.xl),
    ) {
        if (delivered.produced.isNotEmpty()) {
            ProducedFiles(delivered.produced, onOpen)
        }
        if (delivered.presented.isNotEmpty()) {
            PresentedFiles(delivered.presented, onOpen)
        }
    }
}

/**
 * `ProducedFiles.tsx`: one `Files changed` label beside a lane of link chips.
 *
 * The web budgets 96px per chip, 8px gaps and 64px for the counter, and its CSS
 * container bands hide chips 6→2 at 687/583/479/375/271px. Compose has no
 * container queries, so the bands are reproduced here against the lane's own
 * width; the remainder counter is the last shown band's `paths.length - shown`,
 * exactly as `moreLabel` computes it.
 */
@Composable
private fun ProducedFiles(files: List<ProducedFile>, onOpen: (String) -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.md),
    ) {
        Text(
            text = "Files changed",
            style = producedLabelStyle,
            color = colors.labelTertiary,
            maxLines = 1,
        )
        BoxWithConstraints(Modifier.weight(1f)) {
            val shown = files.take(producedShown(maxWidth))
            val remainder = files.size - shown.size
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(DshSpacing.md),
            ) {
                shown.forEach { file ->
                    ProducedChip(file, onOpen)
                }
                if (remainder > 0) {
                    Text(
                        text = if (remainder == 1) "+ 1 file" else "+ $remainder files",
                        style = producedLabelStyle,
                        color = colors.labelTertiary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/** One `Files changed` chip: link glyph + basename, full path as the label. */
@Composable
private fun ProducedChip(file: ProducedFile, onOpen: (String) -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .clip(RoundedCornerShape(DshSpacing.xs))
            .clickableNoRipple { onOpen(file.path) }
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
    ) {
        Icon(
            imageVector = Icons.Rounded.Link,
            contentDescription = "Open ${file.path}",
            tint = colors.link,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = file.basename,
            style = producedLabelStyle,
            color = colors.link,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The delivered-file cards, with the web's four-card collapse. */
@Composable
private fun PresentedFiles(files: List<PresentedFile>, onOpen: (String) -> Unit) {
    val colors = DshTheme.colors
    var expanded by remember { mutableStateOf(false) }
    val collapsible = files.size > COLLAPSED_PRESENTED_COUNT
    val shown = if (collapsible && !expanded) files.take(COLLAPSED_PRESENTED_COUNT) else files

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // The web's grid is two columns until 620px, and one when a single card
        // is alone (`data-single`).
        val columns = if (files.size == 1 || maxWidth < 620.dp) 1 else 2
        Column(verticalArrangement = Arrangement.spacedBy(DshSpacing.lg)) {
            shown.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.lg)) {
                    row.forEach { file ->
                        PresentedCard(file, Modifier.weight(1f), onOpen)
                    }
                    // A lone last card keeps its column width instead of stretching.
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (collapsible) {
                Row(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(DshRadius.md))
                        .clickableNoRipple { expanded = !expanded }
                        .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.xxs),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
                ) {
                    Text(
                        text = if (expanded) "Collapse" else "All ${files.size} files",
                        style = DshType.bodySmall,
                        color = colors.labelTertiary,
                        maxLines = 1,
                    )
                    Icon(
                        imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (expanded) "Collapse delivered files" else "Show all ${files.size} delivered files",
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
        }
    }
}

/**
 * `PresentedFileCard.tsx`: icon, name, a status line that falls back to the
 * upper-cased extension and then `File`, and an always-visible Open control.
 * The whole card is the web's `.cardPreview` overlay, so a tap anywhere opens.
 */
@Composable
private fun PresentedCard(file: PresentedFile, modifier: Modifier = Modifier, onOpen: (String) -> Unit) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(18.dp)
    Row(
        modifier
            .height(60.dp)
            .clip(shape)
            .background(colors.codeBlock)
            .border(0.5.dp, colors.borderL1, shape)
            .clickableNoRipple { onOpen(file.path) }
            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.lg),
    ) {
        Box(
            Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(colors.codeBlock)
                .border(0.5.dp, colors.borderL1, RoundedCornerShape(DshRadius.lg)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = cardIcon(file.path),
                contentDescription = null,
                tint = colors.link,
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = file.basename,
                style = DshType.labelMedium,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = cardStatus(file),
                style = cardStatusStyle,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = "Open",
            style = DshType.bodySmall,
            color = colors.labelPrimary,
            maxLines = 1,
            modifier = Modifier
                .widthIn(min = 44.dp)
                .clip(RoundedCornerShape(DshRadius.lg))
                .background(colors.menu)
                .border(0.5.dp, colors.borderL3, RoundedCornerShape(DshRadius.lg))
                .clickableNoRipple { onOpen(file.path) }
                .padding(horizontal = DshSpacing.md, vertical = DshSpacing.sm),
        )
    }
}

/**
 * The card's status line: the description with a trailing parenthesised suffix
 * stripped, else the upper-cased extension, else `File` (`cardDescription` +
 * `PresentedFileCard.tsx`).
 */
private fun cardStatus(file: PresentedFile): String {
    val description = file.description?.replace(TRAILING_PARENTHETICAL, "")?.trim()
    if (!description.isNullOrEmpty()) return description
    val extension = fileExtension(file.basename).uppercase()
    return extension.ifEmpty { "File" }
}

/** The web's `fileExtension`: the suffix after the last dot, absent for a dotfile. */
private fun fileExtension(name: String): String {
    val dot = name.lastIndexOf('.')
    return if (dot <= 0 || dot == name.length - 1) "" else name.substring(dot + 1)
}

/** The web strips both ASCII `(...)` and full-width `（...）` suffixes. */
private val TRAILING_PARENTHETICAL = Regex("\\s*(?:\\([^()]*\\)|（[^（）]*）)\\s*$")

/** 13px/22, the web's produced-row font. */
private val producedLabelStyle = DshType.rowSummary

/** 10px/16, the web's card status line. */
private val cardStatusStyle = DshType.micro.copy(fontSize = 10.sp, lineHeight = 16.sp)

/** The web's `FileTypeIcon`, narrowed to the three families the card draws. */
private fun cardIcon(path: String): ImageVector {
    val extension = fileExtension(path.substringAfterLast('/').substringAfterLast('\\')).lowercase()
    return when {
        extension in IMAGE_EXTENSIONS -> Icons.Rounded.Image
        extension in TEXT_EXTENSIONS -> Icons.Rounded.Description
        else -> Icons.Rounded.InsertDriveFile
    }
}

private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp", "ico", "heic", "avif")
private val TEXT_EXTENSIONS = setOf(
    "txt", "md", "markdown", "json", "jsonc", "yaml", "yml", "toml", "ini",
    "kt", "kts", "java", "ts", "tsx", "js", "jsx", "py", "rb", "go", "rs",
    "c", "h", "cc", "cpp", "hpp", "cs", "swift", "php", "sh", "bash", "zsh",
    "sql", "xml", "css", "scss", "less", "html", "htm", "lua",
)

/** The band cutoffs, verbatim from `ProducedFiles.module.css` container queries. */
private fun producedShown(width: Dp): Int = when {
    width <= 271.dp -> 1
    width <= 375.dp -> 2
    width <= 479.dp -> 3
    width <= 583.dp -> 4
    width <= 687.dp -> 5
    else -> PRODUCED_SHOWN_LIMIT
}
