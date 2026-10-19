package uk.xa0.dsh.model

/**
 * What the About page's "Restart connection" action is doing, or what it last did.
 *
 * A restart is a transport rebuild, not a shutdown: the process, the cookie jar,
 * the configuration, drafts and the attention centre's pending approvals all stay.
 * The state therefore distinguishes three things the reader must be able to tell
 * apart — a restart in progress, one that finished (and which door it landed on),
 * and one that failed with a reason — because this app's recurring defect is doing
 * something deliberate and saying nothing.
 */
sealed interface ConnectionRestart {

    /** No restart has been run since this process started. */
    data object Idle : ConnectionRestart

    /** A restart is under way; the socket is down and the doors are being probed. */
    data object Running : ConnectionRestart

    /**
     * The restart finished and the app is connected again.
     *
     * [lan] is the route the fresh dial landed on, so the sentence can name the
     * door rather than leaving the reader to infer it from a row further up.
     */
    data class Succeeded(val lan: Boolean) : ConnectionRestart

    /**
     * The restart did not get the app connected again.
     *
     * [reason] is the app's own failure wording — the transport's message or the
     * host's refusal — never a paraphrase.
     */
    data class Failed(val reason: String) : ConnectionRestart
}

/** The action that tears the transport down and builds it again. */
const val CONNECTION_RESTART_ACTION: String = "Restart connection"

/** The action's own busy word; a restart probes two doors and re-authenticates. */
const val CONNECTION_RESTART_RUNNING: String = "Restarting the connection…"

/**
 * The line under the action while it runs. Deliberately a different sentence from
 * [CONNECTION_RESTART_RUNNING]: the label says what is happening, this says what it
 * is waiting on, and two identical strings on one page read as a duplicated row.
 */
const val CONNECTION_RESTART_BUSY_NOTE: String =
    "Both doors are being asked, then the event socket is reopened. This takes a " +
        "moment."

/**
 * What the action does, and what it deliberately keeps. Stated beside the action
 * rather than hidden in a confirmation: it is recoverable and cheap, and the point
 * of the sentence is to separate it from the Close control, which does not touch
 * the transport at all.
 */
const val CONNECTION_RESTART_NOTE: String =
    "Closes and reopens the event socket and asks both doors again, without " +
        "restarting the app. The cookie jar, your drafts and anything waiting for " +
        "you are kept."

/** Said only after a successful rebuild, and only in the terms of the door used. */
const val CONNECTION_RESTART_DONE_LAN: String =
    "Connection restarted. Traffic is using the LAN door."

const val CONNECTION_RESTART_DONE_PUBLIC: String =
    "Connection restarted. Traffic is using the public door."

/**
 * Said after a rebuild that could not connect. [reason] is the app's own wording
 * for the failure, so this sentence never has to guess what went wrong. It makes
 * no promise about a retry: the connect path schedules its own backoff, and
 * whether one is running is not this record's fact to assert.
 */
fun connectionRestartFailure(reason: String): String =
    "The connection was not restarted: $reason."

/**
 * The line About draws for [state], or null when it draws none.
 *
 * Null for [ConnectionRestart.Idle] because the standing explainer is drawn in
 * that case, and a second sentence saying nothing happened would be noise. Null is
 * not an outcome here: Running, Succeeded and Failed all answer.
 */
fun connectionRestartNote(state: ConnectionRestart): String? = when (state) {
    ConnectionRestart.Idle -> null
    ConnectionRestart.Running -> CONNECTION_RESTART_BUSY_NOTE
    is ConnectionRestart.Succeeded ->
        if (state.lan) CONNECTION_RESTART_DONE_LAN else CONNECTION_RESTART_DONE_PUBLIC
    is ConnectionRestart.Failed -> connectionRestartFailure(state.reason)
}
