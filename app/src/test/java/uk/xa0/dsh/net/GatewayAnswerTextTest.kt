package uk.xa0.dsh.net

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a person is told when a gateway call is answered by a page.
 *
 * A 504 is the case this was written for: nginx answers its own error page, the
 * body is markup, and the banner used to carry four hundred characters of
 * `<!DOCTYPE html>` — which pushed the part worth reading, and anything said after
 * it, out of sight. The rule here is narrow on purpose: markup and blank bodies
 * become one plain sentence, and a short plain body is still the web server's own
 * words and is kept.
 *
 * What this cannot prove is what the host's deployment actually answers for a slow
 * prompt. That is read from the deployment's nginx configuration and from the
 * protocol document (`docs/research/protocol.md`, `session/prompt`), not from a
 * test; and no test here reaches `rpcRaw`, which is where this function is called.
 */
class GatewayAnswerTextTest {

    /** The shape a 504 actually has: nginx's own page. */
    @Test
    fun `an nginx error page is replaced by one sentence`() {
        val page = """
            <!DOCTYPE html>
            <html>
            <head><title>504 Gateway Time-out</title></head>
            <body><h1>504 Gateway Time-out</h1></body>
            </html>
        """.trimIndent()

        assertEquals("the host did not answer; its web server replied instead", gatewayAnswerText(page))
    }

    /** A page whose leading whitespace hides the markup is still a page. */
    @Test
    fun `leading whitespace does not disguise markup`() {
        assertEquals(
            "the host did not answer; its web server replied instead",
            gatewayAnswerText("\n\n   <html><body>502 Bad Gateway</body></html>"),
        )
    }

    /** An empty body is the same situation with less to say. */
    @Test
    fun `an empty body says the same thing`() {
        assertEquals("the host did not answer; its web server replied instead", gatewayAnswerText(""))
        assertEquals(
            "the host did not answer; its web server replied instead",
            gatewayAnswerText("   \n\t "),
        )
    }

    /** A short plain body is the web server speaking, so it is kept as it is. */
    @Test
    fun `a short plain body is kept`() {
        assertEquals("not found", gatewayAnswerText("  not found\n"))
        assertEquals(
            "upstream timed out (110: Connection timed out) while reading response header",
            gatewayAnswerText("upstream timed out (110: Connection timed out) while reading response header"),
        )
    }

    /** A plain body long enough to be a page of prose is still cut to one line's worth. */
    @Test
    fun `a long plain body is truncated`() {
        val long = "x".repeat(500)

        assertEquals(200, gatewayAnswerText(long).length)
    }
}
