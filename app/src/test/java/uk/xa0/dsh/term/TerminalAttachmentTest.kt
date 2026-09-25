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
    ) = TerminalInfo(
        id = "term-1",
        title = "shell",
        shell = "/bin/bash",
        cwd = "/workspace",
        cols = cols,
        rows = rows,
        state = state,
        controllerId = controllerId,
    )

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

    @Test
    fun `every issue has copy`() {
        TerminalIssue.entries.forEach { issue ->
            assertTrue("no copy for $issue", terminalIssueFact(issue).isNotBlank())
        }
    }
}
