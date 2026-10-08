package uk.xa0.dsh.model

import org.json.JSONObject
import uk.xa0.dsh.SessionItem

/**
 * One live `api-session/status` frame: what it claimed, and the roster read it raced.
 *
 * [epoch] is the value [RunningBook.beginPull] returned for the roster read that was
 * in flight when the frame arrived (or the last one requested, if none was). It is
 * what lets a frame be ordered against a pull instead of guessed at.
 */
data class LiveRunningFrame(val running: Boolean, val epoch: Long)

/**
 * The single authority on which sessions are running.
 *
 * There are exactly two ways this client learns that a session's agent is working, and
 * they say different things:
 *
 *  - **A `session/list` pull**, which is a *cut* of the host's own state.
 *    `running: true` means the host has an agent bound to that session with status
 *    `running` (`api-session-controller`: `summaryFor` reads
 *    `ctx.agents.get(id)?.status === 'running'`) — i.e. it is the liveness answer, and
 *    it covers a child the app never saw start. `false` covers both "attached but idle"
 *    (`agentAvailable: true`) and "served cold, no agent at all"
 *    (`summarizeCold` hard-codes `running: false, agentAvailable: false`). The journal
 *    projection `subagentTiming.active` rides along in the same row and is deliberately
 *    **not** read as liveness — see [durableRunningOf] for the crash-orphan measurement
 *    that forced that decision.
 *  - **A live `api-session/status` frame**, which the host emits on an `agent/status`
 *    *transition* only (`ctx.on('agent/status', …)`) — including the "started" a pull
 *    taken a moment too early cannot yet carry. It is promptness *and* the repair for a
 *    pull that raced the transition.
 *
 * The rule that orders the two is the cut, and it is a rule about ordering, not a
 * heuristic:
 *
 *  - A frame that arrived *before* the pull was requested describes a transition at or
 *    before the pull's cut, so the pull's answer already contains it — it is dropped.
 *  - A frame that arrived while the pull was in flight, or after it landed, describes a
 *    transition the cut may predate — so it outranks the pull's answer. It is dropped by
 *    the *next* pull instead, which is then at or after that transition.
 *
 * That bounds every frame's life to a single pull: an app that was backgrounded and
 * collected no frames cannot keep a stale "running" alive, and a frame cannot be
 * discarded for arriving "early" relative to a row a later pull introduces — the exact
 * two failures the previous pair of heuristics (`runningAfter`'s sticky previous answer
 * and `staleLiveRunning`'s absent-is-stale test) traded against each other.
 *
 * Every reader asks this one object, so no two surfaces can disagree: `SessionItem.running`
 * is [running], and nothing else.
 */
class RunningBook {

    private val lock = Any()

    /** The cut id of the newest pull *requested*; frames are stamped with it. */
    private var requested = 0L

    /** The cut id of the newest pull *applied*; frames older than it are subsumed. */
    private var cut = 0L

    /** The durable answer per id, from the newest applied pull. */
    private val durable = HashMap<String, Boolean>()

    /** The newest live frame per id, with the epoch it arrived in. */
    private val frames = HashMap<String, LiveRunningFrame>()

    /**
     * Registers that a whole-world roster read is being taken now, and returns the
     * epoch a frame arriving during it must be stamped with.
     *
     * This has to be called when the **request** goes out, not when its answer is
     * folded: the whole point of the stamp is to tell a frame that the cut already
     * contains from one it may not.
     */
    fun beginPull(): Long = synchronized(lock) { ++requested }

    /** Records one live `api-session/status` frame. */
    fun frame(id: String, running: Boolean) {
        synchronized(lock) { frames[id] = LiveRunningFrame(running, requested) }
    }

    /**
     * Applies the pull taken at [pull]: [sample] is the durable answer for every id
     * the host listed.
     *
     * A pull is the whole world, so its sample *replaces* the held one rather than
     * merging into it — an id the host has stopped listing has no running claim left
     * to stand on. A pull older than one already applied is ignored: a slow answer
     * must not put an out-of-date cut in front of a newer one.
     */
    fun applyPull(pull: Long, sample: Map<String, Boolean>) {
        synchronized(lock) {
            if (pull < cut) return
            cut = pull
            durable.clear()
            durable.putAll(sample)
            frames.entries.removeAll { it.value.epoch < cut }
        }
    }

    /** Whether [id]'s agent is working: the newest frame that outranks the cut, else the cut. */
    fun running(id: String): Boolean = synchronized(lock) {
        val frame = frames[id]
        when {
            frame != null && frame.epoch >= cut -> frame.running
            else -> durable[id] ?: false
        }
    }

    /** Forgets everything. Only for a reconnect that invalidates the whole roster. */
    fun clear() {
        synchronized(lock) {
            requested = 0L
            cut = 0L
            durable.clear()
            frames.clear()
        }
    }
}

/**
 * The durable running fact one `session/list` row carries.
 *
 * **`running` is the host's own agent sample, and it is the whole of the durable
 * answer.** The host builds it as `ctx.agents.get(id)?.status === 'running'`
 * (`api-session-controller`: `summaryFor`), which is liveness: an agent bound to the
 * session whose phase is running. A session served cold — not attached in this process
 * — hard-codes `running: false, agentAvailable: false` (`summarizeCold`), and an
 * attached session with an idle agent reads `running: false, agentAvailable: true`.
 *
 * **`subagentTiming.active` is deliberately *not* read here, and must not be.** It is a
 * fold of the child's own journal meaning "a `turn/start` never reached `turn/end`" — a
 * durable fact about the *log*, not a fact about whether anything is running. The
 * difference is not theoretical: a host restart mid-turn leaves that turn open forever
 * with no agent behind it. Measured on the running host after the box crashed during a
 * build, `session/list` held exactly two such rows —
 * `fd23b122-1474-4ea6-87a5-d065d522e3b5` and `dcd6240b-4eb5-4170-b6bd-bf971f3007cf`,
 * both `running: false, agentAvailable: false`, both with `active` present and its
 * `through` frozen 64–65 minutes earlier (the crash), while legitimately running
 * children in the same snapshot had `through` under a minute old. Reading `active` as
 * liveness paints those two dead children as running for as long as the record exists.
 *
 * A running claim the roster denies is therefore *not* rescued by this field. What
 * rescues one is a live `api-session/status` frame newer than the roster cut — the host
 * emits one on every `agent/status` transition, including the "started" a pull taken a
 * moment too early cannot yet see — and ordering the two is [RunningBook]'s whole job.
 */
fun durableRunningOf(row: JSONObject): Boolean = row.optBoolean("running")

/**
 * The running flag one roster row is allowed to carry: the book's answer, and nothing
 * else.
 *
 * This is the whole of `SessionItem.running`'s derivation. It used to be a cache four
 * separate code paths patched — the pull, the live frame, a rebuilt subagent catalog
 * whose projection carries no activity at all, and a second fold of the pull that
 * forgot the row's previous answer — which is how a session with three running children
 * could show `running=3` and then `running=0` a hundred milliseconds later with the tree
 * still holding all three.
 */
fun SessionItem.withRunning(book: RunningBook): SessionItem {
    val answer = book.running(id)
    return if (running == answer) this else copy(running = answer)
}
