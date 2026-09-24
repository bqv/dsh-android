package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
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
import androidx.compose.ui.text.font.FontWeight
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

/** Result rows a chat search card retains before collapsing the middle (CHAT_SEARCH_MAX_LINES). */
private const val SEARCH_MAX_LINES = 8

internal data class SearchLineMatch(val lineNumber: Int, val line: String)
internal data class SearchFileGroup(val path: String, val matches: List<SearchLineMatch>)

/** The search card's material: grouped grep matches or a flat glob path list. */
internal sealed interface SearchCardModel {
    val truncated: Boolean
    val total: Int
    /** The result text carrying a capped search's full-result locator. */
    val recovery: String?

    data class Matches(
        val files: List<SearchFileGroup>,
        override val truncated: Boolean,
        override val total: Int,
        override val recovery: String?,
    ) : SearchCardModel

    data class Paths(
        val paths: List<String>,
        override val truncated: Boolean,
        override val total: Int,
        override val recovery: String?,
    ) : SearchCardModel
}

private fun positiveIntOrNull(value: Any?): Int? =
    (value as? Number)?.takeIf { it.toDouble() == it.toInt().toDouble() }?.toInt()?.takeIf { it >= 1 }

/**
 * Derive the grep/glob card from the result's `meta`, narrowing every field the
 * way `search-card-model.ts` does. Null (the generic row) for anything the card
 * cannot vouch for.
 */
internal fun searchCardModel(
    name: String,
    arguments: String,
    result: String?,
    isError: Boolean,
    meta: JSONObject?,
): SearchCardModel? {
    if (isError || meta == null) return null
    val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    val pattern = args.opt("pattern") as? String ?: return null
    if (name == "grep" && pattern.isEmpty()) return null
    if (name == "glob" && pattern.trim().isEmpty()) return null
    if (name != "grep" && name != "glob") return null
    if (args.has("path") && (args.opt("path") as? String)?.trim().isNullOrEmpty()) return null
    val truncated = meta.opt("truncated") as? Boolean ?: return null
    val total = (meta.opt("total") as? Number)?.toInt()?.takeIf { it >= 0 } ?: return null
    // A capped result's locator lives only in the prose; the card holds the
    // retained rows, so the footer is what recovers the rest.
    val recovery = if (truncated) result else null
    if (name == "grep") {
        if (meta.optString("shape") != "matches") return null
        val filesArray = meta.optJSONArray("files") ?: return null
        val files = ArrayList<SearchFileGroup>(filesArray.length())
        for (i in 0 until filesArray.length()) {
            val file = filesArray.optJSONObject(i) ?: return null
            val path = file.opt("path") as? String ?: return null
            val matchArray = file.optJSONArray("matches") ?: return null
            val matches = ArrayList<SearchLineMatch>(matchArray.length())
            for (j in 0 until matchArray.length()) {
                val match = matchArray.optJSONObject(j) ?: return null
                val lineNumber = positiveIntOrNull(match.opt("lineNumber")) ?: return null
                val line = match.opt("line") as? String ?: return null
                matches.add(SearchLineMatch(lineNumber, line))
            }
            files.add(SearchFileGroup(path, matches))
        }
        return SearchCardModel.Matches(files, truncated, total, recovery)
    }
    if (meta.optString("shape") != "paths") return null
    val pathArray = meta.optJSONArray("paths") ?: return null
    val paths = ArrayList<String>(pathArray.length())
    for (i in 0 until pathArray.length()) {
        paths.add(pathArray.opt(i) as? String ?: return null)
    }
    return SearchCardModel.Paths(paths, truncated, total, recovery)
}

/** One flattened render row; the height cap counts file headers and paths alike. */
private sealed interface SearchRenderRow {
    val key: String

    data class FileHeader(
        val path: String,
        val count: Int,
        val index: Int,
        val collapsed: Boolean,
    ) : SearchRenderRow {
        override val key: String get() = "file:$index"
    }

    data class Match(
        val lineNumber: Int,
        val line: String,
        val fileIndex: Int,
    ) : SearchRenderRow {
        override val key: String get() = "match:$fileIndex:$lineNumber"
    }

    data class Path(val path: String) : SearchRenderRow {
        override val key: String get() = "path:$path"
    }
}

private fun searchRows(model: SearchCardModel, collapsed: Set<Int>): List<SearchRenderRow> =
    when (model) {
        is SearchCardModel.Paths -> model.paths.map { SearchRenderRow.Path(it) }
        is SearchCardModel.Matches -> buildList {
            model.files.forEachIndexed { index, file ->
                add(SearchRenderRow.FileHeader(file.path, file.matches.size, index, index in collapsed))
                if (index !in collapsed) {
                    file.matches.forEach { match ->
                        add(SearchRenderRow.Match(match.lineNumber, match.line, index))
                    }
                }
            }
        }
    }

private fun searchShown(model: SearchCardModel): Int = when (model) {
    is SearchCardModel.Paths -> model.paths.size
    is SearchCardModel.Matches -> model.files.sumOf { it.matches.size }
}

private fun searchCopyText(model: SearchCardModel): String = when (model) {
    is SearchCardModel.Paths -> model.paths.joinToString("\n")
    is SearchCardModel.Matches -> model.files.joinToString("\n\n") { file ->
        (listOf(file.path) + file.matches.map { "${it.lineNumber}: ${it.line}" }).joinToString("\n")
    }
}

@Composable
internal fun SearchCallRow(entry: ChatEntry.ToolCall, model: SearchCardModel) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }
    val summary = remember(entry.arguments) { toolSummary(entry.name, entry.arguments) }
    val title = if (entry.name == "glob") "Glob" else "Grep"

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
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.Search,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
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
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(DshSpacing.xs))
            SearchBlockCard(entry.callId, model)
            // A capped search's recovery locator exists only in the prose.
            model.recovery?.takeIf { it.isNotBlank() }?.let { recovery ->
                Spacer(Modifier.height(DshSpacing.xs))
                Text(
                    text = recovery,
                    style = DshType.bodyMedium,
                    color = colors.labelTertiary,
                    modifier = Modifier.padding(start = DshSpacing.xs),
                )
            }
            Spacer(Modifier.height(DshSpacing.xs))
        }
    }
}

@Composable
private fun SearchBlockCard(callId: String, model: SearchCardModel) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    var folded by rememberSaveable(callId) { mutableStateOf(false) }
    var collapsedFiles by remember(callId) { mutableStateOf(emptySet<Int>()) }
    val rows = searchRows(model, collapsedFiles)
    val empty = rows.isEmpty()
    val shown = searchShown(model)
    val summary = when (model) {
        is SearchCardModel.Paths ->
            if (model.truncated) "Showing $shown of ${model.total} paths" else "$shown paths"
        is SearchCardModel.Matches -> {
            val files = model.files.size
            if (model.truncated) {
                "Showing $shown of ${model.total} matches · $files files"
            } else {
                "$shown matches · $files files"
            }
        }
    }

    val hidden = rows.size - SEARCH_MAX_LINES
    val capped = hidden > 0 && !folded
    val head = if (capped) rows.take(4) else rows
    val naturalTail = if (capped) rows.takeLast(4) else emptyList()
    // When the tail begins inside a file group, restore that group's header so
    // the matches stay attributable; the header consumes a tail slot so the card
    // still shows exactly SEARCH_MAX_LINES rows.
    val tailLead = naturalTail.firstOrNull()
    val tailHeader = if (tailLead is SearchRenderRow.Match &&
        head.none { it is SearchRenderRow.FileHeader && it.index == tailLead.fileIndex }
    ) {
        rows.filterIsInstance<SearchRenderRow.FileHeader>().firstOrNull { it.index == tailLead.fileIndex }
    } else {
        null
    }
    val tail = if (tailHeader == null) naturalTail else naturalTail.drop(1)

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.xs, top = DshSpacing.xs, bottom = DshSpacing.xs)
            .clip(shape)
            .background(colors.codeBlock),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(colors.codeBanner)
                .padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = summary,
                style = DshType.bodyMedium,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (!empty) {
                Spacer(Modifier.width(DshSpacing.lg))
                CopyTextButton(searchCopyText(model))
            }
        }

        if (empty) {
            Text(
                text = "No results",
                style = DshType.codeSmall,
                color = colors.labelTertiary,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = DshSpacing.lg),
            )
        } else {
            Column(
                Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(top = DshSpacing.md, bottom = DshSpacing.lg),
            ) {
                head.forEach { SearchRow(it) { collapsedFiles = toggleIndex(collapsedFiles, it) } }
                if (hidden > 0) {
                    Text(
                        text = if (folded) "Collapse" else "… $hidden more lines",
                        style = DshType.codeSmall,
                        color = colors.labelTertiary,
                        modifier = Modifier
                            .clickableNoRipple { folded = !folded }
                            .padding(horizontal = 14.dp),
                    )
                }
                tailHeader?.let { SearchRow(it) { collapsedFiles = toggleIndex(collapsedFiles, it) } }
                tail.forEach { SearchRow(it) { collapsedFiles = toggleIndex(collapsedFiles, it) } }
            }
        }
    }
}

private fun toggleIndex(collapsed: Set<Int>, row: SearchRenderRow): Set<Int> {
    val index = (row as? SearchRenderRow.FileHeader)?.index ?: return collapsed
    return if (index in collapsed) collapsed - index else collapsed + index
}

@Composable
private fun SearchRow(row: SearchRenderRow, onToggleFile: () -> Unit) {
    val colors = DshTheme.colors
    when (row) {
        is SearchRenderRow.Path -> Text(
            text = row.path,
            style = DshType.codeSmall,
            color = colors.labelPrimary,
            softWrap = false,
            modifier = Modifier
                .height(22.dp)
                .padding(horizontal = 14.dp),
        )

        is SearchRenderRow.Match -> Row(
            Modifier.height(22.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${row.lineNumber}: ",
                style = DshType.codeSmall,
                color = colors.labelTertiary,
                softWrap = false,
            )
            Text(
                text = row.line,
                style = DshType.codeSmall,
                color = colors.labelPrimary,
                softWrap = false,
            )
        }

        is SearchRenderRow.FileHeader -> Row(
            Modifier
                .height(22.dp)
                .clickableNoRipple(onClick = onToggleFile)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = row.path,
                style = DshType.codeSmall.copy(fontWeight = FontWeight.SemiBold),
                color = colors.labelPrimary,
                softWrap = false,
                maxLines = 1,
            )
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = row.count.toString(),
                style = DshType.codeSmall,
                color = colors.labelTertiary,
                maxLines = 1,
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
