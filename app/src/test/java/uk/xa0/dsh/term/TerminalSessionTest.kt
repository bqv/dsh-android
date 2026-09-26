package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The attach loop's own decisions, driven directly.
 *
 * `DshViewModel` is an `AndroidViewModel`, so no JVM test could construct it —
 * which is exactly how W1 (a model that never learned the panel's grid) and W2 (a
 * broken sequence that latched forever) survived a green suite. This is that logic
 * extracted into plain Kotlin.
 */
class TerminalSessionTest {

    private val environment = TerminalEnvironmentInfo(
        cwd = "/workspace", maxInputBytes = 65536, maxCols = 500, maxRows = 200, scrollback = 1000,
    )

    private fun info(
        cols: Int = 80,
        rows: Int = 24,
        state: TerminalState = TerminalState.RUNNING,
        controllerId: String? = "att-1",
        exitCode: Int? = null,
        error: String? = null,
    ) = TerminalInfo(
        id = "term-1",
        title = "shell",
        shell = "/bin/bash",
        cwd = "/workspace",
        cols = cols,
        rows = rows,
        state = state,
        exitCode = exitCode,
        error = error,
        controllerId = controllerId,
    )

    private fun session(
        cols: Int = 80,
        rows: Int = 24,
        controllerId: String? = null,
        policy: TerminalReattachPolicy = TerminalReattachPolicy(),
    ): TerminalAttachmentSession {
        val emulator = TerminalEmulator(cols, rows, environment.scrollback)
        return TerminalAttachmentSession(
            emulator,
            environment,
            info(cols = cols, rows = rows, controllerId = controllerId),
            policy,
        )
    }

    // ---------------------------------------------------------------- W1: grid

    @Test
    fun `the panel's measurement resizes the model, not just the PTY`() {
        val session = session()

        assertEquals(80, session.emulator.columns)
        assertEquals(24, session.emulator.rows)

        val sent = session.measure(45, 17)

        // W1: before the fix this only clamped and sent. The model stayed 80×24, so
        // rows 18–23 were never drawn and nothing wrapped at the panel's edge.
        assertEquals(45, session.emulator.columns)
        assertEquals(17, session.emulator.rows)
        assertEquals(45 to 17, sent)
    }

    @Test
    fun `a stale host state frame does not undo the measured grid`() {
        val session = session()
        session.accept(TerminalFrame.Snapshot(0, "", info(controllerId = session.attachmentId)))
        session.measure(45, 17)

        // The panel measured and its resize is in flight; the host's confirmation is
        // a *newer* frame, so this one still carries the pre-resize grid.
        session.accept(TerminalFrame.State(info(cols = 80, rows = 24, controllerId = session.attachmentId)))

        assertEquals(45, session.emulator.columns)
        assertEquals(17, session.emulator.rows)
    }

    @Test
    fun `before the panel measures the host's grid is the model's grid`() {
        val session = session()
        session.accept(
            TerminalFrame.Snapshot(0, "", info(cols = 100, rows = 40, controllerId = session.attachmentId)),
        )
        assertEquals(100, session.emulator.columns)
        assertEquals(40, session.emulator.rows)

        session.accept(TerminalFrame.State(info(cols = 90, rows = 30, controllerId = session.attachmentId)))
        assertEquals(90, session.emulator.columns)
        assertEquals(30, session.emulator.rows)
    }

    @Test
    fun `a snapshot sizes the model to the grid its bytes were laid out for`() {
        val session = session()
        session.measure(45, 17)

        session.accept(
            TerminalFrame.Snapshot(3, "screen", info(cols = 60, rows = 20, controllerId = session.attachmentId)),
        )

        assertEquals(60, session.emulator.columns)
        assertEquals(20, session.emulator.rows)
    }

    @Test
    fun `a repeated measurement is not sent twice`() {
        val session = session()
        assertEquals(45 to 17, session.measure(45, 17))
        assertNull(session.measure(45, 17))
    }

    // ------------------------------------------------------------ W2: reattach

    @Test
    fun `a sequence gap asks for a fresh attachment instead of latching`() {
        val session = session()
        val first = session.attachmentId
        session.accept(TerminalFrame.Snapshot(10, "screen", info(controllerId = first)))
        session.accept(TerminalFrame.Output(11, "a"))

        // Frame 12 was lost, so 13 is refused and the screen now has a hole.
        assertThrows(TerminalProtocolException::class.java) {
            session.accept(TerminalFrame.Output(13, "c"))
        }

        val restart = session.restart()
        assertNotNull(restart)
        // A fresh attachment is what re-takes input and what makes the host answer
        // with a fresh snapshot; reusing the old id would change nothing.
        assertNotEquals(first, restart!!.attachmentId)
        assertEquals(session.attachmentId, restart.attachmentId)

        // The broken generation cannot continue: the next frame must be a snapshot…
        assertThrows(TerminalProtocolException::class.java) {
            session.accept(TerminalFrame.Output(14, "d"))
        }
        // …and a fresh snapshot re-baselines it, so the panel is not frozen forever.
        session.accept(TerminalFrame.Snapshot(14, "fresh", info(controllerId = session.attachmentId)))
        session.accept(TerminalFrame.Output(15, "e"))
    }

    @Test
    fun `restarts are bounded and backed off`() {
        val policy = TerminalReattachPolicy(maxAttempts = 3, baseDelayMs = 1_000L, maxDelayMs = 8_000L)
        assertEquals(1_000L, policy.next())
        assertEquals(2_000L, policy.next())
        assertEquals(4_000L, policy.next())
        assertNull(policy.next())
    }

    @Test
    fun `a session stops restarting once its budget is spent`() {
        val session = session(policy = TerminalReattachPolicy(maxAttempts = 2, baseDelayMs = 1L, maxDelayMs = 1L))
        assertNotNull(session.restart())
        assertNotNull(session.restart())
        assertNull(session.restart())
    }

    @Test
    fun `a re-attach re-takes input for the new attachment`() {
        val session = session()
        session.accept(TerminalFrame.Snapshot(0, "screen", info(controllerId = session.attachmentId)))
        assertTrue(session.writable)

        session.measure(45, 17)
        session.restart()
        // Until the host answers the new attachment, the panel is read-only…
        assertFalse(session.writable)

        // …and the snapshot naming the fresh id is what makes it writable again.
        session.accept(
            TerminalFrame.Snapshot(4, "screen", info(cols = 45, rows = 17, controllerId = session.attachmentId)),
        )
        assertTrue(session.writable)
        assertEquals(45 to 17, session.measured)
        assertEquals(45, session.emulator.columns)
        assertEquals(17, session.emulator.rows)
    }

    // ------------------------------------------------------- stopped shell (W6)

    @Test
    fun `an exited shell is stopped, not running`() {
        val session = session()
        session.accept(TerminalFrame.Snapshot(0, "", info(controllerId = session.attachmentId)))
        assertFalse(session.stopped)

        session.accept(
            TerminalFrame.State(info(state = TerminalState.EXITED, controllerId = null, exitCode = 0)),
        )

        assertTrue(session.stopped)
        assertFalse(session.writable)
    }
}
