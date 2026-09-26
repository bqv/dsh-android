package uk.xa0.dsh.model

import uk.xa0.dsh.JobItem

/**
 * Pure rules behind the background-jobs seat's green finished marker.
 *
 * Two properties live here, because both are decisions and both were wrong:
 *
 *  1. A list has *settled* only when a session that had a running job now has
 *     none. The comparison is against that session's **own** previous list, so
 *     the first frame after a session switch cannot be read as that session's
 *     job finishing — it is compared with the list the *previous* session left
 *     behind.
 *  2. The marker belongs to the session whose list settled, not to the session
 *     that happens to be open. A session's first observation (an empty list, a
 *     baseline row, a session you have never viewed) never marks it: there is no
 *     transition to detect yet.
 *
 * The host pushes a `jobs` frame per session on every change
 * (`packages/api/session-controller/src/control.ts:105-117`) and the baseline
 * carries every session's list (`control.ts:74-88`), so all of this is
 * observable for sessions other than the one on screen.
 */
object JobsMarker {

    /** True when [previous] had a running job and [current] has none. */
    fun settled(previous: List<JobItem>, current: List<JobItem>): Boolean =
        previous.any { it.running } && current.none { it.running }

    /**
     * The unseen set after one observation of [sessionId]'s list.
     *
     * [sessionId] joins the set only when this observation settled it; every
     * other session's membership is untouched, and an unchanged result is the
     * same instance so a caller can skip republishing.
     */
    fun observed(
        unseen: Set<String>,
        sessionId: String,
        previous: List<JobItem>,
        current: List<JobItem>,
    ): Set<String> =
        if (settled(previous, current) && sessionId !in unseen) unseen + sessionId else unseen

    /** The seat's dot for the session on screen; no session (the pending hero) means no dot. */
    fun unseenFor(unseen: Set<String>, sessionId: String?): Boolean =
        sessionId != null && sessionId in unseen

    /** Opening one session's list retires its dot, and only its dot. */
    fun seen(unseen: Set<String>, sessionId: String?): Set<String> =
        if (sessionId == null) unseen else unseen - sessionId
}
