package uk.xa0.dsh.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import uk.xa0.dsh.model.FilePreview
import uk.xa0.dsh.model.FileTouch
import uk.xa0.dsh.model.PreviewLine
import uk.xa0.dsh.model.SessionFile
import uk.xa0.dsh.model.WorkspaceFileLoader
import uk.xa0.dsh.ui.clickableNoRipple
import uk.xa0.dsh.ui.shortenPath
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The session's Files panel: the files the transcript touched, with a numbered
 * text preview for the selected one.
 *
 * This is the app's answer to the web's Files sidebar, but it is transcript
 * derived rather than a live directory tree (`docs/research/files-and-deliverables.md`
 * records why). The composition is deliberately data-in / callback-out so the
 * parent can wire the live read later:
 *
 *  - [files] is `SessionFiles.derive(entries)`.
 *  - [cached] is `SessionFiles.previews(entries)` — content the durable
 *    transcript already carries, keyed by exact path. It is the first choice,
 *    matching the web's resolution order (the session resource answers before
 *    the workspace file), and it works with no host at all.
 *  - [loader] is the on-demand workspace read (`workspaceFiles/read`). It is
 *    asked only for a path the transcript holds nothing for, and its outcome is
 *    memoized for the panel's lifetime, so re-selecting a file never refetches.
 *    Failure renders the host's own wording rather than a substitute.
 *
 * On a phone the list and preview swap in place; from 720dp they sit side by
 * side, which is the desktop reading of the web's two-pane right sidebar.
 */
@Composable
fun FilesPanel(
    files: List<SessionFile>,
    modifier: Modifier = Modifier,
    /** Session cwd, shown as the list's header crumb. */
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
) {
    val colors = DshTheme.colors
    var selected by remember(initialPath) {
        mutableStateOf(initialPath?.takeIf { path -> files.any { it.path == path } })
    }

    // A selected path can vanish when the transcript reloads; drop it rather
    // than previewing a file the list no longer holds.
    LaunchedEffect(files) {
        if (selected != null && files.none { it.path == selected }) selected = null
    }

    // Workspace reads are memoized for the panel's lifetime: re-selecting a file
    // must not refetch, and a failure keeps the host's message on screen until
    // Retry. `Loading` is stored too, so one map lookup answers the body.
    val fetched = remember { mutableStateMapOf<String, FilePreview>() }
    var reloadKey by remember { mutableStateOf(0) }
    // The effect keys on the selection, not on `loader`: a caller that rebuilds
    // the interface each composition must not cancel and restart a live read.
    val currentLoader by rememberUpdatedState(loader)

    LaunchedEffect(selected, reloadKey) {
        val path = selected ?: return@LaunchedEffect
        val read = currentLoader ?: return@LaunchedEffect
        if (cached.containsKey(path) || fetched.containsKey(path)) return@LaunchedEffect
        fetched[path] = FilePreview.Loading
        try {
            fetched[path] = read.load(path)
        } catch (cancelled: CancellationException) {
            // The selection moved on: leave nothing behind, so returning to the
            // file fetches again instead of sticking on "Reading…".
            fetched.remove(path)
            throw cancelled
        } catch (error: Throwable) {
            fetched[path] = FilePreview.Unavailable(
                error.message?.takeIf { it.isNotBlank() } ?: "Could not read this file.",
            )
        }
    }

    val shown: FilePreview? = selected?.let { path -> cached[path] ?: fetched[path] }
    val retry: (String) -> Unit = { path -> fetched.remove(path); reloadKey += 1 }

    if (files.isEmpty()) {
        Box(modifier.fillMaxSize().background(colors.bgBase), contentAlignment = Alignment.Center) {
            FilesEmptyState()
        }
        return
    }

    BoxWithConstraints(modifier.fillMaxSize().background(colors.bgBase)) {
        val twoPane = maxWidth >= 720.dp
        if (twoPane) {
            Row(Modifier.fillMaxSize()) {
                FileListPane(
                    files = files,
                    root = root,
                    selectedPath = selected,
                    onSelect = { file ->
                        selected = file.path
                        onSelect?.invoke(file)
                    },
                    modifier = Modifier.weight(0.42f).fillMaxHeight(),
                )
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
            FileListPane(
                files = files,
                root = root,
                selectedPath = null,
                onSelect = { file ->
                    selected = file.path
                    onSelect?.invoke(file)
                },
                modifier = Modifier.fillMaxSize(),
            )
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
            text = "Files this session reads, writes, or edits will appear here.",
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
        LazyColumn(Modifier.fillMaxSize()) {
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
        Text(
            text = file.touches.joinToString(" · ") { touchLabel(it) },
            style = DshType.bodySmall,
            color = if (file.modified) colors.link else colors.labelTertiary,
            maxLines = 1,
        )
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
    onRetry: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val ready = preview as? FilePreview.Ready
    // The null-path case is the `when`'s first branch, so a retry always has a path.
    val retry: () -> Unit = { path?.let(onRetry) }

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
                onRetry = retry,
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
                LazyColumn(Modifier.fillMaxSize()) {
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
}

private fun directoryOf(path: String): String {
    val at = maxOf(path.lastIndexOf('/'), path.lastIndexOf('\\'))
    return if (at <= 0) path else path.substring(0, at)
}

private fun fileIcon(path: String): ImageVector {
    val lower = path.lowercase()
    val extension = lower.substringAfterLast('.', "")
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
