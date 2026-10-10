package uk.xa0.dsh.net

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
}
