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
 * There are exactly two ways this client learns that a session's agent is working,
 * and they are the same log read two ways:
 *
 *  - **A `session/list` pull**, which is a *cut* of the session journal. `running: true`
 *    there means the host has an attached Agent with status `running` right now
 *    (`api-session-controller`: `summaryFor` reads
 *    `ctx.agents.get(id)?.status === 'running'`), and `false` is what the same function
 *    answers for every session it serves **cold** — `summarizeCold` hard-codes
 *    `running: false, agentAvailable: false`, which is why a child's dot used to blink
 *    out the instant it was detached. The pull carries the durable half as well:
 *    `subagentTiming.active` is a fold of the child's *own* journal and is present
 *    exactly while a `turn/start` has no matching `turn/end` — for a cold child too. So
 *    the pull's durable answer is `running || active`.
 *  - **A live `api-session/status` frame**, which the host emits on an `agent/status`
 *    *transition* only (`ctx.on('agent/status', …)`). A client that connects while a
 *    child is already running never sees the "started" frame at all; the frame is
 *    promptness, not truth.
 *
 * The rule that makes the two agree is the cut, and it is a rule about ordering, not a
 * heuristic:
 *
 *  - A frame that arrived *before* the pull was requested describes a transition at or
 *    before the pull's cut, so the pull already contains it — it is dropped.
 *  - A frame that arrived while the pull was in flight, or after it landed, describes a
 *    transition the cut may predate — so it outranks the pull's durable answer. It is
 *    dropped by the *next* pull instead, which is then at or after that transition.
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
 * `running` is the host's live-Agent sample — true only while the session is attached —
 * and `subagentTiming.active` is the child's own journal saying a turn never reached
 * `turn/end`. Both are read, because they answer the same question from the two ends
 * the host can answer it: a child that is still working but no longer attached is
 * `running: false` **and** `active` present, and reading only the first is the bug this
 * exists to end.
 *
 * `active` is a *durable* fold, so it survives a cold read: it is present for a child
 * whose agent the host has never attached in this process, which is the case no live
 * frame can ever cover.
 */
fun durableRunningOf(row: JSONObject): Boolean {
    if (row.optBoolean("running")) return true
    // A JSON null folds to `optJSONObject`'s null, which is the absent case too: the
    // projection's `view` omits the field entirely while no turn is open.
    return row.obj("projections")?.obj("values")?.obj("subagentTiming")?.obj("active") != null
}

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
