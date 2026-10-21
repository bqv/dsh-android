package uk.xa0.dsh.net

/**
 * What a deliberate [DshClient.resetTransport] cleared, in counts rather than contents.
 *
 * This exists because "restart did not help" is otherwise unanswerable after the
 * fact. A restart that cleared nothing and one that cleared four pooled
 * connections look identical from the screen, and the difference is the whole
 * diagnosis. The counts are printed as one logcat line under [DshClient]'s route
 * tag, beside the route decisions they explain — see [summary].
 *
 * Nothing here names a host, a session or an approval: the line is safe to paste
 * into a bug report, and a count is all a reader needs to tell "there was nothing
 * left to clear" from "there were still eight things holding it".
 */
data class TransportReset(
    /** Whether a multiplexer existed to shut down. */
    val muxClosed: Boolean,
    /** Door-fault records the resolver forgot (transport holds and wrong answers). */
    val doorFaultsForgotten: Int,
    /** Whether the resolver had cached a winner for the doors. */
    val cachedWinnerForgotten: Boolean,
    /** Connections the shared pool held before it was evicted. */
    val pooledConnections: Int,
    /** Calls the shared dispatcher held (running plus queued) before it was cancelled. */
    val callsCancelled: Int,
    /** The client's own coroutines that were live and were cancelled. */
    val routeJobsCancelled: Int,
) {

    /**
     * The one logcat line; no field is a secret and every field is a count.
     *
     * The prefix is the grep: `adb logcat -s DshRoute` shows this beside the
     * route decisions, so "the restart cleared nothing" and "the restart cleared
     * a stale pool" are different lines rather than the same silence.
     */
    fun summary(): String = buildString {
        append("transport reset: mux=")
        append(if (muxClosed) 1 else 0)
        append(" doorFaults=")
        append(doorFaultsForgotten)
        append(" cachedWinner=")
        append(if (cachedWinnerForgotten) 1 else 0)
        append(" pooledConnections=")
        append(pooledConnections)
        append(" calls=")
        append(callsCancelled)
        append(" routeJobs=")
        append(routeJobsCancelled)
    }
}

/**
 * What [EndpointResolver.restart] forgot: the resolver's half of [TransportReset].
 *
 * Returned rather than inspected separately so the reset and its report are one
 * action — a caller cannot clear the doors and then guess how much it cleared.
 */
data class DoorReset(
    val faultsForgotten: Int,
    val winnerForgotten: Boolean,
)
