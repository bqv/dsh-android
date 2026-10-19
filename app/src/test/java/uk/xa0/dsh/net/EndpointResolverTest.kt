package uk.xa0.dsh.net

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The route decision: LAN first while it answers, the public door otherwise.
 *
 * Every test drives the resolver through its injected answers rather than a
 * socket, which is the point of the injection — the decision is the part worth
 * testing, and it is testable only because no Android API reaches into it.
 */
class EndpointResolverTest {

    private val lan: HttpUrl = "http://192.168.1.110/".toHttpUrlOrNull()!!
    private val public: HttpUrl = "https://cloud.xa0.uk/".toHttpUrlOrNull()!!

    /** A resolver whose "is a DSH host" answer is membership of [answering]. */
    private fun resolver(
        answering: Set<String>,
        probed: MutableList<String> = mutableListOf(),
        localIpv4: String? = null,
        clock: () -> Long = { 0L },
    ) = EndpointResolver(
        isDshHost = { url -> probed += url.host; url.host in answering },
        localIpv4 = { localIpv4 },
        clock = clock,
    )

    @Test
    fun `the LAN wins when it answers`() = runBlocking {
        val r = resolver(answering = setOf("192.168.1.110", "cloud.xa0.uk"))
        r.update(primary = public, lan = lan)

        assertEquals(lan, r.refresh())
        assertEquals(lan, r.peek())
        assertTrue(r.isLan)
    }

    @Test
    fun `the primary is used when the LAN does not answer`() = runBlocking {
        val r = resolver(answering = setOf("cloud.xa0.uk"))
        r.update(primary = public, lan = lan)

        assertEquals(public, r.refresh())
        assertFalse(r.isLan)
        assertEquals(public, r.peek())
    }

    @Test
    fun `peek returns the primary before the first refresh and probes nothing`() {
        val probed = mutableListOf<String>()
        val r = resolver(answering = emptySet(), probed = probed)
        r.update(primary = public, lan = lan)

        assertEquals(public, r.peek())
        assertTrue("peek must not probe", probed.isEmpty())
    }

    @Test
    fun `a resolver with no configured address has nothing to offer`() = runBlocking {
        val r = EndpointResolver(isDshHost = { true })

        assertNull(r.peek())
        assertNull(r.refresh())
    }

    @Test
    fun `a fresh cache is not re-probed`() = runBlocking {
        var now = 0L
        val probed = mutableListOf<String>()
        val answering = mutableSetOf("cloud.xa0.uk")
        val r = EndpointResolver(
            isDshHost = { url -> probed += url.host; url.host in answering },
            localIpv4 = { null },
            clock = { now },
        )
        r.update(primary = public, lan = lan)
        assertEquals(public, r.refresh())
        assertEquals(1, probed.count { it == "192.168.1.110" })

        // The LAN comes back, but the cached public winner is still inside its
        // TTL: refresh() must answer from cache rather than probe.
        answering += "192.168.1.110"
        now = 1_000L
        assertEquals(public, r.refresh())
        assertEquals(1, probed.count { it == "192.168.1.110" })
    }

    @Test
    fun `invalidate forces a re-probe`() = runBlocking {
        var now = 0L
        val answering = mutableSetOf("cloud.xa0.uk")
        val r = EndpointResolver(
            isDshHost = { it.host in answering },
            clock = { now },
        )
        r.update(primary = public, lan = lan)
        assertEquals(public, r.refresh())

        answering += "192.168.1.110"
        now = 1_000L
        assertEquals(public, r.refresh())

        r.invalidate()
        assertEquals(lan, r.refresh())
        assertTrue(r.isLan)
    }

    @Test
    fun `a changed winner changes the reported route`() = runBlocking {
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)

        r.refresh()
        assertTrue(r.isLan)

        // The phone leaves the house: the LAN stops answering, so the same
        // resolution run must land on the public door.
        answering -= "192.168.1.110"
        r.invalidate()
        r.refresh()
        assertFalse(r.isLan)
        assertEquals(public, r.peek())
    }

    @Test
    fun `the configured LAN is re-addressed onto the phone's own subnet`() = runBlocking {
        val probed = mutableListOf<String>()
        val r = EndpointResolver(
            isDshHost = { url -> probed += url.host; url.host == "192.168.0.110" },
            localIpv4 = { "192.168.0.42" },
        )
        r.update(primary = public, lan = lan)

        assertEquals("http://192.168.0.110/", r.refresh().toString())
        assertTrue(r.isLan)
        // The configured address is still tried first — the rewrite is a fallback,
        // not a replacement.
        assertTrue(probed.contains("192.168.1.110"))
        assertTrue(probed.contains("192.168.0.110"))
    }

    @Test
    fun `no rewrite is tried while the phone is on the LAN's own subnet`() = runBlocking {
        val probed = mutableListOf<String>()
        val r = EndpointResolver(
            isDshHost = { url -> probed += url.host; url.host == "192.168.1.110" },
            localIpv4 = { "192.168.1.42" },
        )
        r.update(primary = public, lan = lan)

        r.refresh()
        assertEquals(setOf("192.168.1.110", "cloud.xa0.uk"), probed.toSet())
    }

    @Test
    fun `a LAN-only install reports LAN from its primary`() = runBlocking {
        // What DshClient seeds for a config whose baseUrl is already private:
        // the same URL is both the primary and the LAN candidate. It is probed
        // once, and the door it proves is the LAN.
        val probed = mutableListOf<String>()
        val r = EndpointResolver(
            isDshHost = { url -> probed += url.host; url.host == "192.168.1.110" },
        )
        r.update(primary = lan, lan = lan)

        assertEquals(lan, r.refresh())
        assertTrue(r.isLan)
        assertEquals(listOf("192.168.1.110"), probed)
    }

    @Test
    fun `resolve marks the probe that moved the route, not a cached answer`() = runBlocking {
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(
            isDshHost = { it.host in answering },
            localIpv4 = { null },
            clock = { 0L },
        )
        r.update(primary = public, lan = lan)

        assertTrue("the first probe ever is a move", r.resolve().changed)

        assertFalse("a cached answer is not a move", r.resolve().changed)

        answering -= "192.168.1.110"
        r.invalidate()
        assertTrue("LAN -> public is a move", r.resolve().changed)

        r.invalidate()
        assertFalse("public -> public is not", r.resolve().changed)
    }

    @Test
    fun `re-seeding the candidates drops the cached winner`() = runBlocking {
        val r = resolver(answering = setOf("192.168.1.110", "cloud.xa0.uk"))
        r.update(primary = public, lan = lan)
        r.refresh()
        assertTrue(r.isLan)

        // The user cleared the LAN field on the Setup screen.
        r.update(primary = public, lan = null)
        assertFalse(r.isLan)
        assertEquals(public, r.peek())
    }

    @Test
    fun `a private primary counts as the LAN candidate`() {
        // Existing LAN-only installs must keep working with nothing re-entered,
        // which is the whole reason this rule exists.
        assertTrue(isPrivateLanHost(hostOf("192.168.1.110")))
        assertTrue(isPrivateLanHost(hostOf("http://10.0.0.5:8080/")))
        assertTrue(isPrivateLanHost(hostOf("172.16.4.4")))
        assertTrue(isPrivateLanHost(hostOf("dsh.local")))

        assertFalse(isPrivateLanHost(hostOf("https://cloud.xa0.uk/")))
        assertFalse(isPrivateLanHost(hostOf("https://dsh.xa0.uk:10816/")))
        // 172.32/12 is public address space, not the RFC1918 172.16/12.
        assertFalse(isPrivateLanHost(hostOf("172.32.0.1")))
        assertFalse(isPrivateLanHost(hostOf("host.example.com")))
    }

    @Test
    fun `a door that failed in use stops being preferred`() = runBlocking {
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)
        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)

        // The LAN answered the cheap probe, then a real call failed on it at the
        // transport: a stale socket, or a host that has stopped answering since
        // the probe. The resolution that failure triggers must land on the door
        // that works rather than bouncing straight back onto this one, so the
        // first hold is short but not zero.
        r.noteFailure(lan, DoorFault.Transport)
        r.invalidate()
        val choice = r.resolve()
        assertEquals(public, choice.url)
        assertFalse(r.isLan)
        assertTrue("LAN -> public is a move", choice.changed)
    }

    @Test
    fun `a network change forgives the door that failed`() = runBlocking {
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)

        r.noteFailure(lan, DoorFault.Transport)
        r.invalidate()
        assertEquals(public, r.resolve().url)

        // A different network now: that address may be the host again, and the
        // failure was a fact about the wire that is gone.
        r.networkChanged()
        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)
    }

    @Test
    fun `an avoided door is still used when nothing else answers`() = runBlocking {
        // A hold reorders the candidates; it must never make the app prefer a
        // door that did not answer at all over the one door that did.
        val probed = mutableListOf<String>()
        val r = EndpointResolver(
            isDshHost = { url -> probed += url.host; url.host == "192.168.1.110" },
            localIpv4 = { null },
        )
        r.update(primary = public, lan = lan)
        r.noteFailure(lan, DoorFault.Transport)
        r.invalidate()

        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)
        assertTrue(probed.contains("cloud.xa0.uk"))
    }

    @Test
    fun `re-seeding the candidates forgets what failed on the old ones`() = runBlocking {
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)
        r.noteFailure(lan, DoorFault.Transport)
        r.invalidate()
        assertEquals(public, r.resolve().url)

        // The user corrected the LAN field. The new address has not failed, so it
        // starts with a clean record rather than inheriting the old one's.
        val corrected: HttpUrl = "http://192.168.1.120/".toHttpUrlOrNull()!!
        answering += "192.168.1.120"
        r.update(primary = public, lan = corrected)

        assertEquals(corrected, r.resolve().url)
        assertTrue(r.isLan)
    }

    @Test
    fun `a slow LAN door still beats a fast public one`() = runBlocking {
        // Selection is by candidate order, never by which answer arrived first:
        // the candidates are probed concurrently and the first *preferred* door
        // that answered is taken, so no timing asymmetry between the doors can
        // hand the route to a slower public one. The probe timeout can only
        // disqualify a LAN door that answers more slowly than the timeout
        // allows — it can never promote the public door past a LAN that replied.
        val r = EndpointResolver(
            isDshHost = { url ->
                if (url.host == "192.168.1.110") delay(300)
                true
            },
        )
        r.update(primary = public, lan = lan)

        assertEquals(lan, r.refresh())
        assertTrue(r.isLan)
    }

    @Test
    fun `a transport hold runs out on the clock, but a wrong answer waits for the network`() = runBlocking {
        // Rewritten deliberately, from `only a network change forgives a door, not
        // a fresh resolution`. That test pinned the old contract — nothing but a
        // connectivity edge ever cleared a failure — and it had been protecting a
        // real defect: a *transient* LAN failure (a Wi-Fi roam inside one SSID, a
        // host-side blip) outlived the fault and left the app on the public route
        // for the life of that network, foreground resumes included. The contract
        // now splits on the kind of failure, and this states what replaced it.
        var now = 0L
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(
            isDshHost = { it.host in answering },
            localIpv4 = { null },
            clock = { now },
        )
        r.update(primary = public, lan = lan)

        r.noteFailure(lan, DoorFault.Transport)
        r.invalidate()
        // The failure's own re-resolution has to land on the other door, or the
        // retry inside the call that just failed goes straight back to the door
        // that broke.
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)

        // Still inside the short hold: a fresh resolution, of the kind a resume
        // performs, does not put the door back.
        now = 4_000L
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)

        // The hold is over. Nothing was forgiven and no network changed: the clock
        // alone readmits the door, and nothing anywhere is scheduled to notice.
        now = 6_000L
        r.invalidate()
        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)

        // The other kind is a verdict on the door, not on the wire, so no amount
        // of time makes a fresh probe of it into evidence.
        now = 6_000L + 86_400_000L
        r.noteFailure(lan, DoorFault.WrongAnswer)
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)

        // Only the wire changing forgives that one.
        r.networkChanged()
        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)
    }

    @Test
    fun `a resume lifts a transport hold early`() = runBlocking {
        // The foreground path is a deliberate "ask again after an absence", so it
        // does not have to wait the hold out — which is what makes a transient
        // failure recoverable without a network change.
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)

        r.noteFailure(lan, DoorFault.Transport)
        r.invalidate()
        assertEquals(public, r.resolve().url)

        r.grantRetry()
        r.invalidate()
        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)
    }

    @Test
    fun `a resume never lifts a wrong answer`() = runBlocking {
        // The whole reason the two kinds are apart: a captive portal answers the
        // resume's probe exactly as it answered the first one.
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)

        r.noteFailure(lan, DoorFault.WrongAnswer)
        r.grantRetry()
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)
    }

    @Test
    fun `a resume stops lifting the hold once its bounded retries are spent`() = runBlocking {
        // "Forgiving on every resume" is the other way this goes wrong: an app
        // resumed repeatedly would be put back onto a door that fails every
        // attempt, one failed action at a time. Two liftings per run of failures
        // is the bound, and a door that keeps failing reaches it at once.
        var now = 0L
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(
            isDshHost = { it.host in answering },
            localIpv4 = { null },
            clock = { now },
        )
        r.update(primary = public, lan = lan)

        // First failure, first resume: the retry is granted, and the door is
        // chosen and fails again.
        r.noteFailure(lan, DoorFault.Transport)
        r.grantRetry()
        r.invalidate()
        assertEquals(lan, r.resolve().url)

        now = 6_000L
        r.noteFailure(lan, DoorFault.Transport)
        r.grantRetry()
        r.invalidate()
        assertEquals(lan, r.resolve().url)

        // Third failure: the run has had both of its liftings, so this resume is
        // refused and the lengthened hold stands.
        now = 70_000L
        r.noteFailure(lan, DoorFault.Transport)
        r.grantRetry()
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)

        // And the re-resolution the failing call triggers cannot bounce back
        // either, which is what stops the app alternating between the two routes.
        now = 70_500L
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)
    }

    @Test
    fun `a failure that is already being avoided does not escalate itself`() = runBlocking {
        // An outage fails every call. Counting each report would drive a door to
        // the long hold in the first second of an outage it might recover from, so
        // a report arriving while the hold is still running is the same fault
        // saying the same thing.
        var now = 0L
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(
            isDshHost = { it.host in answering },
            localIpv4 = { null },
            clock = { now },
        )
        r.update(primary = public, lan = lan)

        r.noteFailure(lan, DoorFault.Transport)
        now = 1_000L
        r.noteFailure(lan, DoorFault.Transport)
        now = 2_000L
        r.noteFailure(lan, DoorFault.Transport)

        // The first, short hold — not one lengthened by the two extra reports.
        now = 5_001L
        r.invalidate()
        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)
    }

    @Test
    fun `a quiet spell starts the strike count over`() = runBlocking {
        // Without this a door that blipped once a day would climb to the longest
        // hold and stay there, which is the stranding this change exists to
        // remove, only slower.
        var now = 0L
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(
            isDshHost = { it.host in answering },
            localIpv4 = { null },
            clock = { now },
        )
        r.update(primary = public, lan = lan)

        // Two failures close together: the second earns the minute-long hold.
        r.noteFailure(lan, DoorFault.Transport)
        now = 6_000L
        r.noteFailure(lan, DoorFault.Transport)

        now = 60_000L
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)

        // Long enough quiet later the next failure is a fresh one, not a third
        // strike, so it earns the short hold again.
        now = 1_000_000L
        r.noteFailure(lan, DoorFault.Transport)
        now = 1_006_000L
        r.invalidate()
        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)
    }

    @Test
    fun `the avoided LAN door is named while it is being avoided`() = runBlocking {
        // The readout the About page draws: a public route with a LAN address
        // configured has a reason, and the reader cannot infer it.
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)
        assertEquals(lan, r.resolve().url)
        assertNull("nothing has failed yet", r.lanDoorFault)

        r.noteFailure(lan, DoorFault.Transport)
        assertNull("still the door in use, so not being avoided yet", r.lanDoorFault)

        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertEquals(DoorFault.Transport, r.lanDoorFault)

        // A fault on the public door is not the LAN door's story.
        r.noteFailure(public, DoorFault.Transport)
        assertEquals(DoorFault.Transport, r.lanDoorFault)

        // Back in use, nothing is being avoided and there is nothing to explain.
        r.grantRetry()
        r.invalidate()
        assertEquals(lan, r.resolve().url)
        assertNull(r.lanDoorFault)

        // The other kind reads as itself, because it is the one that does not
        // come back until the network changes.
        r.noteFailure(lan, DoorFault.WrongAnswer)
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertEquals(DoorFault.WrongAnswer, r.lanDoorFault)
    }

    @Test
    fun `a person asking again lifts a wrong answer`() = runBlocking {
        // The verdict is not reversible by the clock — a captive portal answers a
        // probe exactly as it answered the first — but a person holding the phone is
        // evidence the app cannot obtain on its own. Without this the verdict would
        // be one no reader could ever revisit, which is indistinguishable from an app
        // that has stopped trying.
        var now = 0L
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(
            isDshHost = { it.host in answering },
            localIpv4 = { null },
            clock = { now },
        )
        r.update(primary = public, lan = lan)

        r.noteFailure(lan, DoorFault.WrongAnswer)
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertEquals(DoorFault.WrongAnswer, r.lanDoorFault)

        // Days later the clock still has no opinion.
        now = 86_400_000L
        r.invalidate()
        assertEquals(public, r.resolve().url)

        // The person does.
        r.forgiveByUser()
        r.invalidate()
        assertEquals(lan, r.resolve().url)
        assertTrue(r.isLan)
        assertNull(r.lanDoorFault)
    }

    @Test
    fun `forgiving is not a cadence, so a door that answers wrongly again is avoided again`() =
        runBlocking {
            // What stops the human override becoming a loop: the re-ask costs the
            // failed attempt that follows it, and that attempt records the verdict
            // afresh. Two resolutions inside one forgiveness cannot bounce between
            // the doors.
            val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
            val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
            r.update(primary = public, lan = lan)

            r.noteFailure(lan, DoorFault.WrongAnswer)
            r.forgiveByUser()
            r.invalidate()
            assertEquals(lan, r.resolve().url)

            // The call that followed failed the same way, which is what a real
            // exchange records; the door is out again, and no further resolution on
            // its own puts it back.
            r.noteFailure(lan, DoorFault.WrongAnswer)
            r.invalidate()
            assertEquals(public, r.resolve().url)
            r.grantRetry()
            r.invalidate()
            assertEquals(public, r.resolve().url)
            assertFalse(r.isLan)
        }

    @Test
    fun `a config with no LAN candidate says so`() = runBlocking {
        // The other reason a reader can be sitting on the public route: nothing to
        // try. A fault cannot be recorded against a candidate that does not exist, so
        // this fact has to be readable somewhere else.
        val probed = mutableListOf<String>()
        // Both doors answer; the point is that with no LAN candidate the LAN is
        // never *asked*, so the public door is the only answer there can be.
        val r = resolver(answering = setOf("192.168.1.110", "cloud.xa0.uk"), probed = probed)
        r.update(primary = public, lan = null)

        assertFalse(r.hasLanCandidate)
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)
        assertTrue("nothing to probe, so nothing was probed", probed == listOf("cloud.xa0.uk"))

        r.update(primary = public, lan = lan)
        assertTrue(r.hasLanCandidate)
        assertEquals(lan, r.resolve().url)
    }

    @Test
    fun `a forced resolution cannot lift a wrong answer by itself`() = runBlocking {
        // What makes the dial's forced probe safe: a dial re-asks the doors, and the
        // resolver still answers with the hold rather than the probe. Only the
        // network changing or a person's action moves it.
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)
        r.noteFailure(lan, DoorFault.WrongAnswer)

        r.invalidate()
        assertEquals(public, r.resolve().url)
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertFalse(r.isLan)

        r.forgiveByUser()
        r.invalidate()
        assertEquals(lan, r.resolve().url)
    }

    @Test
    fun `a restart drops the cached winner so the next dial really probes`() = runBlocking {
        // The cached winner is exactly the kind of answer a wedged connection
        // leaves behind: it was true for the wire the app could not use, and a TTL
        // would keep serving it without asking. A person rebuilding the
        // connection is owed a fresh question.
        val probed = mutableListOf<String>()
        val r = EndpointResolver(
            isDshHost = { url -> probed += url.host; url.host == "192.168.1.110" },
            localIpv4 = { null },
        )
        r.update(primary = public, lan = lan)
        assertEquals(lan, r.resolve().url)
        assertEquals(lan, r.peek())
        val before = probed.size

        r.restart()

        assertFalse("no LAN winner survives a restart", r.isLan)
        assertEquals("peek falls back to the primary until a probe lands", public, r.peek())
        assertEquals(lan, r.resolve().url)
        assertTrue("the restart re-probed the doors", probed.size > before)
        assertTrue(r.isLan)
    }

    @Test
    fun `a restart clears a wrong-answer verdict and every hold`() = runBlocking {
        // The verdict is the one hold nothing automatic may lift, so a transport
        // restart has to be stronger than the automatic paths — otherwise a door
        // that once answered with a captive portal stays out of the running for
        // the rest of the process, however many times the reader rebuilds the
        // connection.
        val answering = mutableSetOf("192.168.1.110", "cloud.xa0.uk")
        val r = EndpointResolver(isDshHost = { it.host in answering }, localIpv4 = { null })
        r.update(primary = public, lan = lan)

        r.noteFailure(lan, DoorFault.WrongAnswer)
        r.noteFailure(public, DoorFault.Transport)
        r.invalidate()
        assertEquals(public, r.resolve().url)
        assertEquals(DoorFault.WrongAnswer, r.lanDoorFault)

        r.restart()

        assertNull("no door is avoided after a restart", r.lanDoorFault)
        assertEquals("the LAN door is back in the running", lan, r.resolve().url)
        assertTrue(r.isLan)
    }

    @Test
    fun `the first resolution after a restart counts as a move`() = runBlocking {
        // What lets the client drop a multiplexer a racing caller may have built
        // between the restart and the fresh dial: the resolution that follows
        // reports a change even when it lands on the same door, so nothing bound
        // to the old answer is adopted.
        val r = resolver(answering = setOf("cloud.xa0.uk"))
        r.update(primary = public, lan = lan)

        assertTrue("the first probe ever is a move", r.resolve().changed)
        assertFalse("a cached answer is not", r.resolve().changed)

        r.restart()
        assertTrue("a restart's first answer is a move again", r.resolve().changed)
    }
}
