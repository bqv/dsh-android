package uk.xa0.dsh.model

/**
 * Where a start-up attempt has got to, as the app understands it.
 *
 * This exists because the words on that screen used to be a *free* string, and the
 * event multiplexer's own socket trace was wired straight into it: what a reader
 * starting the app was actually told was a stream id and a frame type off the
 * wire — `item s2 baseline (#1)` — which is a fact about the protocol and no fact
 * at all about what the app is doing for them. A step is set at the point the work
 * happens instead, so the sentence on screen cannot drift from the work behind it.
 *
 * Every step but [StartupStep.FINDING_HOST] is a plain wait on the host over the
 * network, and the copy says so: a reader waiting on a round trip is owed the fact
 * that the round trip is what they are waiting on, rather than being left to guess
 * at a queue name.
 */
enum class StartupStep {
    /**
     * Working out which of the host's addresses to dial.
     *
     * The one step that is not always a network wait: the resolver answers from a
     * cache within its time to live and probes the candidates in parallel when it
     * does not, so the copy may not claim the host is being waited on here.
     */
    FINDING_HOST,

    /** Asking the host whether the stored sign-in is still good. */
    CHECKING_SIGN_IN,

    /** Posting the stored username and password, because the host asked for them. */
    SIGNING_IN,

    /** Waiting for the session roster, the largest answer of any start-up. */
    READING_SESSIONS,

    /** The handshake again, on a host that had already answered once. */
    RECONNECTING,
}

/**
 * The two sentences the loading screen draws for a [StartupStep].
 *
 * Both are indeterminate statements: there is no denominator behind any of these
 * waits, so nothing here may be turned into a percentage or a bar. The throbber
 * beside them is indeterminate for the same reason.
 */
object StartupCopy {

    /** The line that names the work, in the app's own voice. */
    fun title(step: StartupStep): String = when (step) {
        StartupStep.FINDING_HOST -> "Looking for the host"
        StartupStep.CHECKING_SIGN_IN -> "Checking your sign-in"
        StartupStep.SIGNING_IN -> "Signing in"
        StartupStep.READING_SESSIONS -> "Reading your sessions"
        StartupStep.RECONNECTING -> "Reconnecting"
    }

    /**
     * The line that says what is being waited on.
     *
     * A second sentence rather than a longer first one because the distinction it
     * carries is the one a reader is owed: the host has been asked something and
     * has not answered yet. Local work — reading the stored configuration, opening
     * the socket — is fast enough that it is never worth a step of its own, and is
     * deliberately not dressed up as progress here. The address a route is resolved
     * to is the exception in the other direction: often cached, so its line claims
     * no wait at all.
     */
    fun detail(step: StartupStep): String = when (step) {
        StartupStep.FINDING_HOST -> "Choosing which of the host's addresses to use."
        StartupStep.CHECKING_SIGN_IN -> "Waiting for the host to answer."
        StartupStep.SIGNING_IN -> "Waiting for the host to accept your sign-in."
        StartupStep.READING_SESSIONS -> "Waiting for the host's session list."
        StartupStep.RECONNECTING -> "Waiting for the host to answer again."
    }
}
