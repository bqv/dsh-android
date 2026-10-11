package uk.xa0.dsh.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The approval answer, attempt by attempt.
 *
 * These are written against the two defects the class has been through. First an
 * accept that completed the send's own trigger and cleared the card in the same
 * breath, so the card vanished before anything had been posted and a failure was one
 * `Log.d` line; the machine was extracted so a tap posts, the reply decides, and a
 * failure stays answerable. Then the other side of it: the clear was made the host's
 * own `{type:"cancel"}`, which the host never sends to the client that answered, so
 * the card stayed until the process was restarted. The accepted reply is now the
 * clear, with a bounded settle grace for a `cancel` to arrive first.
 *
 * What has to hold, and each test below fails a different construction of one of
 * those bugs:
 *
 *  * nothing is posted before a tap, and an accepted post holds `Sent` for the grace
 *    and then ends the wait — no second signal is required, and none is available;
 *  * a `cancel` during the grace ends the wait at once;
 *  * a state past `Waiting`/`Unsent` refuses a tap, so nothing is authorised twice;
 *  * a refusal or an unreachable host leaves the item tappable, with a message, and
 *    the next tap posts again — the one-shot deferred could not do this at all,
 *    since it was consumed by the send;
 *  * a host withdrawal ends the wait without posting anything;
 *  * the cap hands the request back rather than hanging the agent, and a hand-back
 *    that itself fails leaves the item answerable instead of a blocked agent with
 *    nothing on screen.
 *
 * ## What these cannot prove
 *
 * That a card is drawn for any of it: this is the machine, and `ui/ApprovalCardTest`
 * is the drawing. Nor that the host really applies a `result` before it replies and
 * really never cancels the answering client — that is read from the gateway
 * (`dsh-api-gateway/lib/index.js`: `dispatchRpc`, `receiveRemoteEventResult` and
 * `finishRemoteEvent`) and not exercised here; this harness is the machine talking to
 * a stand-in host that decides what to reply. Nor anything about the wall clock: the
 * cap and the grace are injected, so the waits are real time and the test's patience,
 * not the app's five minutes.
 *
 * The `withTimeout`s are liveness bounds, not timing assertions. No test here sleeps
 * and waits for a duration to elapse; each waits on an event the machine emits, and
 * the one test that must outlast the grace uses a grace short enough to be a
 * scheduling detail rather than a measurement.
 */
class ApprovalGateTest {

    /** A stand-in host: records every outcome posted, and says what it did with it. */
    private class Host {
        val posted = Channel<JSONObject>(Channel.UNLIMITED)

        /** How many outcomes reached the host at all, hand-backs included. */
        var count = 0
            private set

        var reply: (JSONObject) -> AnswerDelivery = { AnswerDelivery.Accepted }

        suspend fun send(outcome: JSONObject): AnswerDelivery {
            count++
            posted.trySend(outcome)
            return reply(outcome)
        }
    }

    /** The next value a channel carries, with a bound so a bug fails rather than hangs. */
    private suspend fun <T> Channel<T>.next(): T = withTimeout(5_000) { receive() }

    /** A join that fails rather than hanging when the machine never settles. */
    private suspend fun Job.settle() = withTimeout(5_000) { join() }

    /**
     * The next answer a host was asked to take, skipping the delegated hand-backs a
     * failed cap retries on its own schedule.
     */
    private suspend fun Host.awaitAnswer(): JSONObject {
        while (true) {
            val outcome = posted.next()
            if (outcome.getString("kind") == "result") return outcome
        }
    }

    @Test
    fun `an accepted answer settles after the grace, with no cancel to wait for`() = runBlocking {
        coroutineScope {
            val host = Host()
            val gate = ApprovalGate(timeoutMs = 60_000, settleGraceMs = 40)
            val states = Channel<ApprovalAnswer>(Channel.UNLIMITED)
            gate.onState = { states.trySend(it) }
            val job = launch { gate.answer(host::send) }

            assertTrue("nothing is sent before a tap", gate.state is ApprovalAnswer.Waiting)
            assertTrue("the first tap answers", gate.tap(true))
            assertEquals(ApprovalAnswer.Answering(true), states.next())

            val outcome = host.posted.next()
            assertEquals("result", outcome.getString("kind"))
            assertEquals("allowed-once", outcome.getString("value"))
            assertEquals(ApprovalAnswer.Sent(true), states.next())

            // No `withdraw` is called: this is the reported defect. The accepted
            // reply is the clear, so the wait ends on its own after the grace.
            job.settle()
            assertEquals(ApprovalAnswer.Sent(true), gate.state)

            // And the settle cannot be undone by a late tap: an accepted answer is
            // never sent twice.
            assertFalse("a settled card answers nothing", gate.tap(false))
            assertFalse(gate.tap(true))
        }
    }

    @Test
    fun `a cancel during the settle grace ends the wait at once`() = runBlocking {
        coroutineScope {
            val host = Host()
            // Long enough that only the withdrawal can end the wait inside the
            // bound below: a machine that ignored it would time the test out.
            val gate = ApprovalGate(timeoutMs = 60_000, settleGraceMs = 60_000)
            val states = Channel<ApprovalAnswer>(Channel.UNLIMITED)
            gate.onState = { states.trySend(it) }
            val job = launch { gate.answer(host::send) }

            assertTrue(gate.tap(true))
            assertEquals(ApprovalAnswer.Answering(true), states.next())
            host.posted.next()
            assertEquals(ApprovalAnswer.Sent(true), states.next())

            gate.withdraw()
            job.settle()
            assertEquals("the withdrawal leaves the host's decision standing", 1, host.count)
        }
    }

    @Test
    fun `a refused answer is shown and can be answered again`() = runBlocking {
        coroutineScope {
            var posts = 0
            val host = Host()
            host.reply = {
                posts++
                if (posts == 1) AnswerDelivery.Refused("session/agent-busy") else AnswerDelivery.Accepted
            }
            val gate = ApprovalGate(timeoutMs = 60_000, settleGraceMs = 40)
            val states = Channel<ApprovalAnswer>(Channel.UNLIMITED)
            gate.onState = { states.trySend(it) }
            val job = launch { gate.answer(host::send) }

            assertTrue(gate.tap(true))
            assertEquals(ApprovalAnswer.Answering(true), states.next())
            host.posted.next()

            val refused = states.next()
            assertTrue("a refusal is shown, not logged", refused is ApprovalAnswer.Unsent)
            val message = (refused as ApprovalAnswer.Unsent).message
            assertTrue("the card names the refusal: $message", message.contains("refused"))
            assertTrue("and the code the host gave: $message", message.contains("session/agent-busy"))

            assertTrue("the item takes another tap", gate.tap(false))
            assertEquals(ApprovalAnswer.Answering(false), states.next())
            val second = host.posted.next()
            assertEquals("rejected", second.getString("value"))
            assertEquals(ApprovalAnswer.Sent(false), states.next())
            job.settle()
        }
    }

    @Test
    fun `an answer that never reached the host is shown and can be retried`() = runBlocking {
        coroutineScope {
            var posts = 0
            val host = Host()
            host.reply = {
                posts++
                if (posts == 1) AnswerDelivery.Unreachable("no route to host") else AnswerDelivery.Accepted
            }
            val gate = ApprovalGate(timeoutMs = 60_000, settleGraceMs = 40)
            val states = Channel<ApprovalAnswer>(Channel.UNLIMITED)
            gate.onState = { states.trySend(it) }
            val job = launch { gate.answer(host::send) }

            assertTrue(gate.tap(true))
            assertEquals(ApprovalAnswer.Answering(true), states.next())
            host.posted.next()
            val unsent = states.next()
            assertTrue(unsent is ApprovalAnswer.Unsent)
            val message = (unsent as ApprovalAnswer.Unsent).message
            assertTrue("the card says it did not arrive: $message", message.contains("did not reach"))

            assertTrue("the item takes another tap", gate.tap(true))
            assertEquals(ApprovalAnswer.Answering(true), states.next())
            assertEquals("allowed-once", host.posted.next().getString("value"))
            assertEquals(ApprovalAnswer.Sent(true), states.next())
            job.settle()
        }
    }

    @Test
    fun `a second tap while the answer is in flight is refused`() = runBlocking {
        coroutineScope {
            val inFlight = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var posts = 0
            val gate = ApprovalGate(timeoutMs = 60_000, settleGraceMs = 40)
            val job = launch {
                gate.answer { _ ->
                    posts++
                    inFlight.complete(Unit)
                    release.await()
                    AnswerDelivery.Accepted
                }
            }

            assertTrue(gate.tap(true))
            withTimeout(5_000) { inFlight.await() }
            assertFalse("a tap while the post is out is inert", gate.tap(false))
            assertFalse(gate.tap(true))
            release.complete(Unit)
            job.settle()
            assertEquals("only the first tap reached the host", 1, posts)
        }
    }

    @Test
    fun `a withdrawn waterfall is not answered at all`() = runBlocking {
        coroutineScope {
            var posts = 0
            val gate = ApprovalGate(timeoutMs = 60_000, settleGraceMs = 40)
            val job = launch {
                gate.answer { _ ->
                    posts++
                    AnswerDelivery.Accepted
                }
            }
            gate.withdraw()
            job.settle()
            assertEquals("nothing was posted for a settled waterfall", 0, posts)
        }
    }

    @Test
    fun `the cap hands the request back rather than hanging the agent`() = runBlocking {
        val host = Host()
        val gate = ApprovalGate(timeoutMs = 30, settleGraceMs = 40)
        withTimeout(5_000) { gate.answer(host::send) }
        assertEquals("the host is told to move on", "next", host.posted.next().getString("kind"))
    }

    @Test
    fun `a hand-back the host never took stays answerable`() = runBlocking {
        coroutineScope {
            val host = Host()
            host.reply = { outcome ->
                if (outcome.getString("kind") == "next") AnswerDelivery.Unreachable("no route")
                else AnswerDelivery.Accepted
            }
            val gate = ApprovalGate(timeoutMs = 30, settleGraceMs = 40)
            val states = Channel<ApprovalAnswer>(Channel.UNLIMITED)
            gate.onState = { states.trySend(it) }
            val job = launch { gate.answer(host::send) }

            assertEquals("next", host.posted.next().getString("kind"))
            val unsent = states.next()
            assertTrue("the failed hand-back is shown", unsent is ApprovalAnswer.Unsent)
            assertTrue("and a late answer is still taken", gate.tap(true))
            assertEquals(ApprovalAnswer.Answering(true), states.next())
            assertEquals("allowed-once", host.awaitAnswer().getString("value"))
            assertEquals(ApprovalAnswer.Sent(true), states.next())
            job.settle()
        }
    }
}
