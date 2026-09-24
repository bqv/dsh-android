package uk.xa0.dsh

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import uk.xa0.dsh.data.DshConfig
import uk.xa0.dsh.model.arr
import uk.xa0.dsh.model.bool
import uk.xa0.dsh.model.obj
import uk.xa0.dsh.model.str
import uk.xa0.dsh.net.DshClient
import uk.xa0.dsh.net.RemoteMux
import uk.xa0.dsh.net.StreamEvent
import java.util.concurrent.ConcurrentHashMap

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
     * The single card the UI shows: whichever of the maps above belongs to the
     * session on screen. A waterfall from a background session still notifies,
     * but it must not hijack the card the user is looking at.
     */
    private val _approval = MutableStateFlow<PendingApproval?>(null)
    val approval: StateFlow<PendingApproval?> = _approval.asStateFlow()

    private val _questions = MutableStateFlow<PendingQuestionSet?>(null)
    val questions: StateFlow<PendingQuestionSet?> = _questions.asStateFlow()

    /**
     * Set by the UI so an alert already on screen does not also buzz the phone,
     * and to select which pending item the visible card shows. The setter
     * re-selects immediately, so a background waterfall becomes visible the
     * moment the user opens its session.
     */
    @Volatile
    var visibleSessionId: String? = null
        set(value) {
            field = value
            publishVisible()
        }

    /** The session currently on screen is also "visible" only while an activity is resumed. */
    @Volatile
    var foreground: () -> Boolean = { false }

    /**
     * Forwarded `api-session/status` emits: the session id and whether it is
     * running now. This is the only live running signal for a subagent — the
     * host's session list reports `running: false` for children — so the view
     * model must keep receiving it. It cannot own a second `$events`
     * registration (the host fans a waterfall to both, and the extra `next`
     * makes the first client's answer unsettleable), hence the hook.
     */
    @Volatile
    var onLiveStatus: ((sessionId: String, running: Boolean) -> Unit)? = null

    /**
     * Whether a session is a subagent, so the notification copy can name the
     * subject correctly. Consulted only where a title is built; an absent id, or
     * one the view model does not know, keeps the ordinary "agent" wording.
     */
    @Volatile
    var isSubagent: (sessionId: String) -> Boolean = { false }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    private var eventsJob: Job? = null
    private var attachedMux: RemoteMux? = null

    fun isStarted(): Boolean = eventsJob?.isActive == true

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
     */
    fun start(client: DshClient) {
        val mux = runCatching { client.mux() }.getOrNull() ?: return
        if (attachedMux === mux && eventsJob?.isActive == true) return
        stop()
        mux.postEventResult = { args -> client.rpc("\$events/result", args) }
        mux.onWaterfall = { frame -> handleWaterfall(frame) }
        mux.onWaterfallCancel = { eventId -> handleCancel(eventId) }
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
    }

    fun stop() {
        eventsJob?.cancel()
        eventsJob = null
        attachedMux = null
        pending.clear()
        // Every pending item alerted under its own session id, so each must be
        // withdrawn by that id; the legacy constants only clear sessionless ones.
        approvals.values.forEach { Attention.cancel(context, Attention.approvalId(it.sessionId)) }
        questionSets.values.forEach { Attention.cancel(context, Attention.questionId(it.sessionId)) }
        approvals.clear()
        questionSets.clear()
        _approval.value = null
        _questions.value = null
    }

    /** Re-created after a config change, so the new mux carries the stream. */
    fun restart(client: DshClient) {
        stop()
        start(client)
    }

    // ---------------------------------------------------------------- inbound

    private fun handleEmit(value: JSONObject) {
        if (value.str("type") != "emit") return
        when (value.str("event")) {
            "api-session/status" -> {
                val args = value.arr("args") ?: return
                val sessionId = args.optString(0)
                if (sessionId.isNotEmpty()) onLiveStatus?.invoke(sessionId, args.optBoolean(1))
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
     * or another answerer. Complete the wait so the mux is not left holding it,
     * and drop the card and notification rather than showing a decision that can
     * no longer be delivered.
     */
    private fun handleCancel(eventId: String) {
        pending.remove(eventId)?.complete(JSONObject().put("kind", "next"))
        removeApproval(eventId)?.let {
            Attention.cancel(context, Attention.approvalId(it.sessionId))
            publishVisible()
        }
        removeQuestions(eventId)?.let {
            Attention.cancel(context, Attention.questionId(it.sessionId))
            publishVisible()
        }
    }

    /**
     * Re-projects the per-session maps onto the single visible card. Only the
     * on-screen session's item is offered, so a background waterfall cannot
     * replace it; a waterfall with no session at all is the fallback, because
     * nothing else can select it.
     */
    private fun publishVisible() {
        val visible = visibleSessionId
        _approval.value =
            (visible?.let { approvals[it] }) ?: approvals.values.firstOrNull { it.sessionId == null }
        _questions.value =
            (visible?.let { questionSets[it] }) ?: questionSets.values.firstOrNull { it.sessionId == null }
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

    private suspend fun handleWaterfall(frame: JSONObject): JSONObject = when (frame.str("event")) {
        "approval/request" -> awaitApproval(
            eventId = frame.str("eventId"),
            sessionId = frame.str("agentId").takeIf { it.isNotEmpty() },
            request = frame.obj("request") ?: JSONObject(),
        )

        // Without this branch the host's own `noAnswerer` rejects the request and
        // the agent's question fails outright.
        "user-questions/request" -> awaitQuestion(
            eventId = frame.str("eventId"),
            sessionId = frame.str("agentId").takeIf { it.isNotEmpty() },
            request = frame.obj("request") ?: JSONObject(),
        )

        else -> JSONObject().put("kind", "next")
    }

    /**
     * Waits for a decision. The cap is the escape hatch, not the policy: a
     * pocketed phone must not hang the agent forever, so after the timeout the
     * request is delegated on and the host refuses it in its own words.
     */
    private suspend fun awaitApproval(
        eventId: String,
        sessionId: String?,
        request: JSONObject,
    ): JSONObject {
        val deferred = CompletableDeferred<JSONObject>()
        pending[eventId] = deferred
        val toolName = request.str("toolName").ifEmpty { "a tool" }
        val item = PendingApproval(
            eventId = eventId,
            toolName = toolName,
            reason = request.str("reason").takeIf { it.isNotEmpty() },
            sessionId = sessionId,
        )
        approvals[keyFor(sessionId, eventId)] = item
        publishVisible()
        alert(
            id = Attention.approvalId(sessionId),
            sessionId = sessionId,
            title = "Approval needed",
            text = "$toolName is waiting for your permission.",
        )
        val outcome = withTimeoutOrNull(APPROVAL_TIMEOUT_MS) { deferred.await() }
            ?: JSONObject().put("kind", "next")
        pending.remove(eventId)
        // Only if this exact item still owns its slot (a newer waterfall from the
        // same session may have replaced it): withdraw *its* notification, not the
        // one now on screen.
        removeApproval(eventId)?.let {
            Attention.cancel(context, Attention.approvalId(it.sessionId))
            publishVisible()
        }
        return outcome
    }

    private suspend fun awaitQuestion(
        eventId: String,
        sessionId: String?,
        request: JSONObject,
    ): JSONObject {
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
        if (parsed.isEmpty()) return JSONObject().put("kind", "next")

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
        pending.remove(eventId)
        removeQuestions(eventId)?.let {
            Attention.cancel(context, Attention.questionId(it.sessionId))
            publishVisible()
        }
        return outcome
    }

    // --------------------------------------------------------------- outbound

    fun resolveApproval(allow: Boolean) {
        val current = _approval.value ?: return
        val outcome = JSONObject()
            .put("kind", "result")
            .put("value", if (allow) "allowed-once" else "rejected")
        pending.remove(current.eventId)?.complete(outcome)
        // The card names its own item, so resolving it leaves every other
        // session's pending approval — and notification — untouched.
        removeApproval(current.eventId)?.let {
            Attention.cancel(context, Attention.approvalId(it.sessionId))
            publishVisible()
        }
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
        val onScreen = foreground() && (sessionId == null || sessionId == visibleSessionId)
        if (onScreen) return
        Attention.notify(context, id, title, text, sessionId)
    }

    private companion object {
        const val APPROVAL_TIMEOUT_MS = 5 * 60 * 1000L

        /**
         * Longer than an approval: a question is a real decision, and the cost of
         * being slow is only that the agent moves on without an answer.
         */
        const val QUESTION_TIMEOUT_MS = 15 * 60 * 1000L
    }
}

/** Convenience for callers that only have a config to check. */
fun DshConfig.canConnect(): Boolean = isConfigured
