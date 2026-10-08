package uk.xa0.dsh.model

import uk.xa0.dsh.SessionItem

/**
 * The roster's two membership operations, as pure list folds.
 *
 * The host pushes membership — `api-session/added` / `api-session/removed`
 * (`dsh-api-session-controller`: `session/created`, `session/disposed`, and an
 * `agent/created`/`agent/disposed` re-announcement of availability) — and this client
 * consumed neither, so a spawned session existed on the host and in no drawer until the
 * next whole-world `session/list` pull. Measured: a child created 59 s before a probe was
 * absent from the app's 1,891-row roster while the host listed 1,892, and the parent's
 * lineage sheet read `running=1` against the host's 2 for that whole minute. On an idle
 * parent, where nothing else triggers a pull, the lag is unbounded.
 *
 * These live here rather than inside the ViewModel so the rule can be tested without a
 * device: an added row must land *now*, must not duplicate an id already present, and must
 * keep the roster in the one order everything else assumes.
 */

/**
 * Inserts [row], or replaces the row with the same id, keeping `updatedAt` descending.
 *
 * Replacement rather than insertion is the common case, not the rare one: the host
 * re-announces a session as `added` when its agent is created or disposed, so an id
 * already in the roster arrives again with a fresh `agentAvailable`/`running`. Dropping
 * the old row is what makes the second announcement believed.
 *
 * The order is the one `session/list` itself is sorted into, and the sort is stable, so a
 * row that has not moved in time does not move in the list either.
 */
fun List<SessionItem>.upsertSession(row: SessionItem): List<SessionItem> {
    val out = ArrayList<SessionItem>(size + 1)
    var replaced = false
    for (existing in this) {
        if (existing.id == row.id) {
            out += row
            replaced = true
        } else {
            out += existing
        }
    }
    if (!replaced) out += row
    return out.sortedByDescending { it.updatedAt }
}

/**
 * Drops [id] from the roster, or returns the list unchanged when it is not there.
 *
 * An absent id is not an error: the host announces a removal for sessions this client
 * may never have listed — a session created and disposed between two pulls — and a
 * removal is not evidence that anything else changed.
 */
fun List<SessionItem>.removeSession(id: String): List<SessionItem> {
    if (none { it.id == id }) return this
    return filterNot { it.id == id }
}

/** One row a membership frame added, with the pull epoch it arrived in. */
data class HeldMember(val row: SessionItem, val running: Boolean, val epoch: Long)

/**
 * The rows membership frames added that no pull has yet had a chance to confirm.
 *
 * This is the roster half of the ordering hazard [RunningBook] handles for running state,
 * and it exists because a pull is not instantaneous: `session/list` takes seconds on a
 * host with a couple of thousand sessions, it is triggered by several unrelated things,
 * and a membership frame can land while one is already in flight. That pull's answer was
 * generated for a world in which the new session did not exist, so folding it in naively
 * would *drop the row we just added* — the child would appear and vanish, which is the
 * same "sometimes wrong" shape the push was meant to cure, one cycle wide.
 *
 * The rule is therefore the same rule, over the same epoch: an id added **before** a pull
 * was requested is inside that pull's cut and is released when it lands; an id added
 * **during** the pull's flight may not be, so it is carried forward. Survivors hand back
 * their running sample, because the pull that just landed cannot be evidence about a
 * session created after its cut, and a wholesale replacement of the running book would
 * otherwise answer `false` for it.
 *
 * Everything here runs on the view model's dispatcher, so a lock would be ceremony — but
 * the maps are kept private and the operations are the only door, so a future caller
 * cannot reach in and leave the two halves inconsistent.
 */
class MembershipHold {

    private val held = LinkedHashMap<String, HeldMember>()

    /** The epoch of the newest pull requested; supplied by [RunningBook]'s own counter. */
    fun remember(row: SessionItem, running: Boolean, epoch: Long) {
        held[row.id] = HeldMember(row, running, epoch)
    }

    /** Forgets an id, for a removal that outranks any pull still in flight. */
    fun forget(id: String) {
        held.remove(id)
    }

    /**
     * Applies a pull taken at [pull]: releases the ids that pull's cut contains and
     * returns the ones it may not — with the running sample each must be re-asserted with.
     */
    fun applyPull(pull: Long): List<HeldMember> {
        held.entries.removeAll { it.value.epoch < pull }
        return held.values.toList()
    }

    /** The rows still waiting for a pull that can confirm them. */
    fun rows(): List<SessionItem> = held.values.map { it.row }

    fun isEmpty(): Boolean = held.isEmpty()
}

