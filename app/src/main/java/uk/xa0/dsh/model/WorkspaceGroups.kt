package uk.xa0.dsh.model

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
 * ## The two zero-row groups
 *
 * `sessionIds: []` and "the members exist but this client has not been handed them yet"
 * both draw a group with nothing under it, and they are **not the same claim**:
 *
 *  * **[WorkspaceGroupState.Empty]** — the host said this directory accounts for no
 *    session. "No sessions" is a fact the registry supplied.
 *  * **[WorkspaceGroupState.Loading]** — the host named members this client's roster has
 *    not delivered (it is a `session/list` pull behind, or the pull omitted them:
 *    `DshViewModel.warnOnMissingMembers` logs exactly that divergence). "0" would be a
 *    lie here; the truthful statement is that the sessions have not arrived.
 *
 * They therefore draw different words *and* a different count — see [placeholder] and
 * [headerCount] — which is this app's standing convention for "nothing" versus "not
 * told" (`WidgetCopy.NEEDS_NOTHING` / `NEEDS_UNKNOWN`, and the Router tab's "not asked"
 * rule). A group number that is not yet a fact is not drawn at all.
 *
 * A third case never reaches the screen and is kept distinct anyway:
 * **[WorkspaceGroupState.Filtered]** is every member known but every one of them hidden
 * by the drawer's own filters (search, Archived off, subagents). The reader caused that
 * and can undo it, so the group is simply not drawn — the same rule the web applies when
 * the archived-only filter drops a group with no visible members
 * (`dsh-client-ui-workspace/lib/types/client/tree.d.ts`, `deriveGroups`).
 *
 * ## Order
 *
 * Groups are drawn in the host's registry order — the baseline's item order, kept current
 * by the stream's `order` frames — and an empty Workspace keeps its own place in it. The
 * activity recency that sorts the *session* roster is deliberately not reused here: a
 * Workspace's `updatedAt` moves whenever a session is adopted into it, so sorting groups
 * by it would reorder the sidebar under somebody who was only reading, and would make the
 * manual arrangement `workspace/insertBefore` exists to express unreachable. A Workspace
 * is a place, not a row with an activity. (`createdAt`/`updatedAt` do arrive on every
 * `WorkspaceView`; this client has no use for either.)
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
 * [Members] and [Empty] and [Loading] draw a header; [Filtered] does not.
 */
enum class WorkspaceGroupState {
    /** At least one accounted member has a row the drawer would draw. */
    Members,

    /** The host accounts no session to this Workspace at all. A fact. */
    Empty,

    /** The host accounts members this client's roster has not delivered. Not yet a fact. */
    Loading,

    /** Every member is known, and the drawer's own filters hide all of them. */
    Filtered,
}

/**
 * Decide one group's state from the three things that are actually known about it.
 *
 * @param memberIds the Workspace's accounted membership, as the host last reported it.
 *   Deliberately the *unfiltered* set: a group is never called empty because a filter
 *   hid its members.
 * @param visibleIds the members the drawer would draw right now — after the query, the
 *   Archived switch, the subagent rule and the automation lift-out.
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
 * Whether this group draws a header row.
 *
 * [WorkspaceGroupState.Filtered] is the only state that does not: the group is non-empty
 * and everything about it is known, so an empty header would be the drawer reporting the
 * user's own filter back to them as a fact about the directory.
 */
val WorkspaceGroupState.showsHeader: Boolean
    get() = this != WorkspaceGroupState.Filtered

/**
 * The line a group with no rows draws under its header, or null when it draws none.
 *
 * These two strings are the whole on-screen difference between "this Workspace has
 * nothing" and "this Workspace's sessions have not arrived", so they are held here where
 * a test can assert they are not equal, rather than inline in the drawer.
 */
val WorkspaceGroupState.placeholder: String?
    get() = when (this) {
        WorkspaceGroupState.Empty -> WorkspaceCopy.NO_SESSIONS
        WorkspaceGroupState.Loading -> WorkspaceCopy.SESSIONS_NOT_LOADED
        else -> null
    }

/**
 * The header's count when that number is a fact, or null when the header must not print
 * one.
 *
 * A group whose members have not arrived cannot say "0": the count column would then be
 * making the claim the placeholder beside it is refusing to make.
 */
fun WorkspaceGroupState.headerCount(visibleRows: Int): Int? =
    if (this == WorkspaceGroupState.Loading) null else visibleRows

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
     * carries the same lesson). The word matches the widget's "Running: unknown".
     */
    const val SESSIONS_NOT_LOADED = "Sessions not loaded"

    /**
     * The figure drawn where a group's count would go, when the number is not known: the
     * whole registry, before any baseline has arrived. The same word the widget uses for
     * a reading it could not confirm.
     */
    const val UNKNOWN = "unknown"

    /** The host's label for the catch-all bucket, once the registry can back it. */
    const val UNGROUPED = "Ungrouped"

    /** What the catch-all is while the registry is unknown. */
    const val SESSIONS = "Sessions"

    /** The registry's own placeholder row, before any baseline has arrived. */
    const val WORKSPACES = "Workspaces"
}
