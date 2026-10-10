package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a branch cuts the source log.
 *
 * `session/fork`'s `atSeq` is an **inclusive event seq** that must name an event the
 * host already holds: one that does not is refused (`session/fork-unavailable`), but
 * one that names the *wrong* event forks the wrong conversation and says nothing at
 * all. So the reducer has to hand the screen exactly the boundary the web's
 * `forkAt(seq)` computes — the closing turn's own `turn/end` seq, for each completed
 * turn that has a closing text answer — and nothing for a turn it cannot prove.
 *
 * These are the mobile half of that contract; the other half is the wire, and these
 * are the same numbers the web passes as `atSeq`.
 */
class BranchBoundaryTest {

    private fun turnStart(turn: Int, seq: Int) = JSONObject()
        .put("type", "turn/start")
        .put("seq", seq)
        .put("time", 1000L)
        .put("data", JSONObject().put("turn", turn))

    private fun assistant(turn: Int, step: Int, seq: Int, text: String) = JSONObject()
        .put("type", "assistant/message")
        .put("seq", seq)
        .put("time", 1000L)
        .put(
            "data",
            JSONObject()
                .put("turn", turn)
                .put("step", step)
                .put(
                    "message",
                    JSONObject()
                        .put("role", "assistant")
                        .put(
                            "content",
                            JSONArray().put(JSONObject().put("type", "text").put("text", text)),
                        ),
                ),
        )

    private fun toolCall(turn: Int, seq: Int) = JSONObject()
        .put("type", "tool/call")
        .put("seq", seq)
        .put("time", 1000L)
        .put(
            "data",
            JSONObject()
                .put("turn", turn)
                .put("callId", "call_$seq")
                .put("name", "bash")
                .put("arguments", "{}"),
        )

    private fun turnEnd(turn: Int, seq: Int) = JSONObject()
        .put("type", "turn/end")
        .put("seq", seq)
        .put("time", 1000L)
        .put(
            "data",
            JSONObject()
                .put("turn", turn)
                .put("reason", JSONObject().put("kind", "completed")),
        )

    @Test
    fun `each completed turn branches at its own turn end, not at the newest`() {
        val reducer = TranscriptReducer()
        reducer.applyEvent(turnStart(1, 0))
        reducer.applyEvent(assistant(1, 1, 1, "the first conclusion"))
        reducer.applyEvent(turnEnd(1, 2))
        reducer.applyEvent(turnStart(2, 3))
        reducer.applyEvent(assistant(2, 1, 4, "the second conclusion"))
        reducer.applyEvent(turnEnd(2, 5))

        assertEquals(
            // The answer's own seq → the `turn/end` seq that closed its turn. The
            // older conclusion keeps its own boundary: branching is not a
            // latest-turn-only verb any more.
            mapOf(1 to 2, 4 to 5),
            reducer.closingBranchSeqs(),
        )
    }

    @Test
    fun `the boundary is the turn end, not the closing message`() {
        val reducer = TranscriptReducer()
        reducer.applyEvent(turnStart(1, 0))
        reducer.applyEvent(assistant(1, 1, 1, "done"))
        reducer.applyEvent(turnEnd(1, 2))

        // 1 is the answer; 2 is the boundary the host copies through. Passing 1
        // would inherit a turn with no ending, which the host would balance with its
        // synthetic `forked` events instead of ending on the turn the reader chose.
        assertEquals(mapOf(1 to 2), reducer.closingBranchSeqs())
    }

    @Test
    fun `a turn the host has not closed offers no branch`() {
        val reducer = TranscriptReducer()
        reducer.applyEvent(turnStart(1, 0))
        reducer.applyEvent(assistant(1, 1, 1, "still going"))

        // No `turn/end` has been seen, so there is no seq the host would accept.
        assertTrue(reducer.closingBranchSeqs().isEmpty())
    }

    @Test
    fun `a turn with no text answer offers no branch`() {
        val reducer = TranscriptReducer()
        reducer.applyEvent(turnStart(1, 0))
        reducer.applyEvent(toolCall(1, 1))
        reducer.applyEvent(turnEnd(1, 2))

        // The web's `closing === null`: its action row is omitted entirely, so there
        // is no row to hang a branch button on here either.
        assertTrue(reducer.closingAssistantSeqs().isEmpty())
        assertTrue(reducer.closingBranchSeqs().isEmpty())
    }

    @Test
    fun `a tool call after the answer does not move the boundary`() {
        val reducer = TranscriptReducer()
        reducer.applyEvent(turnStart(1, 0))
        reducer.applyEvent(assistant(1, 1, 1, "I will check"))
        reducer.applyEvent(toolCall(1, 2))
        reducer.applyEvent(turnEnd(1, 3))

        // Deliberately more permissive than the web, whose `branchUnavailable` hides
        // the button once the answer is no longer the turn's last row: this cut is
        // always the turn boundary, so the child is a clean completed-turn prefix and
        // there is no branch point mid-turn for the reader to be misled about.
        assertEquals(mapOf(1 to 3), reducer.closingBranchSeqs())
    }

    @Test
    fun `a later answerless step does not take the closing row`() {
        val reducer = TranscriptReducer()
        reducer.applyEvent(turnStart(1, 0))
        reducer.applyEvent(assistant(1, 1, 1, "here is the answer"))
        // A step that only calls tools commits an assistant message with no prose.
        reducer.applyEvent(assistant(1, 2, 2, ""))
        reducer.applyEvent(turnEnd(1, 3))

        // The action row hangs on the prose, so both the footer and the branch must
        // key off seq 1: a step with nothing to say is not a row either is drawn on.
        assertEquals(setOf(1), reducer.closingAssistantSeqs())
        assertEquals(mapOf(1 to 3), reducer.closingBranchSeqs())
    }

    @Test
    fun `a reset forgets the boundaries`() {
        val reducer = TranscriptReducer()
        reducer.applyEvent(turnStart(1, 0))
        reducer.applyEvent(assistant(1, 1, 1, "done"))
        reducer.applyEvent(turnEnd(1, 2))
        assertEquals(mapOf(1 to 2), reducer.closingBranchSeqs())

        // Opening another session clears the reducer; a boundary that outlived its
        // transcript would fork whatever session is open next at a seq from this one.
        reducer.reset()
        assertTrue(reducer.closingBranchSeqs().isEmpty())
    }
}
