package uk.xa0.dsh.model

/**
 * The diagnostic half of the Workspace roster divergence: what to say, and how often, when a
 * `session/list` pull does not return a member the host's Workspace registry accounts.
 *
 * The registry is the host's durable state; the roster is a pull over it. On this user's host
 * the two disagree permanently — the registry still lists roughly seventy
 * `dsh-automation-session-*` ids minted by a retired automation plugin, whose transcripts are
 * gone, so `session/list` never serves them. The client cannot clean that up and must not
 * pretend it is not there (the drawer states the gap in its count; see
 * [workspaceCountLabel]), but the *log* about it should be a signal, not a flood.
 *
 * Everything here is pure so it can be tested from the JVM harness, which cannot reach the
 * view model's own call sites.
 */

/**
 * Keeps the "session/list omitted a workspace member" warning to one line per episode.
 *
 * The pull that computes the missing set is issued on every roster refresh — a resume, the
 * Refresh control, and the 500 ms debounce behind the streams — so on a host whose registry
 * names members the roster will never return, the old line re-listed the same seventy ids
 * every few seconds. Measured on the user's phone, 295 of these lines sat in the ring buffer,
 * each roughly 4 KB, drowning the log the app relies on. [accept] reports a given missing set
 * once and then stays quiet, following the shape of [ScheduleSignalLatch]: a changed set
 * reports again, and a pull that omits nothing clears the latch so a later recurrence
 * reports. One field, no timer.
 */
class MissingMembersLatch {
    private var reported: Set<String>? = null

    /**
     * Returns [missing] the first time that exact set is offered in this episode, else null.
     *
     * An empty [missing] means the pull was whole: it clears the latch, so the next omission
     * reports. The set is compared rather than any ordered list, so two pulls that omitted the
     * same members are quiet even if the registry happened to name them in a different order.
     *
     * @return the set to report once, or null when this is a repeat or a whole pull.
     */
    fun accept(missing: Set<String>): Set<String>? {
        if (missing.isEmpty()) {
            reported = null
            return null
        }
        if (missing == reported) return null
        reported = missing
        return missing
    }
}

/**
 * The one warning line an omitted-member episode earns: the count first, then a bounded
 * sample of the ids.
 *
 * The count is the fact — seventy members the registry names and the roster never delivers is
 * a host-state problem, not one id — and a few ids are enough to match the line against a
 * registry by hand. The old line listed every id, which was the whole of the flooding; on
 * this host that was ~4 KB per pull.
 */
fun omittedMembersWarning(missing: Collection<String>, sample: Int = 3): String {
    val ordered = missing.sorted()
    val shown = ordered.take(sample).joinToString(", ")
    val more = ordered.size - sample
    val tail = if (more > 0) ", ... and $more more" else ""
    return "${ordered.size} workspace member(s) the registry accounts are missing from session/list: [$shown$tail]"
}
