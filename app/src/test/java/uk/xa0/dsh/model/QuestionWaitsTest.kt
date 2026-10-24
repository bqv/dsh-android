package uk.xa0.dsh.model

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The question waits, woken four different ways.
 *
 * These are written against two regressions. The first made questions unanswerable: a
 * re-announcement on a new `$events` generation replaces a waterfall's wait and hands
 * the old one back; the handler used the slot's ownership as the test for whether to
 * send, and the answer path cleared the same slot as it took the answer. So every
 * reader's answer looked like a replacement, the handler returned without posting it,
 * and the card vanished while the host kept the agent blocked. The second made **skip**
 * send nothing the host could use: the skip was encoded as `{kind:"next"}`, which is
 * the gateway's "decline this answerer" outcome
 * (`dsh-api-remotes/lib/index.js`, `forwardWaterfall`), so the host went looking for
 * another answerer and never learned that anyone had skipped.
 *
 * The properties under test:
 *
 *  * delivery is stated by the wake-up itself and can never be inferred from the slot:
 *    an answer and a skip are both deliverable;
 *  * a skip's payload is a `result` batch with one empty selection per question and no
 *    `custom` — the web's own encoding of a skipped set, not a hand-back;
 *  * a skip keeps its slot until the handler claims it, so a refused skip cannot clear
 *    the card, a second tap is inert, and a re-announcement still wins the card;
 *  * a hand-back from a replacement, a withdrawal or a stop says there is nothing to
 *    send, and a replaced wait never takes the slot its replacement now owns;
 *  * a second answer finds nothing to complete, so one card cannot post twice.
 *
 * What these cannot prove: that [uk.xa0.dsh.AttentionCenter] passes the flag through
 * faithfully at its own call sites, that the host really settles a waterfall on the
 * first outcome it takes, that the host accepts this exact batch (only its schema and
 * the web client say so), or that the phone's socket really delivers it. Setting that
 * out is the wiring around this class, and only a build plus the device can show it.
 * Nor anything about the cap: the timeout is the centre's, and the outcome it
 * synthesises is still a hand-back. No test sleeps; each waits on a completion the
 * class itself performs.
 */
class QuestionWaitsTest {

    private fun outcomeOf(wait: CompletableDeferred<QuestionWaits.Outcome>): QuestionWaits.Outcome =
        runBlocking { withTimeout(5_000) { wait.await() } }

    private fun answersOf(outcome: QuestionWaits.Outcome): JSONArray? =
        outcome.payload.optJSONObject("value")?.optJSONArray("answers")

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
        assertFalse("an answer is not a skip", outcome.skip)
        assertEquals("result", outcome.payload.optString("kind"))
        assertEquals("yes", outcome.payload.optJSONObject("value")?.optString("answers"))
    }

    @Test
    fun `a skip is a result batch of empty selections, not a hand-back`() {
        val waits = QuestionWaits()
        val wait = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", wait)

        assertTrue(waits.skip("event-1", listOf("q1", "q2")))

        val outcome = outcomeOf(wait)
        assertTrue("skipping tells the host, it does not vanish", outcome.deliver)
        assertTrue("the handler knows this is the outcome the host may refuse", outcome.skip)
        assertEquals(
            "a skip is the answer vocabulary, not the approval hand-back",
            "result",
            outcome.payload.optString("kind"),
        )

        val answers = answersOf(outcome) ?: error("the skip carries no answers array")
        assertEquals("one answer item per question of the set", 2, answers.length())
        listOf("q1", "q2").forEachIndexed { index, id ->
            val item = answers.optJSONObject(index) ?: error("answer $index is not an object")
            assertEquals(id, item.optString("id"))
            assertEquals(
                "a skipped question selects nothing",
                0,
                item.optJSONArray("selected")?.length() ?: -1,
            )
            assertFalse(
                "a skipped question carries no custom answer",
                item.has("custom"),
            )
        }
    }

    @Test
    fun `a skip leaves the slot for the handler to claim`() {
        val waits = QuestionWaits()
        val wait = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", wait)

        assertTrue(waits.skip("event-1", listOf("q1")))
        assertTrue(
            "the card is claimed by the handler only once the host has the batch",
            waits.owns("event-1", wait),
        )
        assertFalse("a second tap cannot post the skip twice", waits.skip("event-1", listOf("q1")))
        assertTrue("the handler claims the card", waits.release("event-1", wait))
        assertFalse("and the claim is spent", waits.release("event-1", wait))
    }

    @Test
    fun `a re-announcement after a skip wins the card from it`() {
        val waits = QuestionWaits()
        val skipped = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", skipped)
        assertTrue(waits.skip("event-1", listOf("q1")))

        val fresh = CompletableDeferred<QuestionWaits.Outcome>()
        waits.install("event-1", fresh)

        assertTrue("the skip was still deliverable", outcomeOf(skipped).deliver)
        assertTrue("the re-announcement owns the card", waits.owns("event-1", fresh))
        assertFalse(
            "the skipped wait can no longer take the replacement's card down",
            waits.release("event-1", skipped),
        )
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
        assertFalse("nothing is left to skip", waits.skip("event-2", listOf("q1")))
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
