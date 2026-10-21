package uk.xa0.dsh.net

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import uk.xa0.dsh.scroll.HarnessApplication
import java.io.IOException
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch

/**
 * What `DshClient.resetTransport` actually clears, as far as a JVM can see it.
 *
 * A restart used to leave two kinds of transport state untouched: OkHttp's
 * connection pool, which keeps a socket to an address the host has since left,
 * and its dispatcher, which keeps a hung call and the connection that call owns.
 * Both are reachable from the one client the app holds, and both are pinned here.
 *
 * What this cannot prove: that a *particular* wedge was a stale pooled connection
 * or a hung call — only a live host and the phone's own logcat can say which one
 * a report was. Nor can it reach the multiplexer's socket, the client's route
 * scope or the resolver's door records without a host; those are inspection of
 * [DshClient.resetTransport], [RemoteMux.shutdown] and `EndpointResolverTest`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class DshClientResetTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun client() = DshClient(context)

    @Test
    fun `an idle client reports that it cleared nothing`() {
        val reset = client().resetTransport()

        assertFalse(reset.muxClosed)
        assertEquals(0, reset.doorFaultsForgotten)
        assertFalse(reset.cachedWinnerForgotten)
        assertEquals(0, reset.pooledConnections)
        assertEquals(0, reset.callsCancelled)
        assertEquals(0, reset.routeJobsCancelled)
    }

    @Test
    fun `the client family is reused, because the pool and dispatcher are what hold transport state`() {
        // A restart rebuilds the *transport*, not the OkHttp client objects. They
        // hold only immutable configuration (timeouts, the pinner, the trust
        // manager), so a new instance would add nothing while the two mutable
        // holders — the pool and the dispatcher — are already shared by every
        // shape in the family.
        val client = client()
        val before = client.httpClient()

        client.resetTransport()

        assertSame(before, client.httpClient())
    }

    @Test
    fun `a call in flight is cancelled and counted`() {
        // A server that accepts and then never answers. The call cannot complete
        // on its own — locally refused or blackholed connections fail at different
        // speeds and would race the assertion — so it is genuinely in flight when
        // the reset cancels it.
        val server = ServerSocket(0)
        val release = CountDownLatch(1)
        Thread {
            runCatching { server.accept().use { release.await() } }
        }.apply { isDaemon = true }.start()

        val client = client()
        val call = client.httpClient()
            .newCall(Request.Builder().url("http://127.0.0.1:${server.localPort}/").build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) = Unit
            override fun onResponse(call: Call, response: Response) = response.close()
        })

        try {
            val reset = client.resetTransport()

            assertTrue("the reset must cancel calls, not only evict their connections", call.isCanceled)
            assertTrue("a cancelled call is part of what the line reports", reset.callsCancelled >= 1)
        } finally {
            release.countDown()
            runCatching { server.close() }
        }
    }

    @Test
    fun `the reset leaves one line under the route tag, with counts and no contents`() {
        ShadowLog.clear()
        client().resetTransport()

        val line = ShadowLog.getLogs().lastOrNull {
            it.tag == "DshRoute" && it.msg.orEmpty().startsWith("transport reset: ")
        }
        assertTrue("the reset must be observable in logcat", line != null)
        val message = line!!.msg
        assertTrue(message.contains("mux="))
        assertTrue(message.contains("doorFaults="))
        assertTrue(message.contains("cachedWinner="))
        assertTrue(message.contains("pooledConnections="))
        assertTrue(message.contains("calls="))
        assertTrue(message.contains("routeJobs="))
    }
}
