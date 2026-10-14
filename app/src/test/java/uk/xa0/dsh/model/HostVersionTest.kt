package uk.xa0.dsh.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The host's own DSH version, as its `$events` opening frame names it.
 *
 * The measured shape on 0.2.0-rc.2 is
 * `{"type":"ready","clientId":"…","host":{"home":"/home/user"}}` — host facts and no
 * version — so [HostVersion.NotReported] is the state a real host reaches today. The
 * `version` half cannot be observed against any shipped host and is pinned here
 * instead, because it is the field a host would have to add and the app must read it
 * from this frame and nowhere else.
 *
 * What this cannot prove: that a real host sends (or omits) any particular version.
 * The absence on 0.2.0-rc.2 is read from the host's source
 * (`dsh-api-gateway/lib/index.js`, `openRemoteEvents`) and its shipped client type
 * (`RemoteHostFacts { home, isLoopback }`), not from a running host.
 */
class HostVersionTest {

    private fun ready(hostJson: String): JSONObject =
        JSONObject("""{"type":"ready","clientId":"3165cb04","host":$hostJson}""")

    @Test
    fun `the measured 0_2_0-rc_2 frame reports no version rather than unknown`() {
        // The exact host object a live 0.2.0-rc.2 host sends. "Did not say" is a
        // fact the row can state; Unknown would claim nothing had been heard.
        assertEquals(
            HostVersion.NotReported,
            hostVersionFromReadyFrame(ready("""{"home":"/home/user"}""")),
        )
    }

    @Test
    fun `a version inside the host facts is read exactly as sent`() {
        assertEquals(
            HostVersion.Reported("0.2.0-rc.2"),
            hostVersionFromReadyFrame(
                ready("""{"home":"/home/user","version":"0.2.0-rc.2"}"""),
            ),
        )
    }

    @Test
    fun `a frame with no host object reports none`() {
        val frame = JSONObject("""{"type":"ready","clientId":"3165cb04"}""")
        assertEquals(HostVersion.NotReported, hostVersionFromReadyFrame(frame))
    }

    @Test
    fun `a blank, null or non-string version reports none`() {
        // None of these may be printed as a version, and none is a guess to fill in.
        listOf(
            """{"home":"/home/user","version":""}""",
            """{"home":"/home/user","version":"   "}""",
            """{"home":"/home/user","version":null}""",
            """{"home":"/home/user","version":3}""",
            """{"home":"/home/user","version":{}}""",
        ).forEach { host ->
            assertEquals("host=$host", HostVersion.NotReported, hostVersionFromReadyFrame(ready(host)))
        }
    }

    @Test
    fun `surrounding whitespace is not part of the version`() {
        assertEquals(
            HostVersion.Reported("0.2.0-rc.2"),
            hostVersionFromReadyFrame(
                ready("""{"home":"/home/user","version":" 0.2.0-rc.2 "}"""),
            ),
        )
    }
}
