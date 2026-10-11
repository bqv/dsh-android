package uk.xa0.dsh.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The edge rule that keeps a network callback from behaving like a timer.
 *
 * Testable on the JVM because the decision holds no Android types — that is the
 * whole reason it lives outside the callback. What it cannot prove is the other
 * half: that [DshClient] maps Android's capabilities and link properties onto the
 * right booleans, since those types cannot be constructed here.
 */
class ConnectivityGateTest {

    @Test
    fun `a network becoming usable is news, and staying usable is not`() {
        val gate = ConnectivityGate()
        assertTrue("the first usable report is the edge", gate.capabilities(usable = true))
        assertFalse("the same fact again is not", gate.capabilities(usable = true))
    }

    @Test
    fun `usability lost and regained is news twice`() {
        val gate = ConnectivityGate()
        assertTrue(gate.capabilities(usable = true))
        assertFalse("a drop is not itself a route event", gate.capabilities(usable = false))
        assertTrue("validated again is", gate.capabilities(usable = true))
    }

    @Test
    fun `an address arriving is news, and a repeat is not`() {
        val gate = ConnectivityGate()
        assertTrue(gate.addressed(addressed = true))
        assertFalse(gate.addressed(addressed = true))
        assertFalse(gate.addressed(addressed = false))
        assertTrue("an address that returns is news again", gate.addressed(addressed = true))
    }

    @Test
    fun `the two facts are independent`() {
        val gate = ConnectivityGate()
        // A network with a host on the wire and no internet is never validated, so
        // the address is the only sign it can carry the route.
        assertTrue("an address with no validation is news on its own", gate.addressed(addressed = true))
        assertFalse("unvalidated is not itself news", gate.capabilities(usable = false))
        assertTrue("validation arriving later is", gate.capabilities(usable = true))
        assertFalse("the address repeating is not", gate.addressed(addressed = true))
    }

    @Test
    fun `losing the network makes the next one a fresh edge`() {
        val gate = ConnectivityGate()
        assertTrue(gate.capabilities(usable = true))
        assertTrue(gate.addressed(addressed = true))

        gate.lost()

        // A different wire, reporting the same facts: both are news, because
        // neither fact has been observed about *this* network.
        assertTrue("a new network that is already usable is news", gate.capabilities(usable = true))
        assertTrue("and so is its address", gate.addressed(addressed = true))
    }
}
