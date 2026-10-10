package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The llama.cpp **router** behind a session's local route: what the host
 * *declares* about it, what the host can *measure* on this client's behalf, and
 * what nothing on the host will report at all.
 *
 * The user's own word for this surface is "router", and it is the right one: a
 * `llama-server` started with `--models-dir` is a router that holds several GGUFs
 * and loads one on demand — `/props` answers `{"role":"router", …}` — not a single
 * model server. The tab drawn from this file is therefore named Router.
 *
 * ## What the router itself reports (measured on the host, 2026-10-10)
 *
 * The box runs two OpenRC routers on the host's loopback — `llama` on
 * `127.0.0.1:55555` (`--models-max 1`) and `llamb` on `127.0.0.1:55556`
 * (`--models-max 2`). Each answers, **on the host only**:
 *
 *  * `GET /health` → `{"status":"ok"}`.
 *  * `GET /v1/models` → every GGUF in the scanned directory, each with
 *    `status.value` ∈ `loaded` / `loading` / `unloaded` and, on a loaded entry, the
 *    router's own launch `args` — from which `--ctx-size` (e.g. `122880`) and the
 *    child instance's `--port` (e.g. `43723`) are readable. **This is the load
 *    fact**: which model is resident.
 *  * `GET /slots?model=<id>` → per-slot prefill state for the loaded model:
 *    `is_processing`, `n_prompt_tokens`, `n_prompt_tokens_processed`,
 *    `n_prompt_tokens_cache`, and `next_token[0].n_decoded` / `.n_remain`. **This
 *    is the prefill fact.** Without `?model=` the router answers
 *    `400 model name is missing from the request`.
 *  * `GET /metrics?model=<id>` → Prometheus counters
 *    (`llamacpp:prompt_tokens_total`, `prompt_tokens_cached_total`, …).
 *
 * **The host's Remote API proxies none of it, and no method names a URL.** Re-verified
 * 2026-10-10 rather than inherited: on `127.0.0.1:8080` and `127.0.0.1:8081` — the only
 * two ports the phone can reach — `/health`, `/v1/models`, `/props`, `/slots` and
 * `/metrics` all answer **404**; `:80` answers **302** (nginx's primer redirect).
 * `nginx -T` still shows a single `location / { proxy_pass http://127.0.0.1:8080; }`
 * per server block and no other route.
 *
 * **But the app does reach the router's state — through a terminal on the host.** The
 * route that needs no plugin, no tunnel, no listener and no exposure was found and
 * implemented on 2026-10-10: `model/RouterReadout.kt` and `net/RouterReadoutTransport.kt`
 * open a terminal in the session through the `terminal/*` Remote namespace the app
 * already drives for the Shell tab, run `curl` on the host's own loopback, and read the
 * answer back from the `terminal/follow` output stream. Measured from a throwaway
 * session against both live routers: `/props` + `/v1/models` in one write at 24 ms,
 * plus `/slots` for a resident model at 42 ms in total, attach included at ~46 ms.
 *
 * The rest of this header is kept because it is still true of the *direct* routes, and
 * because it records why a plugin was once believed to be the only answer. The
 * `localRouter/status` spec survives as an optional cheaper path: a host that composes
 * it answers in one round trip and never needs a terminal.
 *
 * ## The two sources this file does use
 *
 *  * **The declarations.** `settings/describe` carries the whole settings
 *    document, including the `llm-pi-ai` namespace whose `providers` map holds
 *    one profile per route: `displayName`, `api`, `baseURL`, per-model
 *    `contextWindow` / `maxTokens` / `input`, and the provider's
 *    `defaultContextWindow` / `defaultInput` / `headers`. Those are the host's own
 *    statements about the endpoint it will talk to, so they are what the tab shows
 *    when nothing has been measured.
 *
 *  * **One measurement the host performs on this client's behalf.**
 *    `llm/discoverModels(settingsNs, {baseURL, api, apiKey})` makes the host open
 *    the endpoint and read its model list with a single `GET <baseURL>/models`. It
 *    is a genuine live probe, not a lookup: against a port with nothing listening it
 *    answers `llm/model-discovery-rejected` / `could not reach http://127.0.0.1:55599/v1/models`,
 *    and against the live router it answers the twelve model ids the router
 *    advertises. That is a real fact about the running router — *whether the host
 *    can reach it, and what it advertises right now* — and it is the only one
 *    `llm/<method>` offers. The provider is deliberately left unnamed in the request;
 *    [discoverModelsArgs] records the probed trap that makes that necessary.
 *
 * ## Why the load and prefill state is still missing, and what would supply it
 *
 * The host *can* see the load state: `llm/discoverModels` is the proof, because it
 * performs exactly the `GET /v1/models` that carries `status.value`. It simply
 * does not **return** it — `readListing` in `dsh-llm-pi-ai/lib/index.js` maps each
 * entry to `{id, name, contextWindow?, maxTokens?}` and drops every other field,
 * `status` and its launch args included. So the transport exists and the report
 * does not. Probed: `llm/discoverModels` against `127.0.0.1:55555/v1` answers
 * twelve `{id, name}` pairs and no status.
 *
 * Swept for a way round it, on 2026-10-10:
 *
 *  * **`llm/discoverModels` cannot be bent into a general GET.** `listingUrl` is a
 *    bare `${base}/models` string concatenation and `readListing` accepts only a
 *    `data` array or a `models` object, so no `baseURL` value reaches `/slots`, and
 *    a `/slots` reply would be rejected as "not a model listing" in any case.
 *  * **No other client-callable method names a URL at all.** Of the 26
 *    `typert.host.js` descriptors installed, only `dsh-llm`'s has a URL-bearing
 *    parameter (`settingsNs` + `request`), and `web/fetch`, `http/get` and five
 *    similar guesses all answer `not found`.
 *  * **There is no one-shot exec, and a client cannot start a job.** The complete
 *    `job` namespace is `job/list`, `job/follow` and `job/kill` — there is no start,
 *    and `dsh-jobs-local` refuses to create one for anything but a live agent
 *    (`"background job owner must be live"`). The `commands` namespace is a
 *    *human-command* registry: `commands/list` and `commands/execute` act on
 *    slash-commands such as `/compact` and `/goal`, so "a registered command that
 *    curls the router" would itself have to be registered by a plugin. A command is
 *    therefore not a route around the plugin; the terminal is.
 *  * **The Web-fetch plugins are not a door.** `dsh-web-fetch-http` is loaded but
 *    registers into the `web` service, not a Remote namespace (no `typert.host.js`
 *    at all), and it is built to *refuse* private addresses — "the connection
 *    cannot resolve the hostname again to a private address". `dsh-http-proxy`
 *    merges loopback into every policy's `noProxy`.
 *  * **`localLlm/<method>` is the wrong router.** Its complete Remote surface is
 *    `getState / start / stop / saveConfig / listSlotFiles / addProviders` —
 *    probed one by one, with `listSlotStatus`, `getSlots`, `getMetrics`,
 *    `getHealth`, `getProps` and `getRouterStatus` all answering `not found`.
 *    `getState` reports that plugin's **own child process**, and `startSlot`
 *    refuses a port whose `/health` already answers ok (`端口 … 已有服务在运行`),
 *    so it can neither adopt nor observe the OpenRC routers. Its `pid` is gated on
 *    `this.proc` being non-null and is therefore always null when it owns no child
 *    — even though its `resolveServerPid()` does ask the OS which process LISTENs
 *    on the configured port.
 *  * **Do not query `/slots?model=` for a model that is not already `loaded`.**
 *    `/props` reports `models_autoload: true`, and one such request in this sweep
 *    did not answer within eight seconds (`HTTP 000`) while the loaded model was
 *    mid-prefill. The readings were taken with `curl -m 8`, no new `llama-server`
 *    appeared (`ss -ltnp` and `pgrep -x llama-server` both unchanged), and the
 *    target model still read `unloaded` afterwards — so nothing was loaded, but
 *    *why* it hung is inference, not measurement. The readout therefore reads
 *    `/v1/models` first and asks for slots only for the model already resident:
 *    [residentModelForSlots] is that gate, and [slotsForReadout] carries the reason
 *    whenever it says no — so "we did not ask" stays distinguishable from "the router
 *    reported no slots".
 *
 * [routerStatusArgs] and [parseRouterStatus] remain the **client half** of the method
 * specified in `docs/HANDOFF.md` as `localRouter/status`. It is no longer the only
 * route and no longer required: it is kept because a host that composes it answers in
 * one round trip, and the tab prefers it when it exists.
 *
 * ## Why the gate is the endpoint and not the provider's name
 *
 * Nothing in `session/modelCatalog` says which provider is local: a catalog group
 * is `{id, name, models}` and nothing else. Provider *names* would be worse than
 * useless — the live host's local routes are `dsh-local`, `local-2slot`,
 * `dsh-compactor` and `dsh-local-aux`, so a `dsh-local` prefix rule would miss one
 * and a hand-kept list would rot. The declared endpoint is the fact: a provider
 * whose profile points at a loopback address is served by something on this very
 * host. [isLoopbackEndpoint] is that rule, and it is why the tab cannot appear for
 * a cloud route — no cloud profile names `127.0.0.1`.
 */

/** One model as the host's provider profile declares it. */
data class LocalModelEntry(
    val id: String,
    val name: String? = null,
    val description: String? = null,
    /** Null when the profile names none; the provider default then applies. */
    val contextWindow: Int? = null,
    val maxTokens: Int? = null,
    /** Empty means "the provider's `defaultInput` applies", not "no modalities". */
    val input: List<String> = emptyList(),
)

/**
 * One provider profile from the host's settings document.
 *
 * Only profiles that name an endpoint are kept: a profile with no [baseURL] has
 * nothing for this tab to describe and nothing to probe, so the invariant "every
 * [LocalProvider] names an endpoint" holds for every reader.
 */
data class LocalProvider(
    val id: String,
    /** The settings namespace this profile was declared in (`llm-pi-ai` live). */
    val settingsNs: String,
    val displayName: String,
    /** The wire protocol, e.g. `openai-completions`. */
    val api: String? = null,
    val baseURL: String,
    val defaultContextWindow: Int? = null,
    val defaultMaxTokens: Int? = null,
    val defaultInput: List<String> = emptyList(),
    /** Header *names* the profile sends, e.g. `["authorization"]`; never values. */
    val headerNames: List<String> = emptyList(),
    /**
     * The profile's declared `authorization` value, kept only so the probe can
     * authenticate exactly as a real request would. It is never rendered: the tab
     * says a credential is configured, never what it is.
     */
    val authHeader: String? = null,
    val models: Map<String, LocalModelEntry> = emptyMap(),
)

/** The router behind the route the session will run, when that route is local. */
data class RouterTarget(
    val provider: LocalProvider,
    val modelId: String,
    /**
     * The host's own entry for [modelId], or null when the profile does not list
     * it — which is a real state: the routers advertise every GGUF in their
     * directory, while the settings document lists the rungs a session may pick.
     */
    val model: LocalModelEntry? = null,
) {
    val displayName: String get() = provider.displayName
    val endpoint: String get() = provider.baseURL

    /** Declared window: the model's own, else the provider's default. */
    val contextWindow: Int? get() = model?.contextWindow ?: provider.defaultContextWindow
    val maxTokens: Int? get() = model?.maxTokens ?: provider.defaultMaxTokens

    /** Declared modalities: the model's own list, else the provider's default. */
    val input: List<String> get() = model?.input?.takeIf { it.isNotEmpty() } ?: provider.defaultInput

    /**
     * Whether the profile declares any credential at all.
     *
     * A fact about the *profile*, not about this route, so both halves are read off
     * [provider]: `headers` is where the host puts the per-request credential, and
     * only its presence is ever reported — the value is [LocalProvider.authHeader]
     * and is never rendered.
     */
    val authenticated: Boolean
        get() = provider.authHeader != null || provider.headerNames.isNotEmpty()
}

/**
 * Reads every provider profile the settings document declares, keyed by provider
 * route.
 *
 * Every namespace is walked rather than `llm-pi-ai` being hardcoded: the profile
 * map is the namespace's shape, and the namespace a route lives in is carried on
 * [LocalProvider.settingsNs] because `llm/discoverModels` needs it back verbatim.
 */
fun parseLocalProviders(reply: JSONObject?): Map<String, LocalProvider> {
    val namespaces = reply?.arr("namespaces") ?: return emptyMap()
    val providers = LinkedHashMap<String, LocalProvider>()
    for (i in 0 until namespaces.length()) {
        val view = namespaces.optJSONObject(i) ?: continue
        val ns = view.str("ns")
        val declared = view.obj("value")?.obj("providers") ?: continue
        for (id in declared.keys()) {
            val entry = declared.optJSONObject(id) ?: continue
            if (entry.str("baseURL").isBlank()) continue
            providers[id] = parseProvider(id, ns, entry)
        }
    }
    return providers
}

private fun parseProvider(id: String, ns: String, entry: JSONObject): LocalProvider {
    val models = LinkedHashMap<String, LocalModelEntry>()
    entry.arr("models")?.let { list ->
        for (i in 0 until list.length()) {
            val declared = list.optJSONObject(i) ?: continue
            val modelId = declared.str("id")
            if (modelId.isEmpty()) continue
            models[modelId] = LocalModelEntry(
                id = modelId,
                name = declared.str("name").takeIf { it.isNotEmpty() },
                description = declared.str("description").takeIf { it.isNotEmpty() },
                contextWindow = declared.intOrNull("contextWindow"),
                maxTokens = declared.intOrNull("maxTokens"),
                input = declared.strings("input"),
            )
        }
    }
    val headers = entry.obj("headers")
    return LocalProvider(
        id = id,
        settingsNs = ns,
        displayName = entry.str("displayName").takeIf { it.isNotEmpty() } ?: id,
        api = entry.str("api").takeIf { it.isNotEmpty() },
        baseURL = entry.str("baseURL").trim(),
        defaultContextWindow = entry.intOrNull("defaultContextWindow"),
        defaultMaxTokens = entry.intOrNull("defaultMaxTokens"),
        defaultInput = entry.strings("defaultInput"),
        headerNames = headers?.keys()?.asSequence()?.toList().orEmpty(),
        authHeader = headers?.str("authorization")?.takeIf { it.isNotEmpty() },
        models = models,
    )
}

/**
 * The router the session's route runs on, or null when there is nothing local
 * behind it.
 *
 * Built from [ModelChoice] — the same value the composer's model trigger draws —
 * so the tab and the chip cannot disagree about which model is selected. Null for
 * every state that is not "this session's route is local": no selection at all, a
 * cloud route, and a pending switch *to* a cloud route (a local route the last
 * turn ran does not keep the tab alive; the session is about to leave it). A
 * pending switch *to* a local route shows the tab, because that is the route the
 * next turn will run.
 *
 * A route the catalog no longer lists still resolves when its provider profile
 * survives: the session holds it, so the tab names the router it would run on,
 * exactly as the trigger names the route.
 */
fun routerTargetOf(
    choice: ModelChoice,
    providers: Map<String, LocalProvider>,
): RouterTarget? {
    val ref = choice.ref ?: return null
    val provider = providers[ref.provider] ?: return null
    if (!isLoopbackEndpoint(provider.baseURL)) return null
    return RouterTarget(provider = provider, modelId = ref.model, model = provider.models[ref.model])
}

/**
 * Whether an endpoint names this very host.
 *
 * Loopback only, and deliberately narrow: `127.0.0.0/8` in full (not just
 * `127.0.0.1`), `localhost`, and IPv6 `::1` with or without the brackets a URL
 * uses. A cloud or LAN endpoint is not local even though it may be reachable —
 * the tab's claim is about a router on the host, so that is what it may say.
 */
fun isLoopbackEndpoint(baseURL: String): Boolean {
    val host = endpointHost(baseURL) ?: return false
    if (host == "localhost" || host == "::1") return true
    val parts = host.split('.')
    return parts.size == 4 &&
        parts[0] == "127" &&
        parts.drop(1).all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all { it.isDigit() } && part.toInt() in 0..255
        }
}

/** The host of [baseURL], lowercased and without brackets or port; null when unreadable. */
fun endpointHost(baseURL: String): String? {
    val value = baseURL.trim()
    if (value.isEmpty()) return null
    val afterScheme = value.substringAfter("://", value)
    val authority = afterScheme.substringBefore('/').substringAfterLast('@')
    if (authority.isEmpty()) return null
    val host = if (authority.startsWith("[")) {
        authority.substringAfter('[').substringBefore(']')
    } else {
        authority.substringBefore(':')
    }
    return host.lowercase().ifEmpty { null }
}

/** What the last reachability check found. */
sealed interface RouterProbeResult {
    /** Nothing checked yet for this route. */
    data object Idle : RouterProbeResult

    data object Checking : RouterProbeResult

    /**
     * The host reached the endpoint and read its model list. The ids are the
     * endpoint's own, in its own order — nothing here is inferred or padded.
     *
     * [load] is the router's own load and prefill report, and it is **null on every
     * host that does not offer `localRouter/status`** — which is every host today.
     * Null therefore means "not reported", never "nothing is loaded": the two are
     * different facts and the tab words them differently. See [RouterLoad].
     */
    data class Reachable(
        val modelIds: List<String>,
        val atMillis: Long,
        val load: RouterLoad? = null,
        /**
         * Why the router's own readout was not obtained, when the endpoint is
         * reachable but nothing was measured.
         *
         * Three states, and they are different facts:
         *
         *  * [load] non-null — the router's load and prefill were read.
         *  * [load] null and this null — nothing here can read them, so they were
         *    never offered. The tab says the readout is not available.
         *  * [load] null and this set — the readout *was* attempted and did not
         *    arrive, and this is the reason in the host's or the app's own words. The
         *    tab shows the reason rather than a generic "not reported", so a readout
         *    that failed for a fixable reason does not look like a host that cannot
         *    offer one.
         *
         * The endpoint being reachable is established independently of the readout —
         * `llm/discoverModels` makes the host perform the `GET <baseURL>/models` — so
         * a readout failure never downgrades the reachability answer itself.
         */
        val readoutIssue: String? = null,
    ) : RouterProbeResult {
        fun advertises(modelId: String): Boolean = modelIds.any { it == modelId }
    }

    /** The host could not reach it, in the host's own words. */
    data class Unreachable(val message: String, val atMillis: Long) : RouterProbeResult
}

// ------------------------------------------------------ the router's own report

/**
 * One model as the router's own listing reports it.
 *
 * [status] is the router's word verbatim (`loaded`, `loading`, `unloaded`, and
 * whatever a future router adds); [isLoaded] and [isLoading] compare it case
 * insensitively rather than the parser narrowing it to an enum, so an unfamiliar
 * value is shown as itself instead of being silently coerced into one of ours.
 */
data class RouterModelStatus(
    val id: String,
    val status: String,
    /** The child instance's `--ctx-size`, when the router reported launch args. */
    val contextSize: Int? = null,
    /** The child instance's `--port`, when the router reported launch args. */
    val childPort: Int? = null,
) {
    val isLoaded: Boolean get() = status.equals("loaded", ignoreCase = true)
    val isLoading: Boolean get() = status.equals("loading", ignoreCase = true)

    /** Whether the weights are in memory, or on their way there. */
    val isResident: Boolean get() = isLoaded || isLoading
}

/**
 * One slot's prefill state, from the router's `/slots`.
 *
 * The two numbers that answer "how far through the prompt is it" are
 * [promptTokens] (the prompt's length) and [promptTokensProcessed] (how much of it
 * this task has consumed). [promptTokensCache] is the part served from the cache
 * instead of recomputed, which is why it is reported separately rather than folded
 * into the difference.
 */
data class RouterSlot(
    val id: Int,
    val isProcessing: Boolean,
    val promptTokens: Int? = null,
    val promptTokensProcessed: Int? = null,
    val promptTokensCache: Int? = null,
    /** `next_token[0].n_decoded` — tokens generated for the task on this slot. */
    val decoded: Int? = null,
    /** `next_token[0].n_remain` — the router's own remaining-output allowance. */
    val remaining: Int? = null,
) {
    /**
     * Prompt tokens still to process, or null when the router reported neither half.
     *
     * Derived from two measured numbers rather than reported by the router, so it is
     * named for what it is and the tab shows the two halves beside it.
     */
    val promptTokensRemaining: Int?
        get() = promptTokens?.let { total ->
            promptTokensProcessed?.let { done -> (total - done).coerceAtLeast(0) }
        }
}

/**
 * The router's own load and prefill report, as the specified `localRouter/status`
 * method would return it.
 *
 * Every field is optional because every field is *reported by the router* and a
 * router that answered a shorter document must not be made to look like one that
 * answered zeroes. An empty [models] list means the router listed nothing — the
 * tab then says so rather than claiming nothing is loaded.
 */
data class RouterLoad(
    /** `/props`'s `role`, e.g. `router`. */
    val role: String? = null,
    /** `/props`'s `max_instances` — how many models this router may hold at once. */
    val maxInstances: Int? = null,
    /** `/props`'s `models_autoload` — whether naming a model loads it on demand. */
    val modelsAutoload: Boolean? = null,
    /** `/v1/models`, in the router's own order. */
    val models: List<RouterModelStatus> = emptyList(),
    /** `/slots?model=<id>`, for the one model that is resident. */
    val slots: List<RouterSlot> = emptyList(),
    /** The model the [slots] belong to; null when no slots were read. */
    val slotsModel: String? = null,
    /** The loaded instance's context size, when it differs from the model listing's. */
    val slotsContextSize: Int? = null,
    /**
     * Why no slot reading is shown, when none is.
     *
     * This is the third reading the tab has to keep apart, and the reason it exists:
     * [slots] being **empty** is the router saying it holds no slots, while this
     * being **set** is the router never being asked — or not answering. Drawing the
     * first sentence for the second case would turn "we did not look" into "there was
     * nothing to see", which is the confusion every honesty rule in this file is
     * about. It is null on a report that was read, empty or not.
     *
     * See [slotsForReadout]: it is set whenever the `/slots` request was not made or
     * did not answer, and it names which.
     */
    val slotsUnread: String? = null,
) {
    val loaded: List<RouterModelStatus> get() = models.filter { it.isLoaded }
    val loading: List<RouterModelStatus> get() = models.filter { it.isLoading }

    /** The slots the router says are mid-task. */
    val processing: List<RouterSlot> get() = slots.filter { it.isProcessing }

    /** Whether a slot reading was obtained at all, as opposed to withheld. */
    val slotsRead: Boolean get() = slotsUnread == null
}

/**
 * One check, bound to the route it was made for.
 *
 * The binding is what makes a stale answer unshowable: a check made for the
 * previous route is never drawn beside the current one, even for the moment
 * before the next check lands.
 */
data class RouterProbeRecord(
    val provider: String,
    val model: String,
    val result: RouterProbeResult,
)

/**
 * `llm/discoverModels` args for one target.
 *
 * The endpoint is the profile's own, so the probe interrogates exactly the router
 * a real request would be sent to. A `Bearer` credential is passed as `apiKey`
 * because the endpoint may require it; a header in any other scheme is not
 * forwarded, since `apiKey` has no honest mapping for it and a wrong credential
 * would turn "the router is down" into "the router refused us".
 *
 * **The provider is deliberately not named, and that is load-bearing.** The host's
 * handler checks its own shipped pi-ai catalog *first*, by provider id, and answers
 * from it without touching the network
 * (`dsh-llm-pi-ai/lib/index.js:2286-2296`). Probed: with
 * `{"provider":"groq","baseURL":"http://127.0.0.1:55599/v1"}` — a port with nothing
 * listening — the call answers `ok:true` with Groq's cloud catalogue, so a
 * reachability verdict built from it would be confident and false. With no
 * `provider`, `catalogModels(undefined)` finds nothing and the call is
 * unconditionally a live `GET <baseURL>/models` (the same request in the same file
 * sends `GET` and nothing else, so this probe can never load a model). The live
 * local profiles are unaffected: their credential is a literal header in the
 * document, which [LocalProvider.authHeader] carries.
 */
fun discoverModelsArgs(target: RouterTarget): JSONObject {
    return JSONObject()
        .put("settingsNs", target.provider.settingsNs)
        .put("request", probeRequest(target))
}

private fun bearerToken(header: String): String? {
    val trimmed = header.trim()
    if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
    return trimmed.substring(7).trim().ifEmpty { null }
}

/**
 * The wire method the client half of the router-status call is written against.
 *
 * **It does not exist on any host today**, and that is deliberate rather than an
 * oversight: the sweep recorded in this file's header found no client-callable
 * method that can report the router's load or prefill state, and inventing a
 * number was not an option. The name, arguments and reply are specified in
 * `docs/HANDOFF.md` so a small host plugin can supply exactly this; until it does,
 * the host answers `not found`, [isMethodAbsent] recognises it, and the tab says
 * the readout is not offered instead of drawing one.
 *
 * The shape is `llm/discoverModels`' own — `{settingsNs, request:{baseURL, api,
 * apiKey}}` — because that method is the proof the transport works, and a plugin
 * that mirrors it inherits the same probe with the same credential handling. It is
 * a different namespace on purpose: `llm/<method>` belongs to the host's model layer, and
 * this is a router readout a separate plugin can own without patching core.
 */
const val ROUTER_STATUS_METHOD: String = "localRouter/status"

/**
 * `localRouter/status` args for one target — the same request `llm/discoverModels`
 * is given, so the two never disagree about which endpoint is being asked.
 */
fun routerStatusArgs(target: RouterTarget): JSONObject {
    return JSONObject()
        .put("settingsNs", target.provider.settingsNs)
        .put("request", probeRequest(target))
}

/** The shared `{baseURL, api, apiKey}` probe body, built from the profile's own declarations. */
private fun probeRequest(target: RouterTarget): JSONObject {
    val request = JSONObject().put("baseURL", target.provider.baseURL)
    target.provider.api?.let { request.put("api", it) }
    target.provider.authHeader?.let { bearerToken(it) }?.let { request.put("apiKey", it) }
    return request
}

/**
 * Whether a failed call means "this host does not expose that method at all".
 *
 * The RPC carrier answers a method it does not have with `not found` / HTTP 404,
 * which is a fact about the *host's composition* and not about the router — so it
 * is the one failure the caller may treat as "try the older, always-present call
 * instead" rather than as a verdict on the router.
 */
fun isMethodAbsent(code: String, message: String): Boolean =
    message.trim().equals("not found", ignoreCase = true) || code == "http/404"

/** One failed RPC, as the carrier reported it. */
data class RouterCallFailure(val code: String, val message: String)

/** What one `localRouter/status` call came back with. */
sealed interface RouterStatusOutcome {
    /** The host answered with a router report — the readout this file exists for. */
    data class Reported(val load: RouterLoad) : RouterStatusOutcome

    /**
     * The host does not compose the method at all. **The only outcome the caller
     * may fall back from**, because it is a fact about the host's composition and
     * says nothing about the router.
     */
    data object NotOffered : RouterStatusOutcome

    /**
     * The call failed, or answered something that is not a router report.
     *
     * Kept distinct from [NotOffered] deliberately. Folding an unreadable reply into
     * "not offered" would make a host that *has* the plugin but answers it wrongly
     * look exactly like a host that does not have it — the tab would quietly show
     * declarations only, and nobody would learn the plugin was broken.
     */
    data class Refused(val message: String) : RouterStatusOutcome
}

/**
 * Classify one `localRouter/status` attempt.
 *
 * [failure] is null when the call itself succeeded, in which case [value] must be a
 * router report or the outcome is [RouterStatusOutcome.Refused].
 */
fun classifyRouterStatus(value: Any?, failure: RouterCallFailure?): RouterStatusOutcome {
    if (failure != null) {
        return if (isMethodAbsent(failure.code, failure.message)) {
            RouterStatusOutcome.NotOffered
        } else {
            RouterStatusOutcome.Refused(routerFailureText(failure.code, failure.message))
        }
    }
    val load = parseRouterStatus(value)
    return if (load != null) {
        RouterStatusOutcome.Reported(load)
    } else {
        RouterStatusOutcome.Refused(
            "The host answered the router-status call with something that is not a " +
                "router report.",
        )
    }
}

/**
 * The router's load and prefill report, or null when the reply is not one.
 *
 * Null means "this is not a router report" — an empty or unexpected document — and
 * never "the router reported nothing": a report whose `models` array is present but
 * empty parses to a [RouterLoad] with no models, which the tab words as the router
 * listing nothing. The two readings stay distinguishable all the way to the screen
 * because they are different facts.
 *
 * Every field is read defensively. A router that omits `/props` data, or a plugin
 * that forwards only part of it, yields nulls and empty lists rather than failing
 * the whole probe — a partial report is still a measurement.
 */
fun parseRouterStatus(value: Any?): RouterLoad? {
    val root = value as? JSONObject ?: return null
    // A reply must carry at least one field this spec defines. Without that guard any
    // object at all — an error envelope, some future method's payload — would parse to
    // an all-null [RouterLoad] and be drawn as a router that reported nothing, which
    // is precisely the confusion this file spends its comments avoiding.
    if (ROUTER_REPORT_KEYS.none { root.has(it) }) return null
    val router = root.obj("router")
    return RouterLoad(
        role = router?.str("role")?.takeIf { it.isNotEmpty() }
            ?: root.str("role").takeIf { it.isNotEmpty() },
        maxInstances = router?.intOrNull("maxInstances") ?: root.intOrNull("maxInstances"),
        modelsAutoload = router?.boolOrNull("modelsAutoload") ?: root.boolOrNull("modelsAutoload"),
        models = parseModelStatuses(root.arr("models")),
        slots = parseSlots(root.obj("slots")),
        slotsModel = root.obj("slots")?.str("model")?.takeIf { it.isNotEmpty() },
        slotsContextSize = root.obj("slots")?.intOrNull("nCtx"),
        // A plugin that withheld the /slots request can say so, and then the tab says
        // that rather than "the router reported no slots" — the same distinction the
        // terminal readout keeps with the same field.
        slotsUnread = root.obj("slots")?.str("unread")?.takeIf { it.isNotEmpty() },
    )
}

/** The fields a `localRouter/status` reply may carry; at least one must be present. */
private val ROUTER_REPORT_KEYS =
    listOf("role", "maxInstances", "modelsAutoload", "models", "slots", "router")

private fun parseModelStatuses(array: JSONArray?): List<RouterModelStatus> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        val id = entry.str("id")
        if (id.isEmpty()) return@mapNotNull null
        RouterModelStatus(
            id = id,
            status = entry.str("status"),
            contextSize = entry.intOrNull("contextSize"),
            childPort = entry.intOrNull("childPort"),
        )
    }
}

private fun parseSlots(slots: JSONObject?): List<RouterSlot> {
    val entries = slots?.arr("entries") ?: return emptyList()
    return (0 until entries.length()).mapNotNull { index ->
        val entry = entries.optJSONObject(index) ?: return@mapNotNull null
        RouterSlot(
            id = entry.optInt("id", -1),
            isProcessing = entry.optBoolean("isProcessing", false),
            promptTokens = entry.intOrNull("promptTokens"),
            promptTokensProcessed = entry.intOrNull("promptTokensProcessed"),
            promptTokensCache = entry.intOrNull("promptTokensCache"),
            decoded = entry.intOrNull("decoded"),
            remaining = entry.intOrNull("remaining"),
        )
    }
}

/**
 * The failure wording for a probe that did not answer.
 *
 * The host's own message is the honest text and is used verbatim — `could not
 * reach http://127.0.0.1:55599/v1/models` names the endpoint that failed, which no
 * paraphrase improves on. Two shapes get their own wording because the host's is
 * not about the router at all: an empty reply, and a host that does not offer this
 * call to this client (the RPC carrier answers `not found` / HTTP 404 for a method
 * it does not expose).
 */
fun routerFailureText(code: String, message: String): String {
    val text = message.trim()
    if (isMethodAbsent(code, text)) {
        return "This host does not offer the model-discovery call to this client."
    }
    if (text.isEmpty()) return "The host did not answer."
    return text
}

// ------------------------------------------------------------------ JSON bits

private fun JSONObject?.intOrNull(key: String): Int? {
    val value = this ?: return null
    if (!value.has(key) || value.isNull(key)) return null
    return value.optInt(key)
}

private fun JSONObject?.boolOrNull(key: String): Boolean? {
    val value = this ?: return null
    if (!value.has(key) || value.isNull(key)) return null
    return value.optBoolean(key)
}

/** A string array's values, or an empty list when the key is absent or not an array. */
private fun JSONObject?.strings(key: String): List<String> {
    val array = this?.optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).takeIf { it.isNotEmpty() }
    }
}
