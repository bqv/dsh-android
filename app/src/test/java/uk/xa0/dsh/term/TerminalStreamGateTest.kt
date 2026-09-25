package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The attachment and sequence rules.
 *
 * This is the half of the feature most likely to be quietly wrong: a generation
 * that does not begin with a screen, or an output frame whose sequence skips, means
 * the rendered grid is missing output and *looks* fine. The web client's
 * `TerminalView.consume` is the specification (`terminal-controller/src/client/model.ts`).
 */
class TerminalStreamGateTest {

    private fun info(
        title: String = "shell",
        state: TerminalState = TerminalState.RUNNING,
        controllerId: String? = "att-1",
    ) = TerminalInfo(
        id = "term-1",
        title = title,
        shell = "/bin/bash",
        cwd = "/workspace",
        cols = 80,
        rows = 24,
        state = state,
        controllerId = controllerId,
    )

    @Test
    fun `no frame is accepted before a snapshot`() {
        val gate = TerminalStreamGate()

        assertEquals(-1, gate.generation)

        // Negative control: sequence 1 is *valid* output numbering and the gate must
        // refuse it anyway, because there is no baseline to continue from. A gate
        // that only checked numbers would accept this and render a partial screen.
        assertThrows(TerminalProtocolException::class.java) {
            gate.accept(TerminalFrame.Output(sequence = 1, data = "hello"))
        }
        assertThrows(TerminalProtocolException::class.java) {
            gate.accept(TerminalFrame.State(info()))
        }
    }

    @Test
    fun `a snapshot starts a generation and sets the sequence baseline`() {
        val gate = TerminalStreamGate()

        val outcome = gate.accept(TerminalFrame.Snapshot(sequence = 7, screen = "screen", info = info()))

        assertEquals(TerminalGateOutcome.NEW_GENERATION, outcome)
        assertEquals(0, gate.generation)
        assertEquals(7, gate.sequence)
        assertEquals("shell", gate.info?.title)
    }

    @Test
    fun `in-order output is accepted and advances the sequence`() {
        val gate = TerminalStreamGate()
        gate.accept(TerminalFrame.Snapshot(sequence = 0, screen = "screen", info = info()))

        assertEquals(TerminalGateOutcome.OUTPUT, gate.accept(TerminalFrame.Output(1, "a")))
        assertEquals(TerminalGateOutcome.OUTPUT, gate.accept(TerminalFrame.Output(2, "b")))
        assertEquals(2, gate.sequence)
    }

    @Test
    fun `a sequence gap is refused`() {
        val gate = TerminalStreamGate()
        gate.accept(TerminalFrame.Snapshot(sequence = 0, screen = "screen", info = info()))
        gate.accept(TerminalFrame.Output(1, "a"))

        assertThrows(TerminalProtocolException::class.java) {
            gate.accept(TerminalFrame.Output(3, "c"))
        }
    }

    @Test
    fun `a repeated sequence number is refused`() {
        val gate = TerminalStreamGate()
        gate.accept(TerminalFrame.Snapshot(sequence = 0, screen = "screen", info = info()))
        gate.accept(TerminalFrame.Output(1, "a"))

        assertThrows(TerminalProtocolException::class.java) {
            gate.accept(TerminalFrame.Output(1, "a"))
        }
    }

    /**
     * The reconnect path: `RemoteMux` replays the registration, the host attaches
     * again and sends a fresh screen whose `sequence` is wherever the terminal got
     * to. That number is the new baseline, and it is *not* required to be greater
     * than the last one — no output may have happened while the socket was down.
     */
    @Test
    fun `a replayed snapshot re-baselines the sequence`() {
        val gate = TerminalStreamGate()
        gate.accept(TerminalFrame.Snapshot(sequence = 10, screen = "first", info = info()))
        gate.accept(TerminalFrame.Output(11, "a"))
        gate.accept(TerminalFrame.Output(12, "b"))

        assertEquals(
            TerminalGateOutcome.NEW_GENERATION,
            gate.accept(TerminalFrame.Snapshot(sequence = 12, screen = "second", info = info())),
        )
        assertEquals(1, gate.generation)
        assertEquals(12, gate.sequence)
        assertEquals(TerminalGateOutcome.OUTPUT, gate.accept(TerminalFrame.Output(13, "c")))
    }

    @Test
    fun `output continuing the previous generation is refused after a snapshot`() {
        val gate = TerminalStreamGate()
        gate.accept(TerminalFrame.Snapshot(sequence = 10, screen = "first", info = info()))
        gate.accept(TerminalFrame.Snapshot(sequence = 20, screen = "second", info = info()))

        assertThrows(TerminalProtocolException::class.java) {
            gate.accept(TerminalFrame.Output(11, "stale"))
        }
    }

    @Test
    fun `state frames carry metadata without touching the sequence`() {
        val gate = TerminalStreamGate()
        gate.accept(TerminalFrame.Snapshot(sequence = 4, screen = "screen", info = info()))
        gate.accept(TerminalFrame.Output(5, "a"))

        val outcome = gate.accept(
            TerminalFrame.State(info(title = "renamed", state = TerminalState.EXITED, controllerId = null)),
        )

        assertEquals(TerminalGateOutcome.STATE, outcome)
        assertEquals(5, gate.sequence)
        assertEquals("renamed", gate.info?.title)
        assertEquals(TerminalState.EXITED, gate.info?.state)
        assertEquals(null, gate.info?.controllerId)
    }

    @Test
    fun `reset forgets the generation so the next frame must be a snapshot`() {
        val gate = TerminalStreamGate()
        gate.accept(TerminalFrame.Snapshot(sequence = 3, screen = "screen", info = info()))
        gate.reset()

        assertEquals(-1, gate.generation)
        assertNull(gate.info)
        assertThrows(TerminalProtocolException::class.java) {
            gate.accept(TerminalFrame.Output(4, "x"))
        }
    }
}
