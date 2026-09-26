package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attachment rules that are not frame ordering: what makes a panel writable,
 * when a resize is worth sending, and what the host's refusals actually mean.
 *
 * These are the parts a reader would otherwise only discover by typing into a
 * terminal that silently drops the keystrokes.
 */
class TerminalAttachmentTest {

    private fun info(
        cols: Int = 80,
        rows: Int = 24,
        state: TerminalState = TerminalState.RUNNING,
        controllerId: String? = "att-1",
        id: String = "term-1",
    ) = TerminalInfo(
        id = id,
        title = "shell",
        shell = "/bin/bash",
        cwd = "/workspace",
        cols = cols,
        rows = rows,
        state = state,
        controllerId = controllerId,
    )

    // -------------------------------------------------- retained-terminal adoption

    @Test
    fun `the running retained terminal is the one adopted`() {
        val retained = listOf(
            info(id = "dead", state = TerminalState.EXITED),
            info(id = "live", state = TerminalState.RUNNING),
        )
        assertEquals("live", terminalToAdopt(retained)?.id)
    }

    @Test
    fun `nothing is adopted when every retained terminal has stopped`() {
        // The defect: this used to fall back to `firstOrNull()`, so the panel adopted
        // the exited host shell, the host answered the follow with its last screen, and
        // the bar said "running" while the keyboard could not type.
        val retained = listOf(
            info(id = "dead", state = TerminalState.EXITED),
            info(id = "failed", state = TerminalState.FAILED),
        )
        assertEquals(null, terminalToAdopt(retained))
    }

    @Test
    fun `an unknown state is not running and is not adopted`() {
        assertEquals(null, terminalToAdopt(listOf(info(state = TerminalState.UNKNOWN))))
    }

    @Test
    fun `an empty roster adopts nothing`() {
        assertEquals(null, terminalToAdopt(emptyList()))
    }

    @Test
    fun `stopped terminals are retired and running ones are not`() {
        val retained = listOf(
            info(id = "live", state = TerminalState.RUNNING),
            info(id = "dead", state = TerminalState.EXITED),
            info(id = "failed", state = TerminalState.FAILED),
            info(id = "unknown", state = TerminalState.UNKNOWN),
        )
        // A running terminal is another attachment's shell and is left alone; an
        // UNKNOWN state is not evidence of a stop, so nothing is assumed about it.
        assertEquals(listOf("dead", "failed"), terminalReapList(retained).map { it.id })
    }

    @Test
    fun `a roster of only running terminals is retired by nothing`() {
        assertEquals(emptyList<TerminalInfo>(), terminalReapList(listOf(info(), info(id = "term-2"))))
    }

    // -------------------------------------------------------- input attachment

    @Test
    fun `input is writable only for the attachment the host names as controller`() {
        assertTrue(isWritable(info(controllerId = "att-1"), "att-1"))
        // The newest attachment owns input; an older one is read-only.
        assertFalse(isWritable(info(controllerId = "att-2"), "att-1"))
    }

    @Test
    fun `a terminal without a controller is read-only`() {
        assertFalse(isWritable(info(controllerId = null), "att-1"))
    }

    @Test
    fun `a stopped terminal is read-only even for its controller`() {
        assertFalse(isWritable(info(state = TerminalState.EXITED, controllerId = "att-1"), "att-1"))
        assertFalse(isWritable(info(state = TerminalState.FAILED, controllerId = "att-1"), "att-1"))
    }

    @Test
    fun `a panel without an attachment is read-only`() {
        assertFalse(isWritable(info(), null))
        assertFalse(isWritable(null, "att-1"))
    }

    // ------------------------------------------------------------ resize gate

    @Test
    fun `the first resize is sent and a repeated one is dropped`() {
        val gate = TerminalResizeGate()
        assertTrue(gate.shouldSend(80, 24))
        assertFalse(gate.shouldSend(80, 24))
        assertTrue(gate.shouldSend(81, 24))
        assertTrue(gate.shouldSend(81, 25))
        assertFalse(gate.shouldSend(81, 25))
    }

    @Test
    fun `the grid the host reports is adopted without a resize`() {
        val gate = TerminalResizeGate()
        gate.adopt(info(cols = 100, rows = 40))
        assertFalse(gate.shouldSend(100, 40))
        assertFalse(gate.shouldSend(100, 40))
        assertTrue(gate.shouldSend(120, 40))
    }

    @Test
    fun `clearing the gate makes the next resize go out`() {
        val gate = TerminalResizeGate()
        gate.adopt(info(cols = 100, rows = 40))
        gate.clear()
        assertTrue(gate.shouldSend(100, 40))
    }

    // -------------------------------------------------------------- grid clamp

    @Test
    fun `the measured grid is clamped to the host limits`() {
        val environment = TerminalEnvironmentInfo(
            cwd = "/workspace", maxInputBytes = 65536, maxCols = 500, maxRows = 200, scrollback = 1000,
        )
        assertEquals(500 to 200, clampGrid(1000, 400, environment))
        assertEquals(80 to 24, clampGrid(80, 24, environment))
    }

    @Test
    fun `the grid floor is the host's own minimum of two columns`() {
        val environment = TerminalEnvironmentInfo(
            cwd = "/workspace", maxInputBytes = 65536, maxCols = 500, maxRows = 200, scrollback = 1000,
        )
        assertEquals(2 to 1, clampGrid(0, 0, environment))
    }

    @Test
    fun `without an environment the measurement stands`() {
        assertEquals(120 to 40, clampGrid(120, 40, null))
        assertEquals(2 to 1, clampGrid(1, 0, null))
    }

    // ------------------------------------------------------------ input budget

    @Test
    fun `the input budget tracks queued bytes and releases them`() {
        val budget = TerminalInputBudget(10)
        assertTrue(budget.offer(4))
        assertTrue(budget.offer(6))
        assertEquals(10, budget.pending)
        assertFalse(budget.offer(1))
        budget.release(4)
        assertTrue(budget.offer(4))
    }

    @Test
    fun `one oversized write is refused outright`() {
        val budget = TerminalInputBudget(10)
        assertFalse(budget.offer(11))
        assertEquals(0, budget.pending)
    }

    @Test
    fun `releasing more than was offered cannot drive the count negative`() {
        val budget = TerminalInputBudget(10)
        budget.offer(4)
        budget.release(99)
        assertEquals(0, budget.pending)
    }

    // --------------------------------------------------------- error to fact

    @Test
    fun `host error codes map to the fact they represent`() {
        assertEquals(TerminalIssue.NO_LIVE_AGENT, terminalIssueOf("gateway/lookup-not-found", null))
        assertEquals(TerminalIssue.LIMIT_REACHED, terminalIssueOf("terminal/limit-reached", null))
        assertEquals(TerminalIssue.READ_ONLY, terminalIssueOf("terminal/control-unavailable", "read-only"))
        assertEquals(
            TerminalIssue.NOT_RUNNING,
            terminalIssueOf("terminal/control-unavailable", "not-running"),
        )
        assertEquals(TerminalIssue.UNKNOWN, terminalIssueOf("gateway/arguments-invalid", null))
    }

    /**
     * The point of the mapping: a session with no live agent will never connect on
     * its own, so the copy has to say why and what to do — never "connecting".
     */
    @Test
    fun `the no-live-agent fact names the cause and the way out`() {
        val fact = terminalIssueFact(TerminalIssue.NO_LIVE_AGENT)
        assertTrue(fact.contains("no live agent"))
        assertTrue(fact.contains("prompt"))
        assertFalse(fact.lowercase().contains("connecting"))
    }

    @Test
    fun `the limit fact carries the host's own number`() {
        assertTrue(terminalIssueFact(TerminalIssue.LIMIT_REACHED, limit = 8).contains("8"))
        // Absent details fall back to the host's documented default rather than
        // printing "null".
        assertTrue(terminalIssueFact(TerminalIssue.LIMIT_REACHED).contains("8"))
    }

    @Test
    fun `the read-only fact is about control, not about a failure`() {
        val fact = terminalIssueFact(TerminalIssue.READ_ONLY)
        assertTrue(fact.contains("read-only"))
        assertTrue(fact.contains("control"))
    }

    /**
     * The lost-sequence fact is only ever shown once the attach loop's bounded
     * re-attach retries are spent (a retry publishes DISCONNECTED with no issue), so
     * it has to name the reader's action rather than narrate a restart that has
     * already given up.
     */
    @Test
    fun `the lost-sequence fact names the action it needs`() {
        val fact = terminalIssueFact(TerminalIssue.OUTPUT_INVALID)
        assertTrue(fact.lowercase().contains("reconnect"))
        assertFalse(fact.lowercase().contains("was restarted"))
    }

    @Test
    fun `every issue has copy`() {
        TerminalIssue.entries.forEach { issue ->
            assertTrue("no copy for $issue", terminalIssueFact(issue).isNotBlank())
        }
    }
}
