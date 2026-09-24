package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.DirectoryEntry
import uk.xa0.dsh.DirectoryLevel
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The workspace directory browser — `DirectoryBrowser.tsx` ported.
 *
 * The web's Miller two-pane view exists to show a folder *and* its children at
 * once, which a 300dp sidebar and a phone cannot hold; here the listed level is
 * the current directory and a row descends into it, so the breadcrumb and the
 * path editor are the way back up. What is kept is the shape the host expects:
 * the level as the host reported it (paths are never joined client-side), hidden
 * rows filtered client-side, and Open confirming the current directory.
 *
 * All state that is not the listing itself — the path draft, the new-folder
 * name, whether hidden rows show — lives here and resets per open, because the
 * caller composes the browser only while it is up.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceBrowser(
    level: DirectoryLevel?,
    loading: Boolean,
    busy: Boolean,
    error: String?,
    onList: (String?) -> Unit,
    onCreateDirectory: (String, String, (String) -> Unit) -> Unit,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = DshTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var showHidden by remember { mutableStateOf(false) }
    // null = the breadcrumb is showing; a string = the path being edited.
    var pathDraft by remember { mutableStateOf<String?>(null) }
    // null = no create form; a string = the folder name being typed.
    var folderDraft by remember { mutableStateOf<String?>(null) }
    // Both editors are opened by a tap on something *else* (the pencil, the New
    // folder row), so they have to claim focus themselves or the operator has to
    // tap a second time before the keyboard appears.
    val pathFocus = remember { FocusRequester() }
    val folderFocus = remember { FocusRequester() }

    // Every open starts at the host home: the dialog is composed per open, so one
    // effect is enough — and the level is deliberately not remembered between
    // opens, or a workspace added from a deep path would not be reachable twice.
    LaunchedEffect(Unit) { onList(null) }

    // Rooted at Home, as the web's `displayCrumbs` does: a deep absolute path
    // would otherwise push the current directory off the row. Outside the home
    // subtree there is no Home crumb to root at, so the full chain stands.
    val crumbs = remember(level) {
        val listed = level ?: return@remember emptyList()
        val at = listed.crumbs.indexOfFirst { it.path == listed.home }
        if (at < 0) {
            listed.crumbs
        } else {
            listOf(DirectoryEntry("Home", listed.home, false)) + listed.crumbs.drop(at + 1)
        }
    }
    val tail = crumbs.lastOrNull()?.path
    val crumbScroll = rememberScrollState()
    // Following `maxValue` rather than the path: the crumb row's width is only
    // known after layout, and the effect body runs before it — keyed on the path
    // alone it would scroll to the *previous* row's end and stop short.
    LaunchedEffect(crumbScroll) {
        snapshotFlow { crumbScroll.maxValue }.collect { crumbScroll.scrollTo(it) }
    }

    val visible = remember(level, showHidden) {
        level?.entries.orEmpty().filter { showHidden || !it.hidden }
    }
    val separator = if (level?.home?.contains('\\') == true) "\\" else "/"
    // The folder a create or Open acts on: the listed level, since there is no
    // separate selection in a one-pane view.
    val targetPath = level?.path
    val targetName = crumbs.lastOrNull()?.name?.takeIf { it.isNotEmpty() }
        ?: targetPath?.trimEnd('/', '\\')?.substringAfterLast('/').orEmpty()

    ModalBottomSheet(
        onDismissRequest = { if (!busy) onDismiss() },
        sheetState = sheetState,
        containerColor = colors.bgBase,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(horizontal = DshSpacing.xl)
                .padding(bottom = DshSpacing.lg)
                .imePadding(),
        ) {
            Text(
                text = "Select Workspace Directory",
                style = DshType.heading2,
                color = colors.labelPrimary,
            )
            Spacer(Modifier.height(DshSpacing.md))

            // Breadcrumb / path editor. The pencil is the only visible way into
            // typing a path, so it stays available even when the level failed to
            // list: an absolute path is then the one way forward.
            if (pathDraft == null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier
                            .weight(1f)
                            .horizontalScroll(crumbScroll),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        crumbs.forEachIndexed { index, crumb ->
                            if (index > 0) {
                                Icon(
                                    Icons.Rounded.ChevronRight,
                                    contentDescription = null,
                                    tint = colors.labelCaption,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                            Text(
                                text = crumb.name,
                                style = DshType.bodyMedium,
                                color = if (crumb.path == tail) colors.labelPrimary else colors.labelSecondary,
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(DshRadius.sm))
                                    .clickableNoRipple(enabled = !busy) { onList(crumb.path) }
                                    .padding(horizontal = DshSpacing.xs, vertical = DshSpacing.xxs),
                            )
                        }
                    }
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .clickableNoRipple(enabled = !busy) {
                                // Seeded with a trailing separator so typing continues
                                // into child names, exactly as the web seeds it.
                                val base = targetPath
                                pathDraft = if (base == null) "" else if (base.endsWith(separator)) base else base + separator
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = "Edit path",
                            tint = colors.labelTertiary,
                            modifier = Modifier.size(14.dp),
                        )
                    }
                }
            } else {
                LaunchedEffect(Unit) { pathFocus.requestFocus() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    DshTextField(
                        value = pathDraft.orEmpty(),
                        onValueChange = { pathDraft = it },
                        placeholder = "Absolute path",
                        modifier = Modifier.weight(1f),
                        minHeight = 40.dp,
                        focusRequester = pathFocus,
                    )
                    Spacer(Modifier.width(DshSpacing.xs))
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickableNoRipple(enabled = !busy) { pathDraft = null },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.Close,
                            contentDescription = "Cancel path edit",
                            tint = colors.labelTertiary,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .clickableNoRipple(enabled = !busy && !pathDraft.isNullOrBlank()) {
                                val submitted = pathDraft.orEmpty()
                                if (submitted.isNotBlank()) {
                                    pathDraft = null
                                    onList(submitted)
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Rounded.ChevronRight,
                            contentDescription = "Open path",
                            tint = if (pathDraft.isNullOrBlank()) colors.labelTertiary else colors.accent,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            Spacer(Modifier.height(DshSpacing.sm))

            Box(Modifier.weight(1f)) {
                if (level != null) {
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(visible, key = { it.path }) { entry ->
                            DirectoryRow(entry = entry, enabled = !busy) { onList(entry.path) }
                        }
                    }
                }
                if (loading) {
                    Text(
                        text = "Loading…",
                        style = DshType.bodySmall,
                        color = colors.labelTertiary,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .clip(RoundedCornerShape(DshRadius.pill))
                            .background(colors.tip)
                            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
                    )
                }
                if (visible.isEmpty() && !loading && level != null) {
                    Text(
                        text = "No folders here",
                        style = DshType.bodySmall,
                        color = colors.labelTertiary,
                        modifier = Modifier.padding(DshSpacing.md),
                    )
                }
            }

            // The backend cuts a level at its complete-result bound; saying so is
            // what stops the missing tail from reading as an empty directory.
            if (level?.truncated == true) {
                Text(
                    text = "Too many folders to list; only the beginning is shown.",
                    style = DshType.micro,
                    color = colors.warn,
                    modifier = Modifier.padding(vertical = DshSpacing.xs),
                )
            }
            error?.let {
                Text(
                    text = it,
                    style = DshType.bodySmall,
                    color = colors.error,
                    modifier = Modifier.padding(vertical = DshSpacing.xs),
                )
            }

            // New-folder form: names one child of the listed level.
            if (folderDraft != null) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = DshSpacing.sm)
                        .clip(RoundedCornerShape(DshRadius.card))
                        .background(colors.bgLayer1)
                        .border(0.5.dp, colors.borderL1, RoundedCornerShape(DshRadius.card))
                        .padding(DshSpacing.md),
                ) {
                    Text(
                        text = "New folder in \"$targetName\"",
                        style = DshType.bodySmall,
                        color = colors.labelSecondary,
                    )
                    Spacer(Modifier.height(DshSpacing.sm))
                    LaunchedEffect(Unit) { folderFocus.requestFocus() }
                    DshTextField(
                        value = folderDraft.orEmpty(),
                        onValueChange = { folderDraft = it },
                        placeholder = "Untitled folder",
                        minHeight = 40.dp,
                        focusRequester = folderFocus,
                    )
                    Spacer(Modifier.height(DshSpacing.sm))
                    Row(horizontalArrangement = Arrangement.spacedBy(DshSpacing.md)) {
                        BrowserButton("Cancel", primary = false, enabled = !busy) { folderDraft = null }
                        // Trim only rejects an all-whitespace name; the host gets the
                        // original spelling, since trimming would create a different
                        // sibling than the one typed.
                        val name = folderDraft.orEmpty()
                        BrowserButton(
                            text = "Create",
                            primary = true,
                            enabled = !busy && name.trim().isNotEmpty() && targetPath != null,
                        ) {
                            val parent = targetPath ?: return@BrowserButton
                            onCreateDirectory(parent, name) { created ->
                                folderDraft = null
                                // Land on the created folder rather than staying on its
                                // parent: in a one-pane view descending is what makes the
                                // new folder visible at all.
                                onList(created)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(DshSpacing.sm))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(DshRadius.md))
                        .clickableNoRipple(enabled = !busy && targetPath != null) { folderDraft = "" }
                        .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.labelSecondary, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(DshSpacing.xs))
                    Text("New folder", style = DshType.bodyMedium, color = colors.labelSecondary)
                }
                Spacer(Modifier.width(DshSpacing.lg))
                Row(
                    Modifier
                        .clip(RoundedCornerShape(DshRadius.md))
                        .clickableNoRipple(enabled = !busy) { showHidden = !showHidden }
                        .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Show hidden files",
                        style = DshType.bodyMedium,
                        color = if (showHidden) colors.accent else colors.labelSecondary,
                    )
                    // The check trails the label so the row never shifts when the
                    // toggle flips.
                    if (showHidden) {
                        Spacer(Modifier.width(DshSpacing.xs))
                        Icon(Icons.Rounded.Check, contentDescription = null, tint = colors.accent, modifier = Modifier.size(14.dp))
                    }
                }
            }

            Spacer(Modifier.height(DshSpacing.sm))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BrowserButton("Cancel", primary = false, enabled = !busy) { onDismiss() }
                Spacer(Modifier.width(DshSpacing.md))
                BrowserButton(
                    text = "Open",
                    primary = true,
                    enabled = !busy && !loading && targetPath != null,
                ) {
                    targetPath?.let(onOpen)
                }
            }
        }
    }
}

/** One folder row: the icon carries the verb, so the row itself is the target. */
@Composable
private fun DirectoryRow(entry: DirectoryEntry, enabled: Boolean, onClick: () -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(enabled = enabled, onClick = onClick)
            .padding(horizontal = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(DshSpacing.md))
        Text(
            text = entry.name,
            style = DshType.bodyMedium,
            color = if (entry.hidden) colors.labelTertiary else colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(14.dp),
        )
    }
}

/** A footer verb: primary is the accent text, secondary the quiet one. */
@Composable
private fun BrowserButton(text: String, primary: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = DshTheme.colors
    val label = when {
        !enabled -> colors.labelTertiary
        primary -> colors.accent
        else -> colors.labelSecondary
    }
    Text(
        text = text,
        style = DshType.labelLarge.copy(fontWeight = if (primary) FontWeight.Medium else FontWeight.Normal),
        color = label,
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(enabled = enabled, onClick = onClick)
            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.sm),
    )
}
