package uk.xa0.dsh.model

import uk.xa0.dsh.SessionItem

/**
 * Which session ids a live-running signal should stop claiming.
 *
 * `session/list` outranks a live frame for an **ordinary** session: a `true` the list
 * contradicts is a "stopped" that arrived while the app was not listening, and trusting
 * it forever leaves a row's dot stuck on for something that finished long ago.
 *
 * For a subagent the list is not an authority at all — the host reports a child as
 * running only while it is *attached*, and cold otherwise — so a child is never stale
 * here. Its own finish arrives as a live frame like everything else.
 *
 * An id the list does not mention at all is **unknown, not stale**. That distinction was
 * the bug: a child's status frame landing before its roster row — or during any refresh
 * that did not carry it — was discarded for being early, which is how a session with
 * three running subagents showed `running=3` and then `running=0` a hundred milliseconds
 * later, with the tree still holding all three.
 *
 * [current]'s row is kept while the event stream is not quiet: the open session is the
 * one the reader is watching, and its own frames are the last to settle.
 */
fun staleLiveRunning(
    live: Set<String>,
    list: List<SessionItem>,
    current: String?,
    quiet: Boolean,
): Set<String> = live.filter { id ->
    val item = list.firstOrNull { it.id == id }
    when {
        item == null -> false
        item.isSubagent || item.running -> false
        id == current && !quiet -> false
        else -> true
    }
}.toSet()

/**
 * The running flag one roster row should carry, given everything already known.
 *
 * `session/list` reports a subagent as running **only while it is attached**, and `false`
 * the moment it is not — so the roster can promote a child to running and then take it
 * away again a second later, without anything having stopped. Measured on the device:
 * a parent with three live children showed `running=3` and then `running=0` 105ms later,
 * the tree still holding all three.
 *
 * The list stays authoritative for an ordinary session, which is what makes a "stopped"
 * that arrived while the app was not listening visible at all. For a subagent it may only
 * ever add: the previous answer stands until a live frame contradicts it.
 */
fun runningAfter(
    sampleRunning: Boolean,
    isSubagent: Boolean,
    previousRunning: Boolean,
    live: Boolean,
    stopped: Boolean,
): Boolean = when {
    sampleRunning -> true
    // Ahead of [live]: a frame that explicitly says *stopped* is the more specific fact,
    // and it is the only thing that may end a subagent. The two sets are exclusive by
    // construction, so this order only matters if that ever stops being true.
    stopped -> false
    live -> true
    !isSubagent -> false
    else -> previousRunning
}
