package uk.xa0.dsh.model

import uk.xa0.dsh.SessionItem

/**
 * Orders a session list so that anything already in [held] keeps the place it had,
 * and anything new goes to the front.
 *
 * The roster arrives freshly sorted by `updatedAt` on every refresh, so a session
 * that receives a message jumps to the top of its group — while the drawer is open
 * and somebody is reading it. The recorder caught that as the list moving with
 * nothing driving it: shifts of two and three rows with only 85-95% of the visible
 * rows surviving, and no gesture and no programmatic scroll behind any of them.
 *
 * Holding the order while the drawer is open costs nothing a reader can see, because
 * the only thing deferred is a re-sort they did not ask for. Membership is never
 * held: a session that arrives or is archived while the drawer is open is shown or
 * hidden straight away, since a stale row is worse than a moved one. A row with no
 * held place is therefore new, and goes to the front, which is where a new session
 * belongs anyway.
 *
 * `sortedWith` is stable, so rows that share a rank — the new ones, and any two the
 * roster delivered in the same order — keep the order they arrived in.
 */
fun holdOrder(sessions: List<SessionItem>, held: List<String>): List<SessionItem> {
    if (held.isEmpty()) return sessions
    val rank = HashMap<String, Int>(held.size)
    held.forEachIndexed { index, id -> rank[id] = index }
    return sessions.sortedWith(compareBy { rank[it.id] ?: -1 })
}
