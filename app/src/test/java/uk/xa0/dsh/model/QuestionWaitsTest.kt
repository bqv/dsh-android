package uk.xa0.dsh.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question waits, woken four different ways.
 *
 * These are written against the regression that made questions unanswerable. A
 * re-announcement on a new `$events` generation replaces a waterfall's wait and hands
 * the old one back; the handler used the slot's ownership as the test for whether to
 * send, and the answer path cleared the same slot as it took the answer. So every
 * reader's answer looked like a replacement, the handler returned without posting it,
 * and the card vanished while the host kept the agent blocked. The property under test
 * is that delivery is stated by the wake-up itself and can never be inferred from the
 * slot:
 *
 *  * an answer or a skip is deliverable, and is deliverable even though taking it
 *    clears the slot;
 *  * a hand-back from a replacement, a withdrawal or a stop says there is nothing to
 *    send, and a replaced wait never takes the slot its replacement now owns;
 *  * a second answer finds nothing to complete, so one card cannot post twice.
 *
 * What these cannot prove: that [uk.xa0.dsh.AttentionCenter] passes the flag through
 * faithfully at its own call sites, that the host really settles a waterfall on the
 * first outcome it takes, or that the phone's socket really delivers it. Setting that
 * out is the wiring around this class, and only a build plus the device can show it.
 * Nor anything about the cap: the timeout is the centre's, and the outcome it
 * synthesises is the same shape as a reader's skip, which is why the skip case is
 * here. No test sleeps; each waits on a completion the class itself performs.
 */
class QuestionWaitsTest {

    private fun outcomeOf(wait: CompletableDeferred<QuestionWaits.Outcome>): QuestionWaits.Outcome =
        runBlocking { withTimeout(5_000) { wait.await() } }

    @Test
    fun `a reader's answer is delivered even though taking it clears the slot`() {
        val waits = QuestionWaits()
        val wait = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", wait)

        val result = JSONObject().put("kind", "result").put("value", JSONObject().put("answers", "yes"))
        assertTrue("the card still had a wait to answer", waits.answer("event-1", result))
        assertFalse("the slot is taken with the answer", waits.owns("event-1", wait))

        val outcome = outcomeOf(wait)
        assertTrue("an answer is not a hand-back", outcome.deliver)
        assertEquals("result", outcome.payload.optString("kind"))
        assertEquals("yes", outcome.payload.optJSONObject("value")?.optString("answers"))
    }

    @Test
    fun `a skip is delivered as a hand-back to the host`() {
        val waits = QuestionWaits()
        val wait = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", wait)

        assertTrue(waits.skip("event-1"))

        val outcome = outcomeOf(wait)
        assertTrue("skipping tells the host, it does not vanish", outcome.deliver)
        assertEquals("next", outcome.payload.optString("kind"))
    }

    @Test
    fun `a replaced wait is woken with nothing to send`() {
        val waits = QuestionWaits()
        val old = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", old)

        val fresh = CompletableDeferred<QuestionWaits.Outcome>()
        val replaced = waits.install("event-1", fresh)

        assertSame("the caller is handed the wait it displaced", old, replaced)
        assertFalse("a replacement must not answer the host", outcomeOf(old).deliver)
        assertTrue("the new wait owns the card", waits.owns("event-1", fresh))
        assertFalse("the old wait cannot take the slot back", waits.release("event-1", old))
        assertTrue("the new wait can", waits.release("event-1", fresh))
    }

    @Test
    fun `a host withdrawal woken wait sends nothing`() {
        val waits = QuestionWaits()
        val wait = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", wait)

        assertTrue(waits.withdraw("event-1"))
        assertFalse(outcomeOf(wait).deliver)
        assertFalse("the waterfall is no longer parked", waits.answer("event-1", JSONObject()))
    }

    @Test
    fun `stopping hands every wait back without sending`() {
        val waits = QuestionWaits()
        val first = CompletableDeferred<QuestionWaits.Outcome>()
        val second = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", first)
        waits.install("event-2", second)

        waits.withdrawAll()

        assertFalse(outcomeOf(first).deliver)
        assertFalse(outcomeOf(second).deliver)
        assertFalse("nothing is left to answer", waits.answer("event-1", JSONObject()))
        assertFalse("nothing is left to answer", waits.skip("event-2"))
    }

    @Test
    fun `one card cannot answer twice`() {
        val waits = QuestionWaits()
        val wait = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", wait)

        assertTrue(waits.answer("event-1", JSONObject().put("kind", "result")))
        assertFalse("the second tap has no wait left to complete", waits.answer("event-1", JSONObject()))

        assertEquals("result", outcomeOf(wait).payload.optString("kind"))
    }
}
