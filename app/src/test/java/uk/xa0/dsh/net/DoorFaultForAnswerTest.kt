package uk.xa0.dsh.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which fault a non-DSH answer earns, from the status that carried it.
 *
 * This is the rule that decides whether a door is read as *wrong* — a verdict the
 * clock may not lift, and which only a network change or a person's own action
 * clears — or as *interrupted*, which is held for a while and forgiven. Getting it
 * backwards is expensive in one direction only: a host that restarts, or answers
 * `404 not found` for an endpoint it does not serve, would have set the LAN door
 * aside until the network changed, which is the shape of bug this exists to stop.
 *
 * What the test cannot prove is what the host actually answers — that is the wire's
 * business, and it was checked against the running gate instead: an unauthenticated
 * `POST /api/<method>` on the LAN door answers `401 unauthorized`, which `rpcRaw`
 * raises as an auth failure before any of this is consulted, and the auth gate
 * answers `502 bad gateway` while its upstream restarts.
 */
class DoorFaultForAnswerTest {

    @Test
    fun `a success that is not a DSH envelope is a verdict on the door`() {
        // The captive-portal shape: it answers as though the request were fine and
        // serves its own page, so no probe of it will ever know better. (A genuinely
        // empty 2xx body never reaches this rule — `rpcRaw` refuses it as an empty
        // response first — so the status here always carries something.)
        assertEquals(DoorFault.WrongAnswer, doorFaultForAnswer(200))
        assertEquals(DoorFault.WrongAnswer, doorFaultForAnswer(201))
        assertEquals(DoorFault.WrongAnswer, doorFaultForAnswer(202))
    }

    @Test
    fun `a host failing the call is not a verdict on the door`() {
        // The webserver's own surfaces: `404 not found` for an endpoint it does not
        // serve, `415` for a media type, `500` when it breaks, and the auth gate's
        // `502 bad gateway` while the carrier behind it restarts. None of these is
        // evidence about which door this is, and all of them are transient.
        assertEquals(DoorFault.Transport, doorFaultForAnswer(400))
        assertEquals(DoorFault.Transport, doorFaultForAnswer(404))
        assertEquals(DoorFault.Transport, doorFaultForAnswer(415))
        assertEquals(DoorFault.Transport, doorFaultForAnswer(500))
        assertEquals(DoorFault.Transport, doorFaultForAnswer(502))
        assertEquals(DoorFault.Transport, doorFaultForAnswer(503))
    }

    @Test
    fun `an auth refusal never reaches the rule`() {
        // Pinned as a fact about the *caller*, not the rule: `rpcRaw` raises 401/403
        // as an auth failure before the body is parsed, so a LAN door that refuses an
        // unauthenticated call — which is exactly what the gate at the LAN address
        // does — cannot be recorded as a wrong answer at all. Should this ever be
        // consulted for one, the bounded hold is the safe half to fall on.
        assertEquals(DoorFault.Transport, doorFaultForAnswer(401))
        assertEquals(DoorFault.Transport, doorFaultForAnswer(403))
    }
}
