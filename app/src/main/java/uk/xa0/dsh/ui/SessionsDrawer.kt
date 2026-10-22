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
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Inbox
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import uk.xa0.dsh.PendingKind
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.WorkspaceItem
import uk.xa0.dsh.model.DrawerGrouping
import uk.xa0.dsh.model.WorkspaceTreeNode
import uk.xa0.dsh.model.holdOrder
import uk.xa0.dsh.model.scheduledTaskMarkLabel
import uk.xa0.dsh.model.workspaceForest
import uk.xa0.dsh.model.SessionSearchHit
import uk.xa0.dsh.model.WorkspaceCopy
import uk.xa0.dsh.model.WorkspaceGroupState
import uk.xa0.dsh.model.WorkspaceListPhase
import uk.xa0.dsh.model.WorkspaceRunState
import uk.xa0.dsh.model.liveWorkIds
import uk.xa0.dsh.model.placeholder
import uk.xa0.dsh.model.ungroupedTitle
import uk.xa0.dsh.model.workspaceCountLabel
import uk.xa0.dsh.model.workspaceGroupState
import uk.xa0.dsh.model.workspaceRunState
import uk.xa0.dsh.diag.DiagLazyList
import uk.xa0.dsh.diag.ScrollDiag
import uk.xa0.dsh.diag.diagDrag
import uk.xa0.dsh.diag.diagOffset
import uk.xa0.dsh.ui.components.DotState
import uk.xa0.dsh.ui.components.DshMark
import uk.xa0.dsh.ui.components.DshTextField
import uk.xa0.dsh.ui.components.StateDot
import uk.xa0.dsh.ui.components.LocalAnimatedDots
import uk.xa0.dsh.ui.search.SessionSearchCopy
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

/**
 * One collapsible group in the sidebar: a Workspace, the flat list, or "Ungrouped".
 *
 * [icon] is null for every group that has a *place* rather than a *kind* — a
 * Workspace, the flat list, Ungrouped — and those keep the folder pair they have
 * always had. Only the catch-all names an icon of its own (an inbox, for sessions
 * outside every directory). There is deliberately no section for sessions a schedule
 * delivers into: the host's Schedule subsystem marks such a row in place rather than
 * separating it, so the drawer draws the mark and leaves the row where it belongs.
 */
private data class Section(
    val key: String,
    val title: String,
    val rows: List<TreeRow>,
    val icon: ImageVector? = null,
    /**
     * The Workspace a new session would be created in, or null for a group that is not
     * a directory.
     *
     * This is what decides the header's `+`. It used to be a blacklist of synthetic
     * keys, which meant a synthetic group offered a `+` that would have asked the host
     * for a session in a Workspace whose name no registry has. A group draws the button
     * only when it can say where the session goes.
     */
    val createIn: String? = null,
    /**
     * Why a Workspace group draws the rows it does, or null for the groups that are not
     * a Workspace (the flat list and the catch-all). Those two exist only when they have
     * rows, so their count is always a fact.
     *
     * It is carried rather than recomputed at draw time because the two facts it decides
     * belong *only* to Workspaces: what a zero-row group says, and whether it may print a
     * count at all. See `model/WorkspaceGroups.kt`.
     */
    val state: WorkspaceGroupState? = null,
    /**
     * Whether this group holds the session on screen — the web's `containsCurrent`
     * (`tree.d.ts`), which tints that Workspace's folder. A place is easier to find when
     * the sidebar says which one you are in.
     */
    val containsCurrent: Boolean = false,
    /**
     * Whether this group holds live work.
     *
     * A second, independent fact from [containsCurrent], and deliberately drawn on a second
     * element: the folder glyph carries "the open session is in here" and the title carries
     * "something here is running", so all four combinations of the two are distinguishable on
     * screen — two glyph states times the two [WorkspaceRunState]s.
     *
     * There is no third, "we cannot tell" title colour. An accounted member the roster never
     * delivered is *absent* for this question, and the incompleteness of the membership is
     * said in the header's count instead ([countLabel]): a dimmed title was a colour whose
     * legend was nowhere on screen, which is not a signal. See `model/WorkspaceGroups.kt`.
     *
     * Required rather than defaulted, so a group added later cannot quietly claim [WorkspaceRunState.Idle].
     */
    val run: WorkspaceRunState,
    /**
     * The header's count column, already decided: the sessions drawn under this header, or
     * "[shown] of [accounted]" when the roster has not delivered every member the registry
     * accounts. Null for the groups that are not a Workspace (the flat list and the catch-all),
     * whose count is always the rows drawn. See `workspaceCountLabel`.
     */
    val countLabel: String? = null,
    /**
     * How many registered Workspaces this one is nested under, in the web's Workspace
     * Tree; 0 in every other mode and for the groups that are not Workspaces.
     *
     * This is a count of *registered ancestors*, not of path segments, and it is what
     * the header and its session rows indent by. The web indents only the contents
     * (`--dsh-workspace-indent`), leaving the row's fill and hit target full width.
     */
    val depth: Int = 0,
    /**
     * The Workspaces registered beneath this one, in registry order.
     *
     * The web's `renderGroup` draws a group's children *before* the group's own
     * sessions (`children: childRows` precedes the session rows in `lib/client.js`),
     * so the drawer walks this list between the header and [rows]. Empty in the
     * sibling-Workspace and flat modes, where every group is a root.
     */
    val children: List<Section> = emptyList(),
    /**
     * Whether the session on screen lives in a *descendant* Workspace — the web's
     * `containsCurrentDescendant`, passed to an ancestor's header so its folder stays
     * highlighted even when the descendant (or the ancestor itself) is folded.
     *
     * Kept separate from [containsCurrent] because the two answer different questions
     * ("this directory holds the open session" versus "something below it does") and
     * are combined only at the point that paints the glyph.
     */
    val containsCurrentDescendant: Boolean = false,
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
 * The view options mirror the host's own: Group by WorkSpace / Workspace Tree / List
 * and Order by Manual/Last updated, with per-section paging so a 20-session
 * workspace does not bury the rest of the list. **Workspace Tree nests Workspaces,
 * not sessions** — each Workspace sits under its nearest *registered* ancestor
 * directory and keeps its own sessions, exactly as the web's "Workspace Tree" does.
 * See `model/WorkspaceTree.kt` for the source reading and the derivation.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SessionsDrawer(
    sessions: List<SessionItem>,
    workspaces: List<WorkspaceItem>,
    /**
     * Whether [workspaces] is the host's answer yet. The registry is streamed, so an
     * empty list has two meanings — no Workspaces registered, or no baseline received —
     * and this is what keeps them apart on screen.
     */
    workspacesPhase: WorkspaceListPhase,
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
    /**
     * Why the last roster pull failed, in the app's own words, or null when the
     * list is current.
     *
     * Drawn above the list rather than left to the log: a failed pull keeps the
     * previous rows (a refresh failing is not evidence that the sessions are
     * gone), and a drawer showing yesterday's sessions with nothing said is worse
     * than an error. See [uk.xa0.dsh.model.sessionListErrorNote].
     */
    sessionsError: String? = null,
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
     * Opens the host-wide Automation tasks sheet, or null to draw no entry.
     *
     * Optional because the drawer is also composed by tests and by surfaces that do
     * not own the host's schedule catalogue; a null here draws no row rather than a
     * row that does nothing. Like About, this is a place you go, not a preference —
     * so it sits in the footer beside them rather than inside Settings.
     */
    onAutomations: (() -> Unit)? = null,
    /**
     * How many schedules the host says will fire again. Zero draws no count.
     *
     * Deliberately *active* only: an ended task is still listed on the sheet, but it
     * is not running, and a badge that counted it would say otherwise.
     */
    activeScheduleTotal: Int = 0,
    /**
     * The persisted Group/Order modes. Passed in rather than held here: they are
     * client-local preferences, so the ViewModel owns them and a cold start
     * restores the user's last choice.
     *
     * [grouping] is the web's own three-way choice rather than a pair of booleans,
     * because "flat" and "tree" are alternatives, not independent switches.
     */
    grouping: DrawerGrouping,
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
    onGrouping: (DrawerGrouping) -> Unit,
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
    // Deliberately the roster alone, with no `subagentCatalog` projections: the caret
    // here gates on `total`, and the tree it expands is the roster
    // (`subagentChildrenOf`). Flooring the total from a catalog would offer a caret
    // for a child the roster has not delivered — one that opens onto nothing. The
    // catalogs carry no activity at all, so nothing here has to avoid folding one:
    // `session.running` is the single authority's answer (`RunningBook`).
    val rollups = remember(sessions) { indexSubagentRollups(sessions) }

    // Where the drawer opens.
    //
    // Remembering the raw index does not work, and the recorder shows why: the roster
    // arrives in chunks, so at startup the drawer's list is briefly nine rows and the
    // full one lands after. Everything added *above* the anchor pushes the reader down
    // the list — measured, 46 rows arriving took the first visible row from 0 to 36, so
    // the drawer opened two thirds of the way down with nothing on screen to explain
    // it, and it looked like it had scrolled itself to the bottom.
    //
    // A position is therefore only remembered once the reader has actually chosen one.
    // Until they touch the drawer it opens at the top, and after that it opens where
    // they left it.
    val drawerTouch = rememberTouchGate()
    var drawerTouched by remember { mutableStateOf(false) }
    var drawerAt by remember { mutableStateOf(0) }
    LaunchedEffect(drawerTouch) {
        snapshotFlow { drawerTouch.isDown }.collect { if (it) drawerTouched = true }
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }.collect { index ->
            if (drawerTouched) drawerAt = index
        }
    }
    LaunchedEffect(drawerOpen) {
        if (!drawerOpen) return@LaunchedEffect
        // Do not move a list a finger is on. Opening the drawer is a swipe, so the
        // hand that opened it is routinely still on the screen when this runs, and the
        // recorder found it: nine of twelve "the app moved the list under a finger"
        // records were this restore, moving one to fifty pixels while the thumb that
        // had just swiped the drawer open was still resting in it.
        snapshotFlow { drawerTouch.isDown }.first { !it }
        ScrollDiag.prog("drawer", "open-restore")
        listState.scrollToItem(drawerAt)
    }

    // The roster's order is held still while the drawer is open. It is refreshed
    // from the live list whenever the drawer is *shut*, so it is current the moment
    // it opens, and frozen for as long as somebody is reading it — a session that
    // receives a message jumps to the top of its group otherwise, under the reader's
    // eye. See [holdOrder] for why membership is not held with it.
    var heldOrder by remember { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(drawerOpen, sessions) {
        if (!drawerOpen) heldOrder = sessions.map { it.id }
    }

    val sections = remember(
        matched,
        sessions,
        workspaces,
        workspacesPhase,
        currentId,
        grouping,
        orderByUpdated,
        showArchived,
        expandedSessions,
        heldOrder,
        // Derived from `sessions` like the rest, and named so a group's run state cannot be
        // read from a rollup computed against a different roster.
        rollups,
    ) {
        val byId = matched.associateBy { it.id }
        // Membership is checked against the *unfiltered* list: a group is only
        // ever skipped because the user's own filters emptied it, never because a
        // member the registry still accounts for has not landed in the list yet.
        // Hiding it then looked permanent — the registry does not change again, so
        // nothing re-emitted the section.
        val known = sessions.associateBy { it.id }

        // Every id the roster reports as live work, which is what a group header's blue is
        // read from. `SessionItem.running` is `RunningBook`'s answer and the only liveness
        // source; the journal fold `subagentTiming.active` is deliberately not consulted
        // (`model/LiveRunning.kt`). The rollup half is what keeps a *delegated* run visible:
        // a subagent is never a Workspace member, so a folder whose only live work is a
        // child would otherwise have no running member to tint on. The row already draws
        // that same fact as its descendant count, in the same accent.
        val live = liveWorkIds(sessions, rollups.filterValues { it.running > 0 }.keys)

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
                            if (orderByUpdated) holdOrder(kids, heldOrder) else kids.sortedBy { it.updatedAt }
                        }
                    children.forEach { walk(it, depth + 1) }
                }
            }
            walk(session, 0)
            return out
        }

        // A session a schedule delivers into is *not* lifted out. Under the old
        // third-party plugin each run was its own throwaway session, and lifting those
        // out of the Workspaces kept fifty of them from turning a directory into a log.
        // The host's Schedule subsystem reused the reader's own session instead, so such
        // a row is an ordinary member of its directory and belongs there; the drawer
        // marks it with a clock (see [SessionRow]) exactly as the web does, and nothing
        // is separated, hidden or reordered. `conversations` is therefore the matched
        // roster unchanged, and there is no automation section.
        val conversations = matched

        if (!grouping.groupsByWorkspace) {
            val ordered = holdOrder(conversations, heldOrder)
            val sections = arrayListOf(
                Section(
                    key = FLAT,
                    title = "Sessions",
                    rows = ordered.flatMap { disclose(it) },
                    run = workspaceRunState(ordered.map { it.id }, live),
                ),
            )
            return@remember sections
        }

        val claimed = HashSet<String>()

        /**
         * One registered Workspace as a [Section], with its nested Workspaces built
         * first so this header can see what they found.
         *
         * Every Workspace the registry reports is drawn, always: a Workspace whose
         * members are all hidden by the Archived switch (or by the subagent rule) is
         * still a directory that exists, and hiding it meant a persisted filter could
         * erase it from the sidebar with nothing on screen to say why. That is a
         * deliberate divergence from the web's `deriveGroups`, which drops a group
         * with no visible members; see `model/WorkspaceGroups.kt` and
         * `docs/PARITY.md`. It also means the web's re-parenting of a dropped
         * Workspace's children to the nearest *shown* ancestor has no work to do here
         * — every parent is shown.
         *
         * The three zero-row cases are not the same claim, and the sidebar must not
         * draw them alike: "this directory accounts for no session" is a fact the
         * registry supplied, "its sessions have not arrived" is not, and "its sessions
         * are all filtered out" is a fact about this drawer rather than about the
         * directory. [workspaceGroupState] keeps them apart and the group header plus
         * its placeholder say which one it is.
         *
         * A tap keeps the one meaning a header has everywhere else — fold/unfold — so
         * nothing here reveals the hidden members: on this client's own registry
         * turning Archived on pours hundreds of rows into the list, and a folder glyph
         * that sometimes folds and sometimes rewrites a global filter is two controls
         * wearing one icon. The Archived switch beside the query box is the control for
         * that, and it is already labelled.
         */
        fun buildWorkspace(node: WorkspaceTreeNode): Section {
            val workspace = node.workspace
            val claimedIds = workspace.sessionIds
            var items = claimedIds.mapNotNull { byId[it] }
            // Manual order is the host's (`workspace.sessionIds`) and does not move
            // on its own, so only the newest-first view needs holding.
            if (orderByUpdated) items = holdOrder(items, heldOrder)
            claimed += claimedIds
            val children = node.children.map { buildWorkspace(it) }
            val ownRun = workspaceRunState(claimedIds, live)
            // A folded ancestor hides the child Workspace whose blue would otherwise
            // be the only sign of live work, so the header takes its subtree's answer.
            // This is `liveWorkIds`'s existing argument one level further up: the same
            // reason a running subagent already lights the folder it was delegated
            // from. The *count* stays the group's own members, because that is what
            // the number beside the header has always counted and what `workspaceCountLabel`
            // reports; a subtree total would count one session under two headers.
            val subtreeRunning = ownRun == WorkspaceRunState.Running ||
                children.any { it.run == WorkspaceRunState.Running }
            return Section(
                key = workspace.id,
                title = workspace.title,
                rows = items.flatMap { disclose(it) },
                createIn = workspace.id,
                state = workspaceGroupState(
                    memberIds = claimedIds,
                    visibleIds = items.map { it.id },
                    knownIds = known.keys,
                ),
                containsCurrent = currentId != null && currentId in claimedIds,
                // The web's `containsCurrentDescendant`: an ancestor's folder stays
                // highlighted while a descendant holds the open session, even with
                // the ancestor folded.
                containsCurrentDescendant = children.any {
                    it.containsCurrent || it.containsCurrentDescendant
                },
                // Read from the members the *section* accounts for. Unfiltered on
                // purpose: a running member the Archived switch has hidden still makes
                // this directory a place where work is happening. A member the roster never
                // delivered is absent for this question — see `workspaceRunState`.
                run = if (subtreeRunning) WorkspaceRunState.Running else WorkspaceRunState.Idle,
                // The one place incomplete membership is stated. Decided from the same
                // unfiltered membership as the run state, and drawn whether the group is folded
                // or open, so the reader never has to open a directory to learn that the client
                // is not showing all of it.
                countLabel = workspaceCountLabel(items.size, claimedIds, known.keys),
                depth = node.depth,
                children = children,
            )
        }

        // In the sibling mode every Workspace is its own root, in registry order; in
        // tree mode the forest is the web's `parents` map walked from its roots. The
        // two modes share the same Section, derivation, collapse set and rows — only
        // the parent links differ, which is why this is one engine rather than two.
        val roots = if (grouping.nestsWorkspaces) {
            workspaceForest(workspaces)
        } else {
            workspaces.map { WorkspaceTreeNode(it, 0, emptyList()) }
        }
        val result = ArrayList<Section>(roots.size + 1)
        roots.forEach { root -> result += buildWorkspace(root) }

        // Sessions outside every Workspace. The host labels this "Ungrouped" — a claim
        // that needs the registry, because until the baseline lands a session on this
        // screen may well belong to a Workspace this client has not been told about.
        // It has no manual order of its own, so it is always newest-first. In tree mode
        // it is a root after the registered Workspaces, as the web's `groupByWorkspace`
        // appends it; it is not nested, because no registered directory owns it.
        val rest = holdOrder(conversations.filterNot { it.id in claimed }, heldOrder)
        if (rest.isNotEmpty()) {
            result += Section(
                key = UNGROUPED,
                title = ungroupedTitle(workspacesPhase),
                rows = rest.flatMap { disclose(it) },
                // Sessions outside every Workspace are still *this* machine's — the host
                // files them under a bucket rather than a directory, and the glyph says
                // so instead of showing a folder that names nothing.
                icon = Icons.Rounded.Inbox,
                run = workspaceRunState(rest.map { it.id }, live),
            )
        }
        result
    }

    // The drawer keeps its place. It does not anchor, and it does not move on.
    //
    // It used to `scrollToItem(0)` on every open — first because the focus system
    // dragged the previously focused row into view on reopen, which pushed every
    // section above it off-screen and made the first Workspace look like it had
    // vanished. Measured over one session: 8 of these, 6 of them with `kept: 0`,
    // meaning every row on screen was replaced; the list went from index 27 to 0
    // again and again. Reading the drawer meant losing your place every single
    // time you opened it, and that is worse than the thing it was fixing.
    //
    // The list's position already persists — the `LazyListState` is remembered
    // across closes — so the reveal is not needed. What is needed is that nothing
    // else moves it: focus is dropped while the drawer is shut, so the row you
    // last tapped is not a target for `bringIntoView` when it reopens. And a
    // programmatic scroll here was never free: `scrollToItem` holds the scroll
    // mutex until layout completes, and any drag landing in that window was
    // cancelled against it — measured as drags of 260–370px that moved the list
    // by exactly nothing.
    val focus = LocalFocusManager.current
    LaunchedEffect(drawerOpen) {
        if (!drawerOpen) focus.clearFocus()
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


    // The drawer is composed while it is shut, so nothing in it needs to animate
    // until it is on screen — see [LocalAnimatedDots].
    CompositionLocalProvider(LocalAnimatedDots provides drawerOpen) {
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
        // Brand lockup — 48dp, a little tighter than the web sidebar's 60px row:
        // its content is centred, so every dp of row height becomes dp of empty
        // band above "Search sessions..." on a phone, where the drawer has no
        // second row of chrome to fill it.
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp),
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
        //
        // It deliberately stays on an unanswered registry too, where its own reason is
        // not yet known: it is the sidebar's only way to start a session, and a stream
        // that never delivers a baseline would otherwise take it away. The target is
        // only recorded until the seat is first used (`startSession`), by which time the
        // registry has usually answered and the session lands in its Workspace.
        val newShape = RoundedCornerShape(DshRadius.card)
        // The button's own visibility gates the separator below it: in the common
        // case there is no button, so there is nothing to separate from the field.
        val showNewButton = !grouping.groupsByWorkspace || workspaces.isEmpty()
        if (showNewButton) {
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

        // Only a separator, and only when there is something to separate. With the
        // New Session button drawn this sits between it and the field; in the common
        // case (grouped, a host with Workspaces) that button is not drawn at all, and
        // the band above "Search sessions..." used to be the lockup's own centred
        // whitespace plus this spacer plus the field's inset — ~34dp of nothing.
        if (showNewButton) Spacer(Modifier.height(DshSpacing.md))

        DshTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = SessionSearchCopy.PLACEHOLDER,
            minHeight = 40.dp,
            // Tighter than a form field: the drawer's chrome above it is a 48dp
            // centred lockup, so its own inset is most of the remaining gap.
            verticalPadding = DshSpacing.md,
            // Emptying the box is the whole of it. The list's own derivations key
            // off a blank query — `matched` lets every session through and the
            // `searchHits` arm comes back empty because `onSearch("")` clears the
            // host page — so there is nothing else to undo. The drawer stays open
            // and the field keeps focus, which is what "show me the list again"
            // means: the reader is still searching, just from the start.
            onClear = { query = "" },
            clearContentDescription = SessionSearchCopy.CLEAR,
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

        // A failed pull, said where the sessions are rather than in the log. The
        // rows above stay because a refresh failing is not evidence that anything
        // is gone; what changes is that the drawer now says it is looking at an
        // older answer. `loading` is deliberately not consulted: a pull in flight
        // does not make the held list current, and a stale line that disappeared
        // on a retry's first frame would be read as a successful refresh.
        val stale = sessionsError
        if (stale != null) {
            Text(
                text = stale,
                style = DshType.micro,
                color = colors.warn,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = DshSpacing.sm)
                    .testTag(SESSIONS_STALE_TAG),
            )
        }

        Spacer(Modifier.height(DshSpacing.sm))

        // The web's Group by menu, as three chips rather than three menu rows: the
        // drawer is 300dp wide and this row has ~232dp for its choices after the
        // label. "Workspace Tree" does not fit between its neighbours at the app's
        // 11sp chip size (the two full labels already take most of the row), so the
        // middle chip is "Tree" — the same abbreviation the app already makes for
        // the web's "In one list" ("List"). Its full name is the chip's accessibility
        // label, and the visible order matches the web's menu: WorkSpace, Tree, List.
        OptionRow("Group") {
            MiniChoice(
                label = "WorkSpace",
                selected = grouping == DrawerGrouping.WORKSPACE,
                testTag = GROUP_WORKSPACE_TAG,
                onClick = { onGrouping(DrawerGrouping.WORKSPACE) },
            )
            MiniChoice(
                label = "Tree",
                selected = grouping == DrawerGrouping.TREE,
                testTag = GROUP_TREE_TAG,
                contentDescription = "Workspace Tree",
                onClick = { onGrouping(DrawerGrouping.TREE) },
            )
            MiniChoice(
                label = "List",
                selected = grouping == DrawerGrouping.FLAT,
                testTag = GROUP_LIST_TAG,
                onClick = { onGrouping(DrawerGrouping.FLAT) },
            )
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

        DiagLazyList("drawer", listState)
        LazyColumn(
            Modifier
                .weight(1f)
                .touchGate(drawerTouch)
                .diagDrag("drawer") { listState.diagOffset() },
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
            // The registry's own arrival, when it is still unknown. Nothing below it
            // can be read as the whole truth until the baseline lands — the group
            // headers are missing, not the Workspaces — so the absence is named here
            // rather than left to be inferred from headers that are not there.
            //
            // Only when there are sessions to group, because this row exists to qualify
            // the sections under it; with an empty roster the drawer's own "Loading…"
            // (below) is the whole story and a second one would be noise. Drawn only in
            // the grouped view, and deliberately not tappable: there is no group to fold
            // yet.
            if (grouping.groupsByWorkspace && workspacesPhase == WorkspaceListPhase.Pending && sessions.isNotEmpty()) {
                item(key = "workspaces-pending") { PendingWorkspaceRow() }
            }
            /**
             * Draw one section: its header, then its nested Workspaces, then its own
             * sessions — the web's `renderGroup` order. Recursive rather than a flat
             * loop because a child Workspace's rows belong between the parent's
             * header and the parent's own sessions.
             *
             * A folded section hides both its rows and its children, exactly as the
             * web's `childRows` is empty while the group is collapsed. A group with
             * no own rows states why *before* its children: "No sessions" placed
             * after a nested child would read as a claim about the whole subtree.
             */
            fun LazyListScope.drawGroup(section: Section) {
                val isCollapsed = section.key in collapsedSections
                val isExpanded = section.key in expandedSections
                val shown = when {
                    isExpanded -> section.rows
                    else -> section.rows.take(PAGE_SIZE)
                }
                val hidden = section.rows.size - shown.size
                // Top-level sessions only: a disclosed subagent belongs to its
                // parent's row, it is not another session in the group.
                val topLevel = section.rows.count { it.depth == 0 }
                // A Workspace's label was decided beside the state it belongs to: the plain
                // figure, or "[shown] of [accounted]" when the roster has not delivered every
                // member the registry accounts. The buckets with no registry row behind them
                // always draw the rows they hold. See `workspaceCountLabel`.
                val count = section.countLabel ?: topLevel.toString()

                // Sticky so the workspace a session belongs to stays named while
                // you scroll its rows.
                stickyHeader(key = "h-${section.key}") {
                    // A pinned header has to be opaque: the row it floats over is
                    // still painted underneath it, so a transparent background
                    // collided the workspace name with the session title.
                    ProjectRow(
                        modifier = Modifier.background(colors.sidebarFill),
                        title = section.title,
                        count = count,
                        collapsed = isCollapsed,
                        icon = section.icon,
                        containsCurrent = section.containsCurrent || section.containsCurrentDescendant,
                        run = section.run,
                        depth = section.depth,
                        onClick = {
                            onCollapsedSections(
                                if (isCollapsed) {
                                    collapsedSections - section.key
                                } else {
                                    collapsedSections + section.key
                                },
                            )
                        },
                        onCreate = section.createIn?.let { workspaceId ->
                            { onNewInWorkspace(workspaceId) }
                        },
                    )
                }

                if (isCollapsed) return

                // What a group with nothing in it means, in words. This is the row
                // that keeps "this Workspace has no sessions", "its sessions are all
                // hidden by the drawer's filters" and "its sessions have not been
                // loaded" apart: those three states draw differently here and, while
                // membership is unknown, in the header's count — and nowhere else on
                // screen are they distinguishable at all.
                section.state?.placeholder?.let { note ->
                    item(key = "note-${section.key}") { GroupNoteRow(note) }
                }

                // Child Workspaces, before this group's own sessions (web `renderGroup`).
                section.children.forEach { child -> this.drawGroup(child) }

                items(count = shown.size, key = { index -> shown[index].session.id }) { index ->
                    val row = shown[index]
                    SessionRow(
                        session = row.session,
                        depth = row.depth,
                        // Registered ancestry is drawn on the same row as subagent
                        // depth; the web cascades one indent per level through both.
                        workspaceDepth = section.depth,
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

            sections.forEach { section -> this.drawGroup(section) }
        }

        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.borderL1),
        )
        // A destination, not a preference: the web puts Automation tasks in its own
        // sidebar panel, and the drawer is the only host-wide shelf this app has.
        // It is a full row above the Settings/About pair so it reads as a place to
        // go, and it never intrudes on the composer.
        onAutomations?.let { open ->
            AutomationFooterRow(
                activeCount = activeScheduleTotal,
                onClick = open,
            )
        }
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
}

/**
 * The drawer's Automation tasks entry: one full-width row, with the count of tasks
 * that will fire again.
 *
 * The count is the *active* total and nothing else. A task that has ended still
 * appears on the sheet (it can be read and deleted there), but this row must never
 * imply that it is still running — the same rule that keeps an inactive task out of
 * `SessionItem.scheduledTaskCount`.
 */
@Composable
private fun AutomationFooterRow(
    activeCount: Int,
    onClick: () -> Unit,
) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(onClick = onClick)
            .testTag("drawer-automation-tasks"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Rounded.Schedule,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier
                .size(28.dp)
                .padding(DshSpacing.sm),
        )
        Spacer(Modifier.width(DshSpacing.md))
        Text(
            text = "Automation tasks",
            style = DshType.bodyMedium,
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.weight(1f))
        if (activeCount > 0) {
            Text(
                text = if (activeCount == 1) "1 active" else "$activeCount active",
                style = DshType.micro,
                color = colors.labelTertiary,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(DshSpacing.md))
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
        // The glyph carries its own inset (28dp box, 6dp padding), so an action
        // that ends in a glyph looks right against the drawer's edge. The trailing
        // action ends in *text*, which does not, and flush-right read as clamped to
        // the edge. This is a trailing spacer rather than end padding on the row so
        // the label moves in but the tap target still reaches the edge.
        Spacer(Modifier.width(DshSpacing.md))
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
 *
 * The two facts a header carries are painted on two different elements, so they cannot be
 * confused for one another:
 *
 *  * **The folder glyph** is [containsCurrent] — "the session you have open lives here" (the
 *    web's `folderActive`). Unchanged.
 *  * **The title** is [run] — "something in here is running". `Running` is the accent blue, so a
 *    folded folder with live work inside it cannot be missed; [WorkspaceRunState.Idle] is the
 *    title's ordinary colour. There is no third title colour: an accounted member the roster
 *    never delivered is absent for this question, and the incompleteness of the membership is
 *    carried by the count beside the title ([count]), in words that can be counted.
 *
 * Every combination of the two therefore draws differently: an ordinary title (idle) or a blue
 * title (running), each under either a tertiary folder (the open session is elsewhere) or an
 * accent folder (the open session is here). The blue is one token used on one element per
 * meaning, and it now has exactly one legend — "blue means something here is running" — which is
 * a sentence about something the reader can see.
 *
 * The title is tinted whether the group is folded or open, not only when collapsed. Folded is
 * the case that prompted this, but an open group is capped at six rows until "Show N more", and
 * the drawer holds the roster's order still while it is open, so the running row can be below
 * the fold of an expanded group too — and the header is sticky, so the tint is the one part of
 * the group that stays on screen while its rows are scrolled through.
 *
 * [run] is also *spoken* (`stateDescription`) when it is `Running`, because the blue is invisible
 * to a screen reader. Idle stays silent, as an idle session row draws no "not running" either.
 */
@Composable
private fun ProjectRow(
    title: String,
    /**
     * How many registered Workspaces this one is nested under: the header indents
     * its contents by 12dp per level, the web's `--dsh-workspace-indent` step. The
     * indent sits inside the row's fill and tap target, so every level's row is the
     * same width to hit and only its contents move.
     */
    depth: Int = 0,
    /**
     * The count column, already decided: the sessions drawn under this header, or
     * "[shown] of [accounted]" when the roster has not delivered every member the registry
     * accounts. Never blank, which is the whole point: a folded group shows this without its
     * own "Sessions not loaded" note, and a bare figure or a word like `unknown` would leave
     * the reader to guess what it left out. See `workspaceCountLabel`.
     */
    count: String,
    collapsed: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * The group's own glyph, when it has one. Null is the Workspace case, whose
     * open/closed folder *is* the disclosure affordance; a group that supplies an
     * icon keeps it whether it is collapsed or not, since its icon says what the
     * group is rather than whether it is open.
     */
    icon: ImageVector? = null,
    /** Null for the buckets with no Workspace behind them: the flat list and Ungrouped. */
    onCreate: (() -> Unit)? = null,
    /**
     * Whether the open session lives in this group. The web tints the folder of the
     * Workspace that contains the selection (`folderActive`,
     * `state-business-primary`), which is how a reader finds their place in a list of
     * directories without opening each one.
     */
    containsCurrent: Boolean = false,
    /** Whether this group holds live work; see `WorkspaceRunState` and the class KDoc. */
    run: WorkspaceRunState,
) {
    val colors = DshTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(onClick = onClick)
            .padding(start = (depth * 12).dp, end = DshSpacing.md)
            .semantics {
                when (run) {
                    WorkspaceRunState.Running -> stateDescription = WorkspaceCopy.RUNNING
                    WorkspaceRunState.Idle -> Unit
                }
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = icon
                ?: if (collapsed) Icons.Rounded.Folder else Icons.Rounded.FolderOpen,
            contentDescription = when {
                icon != null -> null
                collapsed -> "Expand workspace"
                else -> "Collapse workspace"
            },
            tint = if (containsCurrent) colors.accent else colors.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = title,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = if (run == WorkspaceRunState.Running) colors.accent else colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = count,
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

/**
 * The one line a Workspace group draws when it has no rows: what that absence *means*.
 *
 * "No sessions", "Sessions hidden by filters" and "Sessions not loaded" are different
 * claims about a directory, and this row is where they are told apart — the header above it
 * cannot carry the difference alone, because a count of zero looks the same whether the
 * registry said the directory is empty, the drawer's own view rules emptied it, or the
 * roster has simply not delivered its members.
 *
 * Deliberately not clickable: there is no session behind it and nothing a tap could
 * open. It sits where `ShowMoreRow` sits, in the same type, so a group with nothing in
 * it and a group with more to show read as the same kind of footnote rather than one of
 * them looking like a broken session row.
 */
@Composable
private fun GroupNoteRow(label: String) {
    val colors = DshTheme.colors
    Box(
        Modifier
            .fillMaxWidth()
            .height(28.dp)
            .padding(start = DshSpacing.lg + DshSpacing.xl),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(label, style = DshType.bodySmall, color = colors.labelTertiary)
    }
}

/**
 * The grouped list's placeholder while `workspace/follow` has not yet delivered its
 * baseline.
 *
 * What is missing is the *registry*, not any session's rows, so this is drawn once above
 * the groups rather than being folded into the catch-all section: with no Workspaces
 * known, every session is in that bucket, and the bucket's own label would be the drawer
 * making a claim about membership it has not been told. It borrows [ProjectRow]'s shape —
 * folder glyph, name, figure on the right — so it reads as a group that is not yet here
 * rather than as a fifth kind of row, and it is **not** tappable: there is nothing to
 * fold, and a dead disclosure would be worse than no row at all.
 */
@Composable
private fun PendingWorkspaceRow() {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .height(34.dp)
            .padding(end = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Folder,
            contentDescription = null,
            tint = colors.labelTertiary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(DshSpacing.sm))
        Text(
            text = WorkspaceCopy.WORKSPACES,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = WorkspaceCopy.UNKNOWN,
            style = DshType.micro,
            color = colors.labelCaption,
        )
    }
}

@Composable
private fun SessionRow(
    session: SessionItem,
    depth: Int,
    /**
     * How many registered Workspaces the row's section is nested under. A session
     * inherits its Workspace's indent (the web cascades `--dsh-workspace-indent` to
     * the session rows), and a disclosed subagent adds its own step on top.
     */
    workspaceDepth: Int = 0,
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
            .clip(shape)
            .background(if (selected) colors.sidebarItemActive else colors.sidebarFill)
            .clickableNoRipple(onClick = onClick)
            // Subagents nest one step per level under their parent, and a Workspace's
            // sessions inherit their registered ancestry's indent. The indent is inside
            // the fill and the hit target (the web's rule: only the contents move), so
            // a selected row's band still spans the drawer at every depth.
            .padding(
                start = DshSpacing.lg + DshSpacing.md + (depth * 14).dp + (workspaceDepth * 12).dp,
                end = DshSpacing.md,
            ),
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
                // The web's clock mark (`SessionScheduleMark`): the row's leading
                // cell is offered to it only while the row's own primary state is
                // idle, so a live turn or a pending interaction keeps the status dot
                // here and never mounts the mark. The count comes from the one
                // `schedule/catalog` sample the whole roster shares — see
                // `SessionItem.scheduledTaskCount` — so this mark and the landing
                // rule cannot disagree about which sessions host a schedule.
                session.scheduledTaskCount > 0 -> Icon(
                    Icons.Rounded.Schedule,
                    contentDescription = scheduledTaskMarkLabel(session.scheduledTaskCount),
                    tint = colors.labelTertiary,
                    modifier = Modifier.size(13.dp),
                )
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
private fun MiniChoice(
    label: String,
    selected: Boolean,
    /**
     * A stable identity for tests. The three Group chips are one-character
     * differences apart in a screenshot, and their labels are shortened for width,
     * so the tag is what a test can address without depending on either.
     */
    testTag: String? = null,
    /**
     * The web's full name for a chip whose visible label had to be shortened;
     * null when the visible text already says it.
     */
    contentDescription: String? = null,
    onClick: () -> Unit,
) {
    val colors = DshTheme.colors
    val description = contentDescription
    Box(
        Modifier
            .then(if (testTag == null) Modifier else Modifier.testTag(testTag))
            .then(
                if (description == null) {
                    Modifier
                } else {
                    Modifier.semantics { this.contentDescription = description }
                },
            )
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

/**
 * The stale-roster line's seat, for `SessionsDrawerStaleTest`: the sentence
 * names the reason, which varies, so the tag is what identifies the node.
 */
const val SESSIONS_STALE_TAG = "drawer-sessions-stale"

/** The three Group chips; "Tree" is the web's "Workspace Tree" (see the row's comment). */
const val GROUP_WORKSPACE_TAG = "drawer-group-workspace"
const val GROUP_TREE_TAG = "drawer-group-tree"
const val GROUP_LIST_TAG = "drawer-group-list"

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
