package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transcript's row set — what exists to be scrolled, and what the
 * "Conversation display" setting is allowed to take away.
 *
 * The fold is the one mechanism in this app that removes rows the host sent, so
 * it is the one place where a mistake looks like the model never said something.
 */
class TurnProcessTest {

    private fun user(seq: Int, text: String) = ChatEntry.UserMessage(
        seq = seq, id = "u$seq", text = text, time = 0L, fromPlugin = false, summary = null,
    )

    private fun assistant(seq: Int, turn: Int, text: String, reasoning: String = "") =
        ChatEntry.AssistantMessage(
            seq = seq, turn = turn, step = 1, reasoning = reasoning, text = text, time = 0L,
        )

    private fun tool(seq: Int, turn: Int) = ChatEntry.ToolCall(
        seq = seq, callId = "c$seq", name = "bash", arguments = "{}", result = "ok",
        isError = false, time = 0L, turn = turn,
    )

    /** One closed turn: prompt, an interim message, a tool call, then the answer. */
    private val closedTurn = listOf(
        user(1, "do the thing"),
        assistant(2, turn = 1, text = "starting"),
        tool(3, turn = 1),
        assistant(4, turn = 1, text = "done"),
    )

    private fun texts(rows: List<DisplayRow>): List<String> = rows.map { row ->
        when (row) {
            is DisplayRow.Single -> when (val e = row.entry) {
                is ChatEntry.AssistantMessage -> "a:${e.text}"
                is ChatEntry.UserMessage -> "u:${e.text}"
                is ChatEntry.ToolCall -> "tool"
                is ChatEntry.Todos -> "todos"
                is ChatEntry.Notice -> "notice:${e.kind}"
            }

            is DisplayRow.TurnProcess -> "summary"
            is DisplayRow.Live -> "live"
        }
    }

    @Test
    fun `compact folds a closed turn's process behind its summary`() {
        val rows = buildDisplayRows(closedTurn, endedTurns = setOf(1), expandedTurns = emptySet(), live = null)
        assertEquals(listOf("u:do the thing", "summary", "a:done"), texts(rows))
    }

    @Test
    fun `compact keeps every row once the turn is expanded`() {
        val rows = buildDisplayRows(closedTurn, endedTurns = setOf(1), expandedTurns = setOf(1), live = null)
        // The summary stands in at the position of the first process member, and
        // the members follow it rather than being dropped.
        assertEquals(listOf("u:do the thing", "summary", "a:starting", "tool", "a:done"), texts(rows))
    }

    @Test
    fun `normal folds nothing in a closed turn`() {
        // The point of the switch: `ui-chat.transcriptView = normal` is documented
        // as "never folds", and this client used to fold anyway and report
        // `compact` to the settings row regardless of what the host stored.
        val rows = buildDisplayRows(
            closedTurn, endedTurns = setOf(1), expandedTurns = emptySet(), live = null, fold = false,
        )
        assertEquals(listOf("u:do the thing", "a:starting", "tool", "a:done"), texts(rows))
    }

    @Test
    fun `normal keeps the closing answer's reasoning`() {
        // Folding strips the closing message's reasoning, because the fold is what
        // stands in for it. Nothing folds, so nothing is stripped.
        val entries = listOf(
            user(1, "go"),
            assistant(2, turn = 1, text = "answer", reasoning = "because"),
        )
        val folded = buildDisplayRows(entries, setOf(1), emptySet(), null)
        val flat = buildDisplayRows(entries, setOf(1), emptySet(), null, fold = false)
        fun reasoningOf(rows: List<DisplayRow>): String = rows
            .mapNotNull { (it as? DisplayRow.Single)?.entry as? ChatEntry.AssistantMessage }
            .single()
            .reasoning

        assertEquals("", reasoningOf(folded))
        assertEquals("because", reasoningOf(flat))
    }

    @Test
    fun `normal does not fold a turn that is still running either`() {
        val rows = buildDisplayRows(closedTurn, emptySet(), emptySet(), null, fold = false)
        assertTrue("summary" !in texts(rows))
    }

    @Test
    fun `a turn on screen is not folded, however the reader got there`() {
        // The complaint in its own words: the rows being read must not close when the
        // turn they belong to ends.
        val folded = nextFoldedTurns(
            current = emptySet(),
            ended = setOf(1, 2, 3),
            onScreen = setOf(2),
            following = true,
        )
        assertEquals(setOf(1, 3), folded)
    }

    @Test
    fun `the turn that just ended is not folded while it is the one being read`() {
        // Following is not a reason to fold: at the tail, the turn that just ended is
        // the one under the reader's eye as its closing message lands.
        val folded = nextFoldedTurns(emptySet(), setOf(7), onScreen = setOf(7), following = true)
        assertEquals(emptySet<Int>(), folded)
    }

    @Test
    fun `a reader in history keeps the shape they were reading`() {
        val alreadyFolded = setOf(1, 2)
        val folded = nextFoldedTurns(
            current = alreadyFolded,
            ended = setOf(1, 2, 3),
            onScreen = emptySet(),
            following = false,
        )
        assertEquals(alreadyFolded, folded)
    }

    @Test
    fun `scroll past a turn and it folds`() {
        val folded = nextFoldedTurns(setOf(1), setOf(1, 2), onScreen = emptySet(), following = true)
        assertEquals(setOf(1, 2), folded)
    }

    @Test
    fun `the default is still to fold`() {
        // Every caller that has not been told otherwise keeps the old behaviour.
        val rows = buildDisplayRows(closedTurn, setOf(1), emptySet(), null)
        assertTrue("summary" in texts(rows))
    }
}
