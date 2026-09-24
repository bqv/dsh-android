package uk.xa0.dsh.model

import org.json.JSONObject

/**
 * `session/search` — content search across the workspace's visible sessions.
 *
 * The wire facts this file encodes, all read off the host:
 *
 *  - the argument is wrapped as `request`, **not** `_request` (which is what
 *    `session/list` takes) — `packages/api/session-controller/lib/typert.host.js`
 *    binds this endpoint's one parameter with `wire: 'request'`, and both the
 *    request and the value are strict zod objects, so a wrong or extra key is a
 *    `gateway/bad-request` rather than something quietly ignored;
 *  - the query is literal data, never FTS syntax. The provider wraps the whole
 *    string as one quoted phrase (`quoteFtsData`), so `*`, `OR`, `NEAR`, quotes
 *    and the like carry no operator meaning;
 *  - the host rejects a query that is empty after trimming, longer than
 *    [SessionSearch.QUERY_MAX_CODE_UNITS] UTF-16 code units, or carrying NUL
 *    (`list.ts` `normalizeSearchQuery`). The web already folds all three
 *    client-side (`sanitizeSearchQuery`), and [SessionSearch.normalizeQuery] is
 *    that fold ported;
 *  - there is one page and no cursor. The host pages the provider internally and
 *    answers with at most [SessionSearch.RESULT_LIMIT] items plus `hasMore`;
 *    "narrow the query" is the only continuation the protocol offers;
 *  - it matches message *content* only — `user/message` and `assistant/message`,
 *    surface `current` — across sessions visible to the caller;
 *  - a result item is `{sessionId, snippet}` and nothing else. The host groups a
 *    session's matching events into its single strongest match, so one item is
 *    one session — and with no seq or message id on the wire, a hit can address
 *    a session but never a position inside its transcript;
 *  - the snippet is already plain text: the provider's `makeSnippet` strips its
 *    FTS highlight markers, collapses whitespace, and bounds the excerpt (240
 *    code points host-side) with `…` ellipses. A renderer that wants emphasis
 *    has to find the query in the text again — emphasis is not on the wire.
 */
data class SessionSearchHit(
    val sessionId: String,
    val snippet: String,
)

/** The `session/search` value: one bounded page plus the narrower-query hint. */
data class SessionSearchResults(
    val items: List<SessionSearchHit>,
    val hasMore: Boolean,
)

/** Request building for `session/search`. */
object SessionSearch {
    /** `SESSION_SEARCH_RESULT_LIMIT`; also the `{n}` in the web's hasMore copy. */
    const val RESULT_LIMIT = 20

    /** `SESSION_SEARCH_QUERY_MAX_CHARS`, measured in UTF-16 code units. */
    const val QUERY_MAX_CODE_UNITS = 500

    /**
     * Build the RPC args. The key is `request`: `session/list` takes `_request`,
     * and copying that spelling here is rejected as a bad request, not ignored.
     */
    fun args(query: String): JSONObject =
        JSONObject().put("request", JSONObject().put("query", normalizeQuery(query)))

    /**
     * Fold a draft query into the wire contract the way the web's
     * `sanitizeSearchQuery` does: drop NUL, cap at [QUERY_MAX_CODE_UNITS], and
     * drop the final unit rather than split a surrogate pair in half.
     */
    fun normalizeQuery(value: String): String {
        val clean = value.replace("\u0000", "")
        if (clean.length <= QUERY_MAX_CODE_UNITS) return clean
        var end = QUERY_MAX_CODE_UNITS
        if (clean[end - 1].isHighSurrogate() && clean[end].isLowSurrogate()) end--
        return clean.substring(0, end)
    }

    /**
     * True when the host would accept the query. A blank (or all-whitespace)
     * query is rejected with `gateway/bad-request`, so the caller must not send
     * one — the web short-circuits to its idle state instead.
     */
    fun isSearchable(query: String): Boolean = query.trim().isNotEmpty()
}

/**
 * Parse the `session/search` value.
 *
 * Tolerant on purpose: a missing `items`/`hasMore` reads as empty/false, an item
 * without a session id is skipped, and unknown keys are ignored. Only the value
 * the host sends is expected — this is not a general JSON reader. Session ids
 * the caller's roster does not know are kept: the web drops them, but dropping
 * needs the roster, which lives on the caller's side.
 *
 * Note for the caller: the host can also answer the whole call with a structured
 * error instead of a value, most often `gateway/internal` ("session search is
 * unavailable: this deployment does not mount @deepseek-ai/dsh-session-query")
 * on a deployment without the provider. That arrives as a `DshRpcException`, so
 * it never reaches this parser.
 */
fun parseSessionSearchResults(value: JSONObject): SessionSearchResults {
    val items = value.optJSONArray("items")
    val parsed = ArrayList<SessionSearchHit>(items?.length() ?: 0)
    if (items != null) {
        for (i in 0 until items.length()) {
            val item = items.optJSONObject(i) ?: continue
            val sessionId = item.str("sessionId")
            // A row with no address cannot be opened, so it is not a hit.
            if (sessionId.isEmpty()) continue
            parsed += SessionSearchHit(sessionId, item.str("snippet"))
        }
    }
    return SessionSearchResults(parsed, value.bool("hasMore"))
}
