package uk.xa0.dsh.net

/**
 * Which connectivity callbacks are worth a route re-probe.
 *
 * A [android.net.ConnectivityManager.NetworkCallback] reports far more than a
 * route change. Capabilities are re-published for signal strength and bandwidth,
 * and the link's properties for every minor update, so treating each callback as
 * news would make the listener behave like a timer — probing on a cadence the
 * system chose — which is the one thing this app's route handling must not do.
 *
 * What actually matters is the *edge*: the default network becoming one that can
 * carry the route. Two facts say that, and they arrive in either order depending
 * on how the network comes up:
 *
 * - **Usable** — the network has internet and the system has validated it. This
 *   is what a returning home Wi-Fi reports a moment after it is up, and it is
 *   the event the old two-callback version never saw: the first probe could
 *   land before the network was routable, fail, and no later callback ever
 *   asked again.
 * - **Addressed** — the interface has an IPv4 address. A network with a DSH host
 *   on the wire but no internet is never validated, so this is the only sign it
 *   can carry the route at all.
 *
 * Both are edges rather than levels so a callback that repeats the same facts
 * costs nothing. The gate holds no Android types, which is what makes the edge
 * rule testable on the JVM; [DshClient]'s callback reduces Android's answers to
 * the booleans this takes.
 */
class ConnectivityGate {

    private var wasUsable = false
    private var wasAddressed = false

    /**
     * The default network's capability set, reduced to the one fact that matters.
     * True only on the observation that makes it usable.
     */
    fun capabilities(usable: Boolean): Boolean {
        val edge = usable && !wasUsable
        wasUsable = usable
        return edge
    }

    /** Whether the default network has an IPv4 address yet; true only on the edge. */
    fun addressed(addressed: Boolean): Boolean {
        val edge = addressed && !wasAddressed
        wasAddressed = addressed
        return edge
    }

    /**
     * The default network went away. The next one to appear is a fresh edge
     * whatever its facts, because it is a different wire.
     */
    fun lost() {
        wasUsable = false
        wasAddressed = false
    }
}
