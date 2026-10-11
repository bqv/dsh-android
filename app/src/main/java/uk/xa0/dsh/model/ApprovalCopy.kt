package uk.xa0.dsh.model

/**
 * The words an approval card uses, in one place, so the card and the shade entry it
 * re-posts cannot drift apart.
 *
 * ## Why the claim is short
 *
 * `Sent` is transient: [ApprovalGate] holds it for the settle grace (1.5 s, or less
 * if the host's own `cancel` arrives) and the accepted reply has already settled the
 * waterfall, so what the reader sees in that state is on screen for about a second
 * and a half and then goes. A sentence long enough to wrap therefore arrives, moves
 * the card and leaves again, twice per approval — which is worse than saying nothing.
 *
 * So the claim is a short label with a fixed-size mark beside it, and the card gives
 * the label a fixed single line in *every* state (see `ApprovalStatusStrip` in
 * `ui/components/ChatRows.kt`). No wording here can move the card, because a one-line
 * region cannot be moved by a word: it cannot wrap, and a wider word has nowhere to
 * push anything — the card is already as wide as the screen.
 * [STATUS_BUDGET] is the character budget that keeps the settled and in-flight
 * labels distinctively short, and `model/ApprovalCopyTest` fails if a future copy
 * change blows it. The budget is a tripwire for the next editor, not a proof of fit:
 * the JVM harness cannot measure text (`docs/HANDOFF.md`).
 *
 * ## What each state claims
 *
 * The four states are deliberate (`docs/HANDOFF.md`) and none of them is collapsed
 * into another here:
 *
 *  * [ApprovalAnswer.Waiting] — nothing has been sent, so there is no claim to make
 *    and the strip draws nothing at all. It is still *laid out*, which is what stops
 *    the card moving when the reader taps.
 *  * [ApprovalAnswer.Answering] — a tap is on its way and no reply has come back.
 *    Deliberately not "sent": nothing has been confirmed.
 *  * [ApprovalAnswer.Sent] — the host answered the post `ok`, so the waterfall is
 *    settled and the card has at most the grace left. "The host has it" is the whole
 *    of that claim. It cannot be read as still waiting, and it cannot be read as done
 *    before the reply came, because it is written only after the accepted reply.
 *    *Which* answer the host took is deliberately not recapped: the reader chose it
 *    one and a half seconds ago on a button that is now inert, so the claim that
 *    still needs making is about the host, and naming the choice again is what made
 *    the old sentence long. `Sent(true)` and `Sent(false)` are one state; neither is
 *    dropped, and [ApprovalAnswer.Sent] still carries the choice for anything that
 *    needs it.
 *  * [ApprovalAnswer.Unsent] — the post was refused or never arrived and the card is
 *    answerable again. The host's own words are carried here, which is why this is
 *    the one label allowed past [STATUS_BUDGET]: the strip ellipsises it rather than
 *    growing, and the shade entry repeats it in full for a reader who is not looking
 *    at the card.
 */
object ApprovalCopy {

    /**
     * The card's one-line claim for [answer], or null when there is nothing to claim.
     *
     * Single-line by construction: no case contains a newline, which matters because
     * a newline is the one thing in the copy that could defeat a fixed-height strip.
     * `model/ApprovalCopyTest` checks for it.
     */
    fun status(answer: ApprovalAnswer): String? = when (answer) {
        ApprovalAnswer.Waiting -> null
        is ApprovalAnswer.Answering -> "Sending…"
        is ApprovalAnswer.Sent -> "The host has it"
        is ApprovalAnswer.Unsent -> "Not sent: ${answer.message}"
    }

    /**
     * The shade entry for an answer the host has not taken.
     *
     * The same fact as [status]'s `Unsent` claim, without the card's one-line budget:
     * whoever reads this is not looking at the card. Both quote
     * [ApprovalAnswer.Unsent.message] verbatim, which is what keeps them consistent —
     * `model/ApprovalCopyTest` asserts it.
     */
    fun unsentNotificationText(toolName: String, answer: ApprovalAnswer.Unsent): String =
        "$toolName is still waiting — ${answer.message}."

    /** The shade entry's title for the same fact, so the two never have to agree twice. */
    const val UNSENT_TITLE = "Approval answer not sent"

    /**
     * The longest a settled or in-flight claim may be, so that one line stays short
     * enough to be read at a glance and to stay distinct from the state beside it.
     *
     * A chosen budget rather than a measurement — the JVM harness levels every string
     * to one line, so it cannot say what fits on a phone. 24 characters is about
     * 160 dp of 13 sp text, comfortably inside the card on any handset, and it leaves
     * room for a longer word without turning the strip into a sentence again.
     */
    const val STATUS_BUDGET = 24
}
