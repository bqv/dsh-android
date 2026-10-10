package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject

/**
 * The router's own load and prefill report, read **through a terminal the app opens
 * on the host** — the one route to this state that needs no plugin, no tunnel, no
 * listener and no adb.
 *
 * ## Why a terminal, and why it is the whole answer
 *
 * The host runs the terminal; the phone drives it over the same Remote connection
 * every other call in this app rides. So a shell command's output is a host-side
 * measurement that arrives over a channel that is already authenticated and already
 * encrypted, and there is no second channel to authenticate, expose or explain.
 *
 * The routers answer, **on the host's loopback only**:
 *
 *  * `GET <router>/props` → `{"role":"router","max_instances":1,"models_autoload":true,…}`
 *  * `GET <router>/v1/models` → every GGUF in the scanned directory, each with
 *    `status.value` ∈ `loaded`/`loading`/`unloaded` and, on a resident entry, the
 *    child instance's launch `--ctx-size` and `--port`.
 *  * `GET <router>/slots?model=<id>` → per-slot `is_processing`, `n_prompt_tokens`,
 *    `n_prompt_tokens_processed`, `n_prompt_tokens_cache`, `n_ctx` and
 *    `next_token[0].n_decoded`.
 *
 * Probed from a terminal created in a throwaway session on 2026-10-10 (see
 * `docs/HANDOFF.md`): `/props` → `HTTP 200 … role=router max_instances=1
 * autoload=true`; `/v1/models` → 11 entries, 0 resident on `:55555` and 6 entries,
 * 1 resident (`Qwen3-Embedding-0.6B-Q8_0:loaded`) on `:55556`; and on that resident
 * model `/slots?model=Qwen3-Embedding-0.6B-Q8_0` → `is_processing=false
 * n_prompt_tokens=36 processed=0 cache=0 n_ctx=4096 decoded=0`.
 *
 * ## The one safety rule this file exists to keep
 *
 * **`/slots` is asked for only when the router's own listing names exactly one
 * resident model.** `/props` reports `models_autoload: true`, and a previous sweep
 * saw a `/slots?model=<unloaded>` request fail to answer within eight seconds. So
 * the decision is made *here*, from a parsed `/v1/models` reply, and never in shell:
 * [residentModelForSlots] is the single gate, and [RouterLoad.slotsUnread] carries
 * the reason whenever it says no.
 *
 * ## How the stream is framed
 *
 * The terminal is a real PTY with a real interactive bash in it, so the stream
 * carries the shell's echo, its prompt and its ANSI. Two measures make the framing
 * reliable:
 *
 *  1. **Echo is turned off and the prompt is silenced** by the sync line
 *     ([routerReadoutSyncLine]), whose own sentinel is built with `printf '…%s…'`
 *     so the *typed* text can never contain the *resolved* marker — which is what
 *     makes it survive the moment before echo goes off.
 *  2. **Every marker is required to occur exactly once** ([parseReadoutChunk]). A
 *     duplicated marker means the terminal echoed the line, and the honest answer
 *     is then "this read could not be trusted" rather than a number picked out of
 *     an ambiguous stream.
 */

/**
 * The reserved terminal id the readout is created under.
 *
 * One id, not a random one, so the readout is *found again* on the next visit
 * instead of leaving a shell behind each time. It is also the id the Shell tab
 * filters out — see [isRouterReadoutTerminal] — so the readout never appears in the
 * terminal picker and is never adopted as the user's shell.
 */
const val ROUTER_READOUT_TERMINAL_ID: String = "dsh-router-readout"

/** The title the host stores for it; the panel would show this if it ever listed it. */
const val ROUTER_READOUT_TITLE: String = "Router readout"

/**
 * Whether a host terminal is this app's router readout rather than a shell the user
 * opened.
 *
 * The Shell tab lists every terminal a session retains and adopts the first running
 * one, so without this filter a hidden readout could take the seat the user's own
 * shell should have. The reserved id is the whole test: the app never creates a
 * user-visible terminal under it.
 */
fun isRouterReadoutTerminal(id: String): Boolean = id == ROUTER_READOUT_TERMINAL_ID

/** The markers the readout's shell lines print. Plain text, delimiter-shaped. */
object RouterReadoutMarkers {
    const val PROPS_BEGIN = "<<<DSH_P>>>"
    const val PROPS_HTTP = "<<<DSH_PH="
    const val MODELS_BEGIN = "<<<DSH_M>>>"
    const val MODELS_HTTP = "<<<DSH_MH="
    const val READ_END = "<<<DSH_END>>>"
    const val SLOTS_BEGIN = "<<<DSH_S>>>"
    const val SLOTS_HTTP = "<<<DSH_SH="
    const val SLOTS_END = "<<<DSH_SEND>>>"
}

/** The HTTP status curl appends when it could not connect at all. */
const val ROUTER_READOUT_NO_HTTP: Int = 0

/** Per-read ceiling. A read that outlives this is reported as unread, never awaited forever. */
const val ROUTER_READOUT_TIMEOUT_MS: Long = 8_000

/**
 * The line that takes the PTY's echo and the interactive prompt out of the stream.
 *
 * `stty -echo` is the load-bearing part: without it the shell echoes every line this
 * client types, and the echo contains the same markers the command prints, so the
 * reader would have to guess which occurrence was real. Silencing the prompt matters
 * for latency as much as for framing — this box's interactive bash runs liquidprompt,
 * whose git/load/temperature segments cost ~200–700 ms **per prompt**, and that cost
 * lands between one read and the next.
 *
 * [nonce] makes the sentinel unique to *this* attachment, so a `snapshot` replaying
 * the previous screen cannot satisfy the wait. The sentinel is assembled by `printf`
 * so the typed text holds `%s` placeholders where the output holds the resolved
 * marker: echo is still on when this line runs, and an echoed `<<<DSH_SY%sNC-x>>>`
 * must not be mistaken for the answer.
 */
fun routerReadoutSyncLine(nonce: String): String =
    "stty -echo; PROMPT_COMMAND=(); PS1=''; printf '\\n<<<DSH_SY%sNC-%s>>>\\n' '' '$nonce'\n"

/** The sentinel [routerReadoutSyncLine] prints, resolved. */
fun routerReadoutSyncMarker(nonce: String): String = "<<<DSH_SYNC-$nonce>>>"

/**
 * The one line that reads the router's identity and its model listing.
 *
 * One line, and therefore one `terminal/write` — [uk.xa0.dsh.net.RouterReadoutTransport]
 * makes exactly one write call for it — because each write costs a PTY round trip and
 * the two requests share it. `-m 5` bounds each request, and `-w` reports curl's own
 * status so a dead port is reported as HTTP 000 rather than as an empty body.
 *
 * **Each URL is one single-quoted shell word with its suffix inside the quotes.**
 * Quoting the root and appending `/props` outside them would also be *correct* shell —
 * the suffixes are fixed literals with no metacharacter, so nothing there is
 * exploitable — but it is a second rule to remember, and the `/slots` builder quotes
 * the whole URL. One rule for every URL in this file: build the string, then quote it.
 * Nothing is ever concatenated into a command after it has been quoted.
 */
fun routerReadoutReadLine(root: String): String = listOf(
    "printf '\\n${RouterReadoutMarkers.PROPS_BEGIN}\\n'",
    "curl -s -m 5 -w '\\n${RouterReadoutMarkers.PROPS_HTTP}%{http_code}>>>\\n' ${shellQuote("$root/props")}",
    "printf '\\n${RouterReadoutMarkers.MODELS_BEGIN}\\n'",
    "curl -s -m 5 -w '\\n${RouterReadoutMarkers.MODELS_HTTP}%{http_code}>>>\\n' ${shellQuote("$root/v1/models")}",
    "printf '\\n${RouterReadoutMarkers.READ_END}\\n'",
).joinToString("; ") + "\n"

/**
 * The line that reads `/slots` for **one already-resident** model.
 *
 * The caller has already established residence from a parsed `/v1/models` — see
 * [residentModelForSlots]. This function will ask for whatever it is given, so it
 * must not be called with a model the router has not reported as resident.
 */
fun routerReadoutSlotsLine(root: String, modelId: String): String {
    val base = shellQuote(root)
    val url = shellQuote("$root/slots?model=${percentEncodeQuery(modelId)}")
    return listOf(
        "printf '\\n${RouterReadoutMarkers.SLOTS_BEGIN}\\n'",
        "curl -s -m 5 -w '\\n${RouterReadoutMarkers.SLOTS_HTTP}%{http_code}>>>\\n' $url",
        "printf '\\n${RouterReadoutMarkers.SLOTS_END}\\n'",
    ).joinToString("; ") + "\n"
}

/**
 * The router's root URL, from the provider profile's `baseURL`.
 *
 * The profile names an OpenAI-compatible base (`http://127.0.0.1:55555/v1`), while
 * `/props` and `/slots` sit at the router's own root. Derived rather than assumed:
 * the scheme and authority are kept and every path segment is dropped, so a profile
 * written with or without the `/v1` suffix and with or without a trailing slash all
 * resolve to the same root. Null when the string has no authority at all, because a
 * command cannot be built from it.
 */
fun routerRootOf(baseURL: String): String? {
    val value = baseURL.trim()
    if (value.isEmpty()) return null
    val schemeEnd = value.indexOf("://")
    val scheme = if (schemeEnd >= 0) value.substring(0, schemeEnd + 3) else "http://"
    val afterScheme = if (schemeEnd >= 0) value.substring(schemeEnd + 3) else value
    val authority = afterScheme.substringBefore('/').substringBefore('?').substringBefore('#')
    if (authority.isEmpty()) return null
    return scheme + authority
}

/**
 * The model whose `/slots` may be read, or null when none may.
 *
 * **Exactly one resident model, or nothing.** `loading` counts as resident — the
 * weights are on their way and the router has already committed — but two residents
 * is not a state to guess about, and zero means the autoload trap applies and the
 * request must not be made at all.
 */
fun residentModelForSlots(models: List<RouterModelStatus>): String? =
    models.filter { it.isResident }.singleOrNull()?.id

/** What one read of `/props` + `/v1/models` produced, before any interpretation. */
data class RouterReadoutChunk(
    val propsBody: String,
    val propsHttp: Int?,
    val modelsBody: String,
    val modelsHttp: Int?,
)

/**
 * The chunk delimited by [RouterReadoutMarkers], or null when the stream is not
 * exactly one framed answer.
 *
 * **Every marker must occur exactly once.** That is what makes an echoed line — or a
 * stream that replayed a previous screen — a failed read rather than a silently
 * mis-framed one, and it is the property the sync line exists to establish.
 */
fun parseReadoutChunk(text: String): RouterReadoutChunk? {
    val markers = listOf(
        RouterReadoutMarkers.PROPS_BEGIN,
        RouterReadoutMarkers.PROPS_HTTP,
        RouterReadoutMarkers.MODELS_BEGIN,
        RouterReadoutMarkers.MODELS_HTTP,
        RouterReadoutMarkers.READ_END,
    )
    val at = markers.map { marker ->
        if (countOccurrences(text, marker) != 1) return null
        text.indexOf(marker)
    }
    if (at != at.sorted()) return null
    val propsBody = text.substring(at[0] + markers[0].length, at[1]).stripTerminalNoise()
    val propsHttp = httpAfter(text, at[1], markers[1]) ?: return null
    val modelsBody = text.substring(at[2] + markers[2].length, at[3]).stripTerminalNoise()
    val modelsHttp = httpAfter(text, at[3], markers[3]) ?: return null
    return RouterReadoutChunk(propsBody, propsHttp, modelsBody, modelsHttp)
}

/** The same, for the `/slots` line. Null when the framing or the status is unreadable. */
fun parseSlotsChunk(text: String): Pair<String, Int?>? {
    val markers = listOf(
        RouterReadoutMarkers.SLOTS_BEGIN,
        RouterReadoutMarkers.SLOTS_HTTP,
        RouterReadoutMarkers.SLOTS_END,
    )
    val at = markers.map { marker ->
        if (countOccurrences(text, marker) != 1) return null
        text.indexOf(marker)
    }
    if (at != at.sorted()) return null
    val body = text.substring(at[0] + markers[0].length, at[1]).stripTerminalNoise()
    val http = httpAfter(text, at[1], markers[1]) ?: return null
    return body to http
}

/**
 * The whole reading, from the framed chunks to the tab's own verdict type.
 *
 * The rules, in the order they bind:
 *
 *  * **A framing failure is not a router failure.** [chunk] null means the terminal
 *    stream could not be read, and the sentence says that rather than blaming the
 *    router — those are different problems and only one of them is the router's.
 *  * **The host's HTTP status is authoritative.** A non-200 from either endpoint
 *    names the endpoint and the status; it is never turned into "no models".
 *  * **A body that does not parse is reported as unreadable**, not as a router with
 *    nothing loaded.
 *  * **`/slots` is only ever shown from a parsed resident report** — see
 *    [residentModelForSlots] and [slotsForReadout].
 */
fun classifyReadout(
    chunk: RouterReadoutChunk?,
    slots: RouterSlotsReadout?,
    atMillis: Long,
): RouterProbeResult {
    if (chunk == null) {
        return RouterProbeResult.Unreachable(
            message = ROUTER_READOUT_FRAME_FAILURE,
            atMillis = atMillis,
        )
    }
    val propsHttp = chunk.propsHttp
    if (propsHttp != 200) {
        return RouterProbeResult.Unreachable(
            message = "The router answered /props with " + httpText(propsHttp) +
                ", so its identity — and the model-autoload flag that makes /slots " +
                "unsafe for an unloaded model — was not read.",
            atMillis = atMillis,
        )
    }
    val modelsHttp = chunk.modelsHttp
    if (modelsHttp != 200) {
        return RouterProbeResult.Unreachable(
            message = "The router answered /v1/models with " + httpText(modelsHttp) +
                " — the model listing is where the load state comes from, so nothing " +
                "about load or prefill was read.",
            atMillis = atMillis,
        )
    }
    val props = runCatching { JSONObject(chunk.propsBody) }.getOrNull()
        ?: return RouterProbeResult.Unreachable(ROUTER_READOUT_PROPS_UNREADABLE, atMillis)
    val listing = runCatching { JSONObject(chunk.modelsBody).getJSONArray("data") }.getOrNull()
        ?: return RouterProbeResult.Unreachable(ROUTER_READOUT_MODELS_UNREADABLE, atMillis)

    val models = modelStatusesOf(listing)
    val slotsState = slotsForReadout(models, slots)
    val load = RouterLoad(
        role = props.str("role").takeIf { it.isNotEmpty() },
        maxInstances = props.intOrNull("max_instances"),
        modelsAutoload = props.boolOrNull("models_autoload"),
        models = models,
        slots = slotsState.entries,
        slotsModel = slotsState.model,
        slotsContextSize = slotsState.contextSize,
        slotsUnread = slotsState.reason,
    )
    return RouterProbeResult.Reachable(
        modelIds = models.map { it.id },
        load = load,
        atMillis = atMillis,
    )
}

/** The slot report, its absence, and the honest reason for it. */
data class RouterSlotsReadout(
    val entries: List<RouterSlot> = emptyList(),
    val model: String? = null,
    val contextSize: Int? = null,
    /** Non-null when no slot reading is shown, and why. */
    val reason: String? = null,
)

/**
 * Decide what the PREFILL section may show.
 *
 * [slots] is the result of the `/slots` read the caller made, and it must have been
 * made only for a model [residentModelForSlots] returned. When the decision was not
 * to ask, this returns the reason, so "not asked" can never be drawn as "reported
 * nothing" — those are the two readings this file's callers spend the most care
 * keeping apart.
 */
fun slotsForReadout(
    models: List<RouterModelStatus>,
    slots: RouterSlotsReadout?,
): RouterSlotsReadout {
    val resident = residentModelForSlots(models)
    if (resident == null) {
        val residents = models.count { it.isResident }
        return RouterSlotsReadout(
            reason = if (models.isEmpty()) {
                "the router listed no models, so there was no resident model to ask about"
            } else if (residents == 0) {
                "no model is loaded, and /slots is only read for a model that already " +
                    "is — asking about an unloaded one is the query this app must not make"
            } else {
                "$residents models are loaded at once, so there is no single model whose " +
                    "slots these would be"
            },
        )
    }
    if (slots == null) {
        return RouterSlotsReadout(
            reason = "the router did not answer /slots for $resident in time",
        )
    }
    if (slots.reason != null) return slots
    return slots.copy(model = slots.model ?: resident)
}

/** The router's model listing from a parsed `data` array. Unreadable entries are skipped. */
fun modelStatusesOf(array: JSONArray): List<RouterModelStatus> =
    (0 until array.length()).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        val id = entry.str("id")
        if (id.isEmpty()) return@mapNotNull null
        val args = entry.obj("status")?.arr("args")
        RouterModelStatus(
            id = id,
            status = entry.obj("status")?.str("value").orEmpty(),
            contextSize = argValue(args, "--ctx-size"),
            childPort = argValue(args, "--port"),
        )
    }

/** The slot entries from a parsed `/slots` body, or null when it is not slot state. */
fun slotsOf(body: String): List<RouterSlot>? {
    val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
    return (0 until array.length()).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        RouterSlot(
            id = entry.optInt("id", -1),
            isProcessing = entry.optBoolean("is_processing", false),
            promptTokens = entry.intOrNull("n_prompt_tokens"),
            promptTokensProcessed = entry.intOrNull("n_prompt_tokens_processed"),
            promptTokensCache = entry.intOrNull("n_prompt_tokens_cache"),
            decoded = entry.arr("next_token")?.optJSONObject(0)?.intOrNull("n_decoded"),
            remaining = entry.arr("next_token")?.optJSONObject(0)?.intOrNull("n_remain"),
        )
    }
}

/** `n_ctx` from the first slot, which is the loaded instance's own window. */
fun slotsContextSizeOf(body: String): Int? =
    runCatching { JSONArray(body).optJSONObject(0)?.intOrNull("n_ctx") }.getOrNull()

// -------------------------------------------------------------- failure wording

const val ROUTER_READOUT_FRAME_FAILURE: String =
    "The router's readout could not be read from the terminal stream: the markers that " +
        "delimit it were missing or repeated. Nothing about load or prefill was measured."

const val ROUTER_READOUT_PROPS_UNREADABLE: String =
    "The router answered /props with something that is not router state, so its identity " +
        "was read but its load report was not."

const val ROUTER_READOUT_MODELS_UNREADABLE: String =
    "The router answered /v1/models with something that is not a model listing, so the " +
        "load state could not be read."

const val ROUTER_READOUT_NO_AGENT: String =
    "This session has no live agent, so the app could not open a terminal on the host to " +
        "read the router. Opening the session's Shell tab or sending a message gives it one."

/** The app has no session open, so there is no session to open a terminal in. */
const val ROUTER_READOUT_NO_SESSION: String =
    "No session is open, so there is no session to open a terminal in."

// ------------------------------------------------------------------------ shell

/**
 * One shell argument, single-quoted.
 *
 * The URL comes from the host's own settings document rather than from the user, but
 * it is still pasted into a shell command: quoting it is the difference between a
 * profile that reads oddly and one that runs something.
 */
internal fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\\''") + "'"

/**
 * A query-value encoding that keeps unreserved characters.
 *
 * `java.net.URLEncoder` is form encoding, which spells a space `+`; in a query value
 * that means a *plus*, not a space. Model ids on this box contain only unreserved
 * characters, but the encoding is written out rather than assumed.
 */
internal fun percentEncodeQuery(value: String): String {
    val unreserved = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.~"
    val out = StringBuilder()
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val char = byte.toInt().toChar()
        if (char in unreserved) {
            out.append(char)
        } else {
            out.append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }
    return out.toString()
}

// ----------------------------------------------------------------------- bits

/**
 * The stream with the PTY's own noise removed: CR from the line discipline and the
 * bracketed-paste toggles bash emits around every command.
 *
 * Applied to a body **between** two markers, so it can never touch framing.
 */
private fun String.stripTerminalNoise(): String =
    replace("\r", "")
        .replace("\u001b[?2004h", "")
        .replace("\u001b[?2004l", "")
        .trim()

/** The numeric status following [marker], or null when it is not a number. */
private fun httpAfter(text: String, markerAt: Int, marker: String): Int? {
    val start = markerAt + marker.length
    val end = text.indexOf(">>>", start)
    if (end < 0) return null
    return text.substring(start, end).trim().toIntOrNull()
}

private fun countOccurrences(text: String, needle: String): Int {
    var count = 0
    var index = text.indexOf(needle)
    while (index >= 0) {
        count++
        index = text.indexOf(needle, index + needle.length)
    }
    return count
}

/** `--ctx-size`'s value from a launch argv. Null when the flag or its value is absent. */
private fun argValue(args: JSONArray?, flag: String): Int? {
    if (args == null) return null
    for (index in 0 until args.length() - 1) {
        if (args.optString(index) == flag) return args.optString(index + 1).toIntOrNull()
    }
    return null
}

private fun httpText(status: Int?): String = when (status) {
    null -> "a status this client could not read"
    ROUTER_READOUT_NO_HTTP -> "no answer at all (curl status 000)"
    else -> "HTTP $status"
}

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
