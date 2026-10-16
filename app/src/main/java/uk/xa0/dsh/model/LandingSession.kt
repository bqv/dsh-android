package uk.xa0.dsh.model

import uk.xa0.dsh.SessionItem

/**
 * The row the auto-open should land on after a connect, or null when the roster
 * offers nothing to open at all.
 *
 * A cold start has no session of its own, so the app picks one out of the roster.
 * That roster is ordered by `updatedAt` descending — the order `session/list` is
 * sorted into, restated at the end of `fetchSessions` — so "the first row" *is*
 * "the most recently updated row".
 *
 * ## Why a row with a schedule is passed over
 *
 * A session hosting an active scheduled task has its `updatedAt` moved by a machine
 * on a timer. The host delivers each due occurrence into the task's original session
 * as a follow-up turn, so an interval task updates its session every time it fires —
 * on this host one fires every thirty minutes — and a fresh open would land there
 * every time, however long the reader has been away. That is the reported bug
 * (a fresh open landing on a run at 3, 6, 12, 24 and 48 hours idle), and it survives
 * the plugin change underneath it: the old rule keyed on a `dsh-automation-session-…`
 * id prefix, the host's Schedule subsystem reuses ordinary `session-…` ids instead, and
 * a predicate that matched the first shape matched exactly none of the second.
 *
 * The question is the newest row the reader could have been working in, which is the
 * drawer's own notion of a top-level conversation:
 *
 *  - **not a subagent** — [SessionItem.isSubagent], read exactly as the drawer's
 *    row filter reads it. A child is reached through its parent's caret, not by
 *    landing in it.
 *  - **not a session a schedule delivers into** — [SessionItem.scheduledTaskCount],
 *    whose non-zero value is the host's `schedule/catalog` answer for that session
 *    (see `model/Automations.kt`). A second, private spelling of "this one is driven
 *    by a timer" is what would let the landing and the drawer disagree.
 *
 * ## Why it is a preference and not a removal
 *
 * Since the plugin change a host session is a *standing* session: it is the one the
 * reader was in when they asked for the schedule, it keeps its Workspace, and it may
 * also hold their own turns. So it is preferred against, never hidden. It stays listed,
 * tappable and searchable, and it is still what the app opens when the host holds
 * nothing else with content — the two fallbacks below. The cost of the preference is
 * real and accepted: a reader whose most recent genuine conversation happens to host a
 * task is landed beside it rather than in it, and reaches it in one tap.
 *
 * The archived filter is deliberately not repeated here. On a cold start the
 * Workspace stream that carries `archivedSessionIds` has only just been
 * subscribed, so its baseline may not have landed when the landing is chosen — a
 * rule that consults it would skip an archived row on one connect and keep it on
 * the next, which is worse than a rule that never consults it.
 *
 * [sessions] is expected in the roster's own order, which is what makes the first
 * match the newest one; the fold does not re-sort, so it cannot disagree with the
 * order the drawer and the rest of the app are already reading.
 */
fun landingSessionId(sessions: List<SessionItem>): String? {
    val topLevel = sessions.filter { !it.isSubagent }
    return topLevel.firstOrNull { !it.blank && it.scheduledTaskCount == 0 }?.id
        ?: topLevel.firstOrNull { !it.blank }?.id
        ?: topLevel.firstOrNull()?.id
}
