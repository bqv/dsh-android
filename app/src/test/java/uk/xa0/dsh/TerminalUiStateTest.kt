package uk.xa0.dsh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.term.TerminalInfo
import uk.xa0.dsh.term.TerminalIssue
import uk.xa0.dsh.term.TerminalState

/**
 * W6: a `state` frame saying the shell stopped has to reach the panel as a stopped
 * *fact*. Before this, `EXITED` kept `CONNECTED` ("running") and only cleared
 * `writable`, so the bar lied and typing silently did nothing.
 */
class TerminalUiStateTest {

    private fun info(state: TerminalState, exitCode: Int? = null, error: String? = null) = TerminalInfo(
        id = "term-1",
        title = "shell",
        shell = "/bin/bash",
        cwd = "/workspace",
        cols = 80,
        rows = 24,
        state = state,
        exitCode = exitCode,
        error = error,
        controllerId = "att-1",
    )

    @Test
    fun `an exited shell is detached with a reason, not running`() {
        val exited = info(TerminalState.EXITED, exitCode = 3)

        // The grid stays on screen (DISCONNECTED composes the surface); it is not
        // thrown away for the placeholder, and it is not called "running".
        assertEquals(TerminalPhase.DISCONNECTED, terminalPhaseFor(exited))
        assertEquals(TerminalIssue.NOT_RUNNING, terminalStopIssue(exited))
        assertTrue(terminalStopDetail(exited)!!.contains("3"))
    }

    @Test
    fun `a failed shell reports the host's own failure`() {
        val failed = info(TerminalState.FAILED, error = "spawn ENOENT")

        assertEquals(TerminalPhase.FAILED, terminalPhaseFor(failed))
        assertEquals(TerminalIssue.NOT_RUNNING, terminalStopIssue(failed))
        assertEquals("spawn ENOENT", terminalStopDetail(failed))
    }

    @Test
    fun `a running shell has no stop fact and stays connected`() {
        val running = info(TerminalState.RUNNING)

        assertEquals(TerminalPhase.CONNECTED, terminalPhaseFor(running))
        assertNull(terminalStopIssue(running))
        assertNull(terminalStopDetail(running))
    }

    @Test
    fun `an unknown state is metadata only`() {
        val unknown = info(TerminalState.UNKNOWN)

        assertNull(terminalPhaseFor(unknown))
        assertNull(terminalStopIssue(unknown))
        assertNull(terminalStopDetail(unknown))
    }
}
