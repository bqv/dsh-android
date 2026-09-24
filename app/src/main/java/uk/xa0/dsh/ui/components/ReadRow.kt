package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.ExpandMore
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.shortenPath
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/** One line of a read window, keeping the file's own 1-based numbering. */
internal data class ReadCardLine(val number: Int, val text: String)

/** The read card's material, derived from the result envelope. */
internal data class ReadCardModel(
    val label: String,
    val lines: List<ReadCardLine>,
    val totalLines: Int,
    /** Fewer returned lines than the file's total: the banner states the window. */
    val windowed: Boolean,
    val lang: String?,
)

/** Result rows a chat read card retains before collapsing the middle (CHAT_READ_MAX_LINES). */
private const val READ_MAX_LINES = 8

private val READ_ENVELOPE =
    Regex("^<path>([^\\n]*)</path>\\n<type>file</type>\\n<content>\\n([\\s\\S]*)\\n</content>$")
private val READ_NUMBERED_LINE = Regex("^(\\d+): (.*)$")
private val READ_SHOWN = Regex("Showing lines (\\d+)-(\\d+) of (\\d+)\\.")
private val READ_TOTAL = Regex("total (\\d+) lines")

/** Extension -> language id, mirroring `read-render.ts#LANG_BY_EXTENSION` for the banner label. */
private val LANG_BY_EXTENSION = mapOf(
    "ts" to "ts", "tsx" to "tsx", "mts" to "ts", "cts" to "ts",
    "js" to "js", "jsx" to "jsx", "mjs" to "js", "cjs" to "js",
    "json" to "json", "jsonc" to "json", "py" to "py", "rb" to "rb", "go" to "go", "rs" to "rs",
    "java" to "java", "c" to "c", "h" to "c", "cc" to "cpp", "cpp" to "cpp", "hpp" to "cpp",
    "cxx" to "cpp", "cs" to "cs", "kt" to "kotlin", "swift" to "swift", "php" to "php",
    "sh" to "sh", "bash" to "sh", "zsh" to "sh", "yaml" to "yaml", "yml" to "yaml",
    "toml" to "toml", "ini" to "ini", "md" to "md", "markdown" to "md", "mdx" to "mdx",
    "html" to "html", "htm" to "html", "css" to "css", "scss" to "scss", "less" to "less",
    "sql" to "sql", "xml" to "xml", "lua" to "lua",
)

/** Grammar hint from the file extension; a dotfile and an unknown extension both yield none. */
private fun langFromPath(path: String): String? {
    val base = path.substringAfterLast('/').substringAfterLast('\\')
    val dot = base.lastIndexOf('.')
    if (dot <= 0) return null
    return LANG_BY_EXTENSION[base.substring(dot + 1).lowercase()]
}

private fun positiveInt(value: Any?): Int? =
    (value as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()?.takeIf { it >= 1 }

private fun nonNegativeInt(value: Any?): Int? =
    (value as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()?.takeIf { it >= 0 }

/**
 * Validate the result's `meta` window exactly as `read-card-model.ts#readMeta`
 * does: a 1-based `offset`, a non-negative `totalLines`, and strictly
 * increasing line numbers that stay inside the file. Anything else declines, so
 * the card never misnumbers a window.
 */
private fun readMetaWindow(meta: JSONObject?): ReadCardModel? {
    if (meta == null) return null
    val path = meta.opt("path") as? String ?: return null
    val offset = positiveInt(meta.opt("offset")) ?: return null
    val total = nonNegativeInt(meta.opt("totalLines")) ?: return null
    val array = meta.optJSONArray("lines") ?: return null
    val lines = ArrayList<ReadCardLine>(array.length())
    var previous = offset - 1
    for (i in 0 until array.length()) {
        val line = array.optJSONObject(i) ?: return null
        val number = positiveInt(line.opt("number")) ?: return null
        val text = line.opt("text") as? String ?: return null
        if (number <= previous || number > total) return null
        previous = number
        lines.add(ReadCardLine(number, text))
    }
    val lang = when (val value = meta.opt("lang")) {
        null, JSONObject.NULL -> langFromPath(path)
        is String -> value
        else -> return null
    }
    return ReadCardModel(
        label = shortenPath(path),
        lines = lines,
        totalLines = total,
        windowed = total > lines.size,
        lang = lang,
    )
}

/**
 * The `read` card, from the result's persisted `meta` when present and the
 * model-facing envelope otherwise.
 *
 * The web's `read-card-model.ts` numbers its lines from `meta`; the envelope
 * (`formatReadOutput`) repeats the same window, so a record that predates the
 * `meta` field still renders a correct card rather than dropping to the generic
 * row.
 */
internal fun readCardModel(
    arguments: String,
    result: String?,
    isError: Boolean,
    meta: JSONObject? = null,
): ReadCardModel? {
    if (isError || result == null) return null
    val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    val path = args.opt("file_path") as? String ?: args.opt("path") as? String ?: return null
    if (path.trim().isEmpty()) return null
    if (args.has("offset") && positiveInt(args.opt("offset")) == null) return null
    if (args.has("limit") && positiveInt(args.opt("limit")) == null) return null

    val matched = READ_ENVELOPE.matchEntire(result) ?: return null
    val metaWindow = readMetaWindow(meta)
    if (metaWindow != null) return metaWindow

    val displayPath = matched.groupValues[1]
    val body = matched.groupValues[2]
    val split = body.lastIndexOf("\n\n")
    val numbered: String
    val footer: String
    when {
        split >= 0 && body.startsWith("(", split + 2) -> {
            numbered = body.substring(0, split)
            footer = body.substring(split + 2)
        }
        // An empty file's body IS the footer.
        body.startsWith("(") -> {
            numbered = ""
            footer = body
        }
        else -> {
            numbered = body
            footer = ""
        }
    }

    val lines = ArrayList<ReadCardLine>()
    if (numbered.isNotBlank()) {
        var previous = 0
        for (raw in numbered.split('\n')) {
            val line = READ_NUMBERED_LINE.matchEntire(raw) ?: return null
            val number = line.groupValues[1].toIntOrNull() ?: return null
            // Strictly increasing 1-based numbers: a card that misnumbers the
            // window is worse than the generic row.
            if (number <= previous) return null
            previous = number
            lines.add(ReadCardLine(number, line.groupValues[2]))
        }
    }

    val shownOf = READ_SHOWN.find(footer)?.groupValues?.get(3)?.toIntOrNull()
    val totalOf = READ_TOTAL.find(footer)?.groupValues?.get(1)?.toIntOrNull()
    val total = shownOf ?: totalOf ?: lines.size
    return ReadCardModel(
        label = shortenPath(displayPath),
        lines = lines,
        totalLines = total,
        // A byte-capped footer names the range but not the file total, so the
        // window note cannot be drawn for it; the card still renders the lines.
        windowed = total > lines.size,
        lang = langFromPath(displayPath),
    )
}

@Composable
internal fun ReadCallRow(entry: ChatEntry.ToolCall, model: ReadCardModel) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }
    val summary = remember(entry.arguments) { toolSummary(entry.name, entry.arguments) }

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
            Text("Read", style = DshType.rowTitle, color = colors.labelSecondary, maxLines = 1)
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
            ReadBlockCard(entry.callId, model)
            Spacer(Modifier.height(DshSpacing.xs))
        }
    }
}

/**
 * `ReadBlock.tsx`: a banner (path, window note, language, Copy) over a
 * line-numbered body whose middle folds at `CHAT_READ_MAX_LINES` (8), keeping
 * the first 4 and last 4 lines.
 */
@Composable
private fun ReadBlockCard(callId: String, model: ReadCardModel) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    var folded by rememberSaveable(callId) { mutableStateOf(false) }
    val hidden = model.lines.size - READ_MAX_LINES
    val capped = hidden > 0 && !folded
    val head = if (capped) model.lines.take(4) else model.lines
    val tail = if (capped) model.lines.takeLast(4) else emptyList()
    val raw = remember(model.lines) { model.lines.joinToString("\n") { it.text } }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.xs, top = DshSpacing.xs, bottom = DshSpacing.xs)
            .clip(shape)
            .background(colors.codeBlock)
            .border(0.5.dp, colors.borderL1, shape),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.codeBanner)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = model.label,
                style = DshType.bodySmall,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (model.windowed) {
                Spacer(Modifier.width(DshSpacing.lg))
                Text(
                    text = "Showing ${model.lines.size} of ${model.totalLines} lines",
                    style = DshType.bodyMedium,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
            model.lang?.let { lang ->
                Spacer(Modifier.width(DshSpacing.lg))
                Text(lang, style = DshType.bodySmall, color = colors.labelTertiary, maxLines = 1)
            }
            if (model.lines.isNotEmpty()) {
                Spacer(Modifier.width(DshSpacing.lg))
                CopyTextButton(raw)
            }
        }

        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(vertical = DshSpacing.lg),
        ) {
            ReadLines(head)
            if (hidden > 0) {
                Text(
                    text = if (folded) "Collapse" else "… $hidden more lines",
                    style = DshType.codeSmall,
                    color = colors.labelTertiary,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickableNoRipple { folded = !folded }
                        .padding(start = 48.dp),
                )
            }
            ReadLines(tail)
        }
    }
}

@Composable
private fun ReadLines(lines: List<ReadCardLine>) {
    val colors = DshTheme.colors
    lines.forEach { line ->
        Row(Modifier.height(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = line.number.toString(),
                style = DshType.codeSmall,
                color = colors.labelTertiary,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier
                    .width(48.dp)
                    .padding(end = 14.dp),
            )
            Text(
                text = line.text.ifEmpty { " " },
                style = DshType.codeSmall,
                color = colors.labelPrimary,
                softWrap = false,
            )
        }
    }
}

/** "Copy"/"Copied" with the same 1000ms confirmation window as the other cards. */
@Composable
private fun CopyTextButton(text: String) {
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
        modifier = Modifier.clickable {
            if (copied) return@clickable
            clipboard.setText(AnnotatedString(text))
            copied = true
        },
    )
}
