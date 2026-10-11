package uk.xa0.dsh.model

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * What the host did with one attempt to deliver a waterfall outcome.
 *
 * Three cases rather than the one a `try`/`catch` around the call gives you, because
 * a reader has to be told which happened: a host that understood the answer and
 * declined it is a different fact from a phone that never reached the host, and only
 * one of them is worth trying again unchanged.
 */
sealed interface AnswerDelivery {

    /**
     * The host answered the result call `ok`.
     *
     * For an approval's `{kind:"result"}` post that is the host's own word that the
     * waterfall has settled, and it is the confirmation the card rests on. The
     * gateway applies the outcome *before* it replies: `dispatchRpc`
     * (`dsh-api-gateway/lib/index.js`, `$events/result` branch) calls
     * `receiveRemoteEventResult` synchronously and only then returns `{ok:true}`,
     * and that function settles a `result` outcome on the spot.
     *
     * It is not the same statement as "this answer was the one taken". A post for
     * an event that is no longer pending — or from a client that is no longer a
     * delivery of it — is answered `ok` too, because that no-op path returns without
     * throwing. Both readings mean the same thing for the card, though: the host is
     * not holding the waterfall any more. The one thing `ok` does not cover is a
     * stale `clientId`, which fails the call outright (the host finds no active
     * event stream for it) and arrives here as a [Refused].
     */
    data object Accepted : AnswerDelivery

    /**
     * The host answered the answer call itself with a structured error, so the
     * outcome was understood and declined.
     */
    data class Refused(val message: String) : AnswerDelivery

    /**
     * No answer came back at all — no route, a dead socket, an expired session.
     * Whether the host saw the outcome is unknown.
     */
    data class Unreachable(val message: String) : AnswerDelivery
}

/**
 * What an approval card should say, and what it may offer.
 *
 * - [Waiting]: nothing has been sent. Both buttons answer.
 * - [Answering]: a tap is being posted right now. Neither button answers.
 * - [Sent]: the host answered the post `ok`, so the waterfall is settled and the
 *   card has at most a bounded grace left. Neither button answers. The host's own
 *   `cancel` ends it at once if one comes; otherwise the grace runs out.
 * - [Unsent]: the post was refused, or never arrived, with [message] saying so.
 *   Both buttons answer again.
 */
sealed interface ApprovalAnswer {

    /** Nothing has been sent yet: the ordinary card. */
    data object Waiting : ApprovalAnswer

    /** A tap is on its way to the host. */
    data class Answering(val allow: Boolean) : ApprovalAnswer

    /**
     * The host took the answer. The card is up for the settle grace and no longer:
     * the host's reply has already said the waterfall is gone.
     */
    data class Sent(val allow: Boolean) : ApprovalAnswer

    /**
     * The answer did not get through. [allow] is what the reader chose, or null
     * when there was no choice (the cap expired and the hand-back itself failed).
     */
    data class Unsent(val allow: Boolean?, val message: String) : ApprovalAnswer
}

/**
 * One approval waterfall's answer, attempt by attempt, with no Android and no socket.
 *
 * ## The defect this replaces
 *
 * The accept used to complete the very deferred the send was waiting on and then
 * clear the card and its notification on the same synchronous call — before anything
 * had been posted. The POST's reply was never inspected, so a failure was one `Log.d`
 * line, and the reader had been shown a cleared card for an answer the agent never
 * received.
 *
 * The deferred could not simply be left alone, because completing it *is* the send
 * trigger: a one-shot deferred consumed by the send cannot be re-armed, which is why
 * the fix is here rather than in the caller. What owns the answer is a queue, not a
 * latch:
 *
 *  1. [tap] only moves the machine to [ApprovalAnswer.Answering] and queues the
 *     request. It clears nothing, and it refuses a second tap, so the buttons are
 *     genuinely not re-tappable rather than merely looking that way.
 *  2. [answer] hands the outcome to the caller's `send` and waits to hear what the
 *     host did with it.
 *  3. A refusal or a failed delivery returns the machine to a tappable state with
 *     [ApprovalAnswer.Unsent], and the queue outlives the attempt — that is what
 *     makes the retry possible at all.
 *  4. An accepted post leaves the machine in [ApprovalAnswer.Sent] for a bounded
 *     **settle grace** and then ends the wait, because the accepted reply itself is
 *     the confirmation that the waterfall is settled. The card is not left waiting
 *     for a second signal.
 *
 * ## Why the grace, and why not the `cancel`
 *
 * The earlier design made the host's `{type:"cancel"}` the only clear, on the
 * belief that the host pushes one after it takes an answer. It does not push one to
 * the client that answered: `receiveRemoteEventResult` removes the answering client
 * from `pending.deliveries` *before* `finishRemoteEvent` builds its recipient set
 * from that same map, so the cancel goes only to the *other* clients a waterfall was
 * fanned out to. On a phone that is the only client, the recipient set is empty and
 * no cancel is ever emitted — which is why a card could sit there until the process
 * was restarted.
 *
 * The clear is therefore the accepted reply, which the host sends only after it has
 * applied the outcome, and the grace is what the ordering assumption gets to hold
 * in: a `cancel` that *does* arrive (another answerer got there first, or the host's
 * own timeout fired) ends the wait at once, and one that never arrives costs only
 * the grace. Nothing is speculated: the reply has already said the waterfall is
 * gone.
 *
 * ## What it deliberately is not
 *
 * It does not know about notifications, cards or sessions: those are
 * [uk.xa0.dsh.AttentionCenter]'s. It does not decide the cap's *effect* either — it
 * hands the request back through the same `send`, so a hand-back that does not arrive
 * is as visible as an answer that does not.
 */
class ApprovalGate(
    private val timeoutMs: Long,
    /**
     * How long [ApprovalAnswer.Sent] is held after an accepted answer, with a
     * `cancel` cutting it short. Bounded rather than indefinite: the reply that
     * produced the state is already the confirmation.
     */
    private val settleGraceMs: Long,
) {

    private sealed interface Event {
        data class Tapped(val allow: Boolean) : Event
        data object Withdrawn : Event
    }

    /**
     * Requests, not state: a tap is queued and taken by [answer]'s next iteration.
     *
     * One queue for the whole waterfall, not one per attempt, is the point. A refused
     * answer leaves the queue in place and loops the wait, so the reader's next tap is
     * delivered to the same machine — and a host withdrawal queued while a post is in
     * flight is taken the moment the post returns, rather than being lost against a
     * consumed latch.
     */
    private val events = Channel<Event>(Channel.UNLIMITED)

    /**
     * The state the card should be drawn in.
     *
     * Written synchronously by [tap] before anything else happens, so the card enters
     * its answering state on the same frame as the tap; and by [answer] as the host
     * replies. Volatile because [tap] runs on the UI thread while [answer] runs on
     * whichever dispatcher the waterfall handler was started from.
     */
    @Volatile
    var state: ApprovalAnswer = ApprovalAnswer.Waiting
        private set

    /** Told about every transition, so the card can be republished from one place. */
    @Volatile
    var onState: ((ApprovalAnswer) -> Unit)? = null

    /**
     * The reader tapped. Answers false when the tap is inert.
     *
     * The refusal is the guarantee the card's disabled buttons only illustrate: a
     * second tap while an answer is in flight, or after the host has taken one, never
     * reaches [answer].
     */
    fun tap(allow: Boolean): Boolean {
        val current = state
        if (current !is ApprovalAnswer.Waiting && current !is ApprovalAnswer.Unsent) return false
        move(ApprovalAnswer.Answering(allow))
        events.trySend(Event.Tapped(allow))
        return true
    }

    /**
     * The host withdrew this waterfall: its own timeout, another answerer got
     * there first, or the host is settling it now that the answer has landed.
     *
     * It ends the wait at once, from either a fresh wait or the settle grace. The
     * caller is left to drop the card and its notification — or to find that it
     * already has, because a host that takes the card down this way does so through
     * the same handler.
     */
    fun withdraw() {
        events.trySend(Event.Withdrawn)
    }

    /**
     * Runs until this waterfall is settled, posting outcomes through [send].
     *
     * Returns only once there is nothing left to send: the host withdrew it, the cap
     * expired and the request was handed back, or an answer was accepted and its
     * settle grace ran out. A refusal or a failed delivery loops to a fresh wait
     * instead, so a failure stays answerable rather than being swallowed.
     */
    suspend fun answer(send: suspend (JSONObject) -> AnswerDelivery) {
        while (true) {
            val event = withTimeoutOrNull(timeoutMs) { events.receive() }
                // A tap can land in the same instant the cap fires. A person's decision
                // outranks the clock, so the queue is drained once more before the
                // request is handed back — otherwise that tap would be the one silent
                // loss this class exists to remove.
                ?: events.tryReceive().getOrNull()
            if (event == null) {
                // The cap is the escape hatch, not the policy: a pocketed phone must
                // not hang the agent forever, so the request is handed back untouched
                // and the host refuses it in its own words. A hand-back that does not
                // arrive keeps the card up rather than leaving a blocked agent with
                // nothing on screen, and the next cap tries again — a dead socket is
                // the usual reason, and the moment it is back the agent is released
                // without the reader having to do anything. The reader is told once:
                // `move` ignores a repeat of the state it is already in.
                if (send(delegated()) is AnswerDelivery.Accepted) return
                move(ApprovalAnswer.Unsent(null, HAND_BACK_FAILED))
                continue
            }
            when (event) {
                Event.Withdrawn -> return
                is Event.Tapped -> {
                    when (val delivery = send(outcomeFor(event.allow))) {
                        is AnswerDelivery.Accepted -> {
                            move(ApprovalAnswer.Sent(event.allow))
                            // The reply is the confirmation, so the wait ends here —
                            // after the grace, or sooner if a `cancel` arrives. Only a
                            // withdrawal can be queued while the machine is `Sent`,
                            // because `tap` refuses every state but Waiting and Unsent.
                            withTimeoutOrNull(settleGraceMs) { events.receive() }
                            return
                        }

                        is AnswerDelivery.Refused ->
                            move(ApprovalAnswer.Unsent(event.allow, "the host refused it — ${delivery.message}"))

                        is AnswerDelivery.Unreachable ->
                            move(ApprovalAnswer.Unsent(event.allow, "it did not reach the host — ${delivery.message}"))
                    }
                    // Falls through to another wait, with the queue intact.
                }
            }
        }
    }

    private fun move(next: ApprovalAnswer) {
        // A state the machine is already in is not news: re-announcing it would buzz a
        // pocketed phone again for a hand-back that has already failed once.
        if (next == state) return
        state = next
        onState?.invoke(next)
    }

    private fun outcomeFor(allow: Boolean): JSONObject = JSONObject()
        .put("kind", "result")
        .put("value", if (allow) "allowed-once" else "rejected")

    private fun delegated(): JSONObject = JSONObject().put("kind", "next")

    private companion object {
        /** Says the answer is still outstanding, in the card's own words. */
        const val HAND_BACK_FAILED =
            "the request timed out and could not be handed back to the host"
    }
}
