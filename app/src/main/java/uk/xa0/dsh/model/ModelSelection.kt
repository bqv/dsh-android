package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * One `{provider, model, reasoningEffort?}` route reference, which is the shape the
 * host uses for both halves of the `modelSelection` projection and for the
 * `selected` answer to `session/selectModel`.
 *
 * [reasoningEffort] is null when the host did not name one. That is not the same as
 * "off": a null effort means the route's own `defaultEffort` applies, which is what
 * the web trigger's `'Default'` label says.
 */
data class ModelRef(
    val provider: String,
    val model: String,
    val reasoningEffort: String? = null,
) {
    /** How the host addresses the route in prose and how the chip names an unknown one. */
    val id: String get() = "$provider/$model"
}

/**
 * Reads one route reference, or null when it is absent or does not name a route.
 *
 * A JSON `null` and a missing key both arrive here as null and are both "no route":
 * `org.json`'s `optJSONObject` cannot tell them apart, and for this projection the
 * distinction does not exist — the host uses null for "this session has no selection
 * of its own", which is exactly what a missing value would mean too.
 */
fun parseModelRef(value: JSONObject?): ModelRef? {
    val source = value ?: return null
    val provider = source.str("provider")
    val model = source.str("model")
    if (provider.isEmpty() || model.isEmpty()) return null
    return ModelRef(
        provider = provider,
        model = model,
        reasoningEffort = source.str("reasoningEffort").takeIf { it.isNotEmpty() },
    )
}

/**
 * A session's `modelSelection` projection (probed live on host 0.2.0-rc.2).
 *
 * The two halves are **not** the same fact and routinely disagree:
 *
 *  * `next` is what the next turn will use. The host's `session/selectModel`
 *    handler calls `agents.selectForNextRequest(...)`, so a selection applies to
 *    the *next request* and never retroactively. `next` therefore moves the instant
 *    a selection is accepted.
 *  * `lastUsed` is the route the last turn actually ran. It only moves when a turn
 *    runs.
 *
 * While they differ, a switch is **pending**: the session is still running the old
 * route and will run the new one on the next turn. Both being null is an ordinary
 * state too — a session with no selection of its own, which runs the host's global
 * default.
 */
data class HostModelSelection(
    val lastUsed: ModelRef?,
    val next: ModelRef?,
)

/** Reads a `modelSelection` projection value; null when the key was not carried at all. */
fun parseHostModelSelection(value: JSONObject?): HostModelSelection? {
    if (value == null) return null
    return HostModelSelection(
        lastUsed = parseModelRef(value.optJSONObject("lastUsed")),
        next = parseModelRef(value.optJSONObject("next")),
    )
}

/** Where the route the chip advertises came from. */
enum class ModelChoiceSource {
    /** Nothing is known yet: no session selection and no catalog default. */
    NONE,

    /** The open session's own `modelSelection.next`. */
    SESSION_NEXT,

    /**
     * The session has no selection of its own, so the host's **global** default
     * (`session/modelCatalog.default`) is what its next request will run.
     */
    HOST_DEFAULT,

    /** A local pick the host has not accepted yet. */
    PENDING_PICK,
}

/** Why the advertised route is not simply "what is running". */
enum class ModelPendency {
    /** The host's `next`, with no turn having run a different route since. */
    NONE,

    /**
     * Picked on the new-session screen. There is no session to select for yet, so
     * the pick is recorded and applied to the session this seat creates — never
     * sent anywhere before that.
     */
    ON_CREATE,

    /**
     * The host holds this route for the next turn, but the last turn ran a
     * different one. The advertised model is *not* the model that is running.
     */
    NEXT_TURN,

    /** Sent to the host; its answer has not landed, so nothing is in force yet. */
    IN_FLIGHT,
}

/**
 * What the model selector must show, and how sure it is.
 *
 * The chip used to be built from the catalog's global `default`, then set from the
 * *request* and never reconciled with the host's answer, then refreshed only at
 * connect. All three were the same mistake: advertising something other than the
 * host's `modelSelection.next`. [source] and [pendency] are the two facts the chip
 * needs to stop doing that — where the route came from, and whether it is in force.
 */
data class ModelChoice(
    /** Null only when [source] is [ModelChoiceSource.NONE] — nothing to advertise. */
    val ref: ModelRef? = null,
    val source: ModelChoiceSource = ModelChoiceSource.NONE,
    val pendency: ModelPendency = ModelPendency.NONE,
    /** The route the last turn ran, when the host reported one. */
    val lastUsed: ModelRef? = null,
    /**
     * The host's catalog does not offer this route (or does not accept this effort
     * for it), so `session/selectModel` would refuse it and no turn can run it. The
     * route is still advertised — naming what the session *holds* is the honest
     * thing — but the UI must not present it as runnable.
     *
     * False until the catalog has actually answered: "not loaded" is not "gone".
     */
    val unavailable: Boolean = false,
)

/**
 * The model selector's whole state as one value the view model replaces.
 *
 * Being a pure value is the point: every rule below is unit-testable, and the four
 * mistakes this area has already made (the global default, the unanswered request,
 * the one-shot refresh, the selection carried across a session switch) are all
 * expressible here as inputs rather than as UI accidents.
 */
data class ModelSelectionState(
    /** The open session's projection, or null when the host did not carry one. */
    val host: HostModelSelection? = null,
    /** `session/modelCatalog.default` — the host's global default *agent* model. */
    val catalogDefault: ModelRef? = null,
    /** A pick recorded on the new-session screen, applied when a session is created. */
    val pendingPick: ModelRef? = null,
    /** A pick already sent to the host, awaiting its answer. */
    val inFlight: ModelRef? = null,
) {
    /**
     * The route the chip must advertise.
     *
     * A session with a `next` advertises it, whatever the picker's own state; that is
     * the host's answer to "what will the next turn run". The one thing that outranks
     * it is a pick still out with the host, which is shown as in flight rather than as
     * either the old route or the hoped-for one. Only when there is no session, or the
     * session has no selection of its own, does a local pick or the catalog default
     * come into it.
     *
     * [sessionOpen] decides between the two worlds rather than `host == null`,
     * because an open session whose projection has not landed yet is a real moment:
     * its selections must not be replaced by the new-session screen's rules.
     *
     * [isKnown] answers "does the catalog offer this route and this effort?". Passing
     * **null** means the catalog has not answered — a failed `session/modelCatalog`,
     * or a read still in flight — and then nothing is called [ModelChoice.unavailable]:
     * an empty list is "not loaded", not evidence against a model.
     */
    fun choice(sessionOpen: Boolean, isKnown: ((ModelRef) -> Boolean)?): ModelChoice {
        if (sessionOpen) {
            inFlight?.let { return advertise(it, ModelChoiceSource.PENDING_PICK, ModelPendency.IN_FLIGHT, isKnown) }
            host?.next?.let { next ->
                // A pending switch: the previous turn ran something else. `lastUsed`
                // being null means no turn has run at all — nothing contradicts the
                // selection, so it is not a pending *change*.
                val pending = host.lastUsed != null && host.lastUsed != next
                return advertise(
                    ref = next,
                    source = ModelChoiceSource.SESSION_NEXT,
                    pendency = if (pending) ModelPendency.NEXT_TURN else ModelPendency.NONE,
                    isKnown = isKnown,
                    lastUsed = host.lastUsed,
                )
            }
            // No selection of this session's own: the host's global default is what
            // its next request runs. That is the fact the old chip got wrong by
            // drawing the default even when the session *had* a selection.
            return advertise(
                ref = catalogDefault,
                source = ModelChoiceSource.HOST_DEFAULT,
                pendency = ModelPendency.NONE,
                isKnown = isKnown,
                lastUsed = host?.lastUsed,
            )
        }
        // No session: the new-session hero. A pick here has nowhere to go yet, so it
        // is shown as pending-on-create rather than pretended into force.
        pendingPick?.let { return advertise(it, ModelChoiceSource.PENDING_PICK, ModelPendency.ON_CREATE, isKnown) }
        return advertise(
            ref = catalogDefault,
            source = ModelChoiceSource.HOST_DEFAULT,
            pendency = ModelPendency.NONE,
            isKnown = isKnown,
        )
    }

    private fun advertise(
        ref: ModelRef?,
        source: ModelChoiceSource,
        pendency: ModelPendency,
        isKnown: ((ModelRef) -> Boolean)?,
        lastUsed: ModelRef? = null,
    ): ModelChoice {
        if (ref == null) {
            return ModelChoice(ref = null, source = ModelChoiceSource.NONE, lastUsed = lastUsed)
        }
        return ModelChoice(
            ref = ref,
            source = source,
            pendency = pendency,
            lastUsed = lastUsed,
            unavailable = isKnown != null && !isKnown(ref),
        )
    }

    fun withCatalogDefault(default: ModelRef?): ModelSelectionState = copy(catalogDefault = default)

    /** Replaces the session's projection. `null` means the host carried none. */
    fun withHostSelection(selection: HostModelSelection?): ModelSelectionState =
        copy(host = selection)

    fun withPendingPick(pick: ModelRef?): ModelSelectionState = copy(pendingPick = pick)

    /** Records a pick as sent, so the chip can say "applying…" until [withAnswer] lands. */
    fun withInFlight(pick: ModelRef?): ModelSelectionState = copy(inFlight = pick)

    /**
     * Adopts the host's answer to `session/selectModel`.
     *
     * The answer is the host's own statement of what it selected, and it does not
     * always repeat the request — the effort is resolved from the route's default
     * when the request omitted one, and a host that will not route a provider answers
     * with another route entirely. Writing it into `next` is what keeps the chip and
     * the host's state from drifting apart between projection updates.
     */
    fun withAnswer(selected: ModelRef): ModelSelectionState = copy(
        host = HostModelSelection(lastUsed = host?.lastUsed, next = selected),
        inFlight = null,
    )

    /** The pick was refused (or the call failed): drop it and fall back to host truth. */
    fun withRefusal(): ModelSelectionState = copy(inFlight = null)

    /**
     * A session switch. The catalog default is host-wide and survives; everything
     * scoped to the session being left — and the new-session pick, which is about a
     * session that will never be created on this path — goes.
     */
    fun onSessionOpened(): ModelSelectionState = copy(
        host = null,
        pendingPick = null,
        inFlight = null,
    )

    /**
     * Leaving a session for the new-session seat. The session's own state goes, but
     * the seat's pick stays: re-recording the seat's target (a different Workspace,
     * say) is not a reason to discard a model the reader chose for the session that
     * seat will create.
     */
    fun onSessionLeft(): ModelSelectionState = copy(
        host = null,
        inFlight = null,
    )
}
