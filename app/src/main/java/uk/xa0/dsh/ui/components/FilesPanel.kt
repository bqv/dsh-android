package uk.xa0.dsh.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import uk.xa0.dsh.diag.DiagLazyList
import uk.xa0.dsh.diag.DiagScrollColumn
import uk.xa0.dsh.diag.diagDrag
import uk.xa0.dsh.diag.diagOffset
import uk.xa0.dsh.model.FilePreview
import uk.xa0.dsh.model.FileTouch
import uk.xa0.dsh.model.ExplorerState
import uk.xa0.dsh.model.PreviewFormat
import uk.xa0.dsh.model.PreviewLine
import uk.xa0.dsh.model.SessionFile
import uk.xa0.dsh.model.WorkspaceEntry
import uk.xa0.dsh.model.WorkspaceEntryKind
import uk.xa0.dsh.model.WorkspaceExplorer
import uk.xa0.dsh.model.previewFormatOf
import uk.xa0.dsh.model.WorkspaceFileLoader
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.shortenPath
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * What the parent must supply for the panel's explorer half.
 *
 * [onList] takes a **workspace-relative** directory, `""` for the session root,
 * which is what the host's own listing reply names; the parent sends the root as
 * the host's own `.`, because an empty `path` is refused.
 * [onReload] re-lists the level on screen, ignoring any cache — the one
 * invalidation the tree has, since `workspaceFiles/list` is a one-shot call and
 * the tree subscribes to no watch.
 */
data class ExplorerWiring(
    val state: ExplorerState,
    val onList: (String) -> Unit,
    val onReload: () -> Unit,
)

/**
 * The session's Files panel: the files the transcript touched, and the workspace
 * they live in, with a numbered text preview for the selected one.
 *
 * Two lists over one session, and the difference between them is the point:
 *
 *  - **This session** is `SessionFiles.derive(entries)` — the files the
 *    transcript actually touched, in first-seen order. It is true and it is
 *    small, and it stays: it is the only list that can name a file by the exact
 *    spelling a tool call used, which is what a deliverable card's path is.
 *  - **Browse** is the workspace tree, listed one directory at a time from
 *    `workspaceFiles/list` rooted at the session's cwd, with the session's own
 *    touches drawn on it as marks. The user's complaint was that the panel held
 *    only a handful of rows and read as broken rather than scoped; a directory
 *    listing is what makes it read as the workspace, and the marks are what keep
 *    it about *this* session.
 *
 * A mark means the session read, wrote, edited, or declared that exact path. **A
 * row with no mark is a file this session has not touched** — a fact, not a gap:
 * the row is in the list because it is in the directory, and the panel states the
 * convention in words rather than leaving the absence to be read as "unknown".
 * That is the same distinction the present-as-a-row change was made for.
 *
 * The composition stays data-in / callback-out so the panel is host-free:
 *
 *  - [cached] is `SessionFiles.previews(entries)` — content the durable
 *    transcript already carries, keyed by exact path. It is the first choice,
 *    matching the web's resolution order (the session resource answers before
 *    the workspace file), and it works with no host at all.
 *  - [loader] is the on-demand workspace read (`workspaceFiles/read`). It is
 *    asked only for a path the transcript holds nothing for, and its outcome is
 *    memoized for the panel's lifetime, so re-selecting a file never refetches.
 *    Failure renders the host's own wording rather than a substitute.
 *  - [explorer] is the workspace listing and the way to move it. Null means the
 *    parent has no host (or no cwd), and then there is no Browse mode at all
 *    rather than a tab that can only be empty.
 *
 * On a phone the list and preview swap in place; from 720dp they sit side by
 * side, which is the desktop reading of the web's two-pane right sidebar.
 */
@Composable
fun FilesPanel(
    files: List<SessionFile>,
    modifier: Modifier = Modifier,
    /** Session cwd, shown as the list's header crumb and the explorer's root. */
    root: String? = null,
    /** Content from tool results, keyed by exact path. */
    cached: Map<String, FilePreview> = emptyMap(),
    /** On-demand workspace read; null means "use [cached] only". */
    loader: WorkspaceFileLoader? = null,
    /**
     * Path to open straight away, for a caller that arrived from a file link
     * rather than from this list. A path the session never touched is ignored.
     */
    initialPath: String? = null,
    /** Fired when a file row is selected, for a parent that wants to observe it. */
    onSelect: ((SessionFile) -> Unit)? = null,
    /** The workspace listing, when the parent has a host to list with. */
    explorer: ExplorerWiring? = null,
) {
    val colors = DshTheme.colors
    val canExplore = explorer != null && root != null
    // Which list is up. The workspace is the default — a panel derived only from
    // the session's own activity is what read as broken — but a caller that named
    // a file arrived from a card or a link, and the session list is the only one
    // that can select an arbitrary transcript spelling, so that opens as it did.
    var browse by rememberSaveable(initialPath, canExplore) {
        mutableStateOf(canExplore && initialPath == null)
    }
    var selected by remember(initialPath) {
        mutableStateOf(initialPath?.takeIf { path -> files.any { it.path == path } })
    }
    // The session's own record, resolved onto workspace-relative paths once per
    // list rather than per row.
    val badges = remember(files, root) { WorkspaceExplorer.badges(files, root) }

    // A selected path can vanish when the transcript reloads; drop it rather than
    // previewing a file the session list no longer holds. The rule belongs to that
    // list: a browse selection is a workspace path and is not drawn from here.
    LaunchedEffect(files, browse) {
        if (!browse && selected != null && files.none { it.path == selected }) selected = null
    }

    // Ask for the root when Browse has nothing to show. Guarded on the state
    // rather than run on every toggle, so a reader who was two directories down
    // finds the tree where they left it after looking at the session list; a
    // freshly opened panel is `Idle` (the parent resets on close), so that is
    // where the session root is listed. Idleness is a key as well as a condition,
    // or a reset that arrives while Browse is already up would leave the pane
    // saying "Loading…" with nothing in flight.
    val explorerIdle = explorer?.state is ExplorerState.Idle
    LaunchedEffect(browse, root, explorerIdle) {
        val wiring = explorer
        if (browse && explorerIdle && wiring != null && root != null) wiring.onList("")
    }

    // Workspace reads are memoized for the panel's lifetime: re-selecting a file
    // must not refetch, and a failure keeps the host's message on screen until
    // Retry. `Loading` is stored too, so one map lookup answers the body.
    val fetched = remember { mutableStateMapOf<String, FilePreview>() }
    var reloadKey by remember { mutableStateOf(0) }
    // The effect keys on the selection, not on `loader`: a caller that rebuilds
    // the interface each composition must not cancel and restart a live read.
    val currentLoader by rememberUpdatedState(loader)

    // The transcript keys content by its own spelling of the path; the explorer
    // addresses the same file workspace-relatively. Preferring the transcript's
    // key when the row carries a badge means a file the session already read is
    // previewed from the transcript instead of fetched a second time. The *read*
    // still travels under the path on screen, which is what the host resolves.
    val readKey: String? = selected?.let { path -> badges[path]?.path ?: path }

    LaunchedEffect(readKey, reloadKey) {
        val key = readKey ?: return@LaunchedEffect
        val path = selected ?: return@LaunchedEffect
        val read = currentLoader ?: return@LaunchedEffect
        if (cached.containsKey(key) || fetched.containsKey(key)) return@LaunchedEffect
        fetched[key] = FilePreview.Loading
        try {
            fetched[key] = read.load(path)
        } catch (cancelled: CancellationException) {
            // The selection moved on: leave nothing behind, so returning to the
            // file fetches again instead of sticking on "Reading…".
            fetched.remove(key)
            throw cancelled
        } catch (error: Throwable) {
            fetched[key] = FilePreview.Unavailable(
                error.message?.takeIf { it.isNotBlank() } ?: "Could not read this file.",
            )
        }
    }

    val shown: FilePreview? = readKey?.let { key -> cached[key] ?: fetched[key] }
    val retry: () -> Unit = { readKey?.let { key -> fetched.remove(key); reloadKey += 1 } }

    if (files.isEmpty() && !browse) {
        Box(modifier.fillMaxSize().background(colors.bgBase), contentAlignment = Alignment.Center) {
            FilesEmptyState()
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxSize().background(colors.bgBase)) {
        val twoPane = maxWidth >= 720.dp
        Column(Modifier.fillMaxSize()) {
            if (canExplore) {
                FilesModeTabs(
                    browse = browse,
                    onBrowse = { next ->
                        if (next != browse) {
                            browse = next
                            // The two lists address the same file under different
                            // spellings, so a selection made in one is not a
                            // selection in the other.
                            selected = null
                        }
                    },
                )
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.borderL1))
            }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                val list: @Composable (Modifier) -> Unit = { listModifier ->
                    if (browse && explorer != null && root != null) {
                        ExplorerPane(
                            root = root,
                            state = explorer.state,
                            badges = badges,
                            selectedPath = selected,
                            onList = explorer.onList,
                            onReload = explorer.onReload,
                            onSelectFile = { path, badge ->
                                selected = path
                                badge?.let { onSelect?.invoke(it) }
                            },
                            modifier = listModifier,
                        )
                    } else {
                        FileListPane(
                            files = files,
                            root = root,
                            selectedPath = selected,
                            onSelect = { file ->
                                selected = file.path
                                onSelect?.invoke(file)
                            },
                            modifier = listModifier,
                        )
                    }
                }
                if (twoPane) {
                    Row(Modifier.fillMaxSize()) {
                        list(Modifier.weight(0.42f).fillMaxHeight())
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(colors.borderL1))
                        PreviewPane(
                            path = selected,
                            preview = shown,
                            loaderWired = loader != null,
                            showBack = false,
                            onBack = {},
                            onRetry = retry,
                            modifier = Modifier.weight(0.58f).fillMaxHeight(),
                        )
                    }
                } else if (selected == null) {
                    list(Modifier.fillMaxSize())
                } else {
                    PreviewPane(
                        path = selected,
                        preview = shown,
                        loaderWired = loader != null,
                        showBack = true,
                        onBack = { selected = null },
                        onRetry = retry,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

/**
 * This session / Browse, as two pills.
 *
 * The selected one is drawn as a filled pill rather than by tint alone, because
 * a phone in sunlight and a reader who cannot separate two greys both need a
 * shape to read, not a hue.
 */
@Composable
private fun FilesModeTabs(browse: Boolean, onBrowse: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(38.dp)
            .padding(horizontal = DshSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.sm),
    ) {
        ModeTab("This session", selected = !browse) { onBrowse(false) }
        ModeTab("Browse", selected = browse) { onBrowse(true) }
    }
}

@Composable
private fun ModeTab(text: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DshTheme.colors
    Text(
        text = text,
        style = DshType.labelLarge,
        color = if (selected) colors.labelPrimary else colors.labelTertiary,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.pill))
            .background(if (selected) colors.sidebarItemActive else colors.bgBase)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xxs),
    )
}

/**
 * The workspace tree for one directory: breadcrumb over the level's rows.
 *
 * A directory descends in place, a file selects, and `other` is a row that cannot
 * be opened — the host's `read` refuses it for the same reason. Nothing here
 * pages: `workspaceFiles/list` is a one-shot call with no cursor, so the host's
 * entry cap is the only bound and [ExplorerState.Ready]'s `truncated` flag is
 * reported rather than hidden.
 */
@Composable
private fun ExplorerPane(
    root: String,
    state: ExplorerState,
    badges: Map<String, SessionFile>,
    selectedPath: String?,
    onList: (String) -> Unit,
    onReload: () -> Unit,
    onSelectFile: (String, SessionFile?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    var showHidden by remember { mutableStateOf(false) }
    val listing = (state as? ExplorerState.Ready)?.listing
    val current = when (state) {
        is ExplorerState.Ready -> state.listing.path
        is ExplorerState.Loading -> state.path
        is ExplorerState.Failed -> state.path
        ExplorerState.Idle -> ""
    }
    val crumbScroll = rememberScrollState()
    // Following `maxValue` rather than the path: the row's width is only known
    // after layout, and the effect body runs before it — keyed on the path alone
    // it would scroll to the previous row's end and stop short.
    LaunchedEffect(crumbScroll) {
        snapshotFlow { crumbScroll.maxValue }.collect { crumbScroll.scrollTo(it) }
    }
    val entries = remember(listing, badges, showHidden) {
        listing?.let { WorkspaceExplorer.visibleEntries(it, badges, showHidden) }.orEmpty()
    }

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = DshSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(crumbScroll),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WorkspaceExplorer.crumbs(current, WorkspaceExplorer.basename(root))
                    .forEachIndexed { index, crumb ->
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
                            style = DshType.bodySmall,
                            color = if (crumb.path == current) colors.labelPrimary else colors.labelSecondary,
                            maxLines = 1,
                            modifier = Modifier
                                .clip(RoundedCornerShape(DshRadius.sm))
                                .clickableNoRipple { onList(crumb.path) }
                                .padding(horizontal = DshSpacing.xs, vertical = DshSpacing.xxs),
                        )
                    }
            }
            // The one invalidation. The endpoint has no watch the tree subscribes
            // to, so a file the session has just written appears only after this.
            Text(
                text = "Reload",
                style = DshType.bodySmall,
                color = colors.link,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .clickableNoRipple(onClick = onReload)
                    .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = DshSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .clip(RoundedCornerShape(DshRadius.md))
                    .clickableNoRipple { showHidden = !showHidden }
                    .padding(horizontal = DshSpacing.xs, vertical = DshSpacing.xxs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Show hidden files",
                    style = DshType.bodySmall,
                    color = if (showHidden) colors.accent else colors.labelTertiary,
                )
                // The check trails the label so the row never shifts when it flips.
                if (showHidden) {
                    Spacer(Modifier.width(DshSpacing.xs))
                    Icon(
                        Icons.Rounded.Check,
                        contentDescription = null,
                        tint = colors.accent,
                        modifier = Modifier.size(12.dp),
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            when {
                state is ExplorerState.Failed -> ExplorerFailure(
                    message = state.message,
                    onRetry = onReload,
                    modifier = Modifier.fillMaxSize(),
                )

                // Loading and Idle both draw the same sentence: there is nothing
                // held for the requested level either way, and the previous
                // level's rows under a new breadcrumb would be a lie.
                listing == null -> ExplorerNote("Loading…", Modifier.fillMaxSize())

                entries.isEmpty() -> ExplorerNote("Empty directory", Modifier.fillMaxSize())

                else -> {
                    val listState = rememberLazyListState()
                    DiagLazyList("files:explorer", listState)
                    LazyColumn(
                        Modifier
                            .fillMaxSize()
                            .diagDrag("files:explorer") { listState.diagOffset() },
                        state = listState,
                    ) {
                        items(entries, key = { it.name }) { entry ->
                            val childPath = listing.childPath(entry)
                            ExplorerRow(
                                entry = entry,
                                badge = badges[childPath],
                                selected = childPath == selectedPath,
                                onDescend = { onList(childPath) },
                                onOpen = { onSelectFile(childPath, badges[childPath]) },
                            )
                        }
                    }
                }
            }
        }

        // The host cut the level at its entry cap; saying so is what stops the
        // missing tail from reading as an empty directory.
        if (listing?.truncated == true) {
            Text(
                text = "Too many entries, showing only some of them.",
                style = DshType.micro,
                color = colors.warn,
                modifier = Modifier.padding(horizontal = DshSpacing.lg, vertical = DshSpacing.xxs),
            )
        }

        // The standing convention, in words. Absence of a mark is a fact about the
        // session, and a reader who has not been told will read it as unknown.
        Text(
            text = "A file with no mark has not been touched by this session.",
            style = DshType.micro,
            color = colors.labelTertiary,
            modifier = Modifier.padding(horizontal = DshSpacing.lg, vertical = DshSpacing.xs),
        )
    }
}

/** One explorer row: kind icon, name, size, and the session's marks. */
@Composable
private fun ExplorerRow(
    entry: WorkspaceEntry,
    badge: SessionFile?,
    selected: Boolean,
    onDescend: () -> Unit,
    onOpen: () -> Unit,
) {
    val colors = DshTheme.colors
    val directory = entry.kind == WorkspaceEntryKind.DIRECTORY
    val openable = entry.kind != WorkspaceEntryKind.OTHER
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(if (selected) colors.sidebarItemActive else colors.bgBase)
            .clickableNoRipple(enabled = openable) { if (directory) onDescend() else onOpen() }
            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.md),
    ) {
        Icon(
            imageVector = if (directory) Icons.Rounded.Folder else fileIcon(entry.name),
            contentDescription = null,
            tint = if (openable) colors.labelTertiary else colors.labelCaption,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = entry.name,
            style = DshType.labelMedium,
            color = if (openable && !entry.hidden) colors.labelPrimary else colors.labelTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (entry.size != null) {
            Text(
                text = formatSize(entry.size),
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                maxLines = 1,
            )
        }
        badge?.let { TouchBadges(it) }
        if (directory) {
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

@Composable
private fun ExplorerNote(text: String, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Box(modifier.padding(DshSpacing.xxl), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** The host's own wording for a refused listing, plus the way to ask again. */
@Composable
private fun ExplorerFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Column(
        modifier.padding(DshSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
    ) {
        Text(
            text = message,
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Retry",
            style = DshType.bodySmall,
            color = colors.link,
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickableNoRipple(onClick = onRetry)
                .padding(DshSpacing.xs),
        )
    }
}

/** The mark set for one touched file, shared by both lists so they cannot drift. */
@Composable
private fun TouchBadges(file: SessionFile, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Text(
        text = file.touches.joinToString(" · ") { touchLabel(it) },
        style = DshType.bodySmall,
        color = if (file.modified) colors.link else colors.labelTertiary,
        maxLines = 1,
        modifier = modifier,
    )
}

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** Nothing touched yet — the panel's own empty state, not the web's "Empty directory". */
@Composable
private fun FilesEmptyState() {
    val colors = DshTheme.colors
    Column(
        Modifier.padding(DshSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
    ) {
        Icon(
            imageVector = Icons.Rounded.InsertDriveFile,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(28.dp),
        )
        Text("No files yet", style = DshType.labelMedium, color = colors.labelSecondary)
        Text(
            text = "Files this session reads, writes, edits, or presents will appear here.",
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/** The touch list: header crumb over a flat, first-seen file order. */
@Composable
private fun FileListPane(
    files: List<SessionFile>,
    root: String?,
    selectedPath: String?,
    onSelect: (SessionFile) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = DshSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (root != null) {
                PathCrumb(root, Modifier.weight(1f))
            } else {
                Text(
                    text = "Files",
                    style = DshType.titleSmall,
                    color = colors.labelPrimary,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                text = if (files.size == 1) "1 file" else "${files.size} files",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                maxLines = 1,
            )
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.borderL1))
        val listState = rememberLazyListState()
        DiagLazyList("files:tree", listState)
        LazyColumn(
            Modifier
                .fillMaxSize()
                .diagDrag("files:tree") { listState.diagOffset() },
            state = listState,
        ) {
            items(files, key = { it.path }) { file ->
                FileRow(file, selected = file.path == selectedPath, onSelect = { onSelect(file) })
            }
        }
    }
}

/** One touched file: name, touch badges, and the path's directory tail. */
@Composable
private fun FileRow(file: SessionFile, selected: Boolean, onSelect: () -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .background(if (selected) colors.sidebarItemActive else colors.bgBase)
            .clickableNoRipple(onClick = onSelect)
            .padding(horizontal = DshSpacing.lg, vertical = DshSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.md),
    ) {
        Icon(
            imageVector = fileIcon(file.path),
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = file.basename,
                style = DshType.labelMedium,
                color = colors.labelPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = shortenPath(directoryOf(file.path)),
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TouchBadges(file)
    }
}

/**
 * The text preview: a path header over a wrapped, line-numbered monospace body.
 *
 * The web's code/plain-text renderers load with `text-pages` and wrap by
 * default, which is why long lines wrap here instead of scrolling sideways.
 * A page the host cut short says so above the body; the panel never scrolls
 * horizontally, so a minified or generated line still reads down the page.
 */
@Composable
private fun PreviewPane(
    path: String?,
    preview: FilePreview?,
    /** True when a loader is wired: an empty first frame reads as "Reading…". */
    loaderWired: Boolean,
    showBack: Boolean,
    onBack: () -> Unit,
    /**
     * Drops the memoized read and asks again. It takes no path because the
     * panel's map is keyed by the transcript's spelling of the selection, which
     * is not always the path on screen — the caller already holds that key.
     */
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val ready = preview as? FilePreview.Ready
    val picture = preview as? FilePreview.Image
    // SVG opens as the drawing; the toggle is the way to the markup. Defaulted per
    // *path* so switching files does not carry the last file's choice over.
    var svgAsImage by rememberSaveable(path) { mutableStateOf(true) }
    val isSvg = ready != null && previewFormatOf(path.orEmpty()) == PreviewFormat.SVG

    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .padding(horizontal = DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DshSpacing.xs),
        ) {
            if (showBack) {
                Icon(
                    imageVector = Icons.Rounded.ArrowBack,
                    contentDescription = "Back to files",
                    tint = colors.labelSecondary,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickableNoRipple(onClick = onBack)
                        .padding(DshSpacing.xs)
                        .size(16.dp),
                )
            }
            if (path != null) PathCrumb(path, Modifier.weight(1f))
            // SVG is both a drawing and a document, and which one the reader wants
            // depends on what they are doing — look at the icon, or read its path.
            // The choice sits here rather than behind a second file row.
            if (isSvg) SvgViewToggle(asImage = svgAsImage, onToggle = { svgAsImage = !svgAsImage })
            ready?.lang?.let { lang ->
                Text(lang, style = DshType.bodySmall, color = colors.labelTertiary, maxLines = 1)
            }
            if (ready != null && ready.lines > 0) {
                CopyPreviewButton(ready.text)
            }
        }
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.borderL1))

        when {
            path == null -> PreviewNote("Select a file to preview.", Modifier.weight(1f).fillMaxWidth())
            preview is FilePreview.Loading -> PreviewNote("Reading…", Modifier.weight(1f).fillMaxWidth())
            // The host's own wording, verbatim: an unsupported type and a missing
            // file read the same way here, as they do in the web's viewer.
            preview is FilePreview.Unavailable -> PreviewFailure(
                preview.message.ifBlank { "Could not read this file." },
                onRetry = onRetry,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            // A picture, which arrived as bytes because the text read refuses anything
            // that is not UTF-8. Decoding is the pane's job: the model layer carries
            // bytes, and this is where the platform's decoder is.
            picture != null -> PicturePreview(
                picture = picture,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            ready == null -> PreviewNote(
                if (loaderWired) {
                    // The fetch effect has not run its first frame yet.
                    "Reading…"
                } else {
                    "Nothing read from this file in this session."
                },
                Modifier.weight(1f).fillMaxWidth(),
            )

            ready.lines == 0 -> PreviewNote("This file is empty.", Modifier.weight(1f).fillMaxWidth())

            isSvg && svgAsImage -> SvgPreview(
                markup = ready.text,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )

            else -> Column(Modifier.fillMaxSize()) {
                // The transcript knows the file's total length; a workspace page
                // only knows that it stopped short of EOF.
                val banner = when {
                    ready.windowed -> "Showing ${ready.lines} of ${ready.totalLines} lines"
                    ready.truncated -> "Showing the first ${ready.lines} lines"
                    else -> null
                }
                if (banner != null) {
                    Text(
                        text = banner,
                        style = DshType.bodySmall,
                        color = colors.labelTertiary,
                        modifier = Modifier.padding(horizontal = DshSpacing.lg, vertical = DshSpacing.xs),
                    )
                }
                val previewState = rememberLazyListState()
                DiagLazyList("files:preview", previewState)
                LazyColumn(
                    Modifier
                        .fillMaxSize()
                        .diagDrag("files:preview") { previewState.diagOffset() },
                    state = previewState,
                ) {
                    items(ready.numbered, key = { it.number }) { line -> PreviewLineRow(line) }
                }
            }
        }
    }
}

/** One numbered line, wrapping rather than truncating (wrap is the web default). */
@Composable
private fun PreviewLineRow(line: PreviewLine) {
    val colors = DshTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = DshSpacing.lg, vertical = 1.dp)) {
        Text(
            text = line.number.toString(),
            style = DshType.codeSmall,
            color = colors.labelTertiary,
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(40.dp).padding(end = DshSpacing.md),
        )
        Text(
            text = line.text.ifEmpty { " " },
            style = DshType.codeSmall,
            color = colors.labelPrimary,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun PreviewNote(text: String, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Box(modifier.padding(DshSpacing.xxl), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * A failed read: the host's own wording, plus a way out.
 *
 * The result is cached so re-selecting does not refetch, which would otherwise
 * strand a transient host failure on screen for the panel's whole life.
 */
@Composable
private fun PreviewFailure(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Column(
        modifier.padding(DshSpacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
    ) {
        Text(
            text = message,
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            textAlign = TextAlign.Center,
        )
        Text(
            text = "Retry",
            style = DshType.bodySmall,
            color = colors.link,
            modifier = Modifier
                .clip(RoundedCornerShape(DshRadius.sm))
                .clickableNoRipple(onClick = onRetry)
                .padding(DshSpacing.xs),
        )
    }
}

/** Directory part in tertiary ink, final segment in full ink — the web's path row. */
@Composable
private fun PathCrumb(path: String, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    val home = shortenPath(path)
    val at = maxOf(home.lastIndexOf('/'), home.lastIndexOf('\\'))
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (at > 0) {
            Text(
                text = home.substring(0, at + 1),
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        Text(
            text = home.substring(at + 1),
            style = DshType.bodySmall,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Same 1000ms "Copy"/"Copied" swap as the other transcript cards. */
@Composable
private fun CopyPreviewButton(text: String) {
    val colors = DshTheme.colors
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1000)
            copied = false
        }
    }
    Text(
        text = if (copied) "Copied" else "Copy",
        style = DshType.bodySmall,
        color = colors.labelSecondary,
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickableNoRipple {
                if (copied) return@clickableNoRipple
                clipboard.setText(AnnotatedString(text))
                copied = true
            }
            .padding(DshSpacing.xs),
    )
}

private fun touchLabel(touch: FileTouch): String = when (touch) {
    FileTouch.READ -> "Read"
    FileTouch.WRITTEN -> "Write"
    FileTouch.EDITED -> "Edit"
    FileTouch.PRESENTED -> "Presented"
}

private fun directoryOf(path: String): String {
    val at = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (at <= 0) path else path.substring(0, at)
}

/**
 * The row's icon, from the same [previewFormatOf] that decides how it is read.
 *
 * It used to keep its own two extension lists, which is how a file could wear an
 * image icon and still be fetched as text: `heic` and `avif` were "images" to the
 * icon and unknown to the reader. One table, one answer.
 */
private fun fileIcon(path: String): ImageVector = when (previewFormatOf(path)) {
    PreviewFormat.TEXT -> Icons.Rounded.Description
    else -> Icons.Rounded.Image
}

/**
 * A raster image, decoded off the main thread.
 *
 * `BitmapFactory` is given the bytes as they arrived and left to subsample nothing:
 * the window the loader fetches is already bounded, and a preview that is a little
 * large is scrollable, whereas one decoded down to a thumbnail is not readable. A
 * decode that fails says so — a misnamed file is previewed as what its extension
 * claims, and this is where that shows up rather than as a blank pane.
 */
@Composable
private fun PicturePreview(picture: FilePreview.Image, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    val bitmap by produceState<ImageBitmap?>(initialValue = null, picture.bytes) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                android.graphics.BitmapFactory
                    .decodeByteArray(picture.bytes, 0, picture.bytes.size)
                    ?.asImageBitmap()
            }.getOrNull()
        }
    }
    Column(modifier) {
        Text(
            text = captionFor(picture, bitmap),
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            modifier = Modifier.padding(horizontal = DshSpacing.lg, vertical = DshSpacing.xs),
        )
        val decoded = bitmap
        if (decoded == null) {
            PreviewNote("Could not decode this image.", Modifier.weight(1f).fillMaxWidth())
        } else {
            val imageScroll = rememberScrollState()
            DiagScrollColumn("files:image", imageScroll)
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(imageScroll)
                    .diagDrag("files:image") { imageScroll.value }
                    .padding(DshSpacing.md),
            ) {
                Image(
                    bitmap = decoded,
                    contentDescription = picture.absolutePath?.substringAfterLast('/'),
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The line above a picture: what it is, how big it is, and how big it draws. */
private fun captionFor(picture: FilePreview.Image, bitmap: ImageBitmap?): String {
    val parts = mutableListOf(picture.format.name.lowercase().replace("jpeg", "jpeg"))
    bitmap?.let { parts += "${it.width}×${it.height}" }
    picture.sizeBytes?.let { parts += formatBytes(it) }
    return parts.joinToString(" · ")
}

private fun formatBytes(bytes: Int): String = when {
    bytes >= 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    bytes >= 1024 -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/**
 * The drawing half of an SVG.
 *
 * Rendered at the pane's own width and left to scroll vertically, because an SVG has
 * no intrinsic resolution to preserve: `AndroidSVG` scales the document, so asking
 * for the pane's width keeps strokes crisp at any zoom the platform applies. The
 * paper is the panel's own background — an SVG's transparent areas are the page's,
 * exactly as in the web viewer, and filling it any other colour would invent a
 * background the document does not have.
 */
@Composable
private fun SvgPreview(markup: String, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    val paper = colors.bgBase
    val bitmap by produceState<ImageBitmap?>(initialValue = null, markup) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val svg = SVG.getFromString(markup)
                val width = 1024f
                val ratio = runCatching { svg.documentHeight / svg.documentWidth }
                    .getOrNull()
                    ?.takeIf { it.isFinite() && it > 0f }
                    ?: 1f
                val height = (width * ratio).coerceIn(32f, 8192f)
                val output = android.graphics.Bitmap.createBitmap(
                    width.toInt(),
                    height.toInt(),
                    android.graphics.Bitmap.Config.ARGB_8888,
                )
                output.eraseColor(paper.toArgb())
                val canvas = android.graphics.Canvas(output)
                svg.setDocumentWidth(width)
                svg.setDocumentHeight(height)
                svg.renderToCanvas(canvas)
                output.asImageBitmap()
            }.getOrNull()
        }
    }
    val decoded = bitmap
    if (decoded == null) {
        PreviewNote("Could not render this SVG — switch to Source to read it.", modifier)
        return
    }
    val svgScroll = rememberScrollState()
    DiagScrollColumn("files:svg", svgScroll)
    Column(
        modifier
            .verticalScroll(svgScroll)
            .diagDrag("files:svg") { svgScroll.value }
            .padding(DshSpacing.md),
    ) {
        Image(
            bitmap = decoded,
            contentDescription = "SVG drawing",
            contentScale = ContentScale.FillWidth,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Image ⇄ Source, for a file that is legitimately both. */
@Composable
private fun SvgViewToggle(asImage: Boolean, onToggle: () -> Unit) {
    val colors = DshTheme.colors
    Text(
        text = if (asImage) "Source" else "Image",
        style = DshType.bodySmall,
        color = colors.link,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(DshRadius.sm))
            .clickableNoRipple(onClick = onToggle)
            .padding(horizontal = DshSpacing.sm, vertical = DshSpacing.xs),
    )
}
