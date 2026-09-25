package uk.xa0.dsh.ui

import android.widget.Toast
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SubdirectoryArrowRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.PendingKind
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.WorkspaceItem
import uk.xa0.dsh.model.SessionSearchHit
import uk.xa0.dsh.ui.components.DotState
import uk.xa0.dsh.ui.components.DshMark
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.components.StateDot
import uk.xa0.dsh.ui.search.SessionSearchRow
import uk.xa0.dsh.ui.search.SessionSearchRowItem
import uk.xa0.dsh.ui.search.SessionSearchStatus
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** One collapsible group in the sidebar: a Workspace, the flat list, or "Ungrouped". */
private data class Section(
    val key: String,
    val title: String,
    val rows: List<TreeRow>,
)

/** A session plus its nesting depth, so subagents indent under their parent. */
private data class TreeRow(val session: SessionItem, val depth: Int)

/**
 * The session sidebar, ported from the web client's 280dp column.
 *
 * Sessions are grouped by **Workspace** — the host's own directory registry, with
 * its own manual per-workspace order — rather than bucketed by `cwd`. That
 * distinction is load-bearing: a workspace can hold sessions from more than one
 * path, and sessions outside every workspace belong to a bucket the host calls
 * "Ungrouped" (see `ui-workspace/locales.ts`).
 *
 * The view options mirror the host's own: Group by WorkSpace/List and Order by
 * Manual/Last updated, with per-section paging so a 20-session workspace does not
 * bury the rest of the list.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionsDrawer(
    sessions: List<SessionItem>,
    workspaces: List<WorkspaceItem>,
    archivedSessionIds: Set<String>,
    completedSessionIds: Set<String>,
    /**
     * Which blocking interaction each session holds (`AttentionCenter`). A
     * session parked on an approval or a question is neither idle nor ordinary
     * work, and the drawer paints every row at once, so this is a per-session map
     * rather than the single card the chat screen shows.
     */
    pendingInteractions: Map<String, PendingKind> = emptyMap(),
    currentId: String?,
    drawerOpen: Boolean,
    loading: Boolean,
    onSelect: (String) -> Unit,
    onNew: () -> Unit,
    /**
     * Start a session inside one Workspace. The web puts this control on the
     * Workspace row itself (`actions.newSession.aria` = "New session in {name}")
     * rather than offering a bare "new session" and asking where afterwards —
     * which is also what stops a blank being stranded in the Workspace you left.
     */
    onNewInWorkspace: (String) -> Unit = {},
    onRefresh: () -> Unit,
    onSettings: () -> Unit,
    /**
     * The footer's other half. Required like [onSettings]: an About control that
     * silently did nothing would be worse than no control at all.
     */
    onAbout: () -> Unit,
    /**
     * The persisted Group/Order modes. Passed in rather than held here: they are
     * client-local preferences, so the ViewModel owns them and a cold start
     * restores the user's last choice.
     */
    groupByWorkspace: Boolean,
    orderByUpdated: Boolean,
    /**
     * Whether archived sessions are listed. Owned by the caller rather than this
     * composable so it persists: as a local `rememberSaveable` it reset on every
     * restart, unlike Group and Order beside it in the same row.
     */
    showArchived: Boolean,
    /**
     * The Workspace sections the user collapsed, by [Section.key]. Owned by the
     * caller for the same reason as [showArchived]: as a local `remember` it was
     * forgotten on every cold start, so the drawer reopened with every Workspace
     * expanded. Only this collapse state is persisted — the "Show N more" paging
     * and per-session subagent disclosures below stay ephemeral, because they are
     * navigation within one visit rather than a view the user chose to keep.
     */
    collapsedSections: Set<String>,
    onGroupByWorkspace: (Boolean) -> Unit,
    onOrderByUpdated: (Boolean) -> Unit,
    onShowArchived: (Boolean) -> Unit,
    onCollapsedSections: (Set<String>) -> Unit,
    onRename: (String, String) -> Unit = { _, _ -> },
    onFork: (String) -> Unit = {},
    onArchive: (String) -> Unit = {},
    onUnarchive: (String) -> Unit = {},
    onAddWorkspace: () -> Unit = {},
    /**
     * One session's subagent caret opening or closing. The disclosure is the
     * drawer's catalog menu, so the caller reads that session's child catalog when
     * it opens and drops a pull it still owed when it closes — the app's
     * `setSubagentCatalogOpen` (`manager.ts:433-446`). A callback rather than a
     * fetch here because the ViewModel owns every wire read.
     */
    onSubagentCatalogOpen: (String, Boolean) -> Unit = { _, _ -> },
    /**
     * Host content-search hits for the current query (`session/search`), and the
     * callback that asks for them. Reported per keystroke: the ViewModel owns the
     * debounce and the in-flight bookkeeping, because the drawer is recomposed far
     * more often than the user types.
     */
    searchHits: List<SessionSearchHit> = emptyList(),
    searchLoading: Boolean = false,
    searchError: String? = null,
    searchHasMore: Boolean = false,
    onSearch: (String) -> Unit = {},
) {
    val colors = DshTheme.colors
    var query by rememberSaveable { mutableStateOf("") }
    // Section paging ("Show N more") and the per-session subagent disclosure are
    // different namespaces, so they get different sets — and both stay local, as
    // per-visit navigation rather than a persisted preference. The Workspace
    // collapse set beside them is hoisted for exactly that reason.
    var expandedSections by remember { mutableStateOf(emptySet<String>()) }
    var expandedSessions by remember { mutableStateOf(emptySet<String>()) }

    // Deliberately NOT `rememberLazyListState()`: that one is saveable, and because
    // the drawer stays composed while closed, a scroll position left over from a
    // previous run was being restored — so the drawer opened part-way down its
    // list for no visible reason.
    val listState = remember { LazyListState() }

    val matched = remember(sessions, archivedSessionIds, query, showArchived) {
        sessions.filter { session ->
            // Subagents are never top-level rows — the web's `sessionVisible()`
            // excludes them, and here they are reached through their parent's own
            // caret rather than a global "expose subagents" toggle.
            !session.isSubagent &&
                (showArchived || session.id !in archivedSessionIds) &&
                (
                    query.isBlank() ||
                        session.title.contains(query, ignoreCase = true) ||
                        session.cwd.orEmpty().contains(query, ignoreCase = true)
                    )
        }
    }

    // Every keystroke re-asks the ViewModel, which debounces and drops superseded
    // answers; the drawer must not own that, or a fast typist's earlier query
    // would land after the later one.
    LaunchedEffect(query) { onSearch(query) }

    /**
     * The web's merged, flat search list (`deriveSearchResults`): local name and
     * path matches first in the list's own order, then the host's content page for
     * whatever it added, each carrying the label of the Workspace that owns it.
     *
     * A hit whose session the roster does not show is dropped rather than drawn as
     * a bare id: the host searches every session it can see, including subagents
     * and archived ones this drawer is not listing, and the web filters the same
     * way. Name-only matches draw no excerpt, which is what a blank snippet means.
     */
    val searchRows = remember(matched, searchHits, sessions, workspaces, archivedSessionIds, showArchived) {
        val owner = HashMap<String, String>()
        workspaces.forEach { workspace ->
            workspace.sessionIds.forEach { id -> owner[id] = workspace.title }
        }
        val listed = sessions.filter {
            !it.isSubagent && (showArchived || it.id !in archivedSessionIds)
        }.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val rows = ArrayList<SessionSearchRow>(matched.size + searchHits.size)
        matched.forEach { session ->
            if (seen.add(session.id)) {
                rows += SessionSearchRow(
                    sessionId = session.id,
                    title = session.title,
                    workspace = owner[session.id].orEmpty(),
                )
            }
        }
        searchHits.forEach { hit ->
            val session = listed[hit.sessionId] ?: return@forEach
            if (seen.add(hit.sessionId)) {
                rows += SessionSearchRow(
                    sessionId = hit.sessionId,
                    title = session.title,
                    workspace = owner[hit.sessionId].orEmpty(),
                    snippet = hit.snippet,
                )
            }
        }
        rows
    }

    // One walk of the roster supplies every row's activity badge and caret.
    //
    // Deliberately the roster alone, with no `subagents/list` catalogs: the caret
    // here gates on `total`, and the tree it expands is the roster
    // (`subagentChildrenOf`). Flooring the total from a catalog would offer a caret
    // for a child the roster has not delivered — one that opens onto nothing. The
    // per-child `activity` those catalogs carry does reach these rows, through the
    // live-running fold behind `session.running`.
    val rollups = remember(sessions) { indexSubagentRollups(sessions) }

    val sections = remember(
        matched,
        sessions,
        workspaces,
        groupByWorkspace,
        orderByUpdated,
        showArchived,
        expandedSessions,
    ) {
        val byId = matched.associateBy { it.id }
        // Membership is checked against the *unfiltered* list: a group is only
        // ever skipped because the user's own filters emptied it, never because a
        // member the registry still accounts for has not landed in the list yet.
        // Hiding it then looked permanent — the registry does not change again, so
        // nothing re-emitted the section.
        val known = sessions.associateBy { it.id }

        /** One top-level row plus the subagents its caret has disclosed. */
        fun disclose(session: SessionItem): List<TreeRow> {
            val out = ArrayList<TreeRow>()
            val seen = HashSet<String>()
            fun walk(node: SessionItem, depth: Int) {
                if (!seen.add(node.id) || depth > MAX_TREE_DEPTH) return
                out += TreeRow(node, depth)
                if (node.id in expandedSessions) {
                    // A child is a roster row too, so it obeys the same two view
                    // settings as a section's own items. `subagentChildrenOf` is
                    // newest-first; a child has no host manual order, so Manual
                    // reads oldest-first (the section's own Manual order comes
                    // from the Workspace's `sessionIds`).
                    val children = subagentChildrenOf(sessions, node.id)
                        .filter { showArchived || it.id !in archivedSessionIds }
                        .let { kids ->
                            if (orderByUpdated) kids else kids.sortedBy { it.updatedAt }
                        }
                    children.forEach { walk(it, depth + 1) }
                }
            }
            walk(session, 0)
            return out
        }

        if (!groupByWorkspace) {
            return@remember listOf(
                Section(
                    FLAT,
                    "Sessions",
                    matched.sortedByDescending { it.updatedAt }.flatMap { disclose(it) },
                ),
            )
        }

        val claimed = HashSet<String>()
        val result = ArrayList<Section>()

        workspaces.forEach { workspace ->
            var items = workspace.sessionIds.mapNotNull { byId[it] }
            if (orderByUpdated) items = items.sortedByDescending { it.updatedAt }
            claimed += workspace.sessionIds
            // A Workspace with no sessions at all is still a group: "Add
            // workspace…" just created it, and hiding it made the row look inert.
            // A Workspace whose members are all merely filtered out (search,
            // archived, subagents) stays hidden — but one whose members are
            // missing from the list itself does not, because that is a transient
            // the user cannot see or fix.
            val everyMemberKnown = workspace.sessionIds.all { it in known }
            if (items.isNotEmpty() || workspace.sessionIds.isEmpty() || !everyMemberKnown) {
                result += Section(workspace.id, workspace.title, items.flatMap { disclose(it) })
            }
        }

        // Sessions outside every Workspace. The host labels this "Ungrouped"; it
        // has no manual order of its own, so it is always newest-first.
        val rest = matched.filterNot { it.id in claimed }
            .sortedByDescending { it.updatedAt }
        if (rest.isNotEmpty()) {
            result += Section(
                UNGROUPED,
                "Ungrouped",
                rest.flatMap { disclose(it) },
            )
        }
        result
    }

    // Anchor the drawer to the top on open. The old code paged it to the open
    // session instead, which pushed every section above that row off-screen, so
    // the first Workspace (`dsh-android`, owner of the long-running session)
    // looked like it had vanished and every reopen re-pinned it.
    //
    // A plain `remember`-ed `LazyListState` is not enough on its own: Compose's
    // focus system scrolls the previously focused row — normally the open
    // session, since that is the last row the user tapped — back into view when
    // the sheet reopens (verified on the emulator: the drawer opened on the
    // `root` section that owns the open session, not at the top). The top is the
    // one anchor that always shows every group, so the reveal is an explicit
    // scroll to item 0.
    LaunchedEffect(drawerOpen) {
        if (drawerOpen) listState.scrollToItem(0)
    }

    // Rename is the one row verb with a form; the others act immediately.
    var renaming by remember { mutableStateOf<SessionItem?>(null) }

    // The Refresh icon's turn, declared only while a pull is in flight so an idle
    // drawer keeps no animation running.
    val spin = if (loading) {
        rememberInfiniteTransition(label = "refresh").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(durationMillis = 800, easing = LinearEasing)),
            label = "refreshAngle",
        ).value
    } else {
        0f
    }

    renaming?.let { target ->
        RenameDialog(
            initial = target.title,
            onDismiss = { renaming = null },
            onConfirm = { title ->
                renaming = null
                onRename(target.id, title)
            },
        )
    }


    Column(
        Modifier
            .fillMaxSize()
            .background(colors.sidebarFill)
            // The drawer sheet zeroes its window insets so the sidebar can paint to
            // the very top, which otherwise puts the brand row under the status bar
            // and makes its left edge disagree with the New Session button below it.
            .statusBarsPadding()
            // The sheet zeroes its window insets, so the bottom inset is ours to
            // apply: without it the Settings row sits under the gesture bar, where
            // the system swallows the tap and the row is a dead strip.
            .navigationBarsPadding()
            .padding(start = DshSpacing.lg, end = DshSpacing.lg, bottom = DshSpacing.sm),
    ) {
        // Brand lockup — 60dp row, matching the web sidebar.
        Row(
            Modifier
                .fillMaxWidth()
                .height(60.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DshMark(size = 20.dp)
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = "DSH",
                style = DshType.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                color = colors.ink,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Rounded.Refresh,
                contentDescription = "Refresh sessions",
                tint = if (loading) colors.labelDimmed else colors.labelSecondary,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    // Turning while the pull is in flight is the whole feedback
                    // story: the list itself usually comes back unchanged, so
                    // without this the tap had no visible consequence at all.
                    .rotate(if (loading) spin else 0f)
                    .clickableNoRipple(enabled = !loading, onClick = onRefresh)
                    .padding(DshSpacing.sm),
            )
        }

        // New Session — 38dp, hairline border, r12.
        //
        // Only where the per-Workspace `+` cannot do the job: in the flat list
        // there are no Workspace rows to hang it on, and a host with no Workspaces
        // registered has no group at all. Left visible in the grouped case it was
        // the button that created a blank first and asked "where?" second.
        val newShape = RoundedCornerShape(DshRadius.card)
        if (!groupByWorkspace || workspaces.isEmpty()) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(newShape)
                .background(colors.bgLayer1)
                .border(0.5.dp, colors.borderL3, newShape)
                .clickableNoRipple(onClick = onNew),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null, tint = colors.labelPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(DshSpacing.sm))
            Text("New Session", style = DshType.labelLarge, color = colors.labelPrimary)
        }
        }

        Spacer(Modifier.height(DshSpacing.lg))

        DshTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = "Search sessions...",
            minHeight = 40.dp,
        )

        Spacer(Modifier.height(DshSpacing.md))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (matched.isEmpty()) "No matches" else "${matched.size} sessions",
                style = DshType.micro,
                color = colors.labelTertiary,
                modifier = Modifier.weight(1f),
            )
            // Archived is a filter, not a third Group/Order choice, so it is a
            // switch. The whole label+switch is one toggleable checkbox: the row
            // carries the semantics (and the label), and the Switch itself is
            // presentational so a screen reader announces one control.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(DshRadius.sm))
                    .toggleable(
                        value = showArchived,
                        role = Role.Checkbox,
                        onValueChange = onShowArchived,
                    ),
            ) {
                Text(
                    text = "Archived",
                    style = DshType.micro,
                    color = if (showArchived) colors.accent else colors.labelTertiary,
                )
                Spacer(Modifier.width(DshSpacing.sm))
                Switch(
                    checked = showArchived,
                    onCheckedChange = null,
                    modifier = Modifier
                        // Material's switch is 52x32dp with a 48dp touch target -
                        // sized for a settings screen, not for a filter row sitting
                        // among 11sp chips, where it read as the loudest thing in the
                        // drawer. Scaled down it still reads as a switch, and since
                        // the whole label+switch row is the toggle, nothing is lost
                        // by shrinking the drawing rather than the target.
                        .scale(0.62f)
                        .clearAndSetSemantics { },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = colors.bgBase,
                        checkedTrackColor = colors.accent,
                        uncheckedThumbColor = colors.labelTertiary,
                        uncheckedTrackColor = colors.bgLayer1,
                        uncheckedBorderColor = colors.borderL3,
                    ),
                )
            }
        }

        Spacer(Modifier.height(DshSpacing.sm))

        OptionRow("Group") {
            MiniChoice("WorkSpace", groupByWorkspace) { onGroupByWorkspace(true) }
            MiniChoice("List", !groupByWorkspace) { onGroupByWorkspace(false) }
        }
        OptionRow("Order") {
            MiniChoice("Manual", !orderByUpdated) { onOrderByUpdated(false) }
            MiniChoice("Updated", orderByUpdated) { onOrderByUpdated(true) }
        }

        // Adding a directory to the registry belongs with the view controls, not
        // the session list: it changes what the groups *are*, not where a session
        // sits. The web pins the same row below its workspace menu.
        Row(
            Modifier
                .fillMaxWidth()
                .height(28.dp)
                .clip(RoundedCornerShape(DshRadius.md))
                .clickableNoRipple(onClick = onAddWorkspace)
                .padding(horizontal = DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Add,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = "Add workspace…",
                style = DshType.bodyMedium,
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.height(DshSpacing.xs))

        if (loading && sessions.isEmpty()) {
            Text(
                text = "Loading…",
                style = DshType.bodySmall,
                color = colors.labelTertiary,
                modifier = Modifier.padding(DshSpacing.md),
            )
        }

        LazyColumn(
            Modifier.weight(1f),
            state = listState,
            contentPadding = PaddingValues(bottom = DshSpacing.md),
        ) {
            // A query replaces the grouped tree with one flat result list, as the
            // web's browser does: grouping is a way to *find* a session, so it gets
            // out of the way once you are looking for one by name or content.
            if (query.isNotBlank()) {
                searchResults(
                    rows = searchRows,
                    query = query,
                    loading = searchLoading,
                    error = searchError,
                    hasMore = searchHasMore,
                    onSelect = onSelect,
                )
                return@LazyColumn
            }
            sections.forEach { section ->
                val isCollapsed = section.key in collapsedSections
                val isExpanded = section.key in expandedSections
                val shown = when {
                    isExpanded -> section.rows
                    else -> section.rows.take(PAGE_SIZE)
                }
                val hidden = section.rows.size - shown.size

                // Sticky so the workspace a session belongs to stays named while
                // you scroll its rows.
                stickyHeader(key = "h-${section.key}") {
                    // A pinned header has to be opaque: the row it floats over is
                    // still painted underneath it, so a transparent background
                    // collided the workspace name with the session title.
                    ProjectRow(
                        modifier = Modifier.background(colors.sidebarFill),
                        title = section.title,
                        // Top-level sessions only: a disclosed subagent belongs to
                        // its parent's row, it is not another session in the group.
                        count = section.rows.count { it.depth == 0 },
                        collapsed = isCollapsed,
                        onClick = {
                            onCollapsedSections(
                                if (isCollapsed) {
                                    collapsedSections - section.key
                                } else {
                                    collapsedSections + section.key
                                },
                            )
                        },
                        onCreate = section.key
                            .takeIf { it != FLAT && it != UNGROUPED }
                            ?.let { workspaceId -> { onNewInWorkspace(workspaceId) } },
                    )
                }

                if (!isCollapsed) {
                    items(count = shown.size, key = { index -> shown[index].session.id }) { index ->
                        val row = shown[index]
                        SessionRow(
                            session = row.session,
                            depth = row.depth,
                            selected = row.session.id == currentId,
                            archived = row.session.id in archivedSessionIds,
                            completedSessionIds = completedSessionIds,
                            pending = pendingInteractions[row.session.id],
                            rollup = rollups[row.session.id],
                            expanded = row.session.id in expandedSessions,
                            onToggleSubagents = {
                                val expanding = row.session.id !in expandedSessions
                                expandedSessions = if (expanding) {
                                    expandedSessions + row.session.id
                                } else {
                                    expandedSessions - row.session.id
                                }
                                onSubagentCatalogOpen(row.session.id, expanding)
                            },
                            onClick = { onSelect(row.session.id) },
                            onRename = { renaming = row.session },
                            onFork = { onFork(row.session.id) },
                            onArchive = { onArchive(row.session.id) },
                            onUnarchive = { onUnarchive(row.session.id) },
                        )
                    }

                    if (hidden > 0 || isExpanded) {
                        item(key = "more-${section.key}") {
                            ShowMoreRow(
                                label = if (isExpanded) {
                                    "Show less"
                                } else {
                                    "Show $hidden more session${if (hidden == 1) "" else "s"}"
                                },
                                onClick = {
                                    expandedSections = if (isExpanded) {
                                        expandedSections - section.key
                                    } else {
                                        expandedSections + section.key
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.borderL1),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Settings and About are a pair of small, secondary actions, so they
            // split the footer instead of each taking a 48dp row of its own —
            // stacking them would read as two more list items rather than chrome.
            FooterAction(
                icon = Icons.Rounded.Settings,
                label = "Settings",
                onClick = onSettings,
            )
            // Flush to the drawer's right edge rather than centred in its own half:
            // a centred pair left both labels floating a third of the way in, which
            // read as two stray items instead of a footer with two ends.
            Spacer(Modifier.weight(1f))
            FooterAction(
                icon = Icons.Rounded.Info,
                label = "About",
                onClick = onAbout,
            )
        }
    }
}

/**
 * One half of the drawer's footer pair: icon + label on one tap target.
 *
 * The whole half is clickable — the row the icon used to sit in made only the
 * glyph tappable, so the label beside it looked live and was not.
 */
@Composable
private fun FooterAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    Row(
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = colors.labelSecondary,
            modifier = Modifier
                .size(28.dp)
                .padding(DshSpacing.sm),
        )
        Spacer(Modifier.width(DshSpacing.md))
        Text(
            text = label,
            style = DshType.bodyMedium,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The search branch's rows: the merged result list plus the web's status lines.
 *
 * An extension on [LazyListScope] rather than a `Column` inside an `item`, so the
 * rows stay lazily composed and the drawer keeps the one scrollable it already
 * owns — nesting a second vertical scroller is a crash.
 */
private fun LazyListScope.searchResults(
    rows: List<SessionSearchRow>,
    query: String,
    loading: Boolean,
    error: String?,
    hasMore: Boolean,
    onSelect: (String) -> Unit,
) {
    items(count = rows.size, key = { index -> rows[index].sessionId }) { index ->
        // Tap opens the session only: `session/search` answers with a session id
        // and no seq, so there is no position inside the transcript to reveal.
        SessionSearchRowItem(row = rows[index], query = query, onResultClick = onSelect)
    }
    item(key = "search-status") {
        SessionSearchStatus(
            loading = loading,
            error = error,
            empty = rows.isEmpty(),
            hasMore = hasMore,
        )
    }
}

/**
 * Workspace header: 34dp row. The web row is **folder + title** — the disclosure
 * chevron and create button are hover-revealed there, so the open/closed folder is
 * the resting affordance, and tapping anywhere on the row toggles the group.
 */
@Composable
private fun ProjectRow(
    title: String,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** Null for the buckets with no Workspace behind them: the flat list and Ungrouped. */
    onCreate: (() -> Unit)? = null,
) {
    val colors = DshTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(onClick = onClick)
            .padding(end = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (collapsed) Icons.Rounded.Folder else Icons.Rounded.FolderOpen,
            contentDescription = if (collapsed) "Expand workspace" else "Collapse workspace",
            tint = colors.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = title,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count.toString(),
            style = DshType.micro,
            color = colors.labelCaption,
        )
        if (onCreate != null) {
            Spacer(Modifier.width(DshSpacing.sm))
            // Always drawn, not hover-revealed: the web shows this on hover, and a
            // phone has no hover — the same reason the group's own disclosure is
            // the folder rather than a chevron that only appears under a pointer.
            Icon(
                Icons.Rounded.Add,
                contentDescription = "New session in $title",
                tint = colors.labelSecondary,
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .clickableNoRipple(onClick = onCreate)
                    .padding(DshSpacing.xs),
            )
        }
    }
}

/** Per-section paging row — 28dp, matching `sessions.expand` in the web list. */
@Composable
private fun ShowMoreRow(label: String, onClick: () -> Unit) {
    val colors = DshTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(start = DshSpacing.lg)
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(onClick = onClick)
            .padding(start = DshSpacing.xl),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = DshType.bodySmall, color = colors.labelTertiary)
    }
}

@Composable
private fun SessionRow(
    session: SessionItem,
    depth: Int,
    selected: Boolean,
    archived: Boolean,
    completedSessionIds: Set<String>,
    /** The blocking interaction this session holds, if any; see [PendingKind]. */
    pending: PendingKind?,
    /** Subagent descendants of this session; null when it has none. */
    rollup: SubagentRollup?,
    expanded: Boolean,
    onToggleSubagents: () -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.md)
    Row(
        Modifier
            .fillMaxWidth()
            .height(32.dp)
            // Subagents nest one indent step per level under their parent.
            .padding(start = DshSpacing.lg + (depth * 14).dp)
            .clip(shape)
            .background(if (selected) colors.sidebarItemActive else colors.sidebarFill)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(16.dp)
                // The web puts each label on the dot as screen-reader text
                // (`SessionStatusDots`, `rows/Rows.tsx:271-281`); there is no hover
                // card here to reveal it, so the spoken label is the only place the
                // kind is nameable. The amber itself is the actionable half — it says
                // "open this row" — and the row is too tight for a visible chip.
                .then(
                    if (pending == null) Modifier
                    else Modifier.semantics { contentDescription = pending.label },
                ),
            contentAlignment = Alignment.Center,
        ) {
            // The dot is gated exactly like the web row:
            // `showStatus = primaryStatus.state !== 'done' || row.completed`.
            // A plain idle session therefore shows no dot at all — only running
            // (chase) and finished-but-unseen (green) do.
            //
            // The chase means *this* session's own turn is running. The web's
            // `sessionStatuses()` also returns a separate `ongoing` status for
            // `runningSubagentCount` (`rows/Rows.tsx`, labelled `{n} subagents
            // running`), and painting that onto the same dot made an idle parent
            // with a live subagent impersonate an active turn. The count drawn beside
            // the dot is this client's rendering of that second status (a phone has no
            // hover or aria label to carry it), so descendant activity stays reported
            // and the own-turn dot stays honest.
            //
            // Pending outranks running, exactly as the web's precedence does
            // (`rows/Rows.tsx:261-264`): a session blocked on a human is *waiting*,
            // whatever the agent registry still reports about its turn.
            val dot = when {
                pending != null -> DotState.WARNING
                session.running -> DotState.ONGOING
                session.id in completedSessionIds -> DotState.DONE
                else -> null
            }
            when {
                dot != null -> StateDot(state = dot, size = 10.dp)
                // Nested rows keep the "member of" glyph when they have no status
                // of their own. The user explicitly likes it; the earlier problem
                // was that activity was not being shown, not the glyph.
                depth > 0 -> Icon(
                    Icons.Rounded.SubdirectoryArrowRight,
                    contentDescription = "Subagent session",
                    tint = colors.labelCaption,
                    modifier = Modifier.size(12.dp),
                )
            }
        }
        // The desktop carries the count in an aria label; a phone has no such
        // layer, so the running-descendant count is drawn next to the dot, with
        // the rollup's own sentence as its accessibility copy. Deliberate small
        // divergence.
        if (rollup != null && rollup.running > 0) {
            Text(
                text = rollup.running.toString(),
                style = DshType.micro,
                color = colors.accent,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 1.dp)
                    .semantics { contentDescription = rollup.runningLabel ?: rollup.description },
            )
        }
        Spacer(Modifier.width(DshSpacing.xs))
        Text(
            text = session.title,
            style = DshType.bodyLarge.copy(
                fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal,
            ),
            color = when {
                selected -> colors.labelPrimary
                archived -> colors.labelCaption
                else -> colors.labelSecondary
            },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // Per-session subagent disclosure: only a session that actually has
        // descendants gets a caret, so the rest of the list stays quiet.
        if (rollup != null && !rollup.isEmpty) {
            Icon(
                imageVector = if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "Hide subagents" else "Show subagents",
                tint = colors.labelTertiary,
                modifier = Modifier
                    .size(18.dp)
                    .clip(CircleShape)
                    .clickableNoRipple(onClick = onToggleSubagents),
            )
        }
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = relativeTime(session.updatedAt),
            style = DshType.bodySmall,
            color = colors.labelTertiary,
            maxLines = 1,
        )
        // The web reveals these on hover, which does not exist on a phone, so the
        // row carries its own affordance. Three verbs, exactly as the web's menu:
        // no delete, no duplicate, no pin.
        SessionRowMenu(
            id = session.id,
            archived = archived,
            onRename = onRename,
            onFork = onFork,
            onArchive = onArchive,
            onUnarchive = onUnarchive,
        )
    }
}

/** The row's trailing overflow: rename, fork, copy id, and archive (or restore). */
@Composable
private fun SessionRowMenu(
    id: String,
    archived: Boolean,
    onRename: () -> Unit,
    onFork: () -> Unit,
    onArchive: () -> Unit,
    onUnarchive: () -> Unit,
) {
    val colors = DshTheme.colors
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    var open by remember { mutableStateOf(false) }
    Box {
        Icon(
            Icons.Rounded.MoreVert,
            contentDescription = "Session actions",
            tint = colors.labelTertiary,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .clickableNoRipple { open = true }
                .padding(DshSpacing.xxs),
        )
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
        ) {
            DropdownMenuItem(
                text = { Text("Rename", style = DshType.bodyMedium, color = colors.labelPrimary) },
                onClick = { open = false; onRename() },
            )
            DropdownMenuItem(
                // The host forks at the session's last completed turn, so there is
                // nothing to ask the user — the web's item is one tap too.
                text = { Text("Fork session", style = DshType.bodyMedium, color = colors.labelPrimary) },
                onClick = { open = false; onFork() },
            )
            DropdownMenuItem(
                // The id is what every other surface (logs, `session/page`, the host's
                // own CLI, a bug report) needs, and it is not visible anywhere in the
                // UI — so the menu hands it over whole rather than making the user
                // reconstruct it from a truncated title.
                text = { Text("Copy session id", style = DshType.bodyMedium, color = colors.labelPrimary) },
                onClick = {
                    open = false
                    clipboard.setText(AnnotatedString(id))
                    Toast.makeText(context, "Session id copied", Toast.LENGTH_SHORT).show()
                },
            )
            DropdownMenuItem(
                text = {
                    Text(
                        text = if (archived) "Restore" else "Archive",
                        style = DshType.bodyMedium,
                        color = colors.labelPrimary,
                    )
                },
                onClick = {
                    open = false
                    if (archived) onUnarchive() else onArchive()
                },
            )
        }
    }
}

/** Rename form — the only row verb that needs one. */
@Composable
private fun RenameDialog(
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    val colors = DshTheme.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.menu,
        title = { Text("Rename session", style = DshType.labelLarge, color = colors.labelPrimary) },
        text = {
            DshTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Session title",
                minHeight = 44.dp,
            )
        },
        confirmButton = {
            Text(
                text = "Save",
                style = DshType.labelLarge,
                color = if (text.isBlank()) colors.labelTertiary else colors.accent,
                modifier = Modifier
                    .clickableNoRipple(enabled = text.isNotBlank()) { onConfirm(text.trim()) }
                    .padding(DshSpacing.md),
            )
        },
        dismissButton = {
            Text(
                text = "Cancel",
                style = DshType.labelLarge,
                color = colors.labelSecondary,
                modifier = Modifier
                    .clickableNoRipple(onClick = onDismiss)
                    .padding(DshSpacing.md),
            )
        },
    )
}

@Composable
private fun OptionRow(label: String, content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(26.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = DshType.micro,
            color = DshTheme.colors.labelCaption,
            modifier = Modifier.width(44.dp),
        )
        content()
    }
}

@Composable
private fun MiniChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    val colors = DshTheme.colors
    Box(
        Modifier
            .padding(end = DshSpacing.lg)
            .clip(RoundedCornerShape(DshRadius.sm))
            .background(if (selected) colors.accentTertiary else colors.sidebarFill)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xxs),
    ) {
        Text(
            text = label,
            style = DshType.micro,
            color = if (selected) colors.accent else colors.labelTertiary,
        )
    }
}

private const val UNGROUPED = "__ungrouped__"
private const val FLAT = "__flat__"

/** How many sessions a collapsed section shows before "Show N more". */
private const val PAGE_SIZE = 6

/** Guard against a pathological subagent lineage; the host caps depth far below. */
private const val MAX_TREE_DEPTH = 4

private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())
private val dayFormat = SimpleDateFormat("d MMM", Locale.getDefault())

/** Compact relative timestamp, in the style of the web session list. */
internal fun relativeTime(millis: Long): String {
    if (millis <= 0L) return ""
    val now = System.currentTimeMillis()
    val delta = now - millis
    return when {
        delta < 60_000L -> "now"
        delta < 3_600_000L -> "${delta / 60_000L}m"
        isSameDay(millis, now) -> timeFormat.format(Date(millis))
        delta < 7 * 86_400_000L -> "${delta / 86_400_000L}d"
        else -> dayFormat.format(Date(millis))
    }
}

private fun isSameDay(a: Long, b: Long): Boolean {
    val calA = Calendar.getInstance().apply { timeInMillis = a }
    val calB = Calendar.getInstance().apply { timeInMillis = b }
    return calA.get(Calendar.YEAR) == calB.get(Calendar.YEAR) &&
        calA.get(Calendar.DAY_OF_YEAR) == calB.get(Calendar.DAY_OF_YEAR)
}
