package uk.xa0.dsh.model

/**
 * A session a scheduled automation started, as opposed to one a person opened.
 *
 * **The id is the only signal there is**, and that was measured rather than assumed:
 * on the running host, 55 such sessions carried
 *
 *  - `origin` null (never `"automation"` — the field distinguishes `subagent` and
 *    nothing else),
 *  - a `sessionListMetadata` of `{blank, lastPromptAt}` and no automation field,
 *  - membership of a Workspace like any other session, so they appear *inside*
 *    workspace groups and are indistinguishable from a chat someone started.
 *
 * The host does keep an authoritative mapping — `~/.dsh/storages/dsh_automation.json`
 * has a `runs` table with `sessionId`, `automationName` and `status` per run — but no
 * RPC exposes it — the `automations`, `automation`, `runs` and `units` endpoints all
 * answer "not found", as does anything else tried — and a remote client cannot read
 * the host's files. (Written without the `slash-star` form those namespaces are
 * usually printed with: in Kotlin a block comment *nests*, so that sequence inside
 * this one opens a comment the closing marker below no longer closes.) So the prefix the
 * runner puts on the session id is what a client can see, and this is where that
 * knowledge lives rather than being spelled out at each call site.
 *
 * It is a *presentation* rule: nothing is hidden or deleted, the sessions simply get
 * their own group instead of padding out a Workspace's list.
 */
const val AUTOMATION_SESSION_PREFIX: String = "dsh-automation-session"

/** True when [sessionId] names a session an automation started. */
fun isAutomationSessionId(sessionId: String): Boolean =
    sessionId.startsWith(AUTOMATION_SESSION_PREFIX)

/**
 * Splits a roster into the automation runs and everything else, preserving order.
 *
 * Pure so the rule is pinned by a test: a subagent of an automation run is *not*
 * pulled out on its own — it is the same run's work and belongs under the parent
 * wherever the parent is listed. (Its own id is a plain `session-…`, so the prefix
 * test already says so; the guard is here to say why.)
 */
fun <T> partitionAutomations(
    sessions: List<T>,
    idOf: (T) -> String,
): Pair<List<T>, List<T>> {
    val runs = ArrayList<T>()
    val rest = ArrayList<T>()
    for (session in sessions) {
        if (isAutomationSessionId(idOf(session))) runs += session else rest += session
    }
    return runs to rest
}
