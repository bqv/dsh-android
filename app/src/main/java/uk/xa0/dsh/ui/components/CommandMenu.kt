package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Compress
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.RateReview
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material.icons.rounded.Description
import uk.xa0.dsh.HostCommand
import uk.xa0.dsh.ReferenceCandidate
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * One pickable row in the composer command menu.
 *
 * [name] is the host's command name / contribution key, which is what the pick
 * handler switches on. [takesInput] mirrors the host descriptor's optional
 * `input`: a claim inserts the bare token and waits for arguments, while a
 * command without it runs the moment it is picked.
 */
data class CommandEntry(
    val name: String,
    val label: String,
    val description: String?,
    val icon: ImageVector,
    val section: String,
    val takesInput: Boolean = false,
)

/**
 * The composer menu's exact roster: the host's `commands/list` plus the rows
 * this client contributes.
 *
 * The host owns the set of commands, their descriptions and whether a pick
 * claims input. A descriptor carries no display label or section, so the title
 * is title-cased from [HostCommand.name] and the section comes from the fixed
 * layout the web's `ui-commands` uses. `file` and `model` never reach the wire —
 * the web merges them in from its own contributions — so their face, including
 * the `File` row's description, stays local copy.
 */
fun composerCommandEntries(host: List<HostCommand>): List<CommandEntry> {
    val rows = ArrayList<CommandEntry>(host.size + 2)
    host.forEach { command ->
        rows += CommandEntry(
            name = command.name,
            label = commandLabel(command.name),
            // `input.hint` is copy of last resort: a plugin command may publish
            // no description, and a blank row is the bug this roster replaces.
            description = command.description.ifEmpty { command.inputHint ?: "" }.takeIf { it.isNotEmpty() },
            icon = commandIcon(command.name),
            section = sectionFor(command.name),
            takesInput = command.takesInput,
        )
    }
    rows += CommandEntry(
        name = "file",
        label = "File",
        description = "Add a file or image to this message",
        icon = Icons.Rounded.AttachFile,
        section = sectionFor("file"),
    )
    rows += CommandEntry(
        name = "model",
        label = "Model",
        description = "Select the model for this conversation",
        icon = Icons.Rounded.Storage,
        section = sectionFor("model"),
    )
    return sectionRows(rows)
}

/**
 * The menu's sections and their usage order, taken from the web's own
 * `SECTION_ROWS`; a host row outside both lists closes "Commands" in catalog
 * order, exactly as its `sectionRows` does.
 */
private val SECTION_ADD = listOf("file", "goal", "plan", "feedback")
private val SECTION_COMMANDS = listOf("compact", "permission", "model", "export")

private fun sectionFor(name: String): String = if (name in SECTION_ADD) "Add" else "Commands"

private fun sectionRows(rows: List<CommandEntry>): List<CommandEntry> {
    val byName = rows.associateBy { it.name }
    val listed = (SECTION_ADD + SECTION_COMMANDS).toSet()
    val add = SECTION_ADD.mapNotNull { byName[it] }.map { it.copy(section = "Add") }
    val commands = (SECTION_COMMANDS.mapNotNull { byName[it] } + rows.filterNot { it.name in listed })
        .map { it.copy(section = "Commands") }
    return add + commands
}

/** A display title for a host command name: `agent-preset` becomes `Agent preset`. */
private fun commandLabel(name: String): String =
    name.split('-', '_').filter { it.isNotEmpty() }
        .joinToString(" ") { it.replaceFirstChar { first -> first.uppercase() } }

/**
 * The row glyph: the built-ins keep the icons the old static roster gave them, so
 * a host-driven menu is not a visual regression. A command this client has never
 * seen still gets a glyph, so every row lines up with the rest.
 */
private fun commandIcon(name: String): ImageVector = when (name) {
    "file" -> Icons.Rounded.AttachFile
    "goal" -> Icons.Rounded.Flag
    "plan" -> Icons.Rounded.Checklist
    "feedback" -> Icons.Rounded.RateReview
    "compact" -> Icons.Rounded.Compress
    "permission" -> Icons.Rounded.Security
    "model" -> Icons.Rounded.Storage
    "export" -> Icons.Rounded.Download
    else -> Icons.Rounded.Terminal
}

/**
 * The command menu, shown above the composer card.
 *
 * Section headings appear only for an empty query, which is always the case here
 * because the `+` button seeds no query of its own — the typed-`/` variant reuses
 * the draft as its query and is a separate trigger.
 */
@Composable
fun CommandMenu(
    entries: List<CommandEntry>,
    onPick: (CommandEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)

    Column(
        modifier
            .fillMaxWidth()
            .shadow(6.dp, shape)
            .clip(shape)
            .background(colors.menu)
            .border(0.5.dp, colors.borderL1, shape)
            .heightIn(max = 300.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = DshSpacing.sm),
    ) {
        var lastSection: String? = null
        entries.forEach { entry ->
            if (entry.section != lastSection) {
                lastSection = entry.section
                Text(
                    text = entry.section,
                    style = DshType.micro,
                    color = colors.labelTertiary,
                    modifier = Modifier.padding(
                        // 24dp = the card's row inset (8) + the row's own padding
                        // (16), so headings line up with the row icons rather than
                        // sitting slightly left of them.
                        start = 24.dp,
                        end = DshSpacing.lg,
                        top = DshSpacing.md,
                        bottom = DshSpacing.xs,
                    ),
                )
            }
            CommandRow(entry = entry, onClick = { onPick(entry) })
        }
    }
}

@Composable
private fun CommandRow(entry: CommandEntry, onClick: () -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.sm)
            .clip(RoundedCornerShape(DshRadius.pill))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = entry.icon,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(15.dp),
            )
        }
        Spacer(Modifier.width(DshSpacing.lg))
        Text(
            text = entry.label,
            style = DshType.messageBody,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        entry.description?.let { description ->
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = description,
                style = DshType.micro,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        } ?: Spacer(Modifier.weight(1f))
    }
}

/**
 * The `@` menu: files and directories, then sessions, each with the location that
 * tells two same-named entries apart.
 *
 * The web renders this as one list with section headings from the two sources; the
 * host already ranks files and sessions deterministically, so the order is kept
 * rather than re-sorted here.
 */
@Composable
fun ReferenceMenu(
    entries: List<ReferenceCandidate>,
    onPick: (ReferenceCandidate) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (entries.isEmpty()) return
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    val files = entries.filter { it.kind != "session" }
    val sessions = entries.filter { it.kind == "session" }
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.menu)
            .border(0.5.dp, colors.borderL1, shape)
            .heightIn(max = 260.dp)
            .verticalScroll(rememberScrollState())
            .padding(vertical = DshSpacing.xs),
    ) {
        if (files.isNotEmpty()) {
            ReferenceSection("Files & folders", files, onPick)
        }
        if (sessions.isNotEmpty()) {
            ReferenceSection("Sessions", sessions, onPick)
        }
    }
}

@Composable
private fun ReferenceSection(
    title: String,
    entries: List<ReferenceCandidate>,
    onPick: (ReferenceCandidate) -> Unit,
) {
    val colors = DshTheme.colors
    Text(
        text = title,
        style = DshType.micro,
        color = colors.labelTertiary,
        modifier = Modifier.padding(
            start = DshSpacing.lg,
            end = DshSpacing.lg,
            top = DshSpacing.sm,
            bottom = DshSpacing.xxs,
        ),
    )
    entries.forEach { entry ->
        Row(
            Modifier
                .fillMaxWidth()
                .clickableNoRipple { onPick(entry) }
                .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = when (entry.kind) {
                    "directory" -> Icons.Rounded.Folder
                    "session" -> Icons.Rounded.SmartToy
                    else -> Icons.Rounded.Description
                },
                contentDescription = null,
                tint = colors.labelTertiary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = entry.name,
                style = DshType.bodyMedium,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            entry.description?.let {
                Spacer(Modifier.width(DshSpacing.md))
                Text(
                    text = it,
                    style = DshType.micro,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
