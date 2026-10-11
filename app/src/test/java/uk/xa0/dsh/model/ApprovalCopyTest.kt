package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the approval card and its shade entry use.
 *
 * This is a *copy* test with a layout job. The card's state line is a fixed one-line
 * region (`components/ChatRows.kt`), so the one thing the copy could still do to move
 * the card is to contain a line break — checked here — and the one thing it could do
 * to stop being readable at a glance, or to start looking like another state, is to
 * grow back into a sentence. [ApprovalCopy.STATUS_BUDGET] is that tripwire, and the
 * states' claims are checked to be distinct from each other.
 *
 * ## What this cannot prove
 *
 * Nothing here measures text: the JVM harness levels every string to one line
 * whatever it says (`docs/HANDOFF.md`). So "it fits on a phone" and "it does not
 * wrap" are **not** proven — they are *bounded by construction* in the card (fixed
 * height, `maxLines = 1`, ellipsis) and kept short here. The wording assertions
 * below are about the claim each state makes, which is a copy decision, not a
 * measurement.
 */
class ApprovalCopyTest {

    private val message = "it did not reach the host — no route"
    private val states = mapOf(
        "waiting" to ApprovalAnswer.Waiting,
        "answering" to ApprovalAnswer.Answering(true),
        "sent" to ApprovalAnswer.Sent(true),
        "unsent" to ApprovalAnswer.Unsent(true, message),
    )

    @Test
    fun `the card says something for every state but waiting`() {
        assertNull(
            "nothing has been sent, so there is no claim to make",
            ApprovalCopy.status(ApprovalAnswer.Waiting),
        )
        assertEquals("Sending…", ApprovalCopy.status(ApprovalAnswer.Answering(true)))
        assertEquals("The host has it", ApprovalCopy.status(ApprovalAnswer.Sent(true)))
        assertEquals("Not sent: $message", ApprovalCopy.status(ApprovalAnswer.Unsent(true, message)))
    }

    @Test
    fun `no claim carries a line break the strip cannot show`() {
        states.forEach { (name, state) ->
            val claim = ApprovalCopy.status(state) ?: return@forEach
            assertFalse("a $name claim must not have a newline", claim.contains('\n'))
            assertFalse("a $name claim must not have a carriage return", claim.contains('\r'))
        }
    }

    @Test
    fun `the settled and in-flight claims stay inside the budget`() {
        listOf("answering" to ApprovalAnswer.Answering(true), "sent" to ApprovalAnswer.Sent(true))
            .forEach { (name, state) ->
                val claim = ApprovalCopy.status(state)!!
                assertTrue(
                    "the $name claim is ${claim.length} characters, past the ${
                        ApprovalCopy.STATUS_BUDGET
                    }-character budget that keeps the card's line short: \"$claim\"",
                    claim.length <= ApprovalCopy.STATUS_BUDGET,
                )
            }
    }

    @Test
    fun `the unsent claim is the one allowed past the budget, because it is a quote`() {
        val long = ApprovalAnswer.Unsent(true, "a host message long enough to be ellipsised on the card")
        val claim = ApprovalCopy.status(long)!!
        assertTrue(
            "the host's own words are carried rather than bent to fit; the strip ellipsises them",
            claim.length > ApprovalCopy.STATUS_BUDGET,
        )
        assertTrue("the host's message is quoted verbatim", claim.contains(long.message))
    }

    @Test
    fun `the four states cannot be read as each other`() {
        val claims = states.mapValues { (_, state) -> ApprovalCopy.status(state) }
        // Waiting's claim is the absence of one; the other three are distinct and
        // none is a prefix of another, so the strip can never be read as two states.
        val named = claims.filterValues { it != null }.values.map { it!! }
        assertEquals("three states have a claim", 3, named.size)
        assertEquals("they are distinct", 3, named.toSet().size)
        named.forEach { a ->
            named.filter { it != a }.forEach { b ->
                assertFalse("\"$a\" must not be a prefix of \"$b\"", b.startsWith(a))
            }
        }
    }

    @Test
    fun `a sent claim is about the host having it, not about the wait`() {
        // The wording is the state's whole claim, so it is asserted rather than left
        // to a reader of the diff: an answer in flight must not read as taken, and a
        // taken one must not read as still waiting.
        val answering = ApprovalCopy.status(ApprovalAnswer.Answering(true))!!
        val sent = ApprovalCopy.status(ApprovalAnswer.Sent(true))!!
        assertFalse("an in-flight answer has not been taken by anyone", answering.contains("host"))
        assertTrue("a taken answer names the host that took it", sent.contains("host"))
        assertFalse("a taken answer does not read as still on its way", sent.contains("Sending"))
    }

    @Test
    fun `the card and the shade quote the same failure`() {
        val unsent = ApprovalAnswer.Unsent(false, message)
        val shade = ApprovalCopy.unsentNotificationText("shell", unsent)
        assertTrue(
            "the shade repeats the card's message verbatim",
            ApprovalCopy.status(unsent)!!.contains(unsent.message),
        )
        assertTrue("the shade repeats it too", shade.contains(unsent.message))
        assertTrue("the shade names the tool that is still blocked", shade.contains("shell"))
        assertTrue(
            "the shade's title says the same thing as the card's claim",
            ApprovalCopy.UNSENT_TITLE.contains("not sent"),
        )
        assertTrue(
            "and so does the claim itself",
            ApprovalCopy.status(unsent)!!.contains("Not sent"),
        )
    }

    @Test
    fun `a hand-back that failed is a claim like any other`() {
        // The gate's third failure has no `allow` and its own message; it must not be
        // a case this copy forgot, or the strip would draw an empty line beside a
        // warning mark.
        val claim = ApprovalCopy.status(
            ApprovalAnswer.Unsent(
                null,
                "the request timed out and could not be handed back to the host",
            ),
        )!!
        assertTrue("a hand-back failure says it was not sent", claim.startsWith("Not sent: "))
    }
}
