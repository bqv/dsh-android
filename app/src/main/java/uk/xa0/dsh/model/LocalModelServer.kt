package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * The llama.cpp server behind a session's local route: what the host *declares*
 * about it, and the one *live* fact about it this client can obtain.
 *
 * ## What is reachable, and what is not (probed on host 0.2.0-rc.2)
 *
 * The box runs two llama.cpp routers on the host's loopback — `llama` on
 * `127.0.0.1:55555` and `llamb` on `127.0.0.1:55556`. Both answer their own
 * endpoints on the host (`GET /health` → `{"status":"ok"}`, `GET /v1/models` →
 * every GGUF in the scanned directory, each with a `status.value` of `loaded` /
 * `loading` / `unloaded`).
 *
 * **None of that is reachable from this client.** The phone reaches the host
 * through `adb reverse tcp:8081 tcp:8081` and one URL, and that host serves the
 * RPC surface at `/api/<method>` and nothing else: `GET /health`, `/v1/models`,
 * `/props`, `/slots` and `/metrics` all answer `404` on `:8081`, nginx fronts the
 * gate with a single `location /` → `proxy_pass http://127.0.0.1:8080`, and there
 * is no `/api` passthrough. So the router's own health, slots, memory and
 * throughput are **not available here**, and this file never pretends otherwise.
 *
 * ## The two sources this file does use
 *
 *  * **The declarations.** `settings/describe` carries the whole settings
 *    document, including the `llm-pi-ai` namespace whose `providers` map holds
 *    one profile per route: `displayName`, `api`, `baseURL`, per-model
 *    `contextWindow` / `maxTokens` / `input`, and the provider's
 *    `defaultContextWindow` / `defaultInput` / `headers`. Those are the host's own
 *    statements about the server it will talk to, so they are what the tab shows
 *    when nothing has been measured.
 *
 *  * **One measurement the host performs on this client's behalf.**
 *    `llm/discoverModels(settingsNs, {baseURL, api, apiKey})` makes the host open
 *    the endpoint and read its model list with a single `GET <baseURL>/models`. It
 *    is a genuine live probe, not a lookup: against a port with nothing listening it
 *    answers `llm/model-discovery-rejected` / `could not reach http://127.0.0.1:55599/v1/models`,
 *    and against the live router it answers the twelve model ids the router
 *    advertises. That is a real fact about the running server — *whether the host
 *    can reach it, and what it advertises right now* — and it is the only one. The
 *    provider is deliberately left unnamed in the request; [discoverModelsArgs]
 *    records the probed trap that makes that necessary.
 *
 * So the tab describes the server from declarations, plus a reachability check the
 * reader can repeat, and says out loud that the internals are not obtainable here.
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

/** The server behind the route the session will run, when that route is local. */
data class LocalServerTarget(
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

    /** Whether the profile declares any credential at all. */
    val authenticated: Boolean get() = authHeader != null || headerNames.isNotEmpty()
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
 * The server the session's route runs on, or null when there is nothing local
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
 * survives: the session holds it, so the tab names the server it would run on,
 * exactly as the trigger names the route.
 */
fun localServerTargetOf(
    choice: ModelChoice,
    providers: Map<String, LocalProvider>,
): LocalServerTarget? {
    val ref = choice.ref ?: return null
    val provider = providers[ref.provider] ?: return null
    if (!isLoopbackEndpoint(provider.baseURL)) return null
    return LocalServerTarget(provider = provider, modelId = ref.model, model = provider.models[ref.model])
}

/**
 * Whether an endpoint names this very host.
 *
 * Loopback only, and deliberately narrow: `127.0.0.0/8` in full (not just
 * `127.0.0.1`), `localhost`, and IPv6 `::1` with or without the brackets a URL
 * uses. A cloud or LAN endpoint is not local even though it may be reachable —
 * the tab's claim is about a server on the host, so that is what it may say.
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
sealed interface LocalServerProbeResult {
    /** Nothing checked yet for this route. */
    data object Idle : LocalServerProbeResult

    data object Checking : LocalServerProbeResult

    /**
     * The host reached the endpoint and read its model list. The ids are the
     * endpoint's own, in its own order — nothing here is inferred or padded.
     */
    data class Reachable(val modelIds: List<String>, val atMillis: Long) : LocalServerProbeResult {
        fun advertises(modelId: String): Boolean = modelIds.any { it == modelId }
    }

    /** The host could not reach it, in the host's own words. */
    data class Unreachable(val message: String, val atMillis: Long) : LocalServerProbeResult
}

/**
 * One check, bound to the route it was made for.
 *
 * The binding is what makes a stale answer unshowable: a check made for the
 * previous route is never drawn beside the current one, even for the moment
 * before the next check lands.
 */
data class LocalProbeRecord(
    val provider: String,
    val model: String,
    val result: LocalServerProbeResult,
)

/**
 * `llm/discoverModels` args for one target.
 *
 * The endpoint is the profile's own, so the probe interrogates exactly the server
 * a real request would be sent to. A `Bearer` credential is passed as `apiKey`
 * because the endpoint may require it; a header in any other scheme is not
 * forwarded, since `apiKey` has no honest mapping for it and a wrong credential
 * would turn "the server is down" into "the server refused us".
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
fun discoverModelsArgs(target: LocalServerTarget): JSONObject {
    val request = JSONObject()
        .put("baseURL", target.provider.baseURL)
    target.provider.api?.let { request.put("api", it) }
    target.provider.authHeader?.let { bearerToken(it) }?.let { request.put("apiKey", it) }
    return JSONObject()
        .put("settingsNs", target.provider.settingsNs)
        .put("request", request)
}

private fun bearerToken(header: String): String? {
    val trimmed = header.trim()
    if (!trimmed.startsWith("Bearer ", ignoreCase = true)) return null
    return trimmed.substring(7).trim().ifEmpty { null }
}

/**
 * The failure wording for a probe that did not answer.
 *
 * The host's own message is the honest text and is used verbatim — `could not
 * reach http://127.0.0.1:55599/v1/models` names the endpoint that failed, which no
 * paraphrase improves on. Two shapes get their own wording because the host's is
 * not about the server at all: an empty reply, and a host that does not offer this
 * call to this client (the RPC carrier answers `not found` / HTTP 404 for a method
 * it does not expose).
 */
fun localServerFailureText(code: String, message: String): String {
    val text = message.trim()
    if (text.equals("not found", ignoreCase = true) || code == "http/404") {
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

/** A string array's values, or an empty list when the key is absent or not an array. */
private fun JSONObject?.strings(key: String): List<String> {
    val array = this?.optJSONArray(key) ?: return emptyList()
    return (0 until array.length()).mapNotNull { index ->
        array.optString(index).takeIf { it.isNotEmpty() }
    }
}
