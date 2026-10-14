package uk.xa0.dsh.model

import uk.xa0.dsh.SessionItem

/**
 * The row the auto-open should land on after a connect, or null when the roster
 * offers nothing to open at all.
 *
 * A cold start has no session of its own, so the app picks one out of the roster.
 * That roster is ordered by `updatedAt` descending — the order `session/list` is
 * sorted into, restated at the end of `fetchSessions` — so "the first row" *is*
 * "the most recently updated row". An automation run exists to fire on a schedule,
 * which makes it the most recently updated row on any host where one is still
 * running. Landing on `dsh-automation-session-…` on every fresh open is therefore
 * not an accident of one host's data: it is what "newest row wins" means once a
 * schedule is in play.
 *
 * So the newest row is not the question worth asking. The question is the newest
 * row the reader could have been working in, which is the drawer's own notion of a
 * top-level conversation:
 *
 *  - **not a subagent** — [SessionItem.isSubagent], read exactly as the drawer's
 *    row filter reads it. A child is reached through its parent's caret, not by
 *    landing in it.
 *  - **not an automation run** — [isAutomationSessionId], the same prefix test the
 *    drawer's Automations section lifts rows with. A second, private spelling of
 *    "this is a run" is what would let the landing and the drawer disagree.
 *
 * Nothing is hidden by this and no row leaves the roster: a run stays listed,
 * tappable and searchable, and it is still what the app opens when the host holds
 * nothing else with content. Only the *preference* passes over them.
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
    return topLevel.firstOrNull { !it.blank && !isAutomationSessionId(it.id) }?.id
        ?: topLevel.firstOrNull { !it.blank }?.id
        ?: topLevel.firstOrNull()?.id
}
