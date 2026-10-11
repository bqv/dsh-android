package uk.xa0.dsh.model

import kotlinx.coroutines.CompletableDeferred
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
 * These are written against the defect the class was extracted to fix: an accept
 * that completed the send's own trigger and cleared the card in the same breath, so
 * the card vanished before anything had been posted and a failure was one `Log.d`
 * line. What has to hold is a set of relationships, and each test below fails a
 * different construction of the old one:
 *
 *  * nothing is posted before a tap, and a tap that is accepted does **not** clear —
 *    the state stays `Sent`, because the host's own `cancel` is the clear;
 *  * a refusal or an unreachable host leaves the item tappable, with a message, and
 *    the next tap posts again — the one-shot deferred could not do this at all,
 *    since it was consumed by the send;
 *  * a second tap while a post is in flight never reaches the sender;
 *  * a host withdrawal ends the wait without posting anything;
 *  * the cap hands the request back rather than hanging the agent, and a hand-back
 *    that itself fails leaves the item answerable instead of a blocked agent with
 *    nothing on screen.
 *
 * ## What these cannot prove
 *
 * That a card is drawn for any of it: this is the machine, and
 * `ui/ApprovalCardTest` is the drawing. Nor that the host really settles a waterfall
 * with a `cancel` after an accepted post — that is the host contract the design
 * rests on, read from the gateway and not exercised here. Nor anything about the
 * wall clock: the cap is injected, so the wait is real time and the test's patience,
 * not the app's five minutes.
 *
 * The `withTimeout`s are liveness bounds, not timing assertions. No test here sleeps
 * and waits for a duration to elapse; each waits on an event the machine emits.
 */
class ApprovalGateTest {

    /** A stand-in host: records every outcome posted, and says what it did with it. */
    private class Host {
        val posted = Channel<JSONObject>(Channel.UNLIMITED)
        var reply: (JSONObject) -> AnswerDelivery = { AnswerDelivery.Accepted }

        suspend fun send(outcome: JSONObject): AnswerDelivery {
            posted.trySend(outcome)
            return reply(outcome)
        }
    }

    /** The next value a channel carries, with a bound so a bug fails rather than hangs. */
    private suspend fun <T> Channel<T>.next(): T = withTimeout(5_000) { receive() }

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
    fun `a tap is posted and an accepted answer is not cleared by the gate`() = runBlocking {
        coroutineScope {
            val host = Host()
            val gate = ApprovalGate(timeoutMs = 60_000)
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

            // The point of the whole change: the machine returns without clearing.
            // Only the host's `cancel` may take this card away.
            job.join()
            assertEquals(ApprovalAnswer.Sent(true), gate.state)
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
            val gate = ApprovalGate(timeoutMs = 60_000)
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
            job.join()
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
            val gate = ApprovalGate(timeoutMs = 60_000)
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
            job.join()
        }
    }

    @Test
    fun `a second tap while the answer is in flight is refused`() = runBlocking {
        coroutineScope {
            val inFlight = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var posts = 0
            val gate = ApprovalGate(timeoutMs = 60_000)
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
            job.join()
            assertEquals("only the first tap reached the host", 1, posts)
        }
    }

    @Test
    fun `a withdrawn waterfall is not answered at all`() = runBlocking {
        coroutineScope {
            var posts = 0
            val gate = ApprovalGate(timeoutMs = 60_000)
            val job = launch {
                gate.answer { _ ->
                    posts++
                    AnswerDelivery.Accepted
                }
            }
            gate.withdraw()
            job.join()
            assertEquals("nothing was posted for a settled waterfall", 0, posts)
        }
    }

    @Test
    fun `the cap hands the request back rather than hanging the agent`() = runBlocking {
        val host = Host()
        val gate = ApprovalGate(timeoutMs = 30)
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
            val gate = ApprovalGate(timeoutMs = 30)
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
            job.join()
        }
    }
}
