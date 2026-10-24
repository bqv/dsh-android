package uk.xa0.dsh.model

import uk.xa0.dsh.SessionItem

/**
 * Which Workspaces the sidebar draws, and — the part that is easy to get wrong —
 * what it is allowed to say about one that has no rows.
 *
 * ## Where the list comes from
 *
 * The host reports Workspaces directly: `workspace/follow` opens with a `baseline`
 * carrying every registered Workspace (`items:[{workspaceId,path,title,sessionIds,
 * createdAt,updatedAt}], archivedSessionIds, pinnedSessionIds` — see
 * `dsh-api-workspace-controller/lib/typert.remote-client.js`, generated from the Host
 * FaceModel) and then pushes `upsert`/`remove`/`order` increments. There is no
 * `workspace/list` to fall back on, and there is no need for one: the baseline is the
 * whole registry, in the registry's own durable order.
 *
 * That is why a Workspace with **no sessions is knowable**. The running host builds the
 * baseline from `ctx.workspaceRegistry.list()` and projects each entity verbatim
 * (`.../dsh-api-workspace-controller/lib/index.js:66-72`, `workspaceView`), so a
 * registered directory whose `sessionIds` is empty arrives as a row with an empty
 * `sessionIds` — not missing, not inferable-only. The entity is durable registry state;
 * only its `sessionIds` getter is derived, by matching sessions' canonical `cwd`
 * (`.../dsh-workspace/lib/index.js:446-457`). Nothing in this client infers a Workspace
 * from `cwd`, and nothing invents one — a directory no registry row names is not a group
 * at all, however many sessions ran in it.
 *
 * ## The three zero-row groups
 *
 * `sessionIds: []`, "the members exist but this client has not been handed them yet", and
 * "the members exist and every one of them is hidden by this drawer's own view rules" all
 * draw a group with nothing under it, and they are **not the same claim**:
 *
 *  * **[WorkspaceGroupState.Empty]** — the host said this directory accounts for no
 *    session. "No sessions" is a fact the registry supplied.
 *  * **[WorkspaceGroupState.Loading]** — the host named members this client's roster has
 *    not delivered (it is a `session/list` pull behind, or the pull omitted them:
 *    `DshViewModel.warnOnMissingMembers` logs exactly that divergence). "0" would be a
 *    lie here; the truthful statement is that the sessions have not arrived.
 *  * **[WorkspaceGroupState.Filtered]** — every member is known and none of them is drawn
 *    under this header: the Archived switch is off, or they are subagent sessions (never
 *    top-level rows).
 *
 * They therefore draw different words — see [placeholder] — and their header figures differ
 * as well: a group whose membership has not arrived draws "[shown] of [accounted]" rather
 * than a bare number ([workspaceCountLabel]), so the members the roster has not delivered
 * are a figure the reader can see and count, not an absence to be inferred from a blank or
 * from a quieter colour.
 *
 * **Every one of the three draws a header**, and that is a deliberate divergence from the
 * web: its `deriveGroups` drops a group with no visible members
 * (`dsh-client-ui-workspace/lib/types/client/tree.d.ts`), so an archived-only Workspace
 * leaves the sidebar entirely. The reader's Archived switch persists across restarts, so
 * on this client a directory could disappear because of a filter set days ago, with
 * nothing on screen to say the directory — or the filter — exists. The whole point of a
 * Workspace list is that the Workspaces in it can be seen, so here the registry always
 * wins over the filter, and the price is that a group with no visible members must *say*
 * why it has none ([placeholder]). Recorded in `docs/PARITY.md`.
 *
 * ## Order
 *
 * Groups are drawn in the host's registry order — the baseline's item order, kept current
 * by the stream's `order` frames — and an empty Workspace keeps its own place in it. The
 * activity recency that sorts the *session* roster is deliberately not reused here: a
 * Workspace's `updatedAt` moves whenever a session is adopted into it, so sorting groups
 * by it would reorder the sidebar under somebody who was only reading, and would replace
 * the manual arrangement `workspace/insertBefore` stores with a reading of activity. That
 * arrangement is reachable from each row's menu — see `model/WorkspaceOrder.kt`. A
 * Workspace is a place, not a row with an activity. (`createdAt`/`updatedAt` do arrive on
 * every `WorkspaceView`; this client has no use for either.)
 *
 * Everything in this file is pure so the rules can be tested without a device — the
 * drawer's `remember` block cannot be reached from the JVM harness.
 */

/**
 * How far the Workspace registry has got.
 *
 * `workspace/follow` is a stream whose first frame is always a complete `baseline`, so
 * until that frame arrives this client has *not been told* which Workspaces exist. That
 * is a different fact from a host with no Workspaces registered, and the sidebar must not
 * draw them the same. The web's own client model keeps exactly this bit and calls it
 * `WorkspaceListPhase = 'pending' | 'ready'`
 * (`dsh-api-workspace-controller/lib/types/client/model.d.ts`); it is monotone, because
 * a later generation always replaces the projection with another complete baseline.
 */
enum class WorkspaceListPhase {
    /** No baseline has been received: the registry is unknown, not empty. */
    Pending,

    /** A `baseline` has landed: the Workspace list below is the host's own. */
    Ready,
}

/**
 * Why one Workspace's section draws the rows it does.
 *
 * Every state draws a header: the sidebar is the list of the host's Workspaces, so a
 * registered Workspace is always on it. What the states differ in is what a zero-row
 * header says underneath itself.
 */
enum class WorkspaceGroupState {
    /** At least one accounted member has a row the drawer would draw. */
    Members,

    /** The host accounts no session to this Workspace at all. A fact. */
    Empty,

    /** The host accounts members this client's roster has not delivered. Not yet a fact. */
    Loading,

    /**
     * Every member is known, and none of them is drawn under this header.
     *
     * The drawer's own view rules emptied the group — the Archived switch, or the rule that
     * a subagent is reached through its parent rather than as a top-level row. The group
     * still draws, and says so: see [placeholder].
     */
    Filtered,
}

/**
 * Decide one group's state from the three things that are actually known about it.
 *
 * @param memberIds the Workspace's accounted membership, as the host last reported it.
 *   Deliberately the *unfiltered* set: a group is never called empty because a filter
 *   hid its members.
 * @param visibleIds the members the drawer would draw right now — after the query, the
 *   Archived switch and the subagent rule.
 * @param knownIds every session id the roster has delivered, filters ignored. A member
 *   that is archived or a subagent is still *known*; a member that is merely not there
 *   yet is not.
 * @return the state; visible rows always win, because a group that has something to
 *   draw is not reporting an absence.
 */
fun workspaceGroupState(
    memberIds: Collection<String>,
    visibleIds: Collection<String>,
    knownIds: Set<String>,
): WorkspaceGroupState {
    if (visibleIds.isNotEmpty()) return WorkspaceGroupState.Members
    if (memberIds.isEmpty()) return WorkspaceGroupState.Empty
    return if (memberIds.any { it !in knownIds }) {
        WorkspaceGroupState.Loading
    } else {
        WorkspaceGroupState.Filtered
    }
}

/**
 * The line a group with no rows draws under its header, or null when it draws none.
 *
 * These three strings are the whole on-screen difference between "this Workspace has
 * nothing", "this Workspace's sessions have not arrived" and "this Workspace's sessions
 * are all hidden by the drawer's view rules", so they are held here where a test can
 * assert none of them coincides with another, rather than inline in the drawer.
 */
val WorkspaceGroupState.placeholder: String?
    get() = when (this) {
        WorkspaceGroupState.Empty -> WorkspaceCopy.NO_SESSIONS
        WorkspaceGroupState.Loading -> WorkspaceCopy.SESSIONS_NOT_LOADED
        WorkspaceGroupState.Filtered -> WorkspaceCopy.SESSIONS_HIDDEN
        else -> null
    }

/**
 * The figure a Workspace header draws where its count goes.
 *
 * Normally this is the number of sessions drawn under the header. When the roster has not
 * delivered every session the registry accounts to this Workspace — and some of them are
 * therefore not drawn — it is instead "[shown] of [accounted]": the same number, plus the
 * figure the registry holds, so the gap is *stated* in the column where a number belongs. It
 * used to be the word [WorkspaceCopy.UNKNOWN], and before that the dimmed title said it in
 * colour: an absence the reader could not count, in the one place a count is read. "3 of 13"
 * needs no legend.
 *
 * Both halves must hold for the "of" form. A Workspace whose every member is delivered and
 * drawn prints the plain number, even if a filter — the Archived switch — is what removed
 * rows, because that is a fact about this drawer rather than about the registry, and the
 * group's own placeholder says so when it is open. A Workspace whose members are all drawn
 * but whose roster has not *confirmed* them prints the plain number too: the reader can see
 * every accounted session, so there is no gap on screen to report.
 *
 * @param shown the top-level rows the drawer would draw under this header.
 * @param memberIds the Workspace's accounted membership, as the host last reported it.
 *   Deliberately the *unfiltered* set, matching [workspaceGroupState].
 * @param knownIds every session id the roster has delivered, filters ignored.
 */
fun workspaceCountLabel(
    shown: Int,
    memberIds: Collection<String>,
    knownIds: Set<String>,
): String {
    val accounted = memberIds.size
    val missing = memberIds.count { it !in knownIds }
    return if (missing > 0 && shown < accounted) "$shown of $accounted" else shown.toString()
}

/**
 * Whether anything in a group is running.
 *
 * This is the fact the group header's blue means, and it is deliberately *not*
 * `containsCurrent`: that one means "the session you have open lives here", which is a fact
 * about the reader's own position in the list. A Workspace can hold the open session and no
 * work, or work and not the open session, or both, or neither, and the four draw differently.
 *
 * The two states are read off one field, `SessionItem.running` — `RunningBook`'s answer and
 * nothing else (see `model/LiveRunning.kt`). The journal projection `subagentTiming.active`
 * is *not* read as liveness anywhere here: a killed turn leaves it set forever, and a header
 * that stayed blue over a dead child would be worse than no signal at all.
 *
 * **There is no third state.** A member the roster never delivered is treated as absent for
 * this question: the client cannot observe its work, so it must not make the reader's glance
 * at the header into a question. The blue answers what is happening *here*; the fact that the
 * membership is incomplete is a different fact and is said in words — the header's count,
 * [workspaceCountLabel] — never in hue. A colour whose legend is not on screen is not a
 * signal, and the reader who asked "why is this grey" had nothing to read.
 */
enum class WorkspaceRunState {
    /** At least one accounted member is live work. A positive fact; sends the header blue. */
    Running,

    /** Nothing this client can see is running. The resting state, with the ordinary title colour. */
    Idle,
}

/**
 * Decide one group's run state from the two things that are actually known about it.
 *
 * A member the roster never delivered is treated as absent here, deliberately. The negative
 * — "nothing here is running" — is a fact about the work the client can see; withholding the
 * colour because a *different* fact is unknown would put an unreadable legend in the one
 * channel the reader reads at a glance. That other fact, incomplete membership, is stated
 * plainly in the header's count instead ([workspaceCountLabel]); see [WorkspaceRunState].
 *
 * @param memberIds the ids the group accounts for, as the host last reported them. Deliberately
 *   the *unfiltered* set, for the same reason [workspaceGroupState] takes it: a member the
 *   reader's Archived switch has hidden, or that the drawer lifted into another section, is
 *   still work happening in this directory, and a running one must not stop the header going
 *   blue because a filter hid its row.
 * @param liveIds every id the roster reports as live work — its own running turn, or a running
 *   descendant; see [liveWorkIds].
 */
fun workspaceRunState(
    memberIds: Collection<String>,
    liveIds: Set<String>,
): WorkspaceRunState =
    if (memberIds.any { it in liveIds }) WorkspaceRunState.Running else WorkspaceRunState.Idle

/**
 * Every id the roster reports as live work, for [workspaceRunState].
 *
 * Two sources, both `SessionItem.running` (so both `RunningBook`'s answer):
 *
 *  * a session whose **own** turn is running, and
 *  * a **parent of a running descendant**, handed in as
 *    `indexSubagentRollups(sessions).filterValues { it.running > 0 }.keys`.
 *
 * The second is here because a subagent is never a top-level row — measured on the running
 * host, a Workspace's `sessionIds` held no subagent at all while its members had dozens
 * between them. A folder whose only live work is delegated therefore holds no *member* that
 * is running, and without the rollup the header would stay grey and hide exactly the work
 * collapsing it was going to hide. This is the same accent the session row already draws as
 * its descendant count and the header chip draws as `+N running`: "running" in the drawer has
 * always included a running child, and the group header is that fact lifted one level up.
 */
fun liveWorkIds(
    sessions: List<SessionItem>,
    parentsWithRunningDescendants: Collection<String> = emptyList(),
): Set<String> {
    val out = HashSet<String>()
    for (session in sessions) if (session.running) out += session.id
    out += parentsWithRunningDescendants
    return out
}

/**
 * The catch-all section's title.
 *
 * "Ungrouped" is the host's own label (`ui-workspace` locales, `group.ungrouped`) and it
 * is a claim: these sessions belong to no directory. Before the registry has spoken that
 * claim cannot be made — every session on screen might belong to a Workspace this client
 * has not been told about — so the section keeps the neutral title the flat list uses
 * until a `baseline` lands.
 */
fun ungroupedTitle(phase: WorkspaceListPhase): String = when (phase) {
    WorkspaceListPhase.Pending -> WorkspaceCopy.SESSIONS
    WorkspaceListPhase.Ready -> WorkspaceCopy.UNGROUPED
}

/** The sidebar's copy for the Workspace registry. English (GB). */
object WorkspaceCopy {

    /** Confirmed empty: the registry says this directory accounts for no session. */
    const val NO_SESSIONS = "No sessions"

    /**
     * Not yet told: the registry names members the roster has not delivered.
     *
     * Deliberately a statement about what is *known* rather than about what the app is
     * doing. "Loading…" would be the wrong sentence twice over — it promises an arrival
     * that a roster which omitted the member may never make, and it describes the app's
     * own activity where the reader needs the state of the fact (`WidgetCopy.STAMP_OLDEST`
     * carries the same lesson). The header's count beside it says the same thing as a figure
     * ([workspaceCountLabel]), so the note and the number agree.
     */
    const val SESSIONS_NOT_LOADED = "Sessions not loaded"

    /**
     * Nothing drawn, but everything accounted for: the drawer's own view rules emptied the
     * group.
     *
     * Deliberately not "No sessions": the directory holds sessions, and at least one of the
     * rules that can empty a group — the Archived switch — is a control the reader can turn
     * back on, which is a different fact from a directory the host says holds nothing.
     * Deliberately not "your filters" either: the other rule — a subagent is reached
     * through its parent rather than as a top-level row — is the drawer's own, not the
     * reader's, so the sentence names the filters without claiming who set them.
     */
    const val SESSIONS_HIDDEN = "Sessions hidden by filters"

    /**
     * The word drawn where the whole registry's figure would go, before any baseline has
     * arrived: the pending "Workspaces" row's count. The registry is unknown rather than empty,
     * so there is no number to print, and the same word the widget uses for a reading it could
     * not confirm is the honest one.
     *
     * This is the only caller left. A Workspace whose members the roster has not delivered no
     * longer draws it: that header prints "[shown] of [accounted]" instead, which says the
     * same thing and, unlike "unknown", can be read down to a figure ([workspaceCountLabel]).
     */
    const val UNKNOWN = "unknown"

    /** The host's label for the catch-all bucket, once the registry can back it. */
    const val UNGROUPED = "Ungrouped"

    /**
     * What a group header says about live work when it can say it.
     *
     * The header's blue is invisible to a screen reader, so the same fact is spoken. The word is
     * the widget's own (`WidgetCopy.RUNNING`), because it is the same claim on the same
     * authority.
     */
    const val RUNNING = "Running"

    /** What the catch-all is while the registry is unknown. */
    const val SESSIONS = "Sessions"

    /** The registry's own placeholder row, before any baseline has arrived. */
    const val WORKSPACES = "Workspaces"

    /**
     * The two moves a Workspace header offers a screen reader.
     *
     * Touch reorders by dragging the header, which TalkBack cannot perform, so these
     * are attached as the header's custom accessibility actions. They call the same
     * `moveWorkspace` path the drag commits through, and a side that is unavailable
     * (the first or last sibling) is left out rather than offered and refused.
     */
    const val MOVE_UP = "Move up"

    /** The other half of the pair; see [MOVE_UP]. */
    const val MOVE_DOWN = "Move down"
}
