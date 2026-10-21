package uk.xa0.dsh.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The one line a transport reset leaves behind.
 *
 * The reset itself needs a socket, a pool and a host, so what is pinned here is
 * the part a later "the restart did not help" is diagnosed from: the line names
 * every kind of state the reset is supposed to clear, prints counts rather than
 * contents, and cannot be mistaken for the route decisions around it.
 *
 * It cannot prove that [DshClient.resetTransport] counted correctly — that is
 * `DshClientResetTest`'s job for the halves a JVM can reach, and the resolver's
 * half is `EndpointResolverTest`'s. What it can prove is that a reset which
 * cleared nothing reads differently from one that cleared a stale pool.
 */
class TransportResetTest {

    @Test
    fun `an empty reset names every kind of state it looked at, with zeroes`() {
        val summary = TransportReset(
            muxClosed = false,
            doorFaultsForgotten = 0,
            cachedWinnerForgotten = false,
            pooledConnections = 0,
            callsCancelled = 0,
            routeJobsCancelled = 0,
        ).summary()

        assertEquals(
            "transport reset: mux=0 doorFaults=0 cachedWinner=0 pooledConnections=0 calls=0 routeJobs=0",
            summary,
        )
    }

    @Test
    fun `a reset that cleared something says how much, as counts`() {
        val summary = TransportReset(
            muxClosed = true,
            doorFaultsForgotten = 2,
            cachedWinnerForgotten = true,
            pooledConnections = 3,
            callsCancelled = 1,
            routeJobsCancelled = 4,
        ).summary()

        assertTrue(summary.startsWith("transport reset: "))
        assertTrue(summary.contains("mux=1"))
        assertTrue(summary.contains("doorFaults=2"))
        assertTrue(summary.contains("cachedWinner=1"))
        assertTrue(summary.contains("pooledConnections=3"))
        assertTrue(summary.contains("calls=1"))
        assertTrue(summary.contains("routeJobs=4"))
    }

    @Test
    fun `the line carries no host, session or approval`() {
        // The counts are the whole message on purpose: a restart's log line has to
        // be safe to paste, and it is read by someone who wants to know whether
        // anything was cleared rather than what was cleared.
        val summary = TransportReset(
            muxClosed = true,
            doorFaultsForgotten = 1,
            cachedWinnerForgotten = false,
            pooledConnections = 1,
            callsCancelled = 0,
            routeJobsCancelled = 0,
        ).summary()

        assertFalse("a host or address leaked: $summary", summary.contains("http"))
        assertFalse("an identity leaked: $summary", summary.contains("@"))
        assertFalse("a session id leaked: $summary", summary.contains("session"))
    }
}
