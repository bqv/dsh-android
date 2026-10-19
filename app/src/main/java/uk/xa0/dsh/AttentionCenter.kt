package uk.xa0.dsh

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import uk.xa0.dsh.data.DshConfig
import uk.xa0.dsh.model.ApprovalAnswer
import uk.xa0.dsh.model.ApprovalCopy
import uk.xa0.dsh.model.ApprovalGate
import uk.xa0.dsh.model.ApprovalRoster
import uk.xa0.dsh.model.arr
import uk.xa0.dsh.model.bool
import uk.xa0.dsh.model.obj
import uk.xa0.dsh.model.str
import uk.xa0.dsh.net.AnswerSink
import uk.xa0.dsh.net.DshClient
import uk.xa0.dsh.net.MuxState
import uk.xa0.dsh.net.RemoteMux
import uk.xa0.dsh.net.StreamEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The three ways an agent can be blocked on a human, as the web discriminates
 * them (`ui-workspace/src/client/rows/Rows.tsx` `sessionStatuses`). [label] is
 * the web's own `status.*` copy (`ui-workspace/src/client/locales.ts:127-129`);
 * [precedence] is the cross-domain ranking the web registers per domain
 * (`ui-approval/src/client/index.ts:77-79` = 0,
 * `ui-user-questions/src/client/index.ts:91-93` = 1 or 2), applied at
 * `ui-session/src/client/index.ts:375` where the larger value wins a session.
 */
enum class PendingKind(val precedence: Int, val label: String) {
    /** An `approval/request` waterfall; `ui-approval` registers it at 0. */
    APPROVAL(0, "Waiting for approval"),

    /** A `user-questions/request` with no plan intent; `ui-user-questions` at 1. */
    QUESTION(1, "Waiting for answer"),

    /**
     * The same question waterfall with `intent.kind == 'plan-review'`, ranked 2
     * by `ui-user-questions` so a plan beats an ordinary question.
     */
    PLAN_REVIEW(2, "Plan awaiting review"),
}

/**
 * Everything that asks the human for something, owned by the process rather than
 * by a screen.
 *
 * This exists because a DSH agent that wants a human *blocks*: an approval or a
 * question arrives as a waterfall over this client's own event stream, and the
 * host fails it with `NO_PROVIDER` if nobody answers. When the handling lived in
 * the ViewModel, killing the app therefore did not merely hide the alert — it
 * turned a question into a failed tool call with no trace on the phone. Keeping
 * the stream, the pending set and the notifications here means [DshConnectionService]
 * can hold them open with no Activity in existence, and the UI is a *view* of
 * this state that can come and go.
 *
 * The four attention kinds it raises: an approval, a question, a session error
 * and a goal that went blocked. (A session going idle is raised by the view model,
 * which is the only place that knows a turn ended while you were elsewhere.)
 */
class AttentionCenter(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Pending waterfalls keyed by the session they belong to (the eventId when
     * the host sent no session). A single slot was wrong: an agent and its
     * subagent can both be blocked at once, and the newer waterfall used to evict
     * the older card, leaving it answerable only by its timeout.
     */
    private val approvals = ConcurrentHashMap<String, PendingApproval>()
    private val questionSets = ConcurrentHashMap<String, PendingQuestionSet>()

    /**
     * The answer machine behind each pending approval, keyed by `eventId`.
     *
     * It is what a tap now talks to: [ApprovalGate] owns whether the item may be
     * answered, what has been sent and what the host did with it, so a failed answer
     * can be retried without the card ever having been cleared.
     */
    private val answerGates = ConcurrentHashMap<String, ApprovalGate>()

    /**
     * What each fresh `$events` generation says the host is still holding.
     *
     * The accepted reply settles a card on its own, so this is the second,
     * durable reconciliation: a card the host does not re-announce when it opens a
     * new generation is settled, and that holds across a socket death, a route move
     * and a process freeze alike.
     */
    private val roster = ApprovalRoster()

    /** One bounded reconciliation per held event id, so a later generation replaces it. */
    private val reconcileJobs = ConcurrentHashMap<String, Job>()

    /**
     * The single card the UI shows: whichever of the maps above belongs to the
     * session on screen. A waterfall from a background session still notifies,
     * but it must not hijack the card the user is looking at.
     */
    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    private val _questions = MutableStateFlow<PendingQuestionSet?>(null)
    val questions: StateFlow<PendingQuestionSet?> = _questions.asStateFlow()

    /**
     * Which blocking interaction each session is parked on, for the drawer's
     * status dot — a session-scoped projection, not the single visible card
     * above, because the drawer paints every row at once. Sessions whose
     * waterfall carried no id are absent: nothing in the list can name them.
     */
    private val _pendingBySession = MutableStateFlow<Map<String, PendingKind>>(emptyMap())
    val pendingBySession: StateFlow<Map<String, PendingKind>> = _pendingBySession.asStateFlow()

    /**
     * Set by the UI so an alert already on screen does not also buzz the phone,
     * and to select which pending item the visible card shows. The setter
     * re-selects immediately, so a background waterfall becomes visible the
     * moment the user opens its session.
     */
    /**
     * Sessions that count as on screen because the session they belong to is: the
     * subagents a reader's open session dispatched. A parent and its children are one
     * thing the reader attends to, so opening the parent settles their alerts and
     * stops them buzzing while it is in front.
     */
    @Volatile
    var visibleWith: Set<String> = emptySet()
        set(value) {
            field = value
            value.forEach { markSessionSeen(it) }
        }

    @Volatile
    var visibleSessionId: String? = null
        set(value) {
            field = value
            // Opening a session acknowledges it. Its alerts were *suppressed* while it
            // was on screen (`alert` below), but one that arrived while another session
            // — or no app at all — was in front is already in the shade, and the
            // notification existed only to bring the reader here.
            value?.let { markSessionSeen(it) }
            publishVisible()
        }

    /**
     * Withdraws every alert a session may have posted.
     *
     * Called when the reader opens it, and again when the app is resumed onto it:
     * both are the same fact — they are looking at the session now — and neither
     * should leave a "needs attention" row in the shade for something on screen.
     */
    fun markSessionSeen(sessionId: String) {
        for (id in Attention.sessionNotificationIds(sessionId)) Attention.cancel(context, id)
    }

    /** The session currently on screen is also "visible" only while an activity is resumed. */
    @Volatile
    var foreground: () -> Boolean = { false }

    /**
     * Forwarded `api-session/status` emits: the session id and whether it is
     * running now. This is the *prompt* live running signal — the host's session
     * list carries the same fact plus one the event cannot, the running sample a
     * client that was not listening missed — so the view model must keep receiving
     * it. It cannot own a second `$events` registration (the host fans a waterfall
     * to both, and the extra `next` makes the first client's answer unsettleable),
     * hence the hook.
     */
    @Volatile
    var onLiveStatus: ((sessionId: String, running: Boolean) -> Unit)? = null

    /**
     * A forwarded `api-session/added` emit: the session's **whole list row**
     * (`summaryFor`, the same object `session/list` serves, so it carries
     * `sessionId`, `updatedAt`, `running`, `agentAvailable`, `blank`, `cwd`,
     * `origin`, `parentSessionId` and `projections`).
     *
     * The host re-announces a session this way on `session/created`, and again when
     * an agent is created or disposed, so a row here may be a session the client
     * already holds. The view model decides that; this only relays.
     */
    @Volatile
    var onSessionAdded: ((row: JSONObject) -> Unit)? = null

    /** A forwarded `api-session/removed` emit: the disposed session's id. */
    @Volatile
    var onSessionRemoved: ((sessionId: String) -> Unit)? = null

    /**
     * Whether a session is a subagent, so the notification copy can name the
     * subject correctly. Consulted only where a title is built; an absent id, or
     * one the view model does not know, keeps the ordinary "agent" wording.
     */
    @Volatile
    var isSubagent: (sessionId: String) -> Boolean = { false }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var eventsJob: Job? = null

    /** Follows the mux's four-state value so a connect or a drop re-publishes the widget. */
    private var stateJob: Job? = null
    private var attachedMux: RemoteMux? = null

    fun isStarted(): Boolean = eventsJob?.isActive == true

    /**
     * Whether this client could actually be *told* about a waterfall right now: the
     * `$events` registration is live and the socket carrying it is open.
     *
     * This is the one fact that separates "nothing is waiting" from "nobody is
     * listening", and it is the reason the homescreen widget can say the first at all.
     * It is not the same as [isStarted]: a registration on a dropped socket looks
     * identical from here and would let the app promise to hear something it cannot.
     */
    fun isLive(): Boolean = eventsJob?.isActive == true && attachedMux?.state?.value == MuxState.OPEN

    /**
     * Publishes this centre's reading for the homescreen widget: how many sessions are
     * parked on the human, the highest-precedence kind behind that count, and which
     * session the tap should open.
     *
     * Called from every place the pending set can move, and again on the connection
     * service's keep-alive loop — that second caller is what *renews* the reading, so
     * "checked 0:20 ago" means the app was still able to listen twenty minutes ago
     * rather than that nothing happened to be pending. The publisher throttles the
     * actual write; this method is cheap enough to call as often as the loop ticks.
     */
    fun refreshWidget() {
        val app = context.applicationContext as? DshApplication ?: return
        val pending = _pendingBySession.value
        // One kind per session at the web's cross-domain precedence is already folded
        // into the map, so the top of it is the same answer the drawer's worst row shows.
        val top = pending.entries.maxByOrNull { it.value.precedence }
        app.widgets.needsYou(
            count = pending.size,
            kind = top?.value,
            sessionId = top?.key,
            known = isLive(),
        )
    }

    /**
     * Wires the client's event stream and keeps it open.
     *
     * Idempotent *for one mux*: the service and the view model both call it, and
     * whichever runs first does the work. A different mux, though, is a config
     * edit, a sign-out/sign-in or a service restart — all of which build a fresh
     * instance while this loop is still draining the old one. Returning early on a
     * live `eventsJob` there would leave the new mux with no `onWaterfall`
     * handler, and the host fails every approval and question with `NO_PROVIDER`;
     * so a changed instance tears the old registration down first.
     *
     * That teardown is a full [stop]: the cards and their notifications belonged
     * to a different host (or to credentials that have just been dropped), so they
     * go with it. A connection that is being rebuilt against the *same* host wants
     * [reattach] instead, which keeps them.
     */
    fun start(client: DshClient) {
        val mux = runCatching { client.mux() }.getOrNull() ?: return
        if (attachedMux === mux && eventsJob?.isActive == true) return
        stop()
        attach(client, mux)
    }

    /**
     * Re-point this centre at a new mux **without dropping what is on screen**.
     *
     * A transport restart, a route move and a rewire all replace the multiplexer
     * under a centre that is still the reader's only view of an approval or a
     * question. Those cards are a person's outstanding work, not transport state:
     * dropping them would make a restart look as though the agent had given up,
     * and re-raising them through [start] would buzz the phone a second time for
     * a prompt it had already announced.
     *
     * Nothing here needs to guess whether a card is still real. Opening `$events`
     * on the new mux is a new generation, and the host re-announces every waterfall
     * it is still holding before anything else on that stream; [handleGeneration]
     * reconciles the held cards against that roster. A card the host no longer
     * names is taken down after the grace, and one it does name is refreshed in
     * place by [awaitApproval]'s re-announcement path — the same machinery that
     * already carries a card across a dropped socket.
     */
    fun reattach(client: DshClient) {
        val mux = runCatching { client.mux() }.getOrNull() ?: return
        if (attachedMux === mux && eventsJob?.isActive == true) return
        detach()
        attach(client, mux)
    }

    /** Drops the registration without touching any card; see [reattach]. */
    private fun detach() {
        eventsJob?.cancel()
        eventsJob = null
        stateJob?.cancel()
        stateJob = null
        attachedMux = null
    }

    /** Points this centre at [mux]; the caller owns whatever clearing it needs. */
    private fun attach(client: DshClient, mux: RemoteMux) {
        mux.postEventResult = { args -> client.rpc("\$events/result", args) }
        mux.onWaterfall = { frame, send -> handleWaterfall(frame, send) }
        mux.onWaterfallCancel = { eventId -> handleCancel(eventId) }
        mux.onEventsGeneration = { clientId -> handleGeneration(clientId) }
        attachedMux = mux
        eventsJob = scope.launch {
            while (true) {
                runCatching {
                    mux.openStream("\$events", JSONObject()).collect { event ->
                        if (event is StreamEvent.Item) handleEmit(event.value)
                    }
                }
                // The stream can end (socket death, host restart); without this the
                // next approval would go nowhere at all.
                kotlinx.coroutines.delay(2_000)
            }
        }
        // A socket that opens or drops changes what the widget's reading *means*, not
        // just whether it is fresh: the same count of zero is a fact while connected and
        // silence while not. Without this the published reading could lag a connect by as
        // much as the keep-alive's fifteen-second tick, which is the difference between
        // "nothing needs you" and "nobody is listening" during the one moment a user is
        // most likely to be looking at their phone.
        stateJob = scope.launch {
            mux.state.collect { refreshWidget() }
        }
    }

    fun stop() {
        detach()
        // Release the waits before the maps that name them are dropped: a question
        // is handed back and a gate is withdrawn, so neither old handler stays
        // asleep on its own full cap. Both then find their slot already gone and
        // settle nothing — see [awaitQuestion]'s ownership check.
        val waitingQuestions = pending.values.toList()
        pending.clear()
        waitingQuestions.forEach { it.complete(JSONObject().put("kind", "next")) }
        answerGates.values.forEach { it.withdraw() }
        answerGates.clear()
        // The cards are being dropped wholesale, so the roster that describes them
        // must go too, and no reconciliation may fire against a card that is gone.
        reconcileJobs.values.forEach { it.cancel() }
        reconcileJobs.clear()
        roster.clear()
        // Every pending item alerted under its own session id, so each must be
        // withdrawn by that id; the legacy constants only clear sessionless ones.
        approvals.values.forEach { Attention.cancel(context, Attention.approvalId(it.sessionId)) }
        questionSets.values.forEach { Attention.cancel(context, Attention.questionId(it.sessionId)) }
        approvals.clear()
        questionSets.clear()
        _approval.value = null
        _questions.value = null
        _pendingBySession.value = emptyMap()
        // The registration this reading was renewed from is gone, so the reading is no
        // longer something the app can assert — a different statement from a count of
        // zero, which is why the widget is told rather than left to infer it. The
        // instant is recorded, so the card can still say when it last knew.
        refreshWidget()
    }

    /** Re-created after a config change, so the new mux carries the stream. */
    fun restart(client: DshClient) {
        stop()
        start(client)
    }

    // ---------------------------------------------------------------- inbound

    /** Called when the host's LLM adapters change, so the catalog can be re-read. */
    var onAdaptersUpdated: (() -> Unit)? = null

    private fun handleEmit(value: JSONObject) {
        if (value.str("type") != "emit") return
        when (value.str("event")) {
            "api-session/status" -> {
                val args = value.arr("args") ?: return
                val sessionId = args.optString(0)
                if (sessionId.isNotEmpty()) onLiveStatus?.invoke(sessionId, args.optBoolean(1))
            }

            // The provider set changed: a local router started or stopped, an account
            // signed in, a plugin loaded. The host forwards this precisely so a client
            // can re-read the catalog, and the web refreshes on it. Without it the app
            // shows whatever was true at connect — a router that came up afterwards is
            // simply absent until the next reconnect.
            "llm/adapters-updated" -> onAdaptersUpdated?.invoke()

            // Membership. Forwarded as `emit` (`API_REMOTE_FORWARDED_EVENTS`), and the
            // only push-shaped roster change the host offers: without it a spawned
            // session waits for the next whole-world pull, which on an idle parent can
            // be minutes. The payload is the row itself, so it is relayed rather than
            // summarized here — placement is the view model's business.
            "api-session/added" -> {
                value.arr("args")?.optJSONObject(0)?.let { row -> onSessionAdded?.invoke(row) }
            }

            "api-session/removed" -> {
                val sessionId = value.arr("args")?.optString(0).orEmpty()
                if (sessionId.isNotEmpty()) onSessionRemoved?.invoke(sessionId)
            }

            "api-session/error" -> {
                val args = value.arr("args")
                val sessionId = args?.optString(0)?.takeIf { it.isNotEmpty() }
                val message = args?.optString(1).orEmpty()
                alert(
                    id = Attention.ID_ERROR,
                    sessionId = sessionId,
                    title = "Session failed",
                    text = message.ifEmpty { "A session reported an error." },
                )
            }
        }
    }

    /**
     * The host withdrew a waterfall it had already fanned out — its own timeout,
     * another answerer, or a settle this client's own answer caused.
     *
     * One of the two authoritative clears: the other is the settle of an answer the
     * host itself accepted. Either way the card and its notification go together, by
     * this one method, so the shade cannot keep an alert the screen has dropped.
     */
    private fun handleCancel(eventId: String) {
        val held = synchronized(roster) {
            // Ends the answer wait without sending: whatever the reader tapped a moment
            // ago is moot, and the host has already said so.
            answerGates.remove(eventId)?.withdraw()
            pending.remove(eventId)?.complete(JSONObject().put("kind", "next"))
            // The host named this waterfall as settled, so no window of ours may decide
            // about it again, and any reconciliation already waiting is moot.
            roster.onSettled(eventId)
            forgetReconcile(eventId)
            removeApproval(eventId)
        }
        held?.let {
            Attention.cancel(context, Attention.approvalId(it.sessionId))
            publishVisible()
        }
        removeQuestions(eventId)?.let {
            Attention.cancel(context, Attention.questionId(it.sessionId))
            publishVisible()
        }
    }

    /**
     * A fresh `$events` generation has opened, first connect or reconnect alike.
     *
     * The host re-announces every waterfall it is still holding to the new
     * generation, immediately and before anything else on the stream, and names
     * nothing it has settled. A held card the host has not named by the end of the
     * window has therefore been settled — by another answerer, by the host's own
     * timeout, or by an answer of ours — and the roster is the evidence, not a guess.
     *
     * This is what makes the reconciliation durable rather than tied to one signal:
     * a socket that died, a route that moved and a process that was frozen all end
     * in a new generation, and every one of them re-states the host's pending set.
     */
    private fun handleGeneration(clientId: String) {
        val generation = roster.onGeneration()
        Log.d(TAG, "events generation $generation ($clientId): reconciling ${approvals.size} held")
        approvals.values.toList().forEach { held -> scheduleReconcile(held.eventId, generation) }
    }

    /**
     * Gives the host's re-announcement batch one bounded window in which to name
     * [eventId], then takes a card it did not name as settled.
     *
     * The window is the only timing assumption in the reconciliation, and the wire
     * carries no marker for the end of the batch that could remove it. A late
     * re-announcement is self-healing rather than lost: the frame that arrives after
     * the window raises the card again, as a fresh waterfall on the live generation.
     */
    private fun scheduleReconcile(eventId: String, generation: Long) {
        reconcileJobs.remove(eventId)?.cancel()
        reconcileJobs[eventId] = scope.launch {
            delay(RECONCILE_GRACE_MS)
            reconcileJobs.remove(eventId)
            clearIfSettled(eventId, generation)
        }
    }

    /**
     * Takes the card for [eventId] if the host did not name it in [generation], and
     * leaves it alone if it did.
     *
     * The decision and the removal are one step against [awaitApproval]'s claim,
     * under the roster's own monitor: a re-announcement arriving in the instant a
     * window expires must not have the card it has just been told about taken from
     * under it. Every call inside the monitor is cheap and non-suspending — map
     * writes, a channel send, a job cancellation — and the notification and the
     * republication happen outside it.
     */
    private fun clearIfSettled(eventId: String, generation: Long) {
        val held = synchronized(roster) {
            if (!roster.settled(eventId, generation)) return
            answerGates.remove(eventId)?.withdraw()
            roster.onSettled(eventId)
            forgetReconcile(eventId)
            removeApproval(eventId)
        } ?: return
        Log.d(TAG, "approval $eventId settled: the host did not re-announce it")
        Attention.cancel(context, Attention.approvalId(held.sessionId))
        publishVisible()
    }

    /** Ends the reconciliation waiting on [eventId]: its card is no longer in doubt. */
    private fun forgetReconcile(eventId: String) {
        reconcileJobs.remove(eventId)?.cancel()
    }

    /**
     * Re-projects the per-session maps onto the single visible card and onto the
     * drawer's per-session kinds. Only the on-screen session's item is offered to
     * the card, so a background waterfall cannot replace it; a waterfall with no
     * session at all is the fallback, because nothing else can select it.
     */
    private fun publishVisible() {
        val visible = visibleSessionId
        _approval.value =
            (visible?.let { approvals[it] }) ?: approvals.values.firstOrNull { it.sessionId == null }
        _questions.value =
            (visible?.let { questionSets[it] }) ?: questionSets.values.firstOrNull { it.sessionId == null }

        // One kind per session, at the web's cross-domain precedence
        // (`ui-session/src/client/index.ts:366-386`: larger wins), so an approval
        // and a question raised on the same session cannot flap between colours.
        // Re-offering at the same rank replaces, mirroring the web's `>=` tie.
        val perSession = HashMap<String, PendingKind>()
        fun offer(sessionId: String?, kind: PendingKind) {
            val id = sessionId ?: return
            val held = perSession[id]
            if (held == null || kind.precedence >= held.precedence) perSession[id] = kind
        }
        approvals.values.forEach { offer(it.sessionId, PendingKind.APPROVAL) }
        questionSets.values.forEach { set ->
            offer(
                set.sessionId,
                // `plan-review` is not a separate waterfall: it is the same
                // `user-questions/request` whose intent the card already reads.
                if (set.questions.any { it.isPlanReview }) PendingKind.PLAN_REVIEW
                else PendingKind.QUESTION,
            )
        }
        _pendingBySession.value = perSession
        // The widget is a second reader of this same fold, not a second fold. It is
        // published here, at the one place the per-session map is rebuilt, so the card
        // cannot disagree with the drawer's status dots.
        refreshWidget()
    }

    private fun keyFor(sessionId: String?, eventId: String): String = sessionId ?: eventId

    private fun removeApproval(eventId: String): PendingApproval? {
        val key = approvals.entries.firstOrNull { it.value.eventId == eventId }?.key ?: return null
        return approvals.remove(key)
    }

    private fun removeQuestions(eventId: String): PendingQuestionSet? {
        val key = questionSets.entries.firstOrNull { it.value.eventId == eventId }?.key ?: return null
        return questionSets.remove(key)
    }

    private suspend fun handleWaterfall(frame: JSONObject, send: AnswerSink) {
        when (frame.str("event")) {
            "approval/request" -> awaitApproval(
                eventId = frame.str("eventId"),
                sessionId = frame.str("agentId").takeIf { it.isNotEmpty() },
                request = frame.obj("request") ?: JSONObject(),
                send = send,
            )

            // Without this branch the host's own `noAnswerer` rejects the request and
            // the agent's question fails outright.
            "user-questions/request" -> awaitQuestion(
                eventId = frame.str("eventId"),
                sessionId = frame.str("agentId").takeIf { it.isNotEmpty() },
                request = frame.obj("request") ?: JSONObject(),
                send = send,
            )

            else -> send(JSONObject().put("kind", "next"))
        }
    }

    /**
     * Waits for a decision, and keeps waiting after an answer the host would not take.
     *
     * The loop is the fix. Each pass arms a fresh wait; a tap is posted through [send]
     * and its reply decides what happens next. Accepted holds the item on screen in
     * [ApprovalAnswer.Sent] for the settle grace and then takes it — and its
     * notification — away, because the accepted reply has already said the host is
     * done with the waterfall. A `cancel` that arrives first does the same, sooner.
     * Refused or unreachable puts the item back in a tappable state and goes round
     * again, so the reader can answer once more rather than being shown a card that
     * quietly cleared itself.
     *
     * The cap is the escape hatch, not the policy: a pocketed phone must not hang the
     * agent forever, so after the timeout the request is delegated on and the host
     * refuses it in its own words. If even that cannot be delivered, the card stays up
     * instead of leaving a blocked agent with nothing on screen.
     *
     * A re-announcement of the same waterfall on a new generation arrives here again.
     * The previous wait is ended without sending and loses ownership of the card, so
     * the two waits cannot fight over it; and because the host named the waterfall,
     * `roster` records that it is still pending, which is what stops the reconciliation
     * window from taking a card the host still holds.
     */
    private suspend fun awaitApproval(
        eventId: String,
        sessionId: String?,
        request: JSONObject,
        send: AnswerSink,
    ) {
        val toolName = request.str("toolName").ifEmpty { "a tool" }
        val key = keyFor(sessionId, eventId)
        val gate = ApprovalGate(APPROVAL_TIMEOUT_MS, APPROVAL_SETTLE_GRACE_MS)
        // Told about every transition with the gate itself, because a re-announcement
        // can replace this one while a post is still in flight: only the gate that
        // still owns the card may write to it.
        gate.onState = { next -> setAnswer(eventId, gate, next) }
        // Claiming the card and recording the host's announcement are one step against
        // the reconciliation, so a window expiring at this instant cannot take the card
        // this frame has just been told the host is still holding. A re-announcement
        // after a reconnect is the same waterfall on a new generation: the previous
        // wait is ended without sending, and it cannot take this card down when it
        // returns, because it no longer owns the slot (see `settleApproval`).
        val held = synchronized(roster) {
            val current = approvals[key]
            answerGates.put(eventId, gate)?.withdraw()
            roster.onAnnounced(eventId)
            forgetReconcile(eventId)
            approvals[key] = PendingApproval(
                eventId = eventId,
                toolName = toolName,
                reason = request.str("reason").takeIf { it.isNotEmpty() },
                sessionId = sessionId,
                // A re-announcement is the host saying the waterfall is *still pending*,
                // so a post that was in flight for it did not settle it: the card is
                // legitimately answerable again, and the host applies one outcome per
                // waterfall however many posts reach it. An item whose answer did not
                // get through keeps saying so, because the host still holds it and the
                // reader's last attempt is still the last thing that happened to it.
                answer = (current?.answer as? ApprovalAnswer.Unsent) ?: ApprovalAnswer.Waiting,
            )
            current
        }
        publishVisible()
        // A card the reader has already been shown for this waterfall is not news,
        // and its notification is already in the shade: re-posting one for a
        // re-announcement would buzz a pocketed phone for a prompt it was told about
        // the first time.
        if (held == null) {
            alert(
                id = Attention.approvalId(sessionId),
                sessionId = sessionId,
                title = "Approval needed",
                text = "$toolName is waiting for your permission.",
            )
        }

        gate.answer(send)

        // The wait is over because the host no longer holds this waterfall from our
        // side: it withdrew it (and `handleCancel` has already cleared the card), it
        // accepted an answer, or the cap handed the request back. All three clear the
        // card and its notification, by the ownership-checked path below.
        settleApproval(eventId, gate)
    }

    private suspend fun awaitQuestion(
        eventId: String,
        sessionId: String?,
        request: JSONObject,
        send: AnswerSink,
    ) {
        val questions = request.arr("questions") ?: JSONArray()
        val parsed = (0 until questions.length()).mapNotNull { index ->
            val item = questions.optJSONObject(index) ?: return@mapNotNull null
            val options = item.arr("options")?.let { list ->
                (0 until list.length()).mapNotNull { option ->
                    list.optJSONObject(option)?.let {
                        QuestionOption(it.str("label"), it.str("description").takeIf { d -> d.isNotEmpty() })
                    }
                }
            }.orEmpty()
            PendingQuestion(
                id = item.str("id"),
                header = item.str("header").takeIf { it.isNotEmpty() },
                question = item.str("question"),
                detail = item.str("detail").takeIf { it.isNotEmpty() },
                options = options,
                multiSelect = item.bool("multiSelect"),
                intent = item.obj("intent")?.str("kind")?.takeIf { it.isNotEmpty() },
                approveLabel = item.obj("intent")?.str("approve")?.takeIf { it.isNotEmpty() },
            )
        }
        if (parsed.isEmpty()) {
            send(JSONObject().put("kind", "next"))
            return
        }

        val deferred = CompletableDeferred<JSONObject>()
        pending[eventId] = deferred
        // Resolved once: it drives both the notification title and the card, and
        // the session list can change under us while the question is pending.
        val subjectIsSubagent = sessionId != null && isSubagent(sessionId)
        questionSets[keyFor(sessionId, eventId)] = PendingQuestionSet(
            eventId = eventId,
            sessionId = sessionId,
            questions = parsed,
            subjectIsSubagent = subjectIsSubagent,
        )
        publishVisible()
        val subject = if (subjectIsSubagent) "subagent" else "agent"
        alert(
            id = Attention.questionId(sessionId),
            sessionId = sessionId,
            title = if (parsed.size == 1) "Your $subject is asking"
            else "Your $subject is asking (${parsed.size} questions)",
            text = parsed.first().question,
        )
        val outcome = withTimeoutOrNull(QUESTION_TIMEOUT_MS) { deferred.await() }
            ?: JSONObject().put("kind", "next")
        // Only the wait that still owns this waterfall may settle it. A
        // re-announcement on a new generation — a reconnect, a route move or a
        // transport restart — installs a fresh deferred and card for the same
        // eventId, and this older wait is still asleep on its own copy. Letting it
        // run the two lines below would remove the *new* deferred (so the reader's
        // answer would go nowhere) and send its stale outcome through a socket that
        // no longer exists. The approval path has the same ownership rule, enforced
        // by its gate; a question's deferred is the equivalent token.
        if (pending[eventId] !== deferred) return
        pending.remove(eventId)
        removeQuestions(eventId)?.let {
            Attention.cancel(context, Attention.questionId(it.sessionId))
            publishVisible()
        }
        send(outcome)
    }

    // --------------------------------------------------------------- outbound

    /**
     * The reader answered the approval card.
     *
     * It clears nothing. The tap is handed to the item's [ApprovalGate], which moves
     * the card into its answering state and refuses a second tap; the card and its
     * notification then stay until the host's reply (or its own `cancel`) says the
     * waterfall is settled. Nothing here anticipates that reply — which is the whole
     * of the fix for an accept that used to disappear before anything was sent, and
     * for the one that used to stay because the clear it waited on never came.
     */
    fun resolveApproval(allow: Boolean) {
        val current = _approval.value ?: return
        answerGates[current.eventId]?.tap(allow)
    }

    /**
     * Re-draws one item after its answer moved on, and says so in the shade too.
     *
     * A failed answer is re-posted rather than withdrawn: the notification exists to
     * get a reader who never opens the app to a prompt, and an answer the host has not
     * taken is still an open prompt. Only the host's reply, its `cancel`, or the cap
     * expiring takes it away.
     *
     * Answering and Sent deliberately do not re-post. Both are transitions a reader can
     * only make from the card, which means the app is in the foreground on that
     * session, which means [alert] suppresses the post anyway; and Sent lasts only the
     * settle grace before the card and the shade entry go together.
     *
     * The gate is consulted before anything is written, because a re-announcement may
     * have replaced it while this one's post was still in flight: a wait that no longer
     * owns the card has nothing to say about it.
     */
    private fun setAnswer(eventId: String, gate: ApprovalGate, answer: ApprovalAnswer) {
        // Under the roster's monitor, with the claim and the reconciliation: a gate
        // that no longer owns the card must not write to whatever holds it now.
        val held: PendingApproval? = synchronized(roster) {
            if (answerGates[eventId] !== gate) return
            val key = approvals.entries.firstOrNull { it.value.eventId == eventId }?.key ?: return
            approvals.computeIfPresent(key) { _, current -> current.copy(answer = answer) }
            approvals[key]
        }
        publishVisible()
        if (answer !is ApprovalAnswer.Unsent || held == null) return
        alert(
            id = Attention.approvalId(held.sessionId),
            sessionId = held.sessionId,
            // The card's own words, from the one place that holds them: the card
            // states the not-sent claim in a fixed line and may ellipsise the host's
            // message, so this is where that message is repeated in full rather than
            // a second phrasing that could drift away from it.
            title = ApprovalCopy.UNSENT_TITLE,
            text = ApprovalCopy.unsentNotificationText(held.toolName, answer),
        )
    }

    /**
     * The wait is over with nothing left to do: drops the card and its notification.
     *
     * Only the gate that still owns the item may do this. A re-announcement after a
     * reconnect replaces the gate and raises a live card of its own, and the replaced
     * wait must return without touching it — otherwise a reconnect would take down the
     * card the host has just re-announced. The claim is made under the roster's
     * monitor, as [clearIfSettled]'s decision is, and the identity check is the same
     * one [setAnswer] makes.
     */
    private fun settleApproval(eventId: String, gate: ApprovalGate) {
        val held = synchronized(roster) {
            if (answerGates[eventId] !== gate) return
            answerGates.remove(eventId)
            roster.onSettled(eventId)
            forgetReconcile(eventId)
            removeApproval(eventId)
        } ?: return
        Attention.cancel(context, Attention.approvalId(held.sessionId))
        publishVisible()
    }

    fun answerQuestions(answers: List<Triple<String, List<String>, String?>>) {
        val current = _questions.value ?: return
        val payload = JSONArray()
        answers.forEach { (id, selected, custom) ->
            val answer = JSONObject().put("id", id).put("selected", JSONArray(selected))
            if (!custom.isNullOrBlank()) answer.put("custom", custom)
            payload.put(answer)
        }
        pending.remove(current.eventId)?.complete(
            JSONObject().put("kind", "result").put("value", JSONObject().put("answers", payload)),
        )
        removeQuestions(current.eventId)?.let {
            Attention.cancel(context, Attention.questionId(it.sessionId))
            publishVisible()
        }
    }

    /** Delegates the request untouched, which surfaces the host's own refusal. */
    fun skipQuestions() {
        val current = _questions.value ?: return
        pending.remove(current.eventId)?.complete(JSONObject().put("kind", "next"))
        removeQuestions(current.eventId)?.let {
            Attention.cancel(context, Attention.questionId(it.sessionId))
            publishVisible()
        }
    }

    /**
     * Posts an attention notification unless the user is looking at the very
     * session it is about — buzzing a phone for a card already on screen is noise,
     * and the in-app card is always there.
     */
    fun alert(id: Int, sessionId: String?, title: String, text: String) {
        val onScreen = foreground() &&
            (sessionId == null || sessionId == visibleSessionId || sessionId in visibleWith)
        if (onScreen) return
        Attention.notify(context, id, title, text, sessionId)
    }

    private companion object {
        const val TAG = "DshAttention"

        const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * How long a card stays up after the host has taken the answer.
         *
         * The accepted reply is the confirmation, so this is not a wait for one: it
         * is the room a `cancel` gets to arrive first (another answerer, or the
         * host's own timeout), and the beat in which a reader sees that their answer
         * went. Short enough not to be the stale card the reader complained about.
         */
        const val APPROVAL_SETTLE_GRACE_MS = 1_500L

        /**
         * How long a fresh `$events` generation is given to re-announce what the host
         * still holds before a card it did not name is taken as settled.
         *
         * The batch is delivered in the same burst as the generation's `ready` frame,
         * so this is generous room rather than a latency budget; a re-announcement
         * later than it raises the card again rather than being lost.
         */
        const val RECONCILE_GRACE_MS = 3_000L

        /**
         * Longer than an approval: a question is a real decision, and the cost of
         * being slow is only that the agent moves on without an answer.
         */
        const val QUESTION_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

/** Convenience for callers that only have a config to check. */
fun DshConfig.canConnect(): Boolean = isConfigured
