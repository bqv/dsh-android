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
import androidx.compose.material.icons.rounded.Edit
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.json.JSONObject
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.shortenToolPath
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/** Body rows a chat diff card retains before collapsing the middle (CHAT_DIFF_MAX_LINES). */
private const val DIFF_MAX_LINES = 9

/** Diff context lines kept on each side of a change, matching DiffBlock's `structuredPatch` call. */
private const val DIFF_CONTEXT = 3

/** Give up on exact patching past this line-product and show the whole fragment as replaced. */
private const val DIFF_EDIT_BUDGET = 200_000L

internal data class DiffHunk(val path: String, val oldText: String?, val newText: String)

internal data class DiffCardModel(val diffs: List<DiffHunk>)

private enum class DiffKind { PATH, DEL, ADD, CONTEXT, GAP }

private data class DiffRow(val kind: DiffKind, val text: String)

private const val OP_EQUAL = 0
private const val OP_DEL = 1
private const val OP_ADD = 2

private data class DiffOp(val kind: Int, val text: String)

/**
 * Validate and narrow `meta.diffs` exactly as `diff-card-model.ts#narrowDiffs`
 * does. Null is "no usable metadata"; an empty list is a present-but-empty
 * `diffs` array, which the write path treats like absent metadata.
 */
private fun appliedDiffs(meta: JSONObject?): List<DiffHunk>? {
    if (meta == null) return null
    val array = meta.optJSONArray("diffs") ?: return null
    val hunks = ArrayList<DiffHunk>(array.length())
    for (i in 0 until array.length()) {
        val hunk = array.optJSONObject(i) ?: return null
        val path = hunk.opt("path") as? String ?: return null
        val oldText = when (val value = hunk.opt("oldText")) {
            null, JSONObject.NULL -> null
            is String -> value
            else -> return null
        }
        val newText = hunk.opt("newText") as? String ?: return null
        hunks.add(DiffHunk(path, oldText, newText))
    }
    return hunks
}

private fun validEscalation(args: JSONObject): Boolean {
    val permission = args.opt("sandbox_permissions")
    val justification = args.opt("justification")
    if (permission == null && justification == null) return true
    if (permission != "workspace-write" && permission != "danger-full-access") return false
    return justification is String && justification.trim().isNotEmpty()
}

private fun contentLines(text: String?): List<String> {
    if (text.isNullOrEmpty()) return emptyList()
    val body = if (text.endsWith("\n")) text.dropLast(1) else text
    return body.split('\n')
}

/**
 * Derive applied diffs for a root `write`/`edit` call, porting
 * `diff-card-model.ts#diffCardModel`. A `write` without usable `meta.diffs`
 * falls back to its argument-derived whole-file diff; an `edit` deliberately
 * declines, because the applied hunk may differ from the requested one (for
 * example under `replace_all`) and showing the wrong text would be worse than
 * the generic row.
 */
internal fun diffCardModel(
    name: String,
    arguments: String,
    result: String?,
    isError: Boolean,
    meta: JSONObject?,
): DiffCardModel? {
    if (name != "write" && name != "edit") return null
    val args = runCatching { JSONObject(arguments) }.getOrNull() ?: return null
    val path = args.opt("file_path") as? String ?: return null
    if (path.trim().isEmpty()) return null
    if (!validEscalation(args)) return null
    val intended = when (name) {
        "write" -> DiffHunk(path, null, args.opt("content") as? String ?: return null)
        else -> {
            val oldText = args.opt("old_string") as? String ?: return null
            val newText = args.opt("new_string") as? String ?: return null
            DiffHunk(path, oldText.ifEmpty { null }, newText)
        }
    }
    // A running call has no applied result yet; the requested change is the card.
    if (result == null) return DiffCardModel(listOf(intended))
    if (isError) return null
    val applied = appliedDiffs(meta)
    if (applied == null || applied.isEmpty()) {
        return if (name == "write") DiffCardModel(listOf(intended)) else null
    }
    return DiffCardModel(applied)
}

private fun allReplaced(old: List<String>, new: List<String>): List<DiffOp> =
    old.map { DiffOp(OP_DEL, it) } + new.map { DiffOp(OP_ADD, it) }

/** Longest-common-subsequence line diff; the order matches a unified diff's script. */
private fun lcsOps(old: List<String>, new: List<String>): List<DiffOp> {
    val n = old.size
    val m = new.size
    val dp = Array(n + 1) { IntArray(m + 1) }
    for (i in n - 1 downTo 0) {
        for (j in m - 1 downTo 0) {
            dp[i][j] = if (old[i] == new[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
    }
    val ops = ArrayList<DiffOp>(n + m)
    var i = 0
    var j = 0
    while (i < n && j < m) {
        when {
            old[i] == new[j] -> {
                ops.add(DiffOp(OP_EQUAL, old[i])); i++; j++
            }
            dp[i + 1][j] >= dp[i][j + 1] -> {
                ops.add(DiffOp(OP_DEL, old[i])); i++
            }
            else -> {
                ops.add(DiffOp(OP_ADD, new[j])); j++
            }
        }
    }
    while (i < n) {
        ops.add(DiffOp(OP_DEL, old[i])); i++
    }
    while (j < m) {
        ops.add(DiffOp(OP_ADD, new[j])); j++
    }
    return ops
}

/** Split the edit script into contextual hunks, merging runs closer than the context window. */
private fun hunkSlices(ops: List<DiffOp>): List<List<DiffOp>> {
    val changes = ops.indices.filter { ops[it].kind != OP_EQUAL }
    if (changes.isEmpty()) return emptyList()
    val slices = ArrayList<List<DiffOp>>()
    var start = changes.first()
    var end = changes.first()
    for (index in changes.drop(1)) {
        if (index - end <= 2 * DIFF_CONTEXT + 1) {
            end = index
        } else {
            slices.add(ops.subList(maxOf(0, start - DIFF_CONTEXT), minOf(ops.size, end + DIFF_CONTEXT + 1)))
            start = index
            end = index
        }
    }
    slices.add(ops.subList(maxOf(0, start - DIFF_CONTEXT), minOf(ops.size, end + DIFF_CONTEXT + 1)))
    return slices
}

/** Flatten hunks into body rows: a path header per file, a `⋯` gap between fragments and hunks. */
private fun diffRows(diffs: List<DiffHunk>): List<DiffRow> {
    val rows = ArrayList<DiffRow>()
    var previousPath: String? = null
    for (diff in diffs) {
        if (diff.path != previousPath) rows.add(DiffRow(DiffKind.PATH, diff.path))
        else rows.add(DiffRow(DiffKind.GAP, "⋯"))
        previousPath = diff.path
        val old = contentLines(diff.oldText)
        val new = contentLines(diff.newText)
        val ops = if (old.size.toLong() * new.size > DIFF_EDIT_BUDGET) allReplaced(old, new) else lcsOps(old, new)
        hunkSlices(ops).forEachIndexed { index, hunk ->
            if (index > 0) rows.add(DiffRow(DiffKind.GAP, "⋯"))
            hunk.forEach { op ->
                rows.add(
                    DiffRow(
                        when (op.kind) {
                            OP_DEL -> DiffKind.DEL
                            OP_ADD -> DiffKind.ADD
                            else -> DiffKind.CONTEXT
                        },
                        op.text,
                    ),
                )
            }
        }
    }
    return rows
}

@Composable
internal fun DiffCallRow(entry: ChatEntry.ToolCall, model: DiffCardModel) {
    val colors = DshTheme.colors
    var expanded by rememberSaveable(entry.callId) { mutableStateOf(false) }
    val rows = remember(model.diffs) { diffRows(model.diffs) }
    val added = rows.count { it.kind == DiffKind.ADD }
    val removed = rows.count { it.kind == DiffKind.DEL }
    val title = if (entry.name == "write") "Write" else "Edit"
    val path = remember(entry.arguments) {
        runCatching { JSONObject(entry.arguments) }.getOrNull()?.optString("file_path").orEmpty()
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
                Icon(
                    imageVector = if (expanded) Icons.Rounded.ExpandMore else Icons.Rounded.Edit,
                    contentDescription = null,
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(14.dp),
                )
            }
            Spacer(Modifier.width(DshSpacing.sm))
            Text(title, style = DshType.rowTitle, color = colors.labelSecondary, maxLines = 1)
            if (path.isNotBlank()) {
                Spacer(Modifier.width(DshSpacing.md))
                Box(
                    Modifier
                        .size(2.dp)
                        .clip(CircleShape)
                        .background(colors.labelCaption),
                )
                Spacer(Modifier.width(DshSpacing.md))
                Text(
                    text = shortenToolPath(path),
                    style = DshType.rowSummary,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The change size reads without expanding, as ToolRow's diffStat does.
                Spacer(Modifier.width(DshSpacing.xs))
                Text(
                    text = "+$added -$removed",
                    style = DshType.rowSummary,
                    color = colors.labelTertiary,
                    maxLines = 1,
                )
            }
        }

        if (expanded) {
            Spacer(Modifier.height(DshSpacing.xs))
            DiffBlockCard(entry.callId, rows, added, removed, model.diffs.map { it.path }.distinct().size)
            Spacer(Modifier.height(DshSpacing.xs))
        }
    }
}

@Composable
private fun DiffBlockCard(callId: String, rows: List<DiffRow>, added: Int, removed: Int, files: Int) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.card)
    var folded by rememberSaveable(callId) { mutableStateOf(false) }
    val hidden = rows.size - DIFF_MAX_LINES
    val capped = hidden > 0 && !folded
    val head = if (capped) rows.take(5) else rows
    val tail = if (capped) rows.takeLast(4) else emptyList()

    Box(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.xs, top = DshSpacing.xs, bottom = DshSpacing.xs)
            .clip(shape)
            .background(colors.codeBlock),
    ) {
        Column(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = DshSpacing.lg),
        ) {
            head.forEach { DiffLine(it) }
            if (hidden > 0) {
                Text(
                    text = if (folded) "Collapse" else "… $hidden more lines",
                    style = DshType.codeSmall,
                    color = colors.labelTertiary,
                    modifier = Modifier.clickableNoRipple { folded = !folded },
                )
            }
            tail.forEach { DiffLine(it) }
            Spacer(Modifier.height(DshSpacing.md))
            Text(
                text = "└ +$added -$removed · ${if (files == 1) "1 file" else "$files files"}",
                style = DshType.codeSmall,
                color = colors.labelTertiary,
            )
        }
        // Floats over the body's top-right, as the primitive's copy control does.
        Box(Modifier.align(Alignment.TopEnd).padding(top = DshSpacing.md, end = 14.dp)) {
            DiffCopyButton(copyText(rows))
        }
    }
}

private fun copyText(rows: List<DiffRow>): String = rows.joinToString("\n") { row ->
    when (row.kind) {
        DiffKind.DEL -> "- ${row.text}"
        DiffKind.ADD -> "+ ${row.text}"
        DiffKind.CONTEXT -> "  ${row.text}"
        DiffKind.PATH, DiffKind.GAP -> row.text
    }
}

@Composable
private fun DiffLine(row: DiffRow) {
    val colors = DshTheme.colors
    val (prefix, color) = when (row.kind) {
        DiffKind.DEL -> "- " to colors.error
        DiffKind.ADD -> "+ " to colors.success
        DiffKind.CONTEXT -> "  " to colors.labelSecondary
        DiffKind.GAP -> "" to colors.labelTertiary
        DiffKind.PATH -> "" to colors.labelPrimary
    }
    Text(
        text = prefix + row.text,
        style = if (row.kind == DiffKind.PATH) {
            DshType.codeSmall.copy(fontWeight = FontWeight.SemiBold)
        } else {
            DshType.codeSmall
        },
        color = color,
        softWrap = false,
        modifier = Modifier.height(22.dp),
    )
}

/** "Copy"/"Copied" with the same 1000ms confirmation window as the other cards. */
@Composable
private fun DiffCopyButton(text: String) {
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
