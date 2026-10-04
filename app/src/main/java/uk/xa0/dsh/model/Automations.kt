package uk.xa0.dsh.model

/**
 * A session a scheduled automation started, as opposed to one a person opened.
 *
 * **The prefix is the plugin's own convention, not a guess.** Automations are not a
 * host feature at all: they come from `@michengai/dsh-automation` (a third-party DSH
 * plugin installed in the web profile), which ships its own **sidebar tab** and
 * Settings panel — that is the "shown separately" the web has, not a grouping in the
 * core client. The same plugin's own client bundle contains the string
 * `dsh-automation-session-`, so it keys on exactly this prefix to find, adopt and
 * delete its runs' sessions.
 *
 * What that leaves for a client that cannot load a plugin's web bundle — which is any
 * native app, since the plugin's methods are not on the public gateway (its namespace
 * answers "not found", as do five spellings tried) — is the prefix itself. Measured on
 * the running host, 55 such sessions carried
 *
 *  - `origin` null (never `"automation"` — the field distinguishes `subagent` and
 *    nothing else),
 *  - a `sessionListMetadata` of `{blank, lastPromptAt}` and no automation field,
 *  - membership of a Workspace like any other session, so they appear *inside*
 *    workspace groups and are indistinguishable from a chat someone started.
 *
 * The plugin does keep an authoritative mapping — its store,
 * `~/.dsh/storages/dsh_automation.json` has a `runs` table with `sessionId`,
 * `automationName` and `status` per run — but that is plugin-side storage behind the
 * plugin bridge, and a native client can neither call it nor read the host's files.
 *
 * So the prefix is what this app can see, and it lives here rather than being spelled
 * out at each call site.
 *
 * It is a *presentation* rule: nothing is hidden or deleted, the sessions simply get
 * their own group instead of padding out a Workspace's list.
 */
const val AUTOMATION_SESSION_PREFIX: String = "dsh-automation-session"

/** True when [sessionId] names a session an automation started. */
fun isAutomationSessionId(sessionId: String): Boolean =
    sessionId.startsWith(AUTOMATION_SESSION_PREFIX)

/**
 * Whether a session that has just stopped is worth an idle notification.
 *
 * Two kinds of session are not, and for the same reason: nobody is waiting on them.
 * A subagent's turn is part of its parent's, whose result arrives in the parent's
 * transcript anyway; an automation run exists precisely to happen unattended, and one
 * that fires hourly would otherwise post twenty-four "Done" notifications a day for
 * work the reader set going and then walked away from.
 *
 * Both still speak up when something is *stuck* — a question or an approval stalls them
 * where nothing else can show it — and an error or a terminal bell is an event rather
 * than a status. This rules out the status, not the session.
 *
 * A subagent of an automation run is covered twice over: its own id is a plain
 * `session-…`, and it is a subagent.
 */
fun warrantsIdleAlert(sessionId: String, subagent: Boolean): Boolean =
    !subagent && !isAutomationSessionId(sessionId)

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
