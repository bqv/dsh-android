package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The terminal surface for a standard shell call, ported from
 * `bash-sample.tsx` + `ui-primitives/TerminalBlock.tsx`.
 *
 * `output` is null while the call is running (the banner stands alone);
 * `exitCode`/`signal` are the parsed trailing status marker. Persistent
 * shells, background calls, spill previews and failed calls deliberately do
 * not produce a model: the web keeps those on the generic IN/OUT row, and so
 * does the fall-through in `ToolCallRow`.
 */
internal data class BashTerminalModel(
    val command: String,
    val description: String,
    /** The call's `workdir` as authored; the row has no session cwd to resolve it against. */
    val workdir: String?,
    val output: String?,
    val exitCode: Int?,
    val signal: String?,
    val running: Boolean,
) {
    /** A non-zero exit or a terminating signal is the card's own failure signal. */
    val failed: Boolean get() = !running && (signal != null || (exitCode != null && exitCode != 0))
}

private val BASH_EXIT_CODE = Regex("\\n\\[exit code: (\\d+)\\]$")
private val BASH_SIGNAL = Regex("\\n\\[killed by signal: ([^\\]\\n]+)\\]$")

/**
 * Parse the marker literals `@deepseek-ai/dsh-shell/render` appends: a trailing
 * `[exit code: N]` or `[killed by signal: S]`. A settled command with no marker
 * counts as a clean exit, exactly as the web's `parseExitStatus` does.
 */
private fun parseExitStatus(text: String): Triple<String, Int?, String?> {
    BASH_SIGNAL.find(text)?.let {
        return Triple(text.substring(0, it.range.first), null, it.groupValues[1])
    }
    BASH_EXIT_CODE.find(text)?.let {
        return Triple(text.substring(0, it.range.first), it.groupValues[1].toIntOrNull(), null)
    }
    return Triple(text, 0, null)
}

/**
 * Validates the optional escalation pair the shell tools share; an unpaired or
 * malformed declaration means the arguments are not the shape this card knows.
 */
private fun validEscalation(args: JSONObject): Boolean {
    val permission = args.opt("sandbox_permissions")
    val justification = args.opt("justification")
    if (permission == null && justification == null) return true
    if (permission != "workspace-write" && permission != "danger-full-access") return false
    return justification is String && justification.trim().isNotEmpty()
}

/**
 * `terminalCardModel` for the `bash` tool, minus the paths the web keeps
 * generic. Returns null for anything the terminal card must not claim: an
 * errored call, a background call, a persistent shell (no `description`), or
 * malformed arguments — all of which fall back to the generic row.
 */
internal fun bashTerminalModel(
    arguments: String,
    result: String?,
    isError: Boolean,
): BashTerminalModel? {
    if (isError) return null
    val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    val command = args.opt("command") as? String ?: return null
    if (command.trim().isEmpty()) return null
    // The persistent shell omits `description` and can report resets without a
    // process status, so it has no exit code for this card to draw.
    val description = args.opt("description") as? String ?: return null
    if (description.trim().isEmpty()) return null
    if (args.optBoolean("run_in_background", false)) return null
    val timeout = args.opt("timeoutMs")
    if (timeout != null && (timeout !is Number || timeout.toDouble() <= 0)) return null
    if (args.has("workdir") && args.opt("workdir") !is String) return null
    if (!validEscalation(args)) return null

    val workdir = args.opt("workdir") as? String
    if (result == null) {
        return BashTerminalModel(command, description, workdir, null, null, null, running = true)
    }
    val (output, exitCode, signal) = parseExitStatus(result)
    return BashTerminalModel(command, description, workdir, output, exitCode, signal, running = false)
}

/**
 * Prompt label for the working directory: `~` for the account home itself,
 * otherwise the path's last segment, and a bare `$` when the call carried no
 * workdir — matching `TerminalBlock.tsx#promptLabel`.
 */
private fun promptLabel(workdir: String?): String {
    if (workdir.isNullOrBlank()) return "$"
    val trimmed = workdir.trimEnd('/', '\\')
    if (trimmed == "/home/user") return "~"
    val segment = trimmed.split('/', '\\').lastOrNull().orEmpty()
    return segment.ifEmpty { workdir }
}

@Composable
internal fun BashCallRow(entry: ChatEntry.ToolCall, model: BashTerminalModel) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }
    val title = if (entry.name == "pwsh") "Pwsh" else "Bash"
    val summary = remember(model) { model.description.lineSequence().first() }

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
                    model.failed -> StateDot(DotState.ERROR)
                    else -> Icon(
                        Icons.Rounded.Terminal,
                        contentDescription = null,
                        tint = colors.labelTertiary,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            Spacer(Modifier.width(DshSpacing.sm))
            Text(title, style = DshType.rowTitle, color = colors.labelSecondary, maxLines = 1)
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
                    color = if (model.failed) colors.error else colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(DshSpacing.xs))
            TerminalCard(model)
            Spacer(Modifier.height(DshSpacing.xs))
        }
    }
}

@Composable
private fun TerminalCard(model: BashTerminalModel) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.xs, top = DshSpacing.xs, bottom = DshSpacing.xs)
            .clip(shape)
            .background(colors.codeBlock)
            .border(0.5.dp, colors.borderL1, shape),
    ) {
        TerminalBanner(model)
        // A running card is banner-only, so it draws no section divider.
        if (!model.running) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(0.5.dp)
                    .background(colors.borderL2),
            )
            TerminalOutput(model)
        }
    }
}

@Composable
private fun TerminalBanner(model: BashTerminalModel) {
    val colors = DshTheme.colors
    // A multi-line command gets one prompt row per line; a trailing newline is a
    // terminator, not an empty command.
    val commandLines = remember(model.command) {
        val body = if (model.command.endsWith("\n")) model.command.dropLast(1) else model.command
        body.split('\n')
    }
    val cwd = promptLabel(model.workdir)
    val status = when {
        model.signal != null -> "signal ${model.signal}"
        model.exitCode != null && model.exitCode != 0 -> "exit code ${model.exitCode}"
        else -> null
    }
    val empty = remember(model.output) {
        (model.output ?: "").lineSequence().all { it.trim().isEmpty() }
    }

    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 150.dp)
            // The dot's gutter: 8dp inset plus a 22dp column puts the command at
            // the card's 30dp text edge (TerminalBlock's `--dsl-terminal-gutter`).
            .padding(start = DshSpacing.md, end = 14.dp, top = 9.dp, bottom = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(Modifier.width(22.dp)) {
            StateDot(
                state = when {
                    model.running -> DotState.ONGOING
                    model.failed -> DotState.ERROR
                    else -> DotState.DONE
                },
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
        ) {
            // The prompt rows wrap rather than panning sideways.
            //
            // They used to pan, for a good reason: TerminalBlock's `.command`
            // ellipsizes, and on the web the tail is still reachable because the text
            // is mouse-selectable and Copy takes it (`TerminalBlock.tsx:177`). Compose
            // `Text` is not selectable unless wrapped in a SelectionContainer, so an
            // ellipsized script line here was unreachable outright. Wrapping answers
            // that better than panning does — the line is simply *there*, rather than
            // there if you think to drag it — and it costs the reader nothing, where a
            // sideways pan inside a vertically scrolling transcript costs them a
            // gesture that has to be told apart from the list's.
            Column {
                commandLines.forEachIndexed { index, line ->
                    // Top-aligned: a wrapped command makes the row two or three lines
                    // tall, and a centred prompt would float beside the middle of it.
                    Row(verticalAlignment = Alignment.Top) {
                        // The cwd labels the call, so only the first row carries it;
                        // later rows keep a bare `$` to stay aligned.
                        Text(
                            text = if (index == 0) cwd else "$",
                            style = DshType.codeSmall,
                            color = colors.labelTertiary,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(DshSpacing.md))
                        Text(
                            text = line.ifEmpty { " " },
                            style = DshType.codeSmall,
                            color = colors.labelPrimary,
                        )
                    }
                }
            }
        }
        if (status != null) {
            Spacer(Modifier.width(DshSpacing.lg))
            Box(
                Modifier
                    .clip(RoundedCornerShape(DshRadius.pill))
                    .background(colors.bgLayer2)
                    .padding(horizontal = DshSpacing.md),
            ) {
                Text(status, style = DshType.bodySmall, color = colors.error)
            }
        }
        if (!model.running && !empty) {
            Spacer(Modifier.width(DshSpacing.lg))
            TerminalCopyButton(model.output.orEmpty())
        }
    }
}

@Composable
private fun TerminalOutput(model: BashTerminalModel) {
    val colors = DshTheme.colors
    val output = model.output.orEmpty()
    val empty = remember(output) { output.lineSequence().all { it.trim().isEmpty() } }
    if (empty) {
        Text(
            text = "No output",
            style = DshType.codeSmall,
            color = colors.labelTertiary,
            modifier = Modifier.padding(start = 30.dp, end = 14.dp, top = DshSpacing.lg, bottom = DshSpacing.lg),
        )
        return
    }
    // The terminator newline is not an extra blank line to draw.
    val lines = remember(output) {
        val body = if (output.endsWith("\n")) output.dropLast(1) else output
        body.split('\n')
    }
    // The output wraps, and only the lines on screen are composed.
    //
    // The web can stack a horizontal overflow on `.output` and a vertical one on its
    // container; a Compose node cannot resolve both axes' drags, so panning here meant
    // a nested scroller whose gesture had to be told apart from the transcript's — and
    // the widest lines in a bash output are exactly the ones a reader wants to read
    // rather than steer.
    //
    // Lazy rather than a `Column` inside a `verticalScroll`, because that composed
    // *every* line of the output and then clipped to 224dp. A command with a thousand
    // lines of output was a thousand `Text`s on every composition of the row — cheap
    // when each was `softWrap=false` and laid out as one unbroken line, and not cheap
    // at all now that each one is line-broken and wrapped. A running command
    // republishes its output as it arrives, so that cost was paid repeatedly, on
    // frames nobody was touching.
    LazyColumn(
        Modifier
            .heightIn(max = 224.dp)
            .padding(start = 30.dp, end = 14.dp, top = DshSpacing.lg, bottom = DshSpacing.lg),
    ) {
        items(lines.size, key = { it }) { index ->
            Text(
                text = lines[index].ifEmpty { " " },
                style = DshType.codeSmall,
                color = colors.labelPrimary,
            )
        }
    }
}

/** "Copy"/"Copied" with the same 1000ms confirmation window as the message rows. */
@Composable
private fun TerminalCopyButton(text: String) {
    val colors = DshTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1000)
            copied = false
        }
    }
    Text(
        text = if (copied) "Copied" else "Copy",
        style = DshType.bodyMedium,
        color = colors.labelSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickable {
                if (copied) return@clickable
                clipboard.setText(AnnotatedString(text))
                copied = true
            },
    )
}
