package uk.xa0.dsh.model

import kotlinx.coroutines.CompletableDeferred
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The question waits this client is parked on, keyed by the host's `eventId`.
 *
 * A question is a waterfall: the agent blocks until this client posts an outcome to
 * `$events/result`, and the host settles the waterfall with the first outcome it
 * takes. A wait can be woken by four things, and each has to say for itself whether
 * anything still has to be sent:
 *
 *  * the reader answered — [answer] takes the slot and completes the wait with a
 *    deliverable result, so the handler posts it;
 *  * the reader skipped — [skip] completes the wait with a deliverable result as
 *    well, but leaves the slot parked: a skip is the one outcome the host can refuse
 *    outright, so its handler claims the card with [release] only once the post has
 *    been accepted;
 *  * a re-announcement arrived on a new `$events` generation — [install] replaces the
 *    wait, because the answer now has to travel on the new generation's socket, and
 *    hands the replaced one back with nothing to send;
 *  * the host withdrew the waterfall, or the centre is stopping — [withdraw] and
 *    [withdrawAll] end the wait with nothing to send, because the host has settled it
 *    or the socket it would send on is being abandoned.
 *
 * Ownership of the slot is the *card's* token: a handler may only take the card down
 * while it is still the slot's value, so a replaced wait cannot take down the card its
 * replacement has just raised. It is deliberately **not** the test for whether to send.
 * Answering takes the slot as it takes the answer, so a handler inferring "was I
 * replaced?" from the slot alone reads every reader's answer as a replacement and drops
 * it — which is exactly the regression that made questions unanswerable. Delivery is
 * therefore carried by [Outcome.deliver], decided at the moment the wait is woken, and
 * can never be confused with a hand-back.
 */
class QuestionWaits {

    /**
     * What a woken wait is to do.
     *
     * [payload] is the outcome for `$events/result`. [deliver] is false only for a
     * hand-back: that completion exists to end a handler, not to answer anything, and
     * sending it would decline a question the host is still holding.
     *
     * [skip] is true only for a reader's skip. Its payload is a result batch like an
     * answer's, but unlike an answer it may be refused by the host: the centre keeps
     * the card up until the post comes back accepted, and puts it back with the
     * reason when it does not.
     */
    class Outcome(
        val payload: JSONObject,
        val deliver: Boolean,
        val skip: Boolean = false,
    )

    private val slots = ConcurrentHashMap<String, CompletableDeferred<Outcome>>()

    /**
     * Parks [wait] for [eventId] and hands back the wait it replaced.
     *
     * The slot is written before the replaced wait is completed, so when its handler
     * wakes it can only ever see the new wait in the slot.
     */
    fun install(eventId: String, wait: CompletableDeferred<Outcome>): CompletableDeferred<Outcome>? {
        val previous = slots.put(eventId, wait)
        previous?.complete(handBack())
        return previous
    }

    /**
     * Delivers the reader's answer, taking the slot as it does so.
     *
     * Returns false when no wait is parked, which is a card that was already answered,
     * withdrawn or replaced: there is then nothing to send and nothing to clear.
     */
    fun answer(eventId: String, payload: JSONObject): Boolean =
        slots.remove(eventId)?.complete(Outcome(payload, deliver = true)) ?: false

    /**
     * Delivers the reader's skip: a result batch whose every question selects nothing.
     *
     * The batch is the web's own encoding of a skipped set
     * (`dsh-client-ui-user-questions/lib/client.js`, `submitDrafts`: a skipped draft
     * becomes `{id, selected: []}` with no `custom`), and the host documents that
     * shape as a skipped answer (`dsh-tool-ask-user/lib/index.js`: "A submitted
     * skipped question is an answer item with empty selected and no custom"). It is
     * emphatically not `{kind:"next"}`: the gateway reads that as "decline this
     * answerer and ask the next one" (`dsh-api-remotes/lib/index.js`,
     * `forwardWaterfall`), and with no other answerer the host fails the request with
     * `NO_PROVIDER`, which a timed wait turns into a full-timeout `pending` result.
     *
     * Unlike [answer], the slot is left in place for the handler to claim with
     * [release]. A refusal must not be able to clear the card, so the card comes down
     * only once the host has answered the post `ok`, and a second tap finds the wait
     * already completed and does nothing.
     */
    fun skip(eventId: String, questionIds: List<String>): Boolean =
        slots[eventId]?.complete(Outcome(skipped(questionIds), deliver = true, skip = true)) == true

    /** The waterfall is settled elsewhere, so its wait ends without sending anything. */
    fun withdraw(eventId: String): Boolean =
        slots.remove(eventId)?.complete(handBack()) ?: false

    /** Ends every parked wait without sending; the centre is being torn down. */
    fun withdrawAll() {
        val waiting = slots.values.toList()
        slots.clear()
        waiting.forEach { it.complete(handBack()) }
    }

    /** Whether [wait] is still the slot's value, so it may take the card down. */
    fun owns(eventId: String, wait: CompletableDeferred<Outcome>): Boolean =
        slots[eventId] === wait

    /**
     * Gives the slot up if [wait] still owns it.
     *
     * Compare-and-remove, so a handler returning from the cap cannot displace or take
     * down a wait that a re-announcement installed while it was waking.
     */
    fun release(eventId: String, wait: CompletableDeferred<Outcome>): Boolean =
        slots.remove(eventId, wait)

    private fun handBack(): Outcome = Outcome(next(), deliver = false)

    private fun next(): JSONObject = JSONObject().put("kind", "next")

    /**
     * The result batch for a set the reader skipped: every question selects nothing.
     *
     * `answerSchema` in `dsh-user-questions` is strict, so this carries exactly `id`
     * and `selected` — no `custom`, matching the web's skipped draft.
     */
    private fun skipped(questionIds: List<String>): JSONObject {
        val answers = JSONArray()
        questionIds.forEach { id ->
            answers.put(JSONObject().put("id", id).put("selected", JSONArray()))
        }
        return JSONObject()
            .put("kind", "result")
            .put("value", JSONObject().put("answers", answers))
    }
}
