package uk.xa0.dsh.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import uk.xa0.dsh.DshViewModel
import uk.xa0.dsh.ReferenceCandidate
import uk.xa0.dsh.data.BusyEnter
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.DisplayRow
import uk.xa0.dsh.model.LiveAttempt
import uk.xa0.dsh.model.SessionFiles
import uk.xa0.dsh.model.SubagentComposerState
import uk.xa0.dsh.model.SubagentReadOnlyReason
import uk.xa0.dsh.model.TurnDeliverables
import uk.xa0.dsh.model.buildDisplayRows
import uk.xa0.dsh.model.buildToolCallTree
import uk.xa0.dsh.model.buildTrajectory
import uk.xa0.dsh.model.subagentComposerState
import uk.xa0.dsh.model.subagentTargetOf
import uk.xa0.dsh.ui.agentPresetLabel
import uk.xa0.dsh.ui.components.ApprovalCard
import uk.xa0.dsh.ui.components.AssistantMessageRow
import uk.xa0.dsh.ui.components.CommandEntry
import uk.xa0.dsh.ui.components.CommandMenu
import uk.xa0.dsh.ui.components.Composer
import uk.xa0.dsh.ui.components.DeliverableRow
import uk.xa0.dsh.ui.components.DotState
import uk.xa0.dsh.ui.components.DshMark
import uk.xa0.dsh.ui.components.FilesPanel
import uk.xa0.dsh.ui.components.GoalBar
import uk.xa0.dsh.ui.components.JobsSeat
import uk.xa0.dsh.ui.components.JobsSheet
import uk.xa0.dsh.ui.components.LiveAttemptRow
import uk.xa0.dsh.ui.components.NoticeRow
import uk.xa0.dsh.ui.components.QueueDock
import uk.xa0.dsh.ui.components.ReferenceMenu
import uk.xa0.dsh.ui.components.QuestionCard
import uk.xa0.dsh.ui.components.StateDot
import uk.xa0.dsh.ui.components.SubagentReadOnlyComposer
import uk.xa0.dsh.ui.components.TodoCard
import uk.xa0.dsh.ui.components.SessionStatsPills
import uk.xa0.dsh.ui.components.ToolCallRow
import uk.xa0.dsh.ui.components.ToolCallTreeRow
import uk.xa0.dsh.ui.components.TurnProcessRow
import uk.xa0.dsh.ui.components.TurnStatusRow
import uk.xa0.dsh.ui.components.UserMessageRow
import uk.xa0.dsh.ui.components.WorkspaceBrowser
import uk.xa0.dsh.ui.components.composerCommandEntries
import uk.xa0.dsh.ui.composer.ComposerTriggers
import uk.xa0.dsh.ui.composer.composerTriggers
import uk.xa0.dsh.ui.composer.consumeTrigger
import uk.xa0.dsh.ui.composer.replaceTrigger
import uk.xa0.dsh.ui.theme.DshRadius
import uk.xa0.dsh.ui.theme.DshSpacing
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The conversation screen. On a phone the web client's three-column frame
 * collapses to: modal sidebar (drawer), a 76dp-equivalent header, the transcript
 * column, and the sticky composer.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: DshViewModel) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val entries by vm.entries.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val todos by vm.todos.collectAsStateWithLifecycle()
    val header by vm.header.collectAsStateWithLifecycle()
    val endedTurns by vm.endedTurns.collectAsStateWithLifecycle()
    val closingSeqs by vm.closingSeqs.collectAsStateWithLifecycle()
    val turnDurations by vm.turnDurations.collectAsStateWithLifecycle()
    val turnStartedAt by vm.turnStartedAt.collectAsStateWithLifecycle()
    val references by vm.references.collectAsStateWithLifecycle()
    val hostCommands by vm.commands.collectAsStateWithLifecycle()
    val directoryLevel by vm.directoryLevel.collectAsStateWithLifecycle()
    val directoryLoading by vm.directoryLoading.collectAsStateWithLifecycle()
    val directoryBusy by vm.directoryBusy.collectAsStateWithLifecycle()
    val directoryError by vm.directoryError.collectAsStateWithLifecycle()

    // A live clock for the running turn's status line, ticked once a second. Gated
    // on the session's *own* turn (`ownTurnInFlight`) like the row it feeds, so a
    // session the host still reports running while only a subagent works neither
    // draws the row nor keeps a ticker alive for it.
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(ui.ownTurnInFlight, turnStartedAt) {
        if (!ui.ownTurnInFlight) return@LaunchedEffect
        while (true) {
            nowMs = System.currentTimeMillis()
            delay(1000)
        }
    }
    val elapsedMs = turnStartedAt?.let { (nowMs - it).coerceAtLeast(0L) }

    /** Turns whose process rows the user has opened; the rest stay folded. */
    var expandedTurns by remember { mutableStateOf(emptySet<Int>()) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    // With reverseLayout the newest turn is index 0 at the visual bottom, so
    // "at the bottom" is exactly offset 0 of item 0. `canScrollBackward` is not
    // usable here: content padding keeps a little scrollable slack, so it reads
    // true even when the newest message is on screen — which made the button
    // appear while already at the end.
    val atBottom by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }
    }

    // LazyColumn anchors its scroll position by item key, so with reverseLayout a
    // newly inserted turn lands *below* the viewport rather than following it:
    // streaming content never auto-scrolls on its own. So follow explicitly while
    // the user is parked at the end — and only then, so reading history is stable.
    //
    // The two halves are deliberately separate effects, and they only ever move the
    // flag in one direction each:
    //
    //  * re-arm on reaching the end, and never clear here — so a momentarily stale
    //    reading can only ever cause following while already at the end, which is
    //    what we want anyway;
    //  * release on a deliberate drag, which is a discrete event that cannot be
    //    sampled past.
    //
    // The previous version inferred the release by sampling `isScrollInProgress`
    // next to the scroll position. That pair is observable as (scrolling = false,
    // atEnd = false) — most easily just as a flick ends — and in that case neither
    // branch ran, so the flag stayed set and the next streamed token dragged the
    // reader back to the bottom. That is the "it keeps autoscrolling when I have
    // scrolled up" report.
    var stickToBottom by remember { mutableStateOf(true) }

    // Freeze the streaming row while the reader is away from the end.
    //
    // Releasing the follow above stops the list being *scrolled*, but not the row
    // being *laid out again*: a thinking segment or a long answer grows every few
    // milliseconds, and a growing item re-lays-out the list under the reader —
    // "scrolling up into a text block while it's being written to leads to
    // involuntary scrolling". Pinning the content at the moment of the drag means
    // nothing below can reflow while the reader is in history; it unfreezes, and
    // catches up in one step, when they come back to the end.
    var frozenLive by remember { mutableStateOf<LiveAttempt?>(null) }
    val currentLive by rememberUpdatedState(live)

    // ...and hold the *shape* of the ended-turn fold for the same reason.
    //
    // Folding is what a just-finished turn does to rows that a moment earlier were
    // the running turn's rows, and when the reader is inside them it rewrites the
    // whole viewport under them. Measured on a parked reader: at the instant of a
    // fold 79% of the transcript rows in view were replaced, taking the reader
    // from the middle of the turn to the beginning of the session.
    //
    // Re-pinning the reader's item cannot save that one: every row they can see is
    // a fold member, so there is no surviving key to pin and Compose falls back to
    // the raw index. The fix at this level is not to rewrite rows the reader is
    // reading. The web client folds immediately because its reader is parked at the
    // end; holding the set while they are away, and applying it in one step when
    // they return, is the same rule as the live row above.
    var foldedTurns by remember { mutableStateOf(endedTurns) }
    LaunchedEffect(stickToBottom, endedTurns) {
        if (stickToBottom) foldedTurns = endedTurns
    }

    // A session switch is not a scroll. `listState` and the freeze are remembered
    // across sessions on purpose (the list must not be rebuilt per session), so
    // without this an opened session inherits the *previous* one's position and
    // freeze: the reader lands mid-history in a session they just opened, and the
    // old session's live row stays pinned over the new one's. Opening a session
    // means following it.
    LaunchedEffect(ui.currentSessionId) {
        frozenLive = null
        stickToBottom = true
        if (listState.firstVisibleItemIndex != 0 || listState.firstVisibleItemScrollOffset != 0) {
            listState.scrollToItem(0)
        }
    }

    // Following is a *position*, not a gesture.
    //
    // This used to unfreeze only on `atEnd == true`, and to arm the freeze only on
    // `DragInteraction.Start` — so every other way of leaving the bottom was
    // unhandled: tapping the turn rail (a jump is a programmatic `scrollToItem`,
    // which emits no DragInteraction), tapping a row to expand or collapse it, and
    // scrolling part-way into a live row taller than the screen. `atEnd == false`
    // did nothing at all, which left the app in follow mode with an unfrozen live
    // row: the autoscroll below then re-pinned the list to the bottom on the very
    // next token, and a long thinking segment kept being filled in underneath the
    // reader. That is the reported shape — a think row being actively written to
    // and not technically at the bottom.
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0
        }.distinctUntilChanged().collect { atEnd ->
            stickToBottom = atEnd
            if (atEnd) {
                frozenLive = null
            } else if (frozenLive == null) {
                // Snapshot on the way out only: re-entering history must not
                // re-snapshot a row the reader is already part-way through, or the
                // content would jump forward under them on the second drag.
                frozenLive = currentLive
            }
        }
    }
    // There is deliberately no "the turn is over, drop the freeze" rule, and
    // putting one back is the bug this replaces.
    //
    // That rule cleared the snapshot on the app's *belief* that the turn had
    // ended, and the belief flaps: `ownTurnInFlight` is `running && !ownTurnClosed`,
    // `running` is denied by the roster pull, and a live attempt is legitimately
    // absent between two steps of the same turn. Measured mid-turn: two "turn
    // over" instants (00:27:15, 00:27:19) while that same turn ran on to step 6.
    // Each one dropped the freeze under a reader who was in history, and the next
    // attempt's reasoning replaced the text they were reading. The freeze now ends
    // only when a *position* says the reader is done with it — reaching the end,
    // above. `ownTurnInFlight` itself is untouched, so the seats that read it (the
    // elapsed clock and the bottom "Deep diving…" status row) are unchanged.
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { interaction ->
            // The position rule above covers this, but only once the drag has
            // actually moved the list: freezing at touch-down means a token that
            // lands between the finger going down and the first movement cannot
            // reflow the row the reader is reaching for.
            if (interaction is DragInteraction.Start) {
                frozenLive = currentLive
                stickToBottom = false
            }
        }
    }
    // Reaching the oldest loaded row asks the host for the page before it.
    // `session/follow` opens a bounded window, so without this the transcript just
    // stops when you scroll up. Requiring the list to be longer than the viewport
    // keeps a short session from paging on open, and the call is itself a no-op once
    // the host reports nothing older.
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > info.visibleItemsInfo.size && last >= info.totalItemsCount - 1
        }.distinctUntilChanged().collect { atOldest ->
            if (atOldest) vm.loadOlderHistory()
        }
    }

    // The draft carries its selection: the `/` and `@` menus follow the caret, so
    // a tap back into an earlier line must be visible to the trigger derivation.
    var draft by rememberSaveable(ui.currentSessionId, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(""))
    }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // The About sheet is pure local state, so unlike Settings it needs no read on
    // open — see `AboutSheet`.
    var showAbout by rememberSaveable { mutableStateOf(false) }
    var showModels by rememberSaveable { mutableStateOf(false) }
    var showPermission by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var showWorkspace by rememberSaveable { mutableStateOf(false) }
    var showPresets by rememberSaveable { mutableStateOf(false) }
    var showJobs by rememberSaveable { mutableStateOf(false) }
    var showBrowser by rememberSaveable { mutableStateOf(false) }

    // The `conversation.view` strip. The host registers exactly two views — chat
    // (order 0, and the hard-coded default in `view-selection.ts`) and trajectory
    // (order 10) — and the web remembers the choice per session.
    var view by rememberSaveable(ui.currentSessionId) { mutableStateOf(ChatView.CHAT) }
    // The right panel's `files` surface. A phone has no room for a column beside
    // the transcript, so it takes the whole body with its own back bar.
    var showFiles by rememberSaveable(ui.currentSessionId) { mutableStateOf(false) }
    // A deliverable chip opens that path in the panel, so the panel needs to be
    // told what to select when it was not opened from its own list.
    var filesFocus by remember(ui.currentSessionId) { mutableStateOf<String?>(null) }
    // A closed turn's deliverables cannot change, so each row's answer is computed
    // once and kept: recomputing per frame would be a full scan of the transcript
    // on every streaming token.
    val deliverableCache = remember(ui.currentSessionId) { HashMap<String, TurnDeliverables>() }

    // The subagent lineage dropdown behind the header chip.
    var showLineage by rememberSaveable(ui.currentSessionId) { mutableStateOf(false) }
    // Descendant counts per session — the web's `indexSubagentDescendants`. Derived
    // from the roster rather than from any subagent row, so a session's subagent
    // activity is visible without the user having to reveal subagent rows at all.
    // The catalogs only floor the totals here, where the rollup is printed; the
    // drawer passes none, because its caret expands the roster.
    val subagentRollups = remember(ui.sessions, ui.subagentCatalogs) {
        indexSubagentRollups(ui.sessions, ui.subagentCatalogs)
    }

    val attachLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.addAttachment(uri)
    }

    /**
     * Picks from the command menu. `file`, `model` and `permission` are the rows
     * this client owns — an attachment picker, the model sheet, and the permission
     * sheet (which stands in for the web's preset popupSelect) — so they are
     * handled by name. Every other row follows the host descriptor: a command that
     * takes input is claimed into the draft so its arguments can be typed, a bare
     * one runs on the pick.
     */
    // The `+` menu's roster, also the `/` menu's catalogue, built from the host.
    val commandEntries = remember(hostCommands) { composerCommandEntries(hostCommands) }
    val triggers = rememberComposerTriggers(
        value = draft,
        commandEntries = commandEntries,
        searchReferences = vm::searchReferences,
        clearReferences = vm::clearReferences,
        sessionId = ui.currentSessionId,
        refreshCommands = vm::refreshCommands,
    )

    /**
     * The live `/` token, or null — read by the picker so a pick from the typed
     * menu can consume the token it was typed as.
     */
    val onPickCommand: (CommandEntry) -> Unit = { entry ->
        menuOpen = false
        // A pick from the typed menu consumes the token it was typed as, wherever
        // the caret sits; the `+` launcher types no token, so it either seeds the
        // bare token for an input-taking command or changes nothing at all.
        val token = triggers.slash
        when (entry.name) {
            "file" -> {
                draft = draft.consumeTrigger(token)
                attachLauncher.launch("*/*")
            }
            "model" -> {
                draft = draft.consumeTrigger(token)
                showModels = true
            }
            "permission" -> {
                draft = draft.consumeTrigger(token)
                showPermission = true
            }
            else -> if (entry.takesInput) {
                draft = draft.replaceTrigger(token, "/${entry.name} ")
            } else {
                draft = draft.consumeTrigger(token)
                vm.executeCommand("/${entry.name}")
            }
        }
    }
    // The access chip is a dropdown, so its resting label is abbreviated to keep the
    // composer on one row; the picker sheet still shows the host's full names and
    // descriptions, which is where the exact wording matters.
    val permissionLabel = shortAccessLabel(ui.currentPermission, ui.permissionOptions)
    val modelLabel = listOfNotNull(
        shortModelName(ui.selectedModel?.name),
        effortLabel(ui.selectedModel, ui.selectedEffort),
    ).joinToString(" \u00b7 ")


    val current = ui.sessions.firstOrNull { it.id == ui.currentSessionId }
    val isHero = entries.isEmpty() && live == null
    // An empty transcript is not the same fact as a blank session. The follow
    // stream's opening snapshot is the one event that proves the host has
    // answered for *this* session, and `TranscriptReducer.applySnapshot` is what
    // sets the header (`publishTranscript` copies it out); `openSession`'s
    // `reducer.reset()` is what clears it. So a null header while a session is
    // open means nothing has loaded yet, not that there is nothing to load.
    // The roster's own `blank` is the other half: only the host may call a
    // session provisional, and that hero is where a new session is meant to
    // start, so it keeps the hero from the first frame rather than flashing a
    // spinner first.
    val transcriptArrived = header != null
    val awaitingTranscript =
        isHero && ui.currentSessionId != null && !transcriptArrived && current?.blank != true
    // Bug A: silence while connected, a named state otherwise.
    val connectionLabel = ui.connectionLabel
    // The host refuses to recompose an agent once a turn has run, so the chip is
    // only meaningful while the session is blank — the web hero seat's own rule.
    // The label prefers the session's recorded preset, then the host default.
    val presetLabel = ui.agentPresetOptions.firstOrNull { it.id == ui.currentAgentPreset }
        ?.let { agentPresetLabel(it.id, it.name) }
        ?: ui.currentAgentPreset.takeIf { it.isNotBlank() }
        ?: ui.agentPresetOptions.firstOrNull { it.isDefault }?.name
        ?: ui.agentPresetOptions.firstOrNull()?.name
    val showPresetChip = isHero && current?.blank == true && presetLabel != null
    // A one-shot subagent's inbox is not its own to mutate — the web's
    // `queueMutable = subagent === null || mode === 'continuable'`.
    val queueMutable = current?.isSubagent != true || current.subagentMode == "continuable"
    // The web's `selectReadOnlySubagent`: an addressed child claims the composer's
    // seat with a status frame instead of taking input — a one-shot record always,
    // a continuable child only once its parent is known to be offline. Availability
    // is unknown until `subagents/list` lands, and unknown keeps the composer, so
    // the seat never flickers into a frame it would have to take back.
    val composerState = subagentComposerState(
        target = current?.let { subagentTargetOf(it.id, it.parentSessionId, it.subagentMode) },
        parentAvailable = ui.subagentParentAvailable,
        running = ui.running,
    )
    val readOnlyReason = (composerState as? SubagentComposerState.ReadOnly)?.reason

    // The web marks a catalog "open" while its menu is up: that is what turns a
    // frame-driven refresh into an immediate pull, and what drops a debounce the
    // opening owed on close (`setSubagentCatalogOpen`, manager.ts:433-446). The
    // phone's equivalent catalog is this sheet, rooted at the session on screen.
    DisposableEffect(showLineage, current?.id) {
        val root = current?.id
        if (showLineage && root != null) vm.setSubagentCatalogOpen(root, true)
        onDispose { if (root != null) vm.setSubagentCatalogOpen(root, false) }
    }

    // The loading body is the Column's *last* child, so the box it centres its
    // contents in is everything below the chrome — an area that already ends at
    // the viewport's bottom edge. Its centre therefore sits `chrome / 2` below
    // the viewport's centre, and the content is translated back up by exactly
    // that half rather than restructured into a viewport-filling overlay. The
    // chrome is measured, not assumed: the header grows a tab strip outside the
    // hero, and the banner is present only while `ui.error` is set.
    var headerPx by remember { mutableIntStateOf(0) }
    var bannerPx by remember { mutableIntStateOf(0) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        // Material's own gesture commits only after the panel has travelled half
        // its width (150dp here), so a normal thumb swipe shows a peek and springs
        // back. `drawerDrag` below replaces it with a finger-following drag that
        // commits at a quarter of the width or on a modest fling.
        gesturesEnabled = false,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .width(DRAWER_WIDTH)
                    .drawerDrag(drawerState, DRAWER_WIDTH),
                windowInsets = WindowInsets(0, 0, 0, 0),
                drawerContainerColor = DshTheme.colors.sidebarFill,
            ) {
                SessionsDrawer(
                    sessions = ui.sessions,
                    workspaces = ui.workspaces,
                    archivedSessionIds = ui.archivedSessionIds,
                    completedSessionIds = ui.completedSessionIds,
                    pendingInteractions = ui.pendingInteractions,
                    currentId = ui.currentSessionId,
                    drawerOpen = drawerState.isOpen,
                    loading = ui.sessionsLoading,
                    onSelect = {
                        vm.openSession(it)
                        scope.launch { drawerState.close() }
                    },
                    onNew = {
                        // Inherits the current (or most recent) Workspace rather
                        // than creating at a bare cwd, which is what used to drop
                        // a new session into Ungrouped.
                        vm.startSession(current?.cwd ?: header?.cwd)
                        scope.launch { drawerState.close() }
                    },
                    onNewInWorkspace = { workspaceId ->
                        vm.startSessionInWorkspace(workspaceId)
                        scope.launch { drawerState.close() }
                    },
                    onRefresh = { vm.refreshSidebar() },
                    groupByWorkspace = ui.drawerGroupByWorkspace,
                    orderByUpdated = ui.drawerOrderByUpdated,
                    showArchived = ui.drawerShowArchived,
                    collapsedSections = ui.drawerCollapsedSections,
                    onGroupByWorkspace = vm::setDrawerGroupByWorkspace,
                    onOrderByUpdated = vm::setDrawerOrderByUpdated,
                    onShowArchived = vm::setDrawerShowArchived,
                    onCollapsedSections = vm::setDrawerCollapsedSections,
                    // The box is one control with two answers: the roster filtered
                    // locally by title/path, and the host's own content search. The
                    // ViewModel owns the debounce and drops superseded pages.
                    searchHits = ui.sessionSearchHits,
                    searchLoading = ui.sessionSearchLoading,
                    searchError = ui.sessionSearchError,
                    searchHasMore = ui.sessionSearchHasMore,
                    onSearch = vm::searchSessionContent,
                    onRename = vm::renameSession,
                    onFork = vm::forkSession,
                    onArchive = vm::archiveSession,
                    onUnarchive = vm::unarchiveSession,
                    // The drawer's per-session caret is the other catalog
                    // disclosure: opening one is this app's `setSubagentCatalogOpen`
                    // (`manager.ts:433-446`), which reads the child catalog the
                    // caret is about to reveal.
                    onSubagentCatalogOpen = vm::setSubagentCatalogOpen,
                    // The browser is a modal of its own, so the drawer steps out of
                    // the way first: two stacked scrims would eat the first back
                    // gesture and hide which layer it belongs to.
                    onAddWorkspace = {
                        vm.resetDirectoryBrowser()
                        showBrowser = true
                        scope.launch { drawerState.close() }
                    },
                    onSettings = {
                        showSettings = true
                        // The sheet is the only reader of the host's settings
                        // document, so it reads on open rather than on a timer.
                        vm.loadHostSettings()
                        scope.launch { drawerState.close() }
                    },
                    onAbout = {
                        showAbout = true
                        // One modal at a time: the drawer's sheet and the About
                        // sheet are separate scrims, and leaving both up eats the
                        // first back gesture.
                        scope.launch { drawerState.close() }
                    },
                )
            }
        },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .drawerDrag(drawerState, DRAWER_WIDTH)
                .background(DshTheme.colors.bgBase)
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding(),
        ) {
            ChatHeader(
                title = current?.title ?: "DSH",
                cwd = current?.cwd ?: header?.cwd,
                onMenu = { scope.launch { drawerState.open() } },
                jobs = ui.jobs,
                jobsFinishedUnseen = ui.jobsFinishedUnseen,
                onJobs = {
                    vm.markJobsSeen()
                    showJobs = true
                },
                // The web renders `.tabs` only when more than one view is
                // registered, so the hero screen — which has nothing to inspect
                // yet — carries no strip at all.
                view = view,
                onView = { view = it },
                showTabs = !isHero,
                filesOpen = showFiles,
                onFiles = {
                    filesFocus = null
                    showFiles = !showFiles
                },
                lineage = current?.let { subagentRollups[it.id] },
                onLineage = { showLineage = true },
                // Feeds the loading offset above: the header is the top half of
                // the chrome, and its own height is not a constant.
                modifier = Modifier.onSizeChanged { headerPx = it.height },
            )

            if (ui.error != null) {
                ErrorBanner(
                    message = ui.error!!,
                    onDismiss = vm::dismissError,
                    // An expired session is not something to dismiss: the banner
                    // must carry the way out, or the screen is a dead end.
                    actionLabel = if (ui.errorNeedsSignIn) "Sign in again" else null,
                    onAction = if (ui.errorNeedsSignIn) vm::signInAgain else null,
                    // The banner sits between the header and the body, so it is
                    // part of the chrome the loading content has to be lifted by.
                    modifier = Modifier.onSizeChanged { bannerPx = it.height },
                )
            }

            if (showFiles) {
                // Derived on demand: the transcript can hold a thousand rows and
                // neither scan is worth paying for while the panel is closed.
                val files = remember(entries) { SessionFiles.derive(entries) }
                val previews = remember(entries) { SessionFiles.previews(entries) }
                // Rebuilt with the session: the loader is scoped by the session id
                // the host resolves the path against, so a stale one would read the
                // wrong workspace root.
                val fileLoader = remember(current?.id) { vm.workspaceFileLoader() }
                FilesSurface(
                    files = files,
                    previews = previews,
                    root = current?.cwd ?: header?.cwd,
                    focus = filesFocus,
                    loader = fileLoader,
                    onClose = { showFiles = false },
                    modifier = Modifier.weight(1f),
                )
            } else if (view == ChatView.TRAJECTORY) {
                val trajectory = remember(entries, endedTurns) {
                    buildTrajectory(entries, endedTurns, vm.toolParents())
                }
                TrajectoryScreen(
                    model = trajectory,
                    title = current?.title ?: "DSH",
                    onClose = { view = ChatView.CHAT },
                    modifier = Modifier.weight(1f),
                    // The conversation header above already carries the title, the
                    // back-to-drawer seat and the view tabs, exactly as the web's
                    // `.viewArea` sits below `.tabs` — the screen's own title row
                    // would be a second copy of it.
                    showHeader = false,
                )
            } else if (awaitingTranscript) {
                // A non-blank session with nothing loaded yet: the host has not
                // answered this session's follow stream. Naming the connection
                // when it is the reason costs no new copy — the drawer's own
                // "Loading…" plus the shell's connection word.
                //
                // The composer and the pills are not siblings of this branch —
                // they live in the transcript `else` below — so the chrome is
                // the whole offset: `weight(1f)`'s box runs from the header
                // (plus banner) to the Column's bottom, putting its centre at
                // `H/2 + (header + banner)/2`. Half of that, negated, is the
                // viewport's centre. Reading the states inside `offset` keeps the
                // first frame still (both are 0 until measured) and re-places
                // instead of recomposing when the banner arrives.
                LoadingSessionBody(
                    connection = connectionLabel,
                    // A Box wraps its content, so without `fillMaxWidth` the
                    // Column's Start alignment parks the dot+label on the left
                    // edge; filling the width is what makes the centring two-
                    // dimensional.
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .offset { IntOffset(0, -(headerPx + bannerPx) / 2) },
                )
            } else if (isHero) {
                // The hero has no flex child that can absorb a pill the way the
                // transcript does, so an in-flow row would lift the centred
                // composer by half the pill's height when the socket drops.
                // Overlaid on the seat it costs no layout at all, and the strip
                // it lands on is empty because the hero block is centred.
                Box(Modifier.weight(1f)) {
                    HeroBody(
                        modifier = Modifier.fillMaxSize(),
                        value = draft,
                        onValueChange = { draft = it },
                        running = ui.running,
                        modelLabel = modelLabel,
                        onModelClick = { showModels = true },
                        permissionLabel = permissionLabel,
                        onPermissionClick = { showPermission = true },
                        planActive = ui.planActive,
                        onExitPlan = vm::exitPlanMode,
                        contextPercent = ui.contextPercent,
                        contextTokens = ui.contextTokens,
                        contextWindow = ui.contextWindow,
                        contextBreakdown = ui.contextBreakdown,
                        attachments = ui.attachments,
                        onToggleCommands = { menuOpen = !menuOpen },
                        onRemoveAttachment = vm::removeAttachment,
                        menuOpen = menuOpen,
                        triggers = triggers,
                        references = references,
                        clearReferences = vm::clearReferences,
                        onPickCommand = onPickCommand,
                        workspaceLabel = current?.cwd?.let(::shortenLastSegment) ?: "Choose workspace",
                        onWorkspaceClick = { showWorkspace = true },
                        presetLabel = presetLabel,
                        showPresetChip = showPresetChip,
                        onPresetClick = {
                            vm.refreshAgentPresets()
                            showPresets = true
                        },
                        onSend = {
                            val text = draft.text
                            draft = TextFieldValue("")
                            stickToBottom = true
                            vm.send(text)
                        },
                        onStop = vm::stop,
                        // A child can land on the hero for the moment before its
                        // history snapshot arrives; the same gate claims its seat
                        // there as in a live session.
                        readOnlyReason = readOnlyReason,
                    )
                    connectionLabel?.let {
                        ConnectionPill(
                            label = it,
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = DshSpacing.md),
                        )
                    }
                }
            } else {
                // Ascending seq order from the reducer; reversed for reverseLayout
                // so the newest turn sits at the visual bottom.
                // `frozenLive` while the reader is in history, so the streaming row
                // cannot grow and reflow the list under them.
                val shownLive = frozenLive ?: live
                val rows = remember(entries, foldedTurns, expandedTurns, shownLive) {
                    buildDisplayRows(entries, foldedTurns, expandedTurns, shownLive).asReversed()
                }

                // The tool-call forest, keyed by call id so a row can find its own
                // node. Keyed on `entries` because that is what changes when a
                // dispatch event lands: the same event either adds the sub-call's
                // row or fills its result in.
                val toolNodes = remember(entries) {
                    buildToolCallTree(
                        entries.filterIsInstance<ChatEntry.ToolCall>(),
                        vm.toolParents(),
                    ).associateBy { it.entry.callId }
                }

                // Jump targets: the row index of every real (non-plugin) user
                // message, oldest first so the rail reads top-to-bottom in time.
                // Each carries its prompt and the assistant prose that answered
                // it, which is what the rail's preview shows while you scrub.
                val jumpTargets = remember(rows) {
                    rows.withIndex()
                        .mapNotNull { (index, row) ->
                            val entry = (row as? DisplayRow.Single)?.entry
                            if (entry is ChatEntry.UserMessage && !entry.fromPlugin) {
                                TurnJump(
                                    index = index,
                                    prompt = entry.text,
                                    response = responseAfter(rows, index),
                                )
                            } else {
                                null
                            }
                        }
                        .reversed()
                }
                val firstVisible by remember { derivedStateOf { listState.firstVisibleItemIndex } }
                // The turn you are reading is the newest target at or above the
                // first visible row.
                val activeTarget = remember(jumpTargets, firstVisible) {
                    jumpTargets.filter { it.index <= firstVisible }.maxByOrNull { it.index }?.index
                }

                // Token deltas change the live row's height without changing the
                // item count, so height growth has to trigger the follow too.
                val liveLength = (live?.text?.length ?: 0) + (live?.reasoning?.length ?: 0)
                LaunchedEffect(rows.size, liveLength, todos.size) {
                    // `isScrollInProgress` is the guard that matters: without it a
                    // streaming token could jump the list mid-drag, which reads as
                    // the app fighting the finger.
                    if (stickToBottom && !listState.isScrollInProgress && rows.isNotEmpty()) {
                        listState.scrollToItem(0)
                    }
                }

                // Bug A: the transcript is the flex child, so a row inserted
                // above it takes its height out of the transcript's flexible
                // space and leaves every seat below — the dock, the composer —
                // exactly where it was. The newest turn stays pinned to the
                // bottom and no row moves.
                connectionLabel?.let {
                    ConnectionPill(
                        label = it,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .padding(top = DshSpacing.sm),
                    )
                }

                // reverseLayout keeps the newest turn pinned to the bottom, so a
                // streaming answer never yanks the viewport while you read history.
                Box(Modifier.weight(1f)) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        reverseLayout = true,
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            start = DshSpacing.xl,
                            end = DshSpacing.xl,
                            top = DshSpacing.xl,
                            bottom = DshSpacing.md,
                        ),
                        verticalArrangement = Arrangement.spacedBy(DshSpacing.xl),
                    ) {
                        // The running-turn status line sits at the visual bottom, which
                    // under reverseLayout means emitting it first. Gated on this
                    // session's own turn, not the host's `running`: a parent whose
                    // turn the journal has closed reports a live subagent through the
                    // header's `+N running` chip, not by wearing this row.
                    if (ui.ownTurnInFlight) {
                        item(key = "turn-status") { TurnStatusRow(elapsedMs) }
                    }
                    items(items = rows, key = { it.key }) { row ->
                            Box(Modifier.widthIn(max = 920.dp)) {
                              Column {
                                when (row) {
                                    is DisplayRow.Single -> when (val entry = row.entry) {
                                        is ChatEntry.UserMessage -> UserMessageRow(entry, vm::imageBitmap)
                                        is ChatEntry.AssistantMessage -> AssistantMessageRow(
                                            entry = entry,
                                            showFooter = entry.seq in closingSeqs,
                                            durationMs = turnDurations[entry.turn],
                                        )
                                        is ChatEntry.ToolCall -> {
                                            // A PTC sub-call renders under the call
                                            // that dispatched it; every other call
                                            // has no node children and falls through
                                            // to the same flat row as before.
                                            val node = toolNodes[entry.callId]
                                            if (node != null) {
                                                ToolCallTreeRow(node, vm::imageBitmap)
                                            } else {
                                                ToolCallRow(entry, vm::imageBitmap)
                                            }
                                        }
                                        is ChatEntry.Todos -> TodoCard(entry.items)
                                        is ChatEntry.Notice -> NoticeRow(entry)
                                    }

                                    is DisplayRow.TurnProcess -> TurnProcessRow(row) {
                                        expandedTurns = if (row.turn in expandedTurns) {
                                            expandedTurns - row.turn
                                        } else {
                                            expandedTurns + row.turn
                                        }
                                    }

                                    is DisplayRow.Live -> LiveAttemptRow(row.attempt)
                                }

                                // `turn-deliverables.ts`: the row belongs to the turn's
                                // *closing* message, so it lands after the prose that
                                // ended the turn — and, for a folded turn, after the
                                // process summary that now stands in for that prose.
                                // Through-seq is the closing message's own seq, which is
                                // the web's `producedForClosing(data, owner.seq)`.
                                val delivered = when {
                                    row is DisplayRow.TurnProcess ->
                                        deliverableCache.getOrPut(row.key) {
                                            SessionFiles.deliverablesFor(entries, row.turn)
                                        }

                                    row is DisplayRow.Single &&
                                        row.entry is ChatEntry.AssistantMessage &&
                                        row.entry.seq in closingSeqs ->
                                        deliverableCache.getOrPut(row.key) {
                                            SessionFiles.deliverablesFor(entries, row.entry.turn, row.entry.seq)
                                        }

                                    else -> null
                                }
                                if (delivered != null && !delivered.isEmpty) {
                                    DeliverableRow(
                                        delivered = delivered,
                                        onOpen = { path ->
                                            filesFocus = path
                                            showFiles = true
                                        },
                                    )
                                }
                              }
                            }
                        }
                    }

                    // Turn navigator rail: one tick per human message that opened a
                    // turn, so you can jump straight to any prompt. The web hides it
                    // under a 900px container; on a phone it is the fastest way back
                    // through a long session, so it stays.
                    if (jumpTargets.size > 1) {
                        TurnRail(
                            targets = jumpTargets,
                            activeIndex = activeTarget,
                            onJump = { index -> scope.launch { listState.animateScrollToItem(index) } },
                            // Flush to the edge and only as wide as the ticks: the
                            // rail owns the whole column for pointer input, so any
                            // inset ate into the rows' trailing controls (the To-dos
                            // chevron sits in that strip and was unreachable).
                            modifier = Modifier
                                .align(Alignment.CenterEnd)
                                .padding(end = 1.dp),
                        )
                    }

                    // With reverseLayout index 0 is the bottom, so "at the bottom"
                    // is exactly "cannot scroll backwards".
                    if (!atBottom) {
                        ScrollToBottomButton(
                            onClick = { scope.launch { listState.animateScrollToItem(0) } },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(end = DshSpacing.xl, bottom = DshSpacing.lg),
                        )
                    }
                }

                if (ui.approval != null) {
                    ApprovalCard(
                        toolName = ui.approval!!.toolName,
                        reason = ui.approval!!.reason,
                        onAllow = { vm.resolveApproval(true) },
                        onReject = { vm.resolveApproval(false) },
                        modifier = Modifier.padding(horizontal = DshSpacing.xl, vertical = DshSpacing.sm),
                    )
                }

                // A host question blocks the agent's tool call, so it takes the
                // same seat as an approval — directly above the composer.
                ui.questions?.let { questions ->
                    QuestionCard(
                        set = questions,
                        onSubmit = vm::answerQuestions,
                        onSkip = vm::skipQuestions,
                        modifier = Modifier.padding(horizontal = DshSpacing.xl, vertical = DshSpacing.sm),
                    )
                }

                // Dock order mirrors the web UI: todo (0) → goal (10) → queue (20).
                if (todos.isNotEmpty()) {
                    TodoCard(
                        items = todos,
                        modifier = Modifier.padding(horizontal = DshSpacing.xl, vertical = DshSpacing.sm),
                    )
                }

                ui.goal?.takeIf { it.visible }?.let { goal ->
                    GoalBar(
                        goal = goal,
                        onPause = vm::pauseGoal,
                        onResume = vm::resumeGoal,
                        onEdit = vm::editGoal,
                        onClear = vm::clearGoal,
                        modifier = Modifier.padding(horizontal = DshSpacing.xl, vertical = DshSpacing.sm),
                    )
                }

                // Queue (dock order 20). Its bottom 3dp are tucked under the
                // composer card — the web's negative margin — so the two read as
                // one surface; Column paints the later child over the earlier
                // one, which is what hides the seam. The dock stays composed when
                // the queue is empty so it can animate itself away, and its offset
                // spans the composer's top gap as well: that keeps the composer
                // still while the dock unfolds and sinks above it.
                //
                // The extra `md` inset on each side is the web's
                // `--dsh-composer-dock-inset` (8px): the panel is deliberately
                // *narrower* than the input card, so its square bottom corners
                // hide inside the card's 22dp rounded top corners. At the card's
                // own width they poked out beside the curve as two dark ears,
                // which is what made the join read as two separate cards.
                // Boxed rather than modified in place: the dock's own size
                // animation clips its content to the growing bounds, so the offset
                // has to live outside it or the tucked bottom edge gets cut off.
                Box(
                    Modifier
                        .padding(horizontal = DshSpacing.xl + DshSpacing.md)
                        .offset(y = DshSpacing.sm + 3.dp),
                ) {
                    QueueDock(
                        items = ui.queue,
                        running = ui.running,
                        mutable = queueMutable,
                        busy = ui.queueBusy,
                        onEdit = vm::editQueueItem,
                        onRemove = vm::removeQueueItem,
                        onSteer = vm::steerQueueItem,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // The menu sits directly above the composer card, as the web's
                // menu is anchored to the card's top edge.
                // `@` and `/` are mutually exclusive by their first character, so
                // only one of these can be open at a time.
                ComposerPopups(
                    triggers = triggers,
                    references = references,
                    value = draft,
                    onValueChange = { draft = it },
                    menuOpen = menuOpen,
                    onPickCommand = onPickCommand,
                    clearReferences = vm::clearReferences,
                    modifier = Modifier.padding(
                        start = DshSpacing.xl,
                        end = DshSpacing.xl,
                        bottom = DshSpacing.sm,
                    ),
                )

                if (readOnlyReason != null) {
                    // The frame takes the composer's own slot and padding, so the
                    // queue dock, the pills below and the transcript above it do
                    // not move when the seat is claimed.
                    SubagentReadOnlyComposer(
                        reason = readOnlyReason,
                        modifier = Modifier.padding(
                            start = DshSpacing.xl,
                            end = DshSpacing.xl,
                            // Keeps the frame in the composer's slot: the same
                            // 6dp the card leaves above the pills.
                            bottom = DshSpacing.sm,
                            top = DshSpacing.sm,
                        ),
                    )
                } else {
                    Composer(
                        value = draft,
                        onValueChange = { draft = it },
                        onSend = {
                            val text = draft.text
                            draft = TextFieldValue("")
                            stickToBottom = true
                            vm.send(text, vm.submitMode())
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        onSendMode = { mode ->
                            val text = draft.text
                            draft = TextFieldValue("")
                            stickToBottom = true
                            vm.send(text, mode)
                            scope.launch { listState.animateScrollToItem(0) }
                        },
                        onToggleBusyEnter = {
                            vm.updateBusyEnter(
                                if (ui.busyEnter == BusyEnter.STEER) BusyEnter.QUEUE else BusyEnter.STEER,
                            )
                        },
                        busyEnter = ui.busyEnter,
                        onStop = vm::stop,
                        running = ui.running,
                        modelLabel = modelLabel,
                        onModelClick = { showModels = true },
                        permissionLabel = permissionLabel,
                        onPermissionClick = { showPermission = true },
                        planActive = ui.planActive,
                        onExitPlan = vm::exitPlanMode,
                        contextPercent = ui.contextPercent,
                        contextTokens = ui.contextTokens,
                        contextWindow = ui.contextWindow,
                        contextBreakdown = ui.contextBreakdown,
                        attachments = ui.attachments,
                        onToggleCommands = { menuOpen = !menuOpen },
                        onRemoveAttachment = vm::removeAttachment,
                        modifier = Modifier.padding(
                            start = DshSpacing.xl,
                            end = DshSpacing.xl,
                            // Matches the pills' own bottom margin below, so the
                            // two gaps around the row are the same 6dp. With the
                            // pills absent this is the composer's clearance to the
                            // navigation inset, which is why it is not larger.
                            bottom = DshSpacing.sm,
                            // Constant whether or not the dock is present: the dock's
                            // own offset reaches back down over this gap when it is.
                            top = DshSpacing.sm,
                        ),
                    )
                }

                // The web mounts the statistic pills on the composer dock, just
                // under the card — not in the header, which is where they would
                // otherwise look like they belonged. Self-gating, so an empty
                // session (and every session before its projections land) draws
                // nothing at all rather than an empty row.
                SessionStatsPills(
                    stats = ui.sessionStats,
                    modifier = Modifier.padding(
                        start = DshSpacing.xl,
                        end = DshSpacing.xl,
                        // The block is the last thing in the column, so it owns the
                        // gap to the navigation inset; without it the pills sat on
                        // whatever the inset happened to be on that device.
                        bottom = DshSpacing.sm,
                    ),
                )
            }
        }
    }

    if (showSettings) {
        SettingsSheet(
            themeMode = ui.themeMode,
            agentPreset = ui.agentPreset,
            onTheme = vm::updateTheme,
            onPreset = vm::updatePreset,
            busyEnter = ui.busyEnter,
            onBusyEnter = vm::updateBusyEnter,
            onDismiss = { showSettings = false },
            // Read-only parity data (SettingsContent.kt). The General rows are
            // host-backed now; the values below are the pre-read fallbacks and the
            // rows the app has no writer for.
            hostSettings = ui.hostSettings,
            hostSettingsLoading = ui.hostSettingsLoading,
            hostSettingsError = ui.hostSettingsError,
            settingsSaving = ui.settingsSaving,
            settingsWriteError = ui.settingsWriteError,
            onSettingWrite = vm::writeHostSetting,
            models = ui.models,
            providerOrder = ui.providerOrder,
            permissionOptions = ui.permissionOptions,
            // No default-permission state exists, so the row shows "Unavailable"
            // rather than claiming a value the host never sent.
            defaultPermission = "",
            // The app only implements the folded ("Compact") transcript view.
            transcriptView = "compact",
            agentPresetOptions = ui.agentPresetOptions,
            agentModePickerEnabled = true,
            // Archived ids are a set on this client, so the host's own
            // archive order is not recoverable here.
            archivedSessions = ui.sessions.filter { it.id in ui.archivedSessionIds },
            workspaces = ui.workspaces,
            sessionsLoading = ui.sessionsLoading,
            onUnarchive = vm::unarchiveSession,
        )
    }

    if (showAbout) {
        AboutSheet(
            baseUrl = ui.baseUrl,
            username = ui.username,
            socketConnected = ui.socketConnected,
            onSignOut = {
                showAbout = false
                vm.signOut()
            },
            onDismiss = { showAbout = false },
            // The same session facts the Settings sheet used to print, from the
            // same two sources: the roster row and the session header.
            sessionTitle = current?.title.orEmpty(),
            sessionCwd = current?.cwd ?: header?.cwd,
            sessionPermission = ui.currentPermission,
            sessionAgentPreset = ui.currentAgentPreset,
            permissionOptions = ui.permissionOptions,
            selectedModel = ui.selectedModel,
            selectedEffort = ui.selectedEffort,
        )
    }

    if (showLineage && current != null) {
        ModalBottomSheet(
            onDismissRequest = { showLineage = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DshTheme.colors.bgBase,
        ) {
            LineageSheet(
                rootId = current.id,
                sessions = ui.sessions,
                rollups = subagentRollups,
                onOpen = { id ->
                    showLineage = false
                    vm.openSession(id)
                },
            )
        }
    }

    if (showJobs) {
        ModalBottomSheet(
            onDismissRequest = { showJobs = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = DshTheme.colors.bgBase,
        ) {
            JobsSheet(
                jobs = ui.jobs,
                onDismiss = { showJobs = false },
                modifier = Modifier.padding(horizontal = DshSpacing.lg, vertical = DshSpacing.md),
            )
        }
    }

    if (showWorkspace) {
        WorkspaceSheet(
            workspaces = ui.workspaces,
            currentCwd = current?.cwd,
            onPickWorkspace = { workspaceId ->
                showWorkspace = false
                vm.startSessionInWorkspace(workspaceId)
            },
            onPickPath = { cwd ->
                showWorkspace = false
                vm.startSessionIn(cwd)
            },
            onDismiss = { showWorkspace = false },
        )
    }

    // Registering a directory is a different act from picking one for a new
    // session: Open here adds the Workspace, then adopts it the same way the
    // workspace chip does, so the session you land in is the one you just added.
    if (showBrowser) {
        WorkspaceBrowser(
            level = directoryLevel,
            loading = directoryLoading,
            busy = directoryBusy,
            error = directoryError,
            onList = vm::listDirectory,
            onCreateDirectory = vm::createDirectory,
            onOpen = { path ->
                vm.addWorkspace(path) { workspaceId ->
                    showBrowser = false
                    // The id, not the path: only it attaches the new session to the
                    // workspace, so the session lands in that group.
                    if (workspaceId.isNotEmpty()) {
                        vm.startSessionInWorkspace(workspaceId)
                    } else {
                        vm.startSessionIn(path)
                    }
                }
            },
            onDismiss = {
                showBrowser = false
                vm.resetDirectoryBrowser()
            },
        )
    }

    if (showPermission) {
        PermissionSheet(
            options = ui.permissionOptions,
            current = ui.currentPermission,
            onSelect = {
                vm.setPermission(it)
                showPermission = false
            },
            onDismiss = { showPermission = false },
        )
    }

    if (showPresets) {
        AgentPresetSheet(
            options = ui.agentPresetOptions,
            current = ui.currentAgentPreset,
            onSelect = {
                vm.selectAgentPreset(it)
                showPresets = false
            },
            onDismiss = { showPresets = false },
        )
    }

    if (showModels) {
        ModelSheet(
            models = ui.models,
            providerOrder = ui.providerOrder,
            selected = ui.selectedModel,
            selectedEffort = ui.selectedEffort,
            onSelect = { option, effort ->
                vm.selectModel(option, effort)
                showModels = false
            },
            onDismiss = { showModels = false },
        )
    }
}

/**
 * The header's lineage seat: the descendant count plus the chevron that opens the
 * tree, mirroring `SubagentHeaderLineage`.
 *
 * The label is deliberately terse (`+1 subagent`) — see [SubagentRollup.label] — so
 * a live session is signalled by the accent tint and an ongoing dot rather than by
 * the word "running", and the chip keeps the same width as subagents start and
 * finish. The full web sentence is the accessibility label.
 */
@Composable
private fun LineageChip(rollup: SubagentRollup, onClick: () -> Unit) {
    val colors = DshTheme.colors
    val running = rollup.running > 0
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(colors.hover)
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.sm, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (running) {
            StateDot(state = DotState.ONGOING, size = 8.dp)
            Spacer(Modifier.width(DshSpacing.sm))
        }
        Text(
            text = rollup.label,
            style = DshType.micro,
            color = if (running) colors.link else colors.labelTertiary,
            maxLines = 1,
            modifier = Modifier.semantics { contentDescription = rollup.description },
        )
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = if (running) colors.link else colors.labelCaption,
            modifier = Modifier
                .size(14.dp)
                .padding(start = 1.dp),
        )
    }
}

/** One row of the lineage tree: the subagent, indented by its depth. */
private data class LineageRow(val session: uk.xa0.dsh.SessionItem, val depth: Int)

/**
 * Flattens [parentId]'s subagent descendants depth-first, so the sheet can render
 * the web's `role="tree"` as an indented list.
 *
 * Depth is capped: the host caps a real lineage far below this, and a cycle would
 * otherwise be unbounded.
 */
private fun flattenLineage(
    sessions: List<uk.xa0.dsh.SessionItem>,
    parentId: String,
    maxDepth: Int = 6,
): List<LineageRow> {
    val out = ArrayList<LineageRow>()
    fun walk(id: String, depth: Int) {
        if (depth > maxDepth) return
        for (child in subagentChildrenOf(sessions, id)) {
            out += LineageRow(child, depth)
            walk(child.id, depth + 1)
        }
    }
    walk(parentId, 0)
    return out
}

/**
 * The lineage dropdown: the subagents under the current session that are working
 * right now, newest first, each row opening that subagent's own session.
 *
 * Running only, to match the chip that opens it. The web portals a full `role="tree"`
 * of every descendant here; on this client the complete tree - archived records
 * included - is the drawer's per-session caret, and duplicating it in a sheet made
 * the chip's number and the sheet's contents disagree.
 */
@Composable
private fun LineageSheet(
    rootId: String,
    sessions: List<uk.xa0.dsh.SessionItem>,
    rollups: Map<String, SubagentRollup>,
    onOpen: (String) -> Unit,
) {
    val colors = DshTheme.colors
    val rows = remember(sessions, rootId) {
        flattenLineage(sessions, rootId).filter { it.session.running }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.md),
    ) {
        Text("Running subagents", style = DshType.heading3, color = colors.labelPrimary)
        Spacer(Modifier.height(DshSpacing.sm))
        if (rows.isEmpty()) {
            Text("Nothing is running.", style = DshType.bodyMedium, color = colors.labelTertiary)
            Spacer(Modifier.height(DshSpacing.xl))
            return@Column
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            items(rows, key = { it.session.id }) { row ->
                LineageSheetRow(
                    session = row.session,
                    depth = row.depth,
                    rollup = rollups[row.session.id],
                    onClick = { onOpen(row.session.id) },
                )
            }
        }
        Spacer(Modifier.height(DshSpacing.md))
    }
}

@Composable
private fun LineageSheetRow(
    session: uk.xa0.dsh.SessionItem,
    depth: Int,
    rollup: SubagentRollup?,
    onClick: () -> Unit,
) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(DshRadius.md))
            .clickableNoRipple(onClick = onClick)
            .padding(
                start = DshSpacing.md + (DshSpacing.xl * depth),
                end = DshSpacing.md,
                top = DshSpacing.lg,
                bottom = DshSpacing.lg,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StateDot(
            state = when {
                session.running -> DotState.ONGOING
                session.blank -> DotState.IDLE
                else -> DotState.DONE
            },
            size = 8.dp,
        )
        Spacer(Modifier.width(DshSpacing.md))
        Text(
            text = session.subagentLabel ?: session.title.ifBlank { session.id },
            style = DshType.bodyMedium,
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        // A child that has children of its own says so, which is what makes the
        // flattened tree readable.
        if (rollup != null && !rollup.isEmpty) {
            Spacer(Modifier.width(DshSpacing.md))
            Text(
                text = rollup.total.toString(),
                style = DshType.micro,
                color = if (rollup.running > 0) colors.link else colors.labelCaption,
            )
        }
        Spacer(Modifier.width(DshSpacing.md))
        Text(
            text = when (session.subagentMode) {
                "continuable" -> "Continuable"
                "one-shot" -> "One-shot"
                else -> ""
            },
            style = DshType.micro,
            color = colors.labelCaption,
            maxLines = 1,
        )
    }
}

@Composable
private fun ChatHeader(
    title: String,
    cwd: String?,
    onMenu: () -> Unit,
    jobs: List<uk.xa0.dsh.JobItem> = emptyList(),
    jobsFinishedUnseen: Boolean = false,
    onJobs: () -> Unit = {},
    view: ChatView = ChatView.CHAT,
    onView: (ChatView) -> Unit = {},
    showTabs: Boolean = false,
    filesOpen: Boolean = false,
    onFiles: () -> Unit = {},
    lineage: SubagentRollup? = null,
    onLineage: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    Column(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(56.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Menu,
                contentDescription = "Sessions",
                tint = colors.labelPrimary,
                modifier = Modifier
                    .size(HEADER_SEAT)
                    .clip(CircleShape)
                    .clickableNoRipple(onClick = onMenu)
                    .padding(DshSpacing.md),
            )
            Spacer(Modifier.width(HEADER_SEAT_GAP))
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = DshType.messageBody.copy(fontWeight = FontWeight.Medium),
                    color = colors.labelPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!cwd.isNullOrBlank()) {
                    Text(
                        text = shortenPath(cwd),
                        style = DshType.bodySmall,
                        color = colors.labelTertiary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // The `SubagentHeaderLineage` seat, worn as one of the header's trailing
            // seats rather than inline after the title.
            //
            // Inline, it centred on the title *line* while the block it sits in is
            // two lines tall (title + directory), which measured 22px — about 8dp on
            // this 420dpi screen — above the block's centre, so it read as floating
            // high. Here it centres on the header row like the files and jobs seats,
            // and the title gets its full width back instead of truncating to make
            // room for it.
            //
            // Running only, and absent when nothing is running: this is a live
            // indicator, and the drawer's per-session caret is the complete tree.
            if (lineage != null && lineage.running > 0) {
                LineageChip(lineage, onLineage)
                Spacer(Modifier.width(HEADER_SEAT_GAP))
            }
            // The right panel's seat, which on the web is a header utility. It
            // stays available on the hero screen too: a blank session still has a
            // workspace worth listing.
            Icon(
                Icons.Rounded.Folder,
                contentDescription = if (filesOpen) "Close files" else "Files",
                tint = if (filesOpen) colors.link else colors.labelSecondary,
                modifier = Modifier
                    .size(HEADER_SEAT)
                    .clip(CircleShape)
                    .clickableNoRipple(onClick = onFiles)
                    .padding(DshSpacing.md),
            )
            Spacer(Modifier.width(HEADER_SEAT_GAP))
            JobsSeat(jobs = jobs, finishedUnseen = jobsFinishedUnseen, onClick = onJobs)
            // No separate running-turn dot here on purpose. `JobsSeat` already
            // draws a `StateDot(ONGOING)` while a job runs, and this glyph is the
            // same one, so a running turn plus a running job painted two identical
            // spinners side by side, animating on two independent transitions -
            // visibly out of step. The web has no running indicator in the
            // conversation header at all (`conversation.session.header.utilities`
            // has exactly one contributor, the desktop-only open-in-app button);
            // running state is the sidebar row's status and the composer's own
            // stop affordance, both of which this app already has.
            Spacer(Modifier.width(HEADER_SEAT_GAP))
        }
        if (showTabs) {
            ViewTabs(selected = view, onSelect = onView)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.borderL3),
        )
    }
}

/**
 * The two registered `conversation.view` entries, in the host's own order.
 *
 * `ui-conversation/src/client/contract/views.ts` — chat (order 0, the default in
 * `view-selection.ts`) and trajectory (order 10).
 */
private enum class ChatView(val label: String) {
    CHAT("Chat"),
    TRAJECTORY("Trajectory"),
}

/**
 * `.tabs` from the conversation header.
 *
 * The web's active state is a 2px underline plus `state-business-primary` text,
 * not a pill; inactive labels are `label-tertiary` at 13px/16px weight 500. The
 * strip is `gap:36px; margin-top:10px; padding-left:8px`. 36px gaps would push
 * the second label off a narrow phone once both words are drawn, so the gap is
 * tightened — the only deliberate divergence, and it costs nothing to read.
 */
@Composable
private fun ViewTabs(selected: ChatView, onSelect: (ChatView) -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = DshSpacing.md, end = DshSpacing.md, top = DshSpacing.md),
        horizontalArrangement = Arrangement.spacedBy(DshSpacing.xxl),
    ) {
        ChatView.entries.forEach { entry ->
            val active = entry == selected
            // `IntrinsicSize.Max` is what keeps the underline as wide as the label:
            // a plain `fillMaxWidth()` inside the Row measures against the *row*,
            // so the first tab would stretch across the strip and push the second
            // one off the screen.
            Column(
                Modifier
                    .width(IntrinsicSize.Max)
                    .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                    .clickableNoRipple { onSelect(entry) }
                    .padding(horizontal = DshSpacing.xs, vertical = DshSpacing.md),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = entry.label,
                    style = DshType.micro.copy(fontSize = 13.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
                    color = if (active) colors.link else colors.labelTertiary,
                )
                Spacer(Modifier.height(DshSpacing.md))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (active) colors.link else Color.Transparent),
                )
            }
        }
    }
}

/**
 * The `files` surface of the web's right panel, full-body on a phone.
 *
 * The web opens it as a preview tab beside the transcript and keeps the header in
 * place; a phone has no room for both, so this carries its own back bar and the
 * path it was asked to select.
 */
@Composable
private fun FilesSurface(
    files: List<uk.xa0.dsh.model.SessionFile>,
    previews: Map<String, uk.xa0.dsh.model.FilePreview>,
    root: String?,
    focus: String?,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Reads a file the *transcript* never touched. Without it the pane can only
     * ever show what the session itself read or wrote, which is most of the reason
     * a file the agent merely mentioned looked like it had no contents.
     */
    loader: uk.xa0.dsh.model.WorkspaceFileLoader? = null,
) {
    val colors = DshTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(colors.bgBase),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .padding(horizontal = DshSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.ArrowBack,
                contentDescription = "Back to chat",
                tint = colors.labelSecondary,
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .clickableNoRipple(onClick = onClose)
                    .padding(DshSpacing.md),
            )
            Spacer(Modifier.width(DshSpacing.sm))
            Text(
                text = "Files",
                style = DshType.messageBody.copy(fontWeight = FontWeight.Medium),
                color = colors.labelPrimary,
            )
            if (root != null) {
                Spacer(Modifier.width(DshSpacing.md))
                Text(
                    text = shortenPath(root),
                    style = DshType.bodySmall,
                    color = colors.labelTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(colors.borderL3),
        )
        FilesPanel(
            files = files,
            root = root,
            cached = previews,
            loader = loader,
            initialPath = focus,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun RunningDot() {
    val transition = rememberInfiniteTransition(label = "running")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label = "alpha",
    )
    Box(
        Modifier
            .size(8.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(DshTheme.colors.accent),
    )
}

/**
 * The web UI's back-to-bottom affordance: a 34dp floating circle pinned above the
 * composer, shown only once you have scrolled away from the newest turn.
 */
@Composable
private fun ScrollToBottomButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Box(
        modifier
            .size(34.dp)
            .shadow(2.dp, CircleShape)
            .clip(CircleShape)
            .background(colors.bgLayer2)
            .border(0.5.dp, colors.borderL3, CircleShape)
            .clickableNoRipple(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.ArrowDownward,
            contentDescription = "Scroll to latest",
            tint = colors.labelPrimary,
            modifier = Modifier.size(16.dp),
        )
    }
}

/** One rail mark: where in the transcript it jumps to, and what it says. */
private data class TurnJump(val index: Int, val prompt: String, val response: String)

/**
 * The assistant prose that answered the prompt at [index].
 *
 * [rows] is newest-first, so the answer lives at *smaller* indices: walk back
 * until the previous human message, collecting assistant text in turn order.
 */
private fun responseAfter(rows: List<DisplayRow>, index: Int): String =
    rows.subList(0, index)
        .takeWhile { row ->
            val entry = (row as? DisplayRow.Single)?.entry
            !(entry is ChatEntry.UserMessage && !entry.fromPlugin)
        }
        .asReversed()
        .mapNotNull { ((it as? DisplayRow.Single)?.entry as? ChatEntry.AssistantMessage)?.text }
        .filter { it.isNotBlank() }
        .joinToString("\n")

/**
 * The turn rail.
 *
 * At rest it is the web's rail: short ticks — 12dp idle, 20dp for the turn you
 * are reading — inside a taller tap target, since the web's 12x2px marks are far
 * below a comfortable touch size.
 *
 * Pressing it grows the whole rail into a scrubber, and it stays grown until the
 * finger lifts: the ticks widen, a panel appears behind them, and the mark under
 * your finger highlights with a preview of that turn's prompt and answer. Lifting
 * jumps there. This is the web's hover affordance (preview mark + tooltip card)
 * translated to touch, which is the only way its rail can work at all on a
 * phone — there is no hover to reveal anything.
 */
@Composable
private fun TurnRail(
    targets: List<TurnJump>,
    activeIndex: Int?,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    val shown = targets.takeLast(MAX_RAIL_TICKS)
    var pressed by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf<Int?>(null) }
    // The tick ladder's own bounds, in this Box's coordinates: the pointer only
    // knows a local y, and the surface padding shifts the ladder inside the Box.
    var ladder by remember { mutableStateOf<Rect?>(null) }

    val pad by animateDpAsState(if (pressed) 8.dp else 0.dp, label = "railPad")
    val railShape = RoundedCornerShape(DshRadius.card)
    val previewIndex = hovered?.takeIf { pressed && it in shown.indices }
    val preview = previewIndex?.let { shown[it] }

    Box(
        modifier
            .clip(railShape)
            .then(if (pressed) Modifier.background(colors.tip).border(0.5.dp, colors.borderL1, railShape) else Modifier)
            .padding(horizontal = pad, vertical = pad)
            .pointerInput(shown.size) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    hovered = shown.indexOfAt(down.position.y, ladder)
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) {
                            // Release is the commit: the web's onClick lands here too.
                            hovered?.let { if (it in shown.indices) onJump(shown[it].index) }
                            pointer.consume()
                            break
                        }
                        hovered = shown.indexOfAt(pointer.position.y, ladder)
                        pointer.consume()
                    }
                    pressed = false
                    hovered = null
                }
            },
    ) {
        Column(
            Modifier
                .width(RAIL_WIDTH)
                .heightIn(max = 280.dp)
                .onGloballyPositioned { ladder = it.boundsInParent() },
            verticalArrangement = Arrangement.spacedBy(DshSpacing.sm),
            horizontalAlignment = Alignment.End,
        ) {
            shown.forEachIndexed { position, target ->
                val active = target.index == activeIndex
                val isHovered = position == previewIndex
                val width by animateDpAsState(
                    when {
                        active -> 20.dp
                        isHovered -> 18.dp
                        pressed -> 14.dp
                        else -> 12.dp
                    },
                    label = "railTick",
                )
                val tint = when {
                    active -> colors.labelPrimary
                    isHovered -> colors.labelTertiary
                    else -> colors.borderL4
                }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(16.dp),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .width(width)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(tint),
                    )
                }
            }
        }

        // Drawn fully to the left of the rail — the web's `right: calc(100% + 10px)`
        // measures from the frame's *left* edge, not from the marks — so it never
        // covers the ticks it is describing. The Box is unclipped, so the card
        // overhangs the transcript gutter.
        if (preview != null) {
            TurnPreviewCard(
                preview = preview,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .offset(x = -(RAIL_WIDTH + pad * 2 + 10.dp)),
            )
        }
    }
}

/** The rail's hover card: the turn's prompt, then its answer. */
@Composable
private fun TurnPreviewCard(preview: TurnJump, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    val shape = RoundedCornerShape(DshRadius.lg)
    Column(
        modifier
            .widthIn(max = 260.dp)
            .shadow(6.dp, shape)
            .clip(shape)
            .background(colors.menu)
            .border(0.5.dp, colors.borderL1, shape)
            .padding(horizontal = DshSpacing.lg, vertical = 10.dp),
    ) {
        Text(
            text = preview.prompt,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.labelPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (preview.response.isNotBlank()) {
            Spacer(Modifier.height(DshSpacing.xs))
            Text(
                text = preview.response,
                style = DshType.bodySmall,
                color = colors.labelCaption,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Which tick a pointer at [y] is over.
 *
 * Rows are a uniform pitch, so the ladder's own height divides cleanly — no need
 * to know the gap. [ladder] is the measured ladder rect (in the gesture owner's
 * coordinates), which the rail's own padding would otherwise skew.
 */
private fun List<TurnJump>.indexOfAt(y: Float, ladder: Rect?): Int? {
    val rect = ladder ?: return null
    if (isEmpty() || rect.height <= 0f) return null
    val relative = (y - rect.top).coerceIn(0f, rect.height - 0.01f)
    return ((relative / rect.height) * size).toInt().coerceIn(0, size - 1)
}

private const val MAX_RAIL_TICKS = 22

/** The web's rail frame is 28px wide; the ticks are right-aligned inside it. */
private val RAIL_WIDTH = 22.dp

/** Sidebar width; `drawerDrag` needs it to know what a full travel is. */
private val DRAWER_WIDTH = 300.dp

/** How far from the left edge a closed drawer's swipe may start. */
private val DRAWER_EDGE = 96.dp

/** Horizontal travel that commits a drawer swipe; short on purpose. */
private val DRAWER_COMMIT = 40.dp

/**
 * The header's seat rhythm.
 *
 * Every trailing seat — the lineage chip, the files button, the jobs pill — occupies
 * the same square touch box and is separated by the same gap. Before this each seat
 * carried its own inset and its own spacer (6dp after the hamburger, 6dp after the
 * chip, none at all between files and jobs, then a 12dp tail), so the spacing was
 * whatever the paddings happened to add up to and the row read as unevenly bunched.
 */
private val HEADER_SEAT = 40.dp
private val HEADER_SEAT_GAP = DshSpacing.xs

/**
 * A drawer swipe with a threshold that suits a thumb.
 *
 * Material's own gesture commits only once the panel has travelled half its
 * width — 150dp here — so an ordinary swipe shows a peek and springs back, which
 * reads as "it will not open". This replaces it (`gesturesEnabled = false`):
 * a rightward swipe opens and a leftward one closes from a modest travel or a
 * fling, while a vertical drag is handed straight back to the list, which is
 * what keeps transcript scrolling and the sidebar's own list usable.
 *
 * A closed drawer only listens near the left edge, so a horizontal drag inside
 * the transcript is not stolen. Material 1.2 exposes no finger-following
 * `dragTo`, so the panel animates the rest of the way once the swipe commits.
 */
@Composable
private fun Modifier.drawerDrag(drawerState: DrawerState, width: Dp): Modifier {
    val density = LocalDensity.current
    val viewConfiguration = LocalViewConfiguration.current
    val scope = rememberCoroutineScope()
    val edgePx = with(density) { DRAWER_EDGE.toPx() }
    val commitPx = with(density) { DRAWER_COMMIT.toPx() }
    val flingPx = with(density) { 200.dp.toPx() }
    // The panel's own width only matters to keep the parameter honest about what
    // "travel" means; the commit threshold above is absolute.
    val widthPx = with(density) { width.toPx() }

    return pointerInput(drawerState, widthPx) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (!drawerState.isOpen && down.position.x > edgePx) return@awaitEachGesture

            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var totalX = 0f
            var totalY = 0f
            var last = down.position
            var dragging = false

            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                // Something below us already took this gesture - the obvious case is
                // a horizontally scrollable bash output block, whose text starts
                // about 46dp from the screen edge and so sits inside the drawer's
                // 96dp band. In the Main pass the child has had its turn first, so a
                // consumed change means "this drag belongs to the transcript", and
                // claiming it anyway was why long lines could not be scrolled
                // sideways.
                if (change.isConsumed) return@awaitEachGesture
                totalX += change.position.x - last.x
                totalY += change.position.y - last.y
                last = change.position
                tracker.addPosition(change.uptimeMillis, change.position)

                if (!dragging) {
                    if (abs(totalX) > viewConfiguration.touchSlop && abs(totalX) > abs(totalY)) {
                        dragging = true
                    } else if (abs(totalY) > viewConfiguration.touchSlop) {
                        return@awaitEachGesture
                    }
                }
                if (dragging) change.consume()
            }

            if (!dragging) return@awaitEachGesture
            val velocity = tracker.calculateVelocity().x
            val open = when {
                velocity > flingPx -> true
                velocity < -flingPx -> false
                else -> totalX > commitPx
            }
            scope.launch { if (open) drawerState.open() else drawerState.close() }
        }
    }
}

/**
 * Derives [ComposerTriggers] and keeps the host's rosters in step with them.
 *
 * This is shared rather than duplicated because the hero and a session are the
 * same input surface: the blank-session seat used to get none of it, so `/` and
 * `@` did nothing there while the identical typing worked one screen later.
 *
 * The derivation itself lives in `ui/composer`, out of this 2100-line file; this
 * only drives the host callbacks the derived query feeds.
 */
@Composable
private fun rememberComposerTriggers(
    value: TextFieldValue,
    commandEntries: List<CommandEntry>,
    searchReferences: (String) -> Unit,
    clearReferences: () -> Unit,
    sessionId: String?,
    refreshCommands: () -> Unit,
): ComposerTriggers {
    val triggers = remember(value, commandEntries) { composerTriggers(value, commandEntries) }
    LaunchedEffect(triggers.referenceQuery) {
        val query = triggers.referenceQuery
        if (query != null) searchReferences(query) else clearReferences()
    }
    // The roster is the host's and is session-scoped, so it is re-read whenever
    // the open session changes rather than cached once at connect.
    LaunchedEffect(sessionId) { refreshCommands() }
    return triggers
}

/**
 * The two popups that hang off the composer: the `@` reference roster and the
 * `/` (or `+`) command roster. `@` and `/` are mutually exclusive by their first
 * character, so only one can be open at a time.
 */
@Composable
private fun ComposerPopups(
    triggers: ComposerTriggers,
    references: List<ReferenceCandidate>,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    menuOpen: Boolean,
    onPickCommand: (CommandEntry) -> Unit,
    clearReferences: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val query = triggers.referenceQuery
    if (query != null) {
        ReferenceMenu(
            entries = references,
            onPick = { candidate ->
                // Replace the token under the caret, keeping whatever surrounds
                // it in the draft; a pick must never rewrite the draft's end.
                onValueChange(value.replaceTrigger(triggers.reference, candidate.mention + " "))
                clearReferences()
            },
            modifier = modifier,
        )
    }
    if (menuOpen || triggers.slashQuery != null) {
        CommandMenu(entries = triggers.shownEntries, onPick = onPickCommand, modifier = modifier)
    }
}

/** Empty-session state: mark + "New Session" with the composer vertically centred. */
@Composable
private fun HeroBody(
    modifier: Modifier,
    value: TextFieldValue,
    onValueChange: (TextFieldValue) -> Unit,
    running: Boolean,
    modelLabel: String?,
    onModelClick: () -> Unit,
    permissionLabel: String?,
    onPermissionClick: () -> Unit,
    planActive: Boolean,
    onExitPlan: () -> Unit,
    contextPercent: Int,
    contextTokens: Int,
    contextWindow: Int,
    contextBreakdown: uk.xa0.dsh.ContextBreakdown?,
    attachments: List<uk.xa0.dsh.PendingAttachment>,
    onToggleCommands: () -> Unit,
    onRemoveAttachment: (String) -> Unit,
    menuOpen: Boolean,
    triggers: ComposerTriggers,
    references: List<ReferenceCandidate>,
    clearReferences: () -> Unit,
    onPickCommand: (CommandEntry) -> Unit,
    workspaceLabel: String,
    onWorkspaceClick: () -> Unit,
    presetLabel: String?,
    showPresetChip: Boolean,
    onPresetClick: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    readOnlyReason: SubagentReadOnlyReason?,
) {
    val colors = DshTheme.colors
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = DshSpacing.xl),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DshMark(size = 26.dp)
            Spacer(Modifier.width(10.dp))
            Text(
                text = "New Session",
                style = DshType.heading1.copy(fontWeight = FontWeight.Medium),
                color = colors.labelPrimary,
            )
        }
        Spacer(Modifier.height(DshSpacing.xxl))

        // The hero's workspace chip: which directory this session will run in. The
        // web places it 8px above the card, and it is a *different* control from
        // the access-mode chip inside the composer.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
        Row(
            Modifier
                .height(28.dp)
                .clip(RoundedCornerShape(DshRadius.thumb))
                .clickableNoRipple(onClick = onWorkspaceClick)
                .padding(horizontal = DshSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Folder,
                contentDescription = null,
                tint = colors.labelSecondary,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(DshSpacing.xs))
            Text(
                text = workspaceLabel,
                style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
                color = colors.labelSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(DshSpacing.xxs))
            Icon(
                Icons.Rounded.ExpandMore,
                contentDescription = null,
                tint = colors.labelCaption,
                modifier = Modifier.size(12.dp),
            )
        }
        // The agent-preset chip, the web hero's second seat: the preset this
        // session's agent will run. Offered only while the session is blank.
        if (showPresetChip && presetLabel != null) {
            Spacer(Modifier.width(DshSpacing.md))
            AgentPresetChip(label = presetLabel, onClick = onPresetClick)
        }
        }
        Spacer(Modifier.height(DshSpacing.md))

        // Typing `/` or `@` here behaves exactly as it does in a session: the same
        // triggers, the same host rosters, the same popups.
        ComposerPopups(
            triggers = triggers,
            references = references,
            value = value,
            onValueChange = onValueChange,
            menuOpen = menuOpen,
            onPickCommand = onPickCommand,
            clearReferences = clearReferences,
            modifier = Modifier.padding(bottom = DshSpacing.sm),
        )
        if (readOnlyReason != null) {
            SubagentReadOnlyComposer(reason = readOnlyReason)
        } else {
            Composer(
                value = value,
                onValueChange = onValueChange,
                onSend = onSend,
                onStop = onStop,
                running = running,
                modelLabel = modelLabel,
                onModelClick = onModelClick,
                permissionLabel = permissionLabel,
                onPermissionClick = onPermissionClick,
                planActive = planActive,
                onExitPlan = onExitPlan,
                contextPercent = contextPercent,
                contextTokens = contextTokens,
                contextWindow = contextWindow,
                contextBreakdown = contextBreakdown,
                attachments = attachments,
                onToggleCommands = onToggleCommands,
                onRemoveAttachment = onRemoveAttachment,
            )
        }
        Spacer(Modifier.height(DshSpacing.xl))
    }
}

/**
 * The agent-preset chip: a 28dp pill in the same family as the workspace chip,
 * with a hub glyph, the preset's display name, and a disclosure chevron.
 */
@Composable
private fun AgentPresetChip(label: String, onClick: () -> Unit) {
    val colors = DshTheme.colors
    Row(
        Modifier
            .height(28.dp)
            .clip(RoundedCornerShape(DshRadius.thumb))
            .clickableNoRipple(onClick = onClick)
            .padding(horizontal = DshSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Rounded.Hub,
            contentDescription = null,
            tint = colors.labelSecondary,
            modifier = Modifier.size(13.dp),
        )
        Spacer(Modifier.width(DshSpacing.xs))
        Text(
            text = label,
            style = DshType.bodyMedium.copy(fontWeight = FontWeight.Medium),
            color = colors.labelSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.width(DshSpacing.xxs))
        Icon(
            Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = colors.labelCaption,
            modifier = Modifier.size(12.dp),
        )
    }
}

/**
 * The one-line error strip over the transcript.
 *
 * Tapping anywhere dismisses it (the long-standing behaviour); [actionLabel]
 * adds a second, separately tappable verb for failures the user can actually do
 * something about — an expired session's "Sign in again", which is the
 * difference between a dead end and a way out.
 */
@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val colors = DshTheme.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(colors.warnTertiary)
            .padding(horizontal = DshSpacing.xl, vertical = DshSpacing.lg)
            .clickableNoRipple(onClick = onDismiss),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = message,
            style = DshType.bodySmall,
            color = colors.error,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            Text(
                text = actionLabel,
                style = DshType.bodySmall,
                color = colors.link,
                // Its own tap target: the inner clickable consumes the event, so
                // the action never also dismisses the banner it lives on.
                modifier = Modifier
                    .clickableNoRipple(onClick = onAction)
                    .padding(horizontal = DshSpacing.sm),
            )
        }
        Text("Dismiss", style = DshType.bodySmall, color = colors.link)
    }
}

/**
 * The connection pill (Bug A).
 *
 * The socket being down was invisible in the conversation: sends looked like
 * they went nowhere and nothing said why. This is the smallest honest thing to
 * put up — the shell's own two words, a chase dot while the supervisor is
 * working, and the browser surface's pill idiom. It is only ever composed for a
 * non-null [uk.xa0.dsh.UiState.connectionLabel], so an ordinary OPEN socket
 * draws nothing.
 */
@Composable
private fun ConnectionPill(label: String, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Row(
        modifier
            .clip(RoundedCornerShape(DshRadius.pill))
            .background(colors.tip)
            .padding(horizontal = DshSpacing.md, vertical = DshSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The chase is the honest glyph for both non-OPEN states: the supervisor
        // re-dials whether the mux is mid-handshake or waiting out a backoff.
        StateDot(state = DotState.ONGOING, size = 8.dp)
        Spacer(Modifier.width(DshSpacing.sm))
        Text(text = label, style = DshType.bodySmall, color = colors.labelSecondary)
    }
}

/**
 * Bug B: a non-blank session whose transcript has not arrived yet.
 *
 * The hero used to stand in for this, which is what made a real session with a
 * slow or unreachable host look like a brand-new one. The wording is the
 * drawer's existing "Loading…" (`SessionsDrawer`), and when the connection is
 * the reason it is named underneath rather than guessed at.
 */
@Composable
private fun LoadingSessionBody(connection: String?, modifier: Modifier = Modifier) {
    val colors = DshTheme.colors
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StateDot(state = DotState.ONGOING, size = 8.dp)
                Spacer(Modifier.width(DshSpacing.sm))
                Text(
                    text = "Loading…",
                    style = DshType.bodyMedium,
                    color = colors.labelTertiary,
                )
            }
            if (connection != null) {
                ConnectionPill(connection, Modifier.padding(top = DshSpacing.md))
            }
        }
    }
}

/** Abbreviate the home prefix the way the web client's path crumbs do. */
internal fun shortenPath(path: String): String =
    when {
        path == "/home/user" -> "~"
        path.startsWith("/home/user/") -> "~/" + path.removePrefix("/home/user/")
        else -> path
    }

/**
 * A tool argument path, shortened for a one-line row.
 *
 * Tool rows carry absolute paths like
 * `/home/user/var/work/dsh-android/app/src/main/java/uk/xa0/dsh/ui/ChatScreen.kt`,
 * which is most of a phone's line width. The home prefix collapses to `~`, and a
 * still-long path keeps its head and tail with `…` between, so what identifies
 * the file (its name, and the project it lives in) survives the elision.
 */
internal fun shortenToolPath(path: String, max: Int = 56): String {
    val home = shortenPath(path)
    if (home.length <= max) return home
    val parts = home.split('/').filter { it.isNotEmpty() }
    if (parts.size <= 3) return home
    val head = parts.take(2).joinToString("/", prefix = if (home.startsWith("/")) "/" else "")
    val tail = parts.takeLast(2).joinToString("/")
    val elided = "$head/…/$tail"
    return if (elided.length < home.length) elided else home
}

/**
 * Short resting label for the access-mode chip; the sheet shows the host's names.
 *
 * `workspace-write` is deliberately rendered as "Write" rather than "Workspace":
 * the latter reads as a *directory* selector, which is a different control.
 */
private fun shortAccessLabel(current: String, options: List<uk.xa0.dsh.PermissionOption>): String? {
    if (current.isEmpty()) return null
    return when (current) {
        "read-only" -> "Read-only"
        "workspace-write" -> "Write"
        "danger-full-access" -> "Full access"
        else -> options.firstOrNull { it.value == current }?.name ?: current
    }
}

/**
 * Trims a leading family token so the model trigger fits beside the mode chip on a
 * phone: `DeepSeek-V41-Flash` renders as `V41-Flash`. The model sheet still lists
 * every model under its full name.
 */
private fun shortModelName(name: String?): String? {
    if (name == null) return null
    // Elide the *version*, not the name: dropping the leading segment (as this
    // first did) kept the version and threw away the model's identity.
    val parts = name.split('-')
    return if (parts.size >= 3) "${parts.first()}-${parts.last()}" else name
}

/** The active reasoning effort, shown beside the model as the web trigger does. */
private fun effortLabel(model: uk.xa0.dsh.ModelOption?, effort: String?): String? {
    if (effort.isNullOrEmpty()) return null
    return model?.efforts?.firstOrNull { it.id == effort }?.name ?: effort
}

/** Last path segment, for the hero's workspace chip. */
private fun shortenLastSegment(path: String): String =
    path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
