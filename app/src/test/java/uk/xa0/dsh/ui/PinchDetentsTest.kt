package uk.xa0.dsh.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pinch accumulator, as arithmetic.
 *
 * A gesture cannot be asserted in a unit test, but what a gesture *means* can: a
 * pinch is a ratio, a report is a detent, and neither a jittering finger nor a
 * re-placed one may be allowed to turn into a size that jumps. Every span below is
 * written with its ratio spelled out, so the numbers are the arithmetic and not a
 * recording of whatever the implementation happened to do.
 */
class PinchDetentsTest {

    private fun detents() = PinchDetents()

    @Test
    fun `the opening span is the zero of the gesture`() {
        val detents = detents()
        // Two fingers landing are not a spread, however far apart they land.
        assertEquals(0, detents.steps(40f))
        // 1.125× the opening span — log₁.₂₅(1.125) = 0.53 — is inside the first
        // detent and is not a step.
        assertEquals(0, detents.steps(45f))
        // 1.275× — 1.09 detents — has crossed it.
        assertEquals(1, detents.steps(51f))
    }

    @Test
    fun `a quarter more span is one step larger`() {
        val detents = detents()
        detents.steps(100f)

        // 1.4× is 1.51 detents: one whole step, with 0.51 of the next left over.
        assertEquals(1, detents.steps(140f))
        // The same span again asks for nothing — the remainder was spent, not
        // re-credited, so a still finger cannot step once per frame.
        assertEquals(0, detents.steps(140f))
        // And the 0.51 that was left over is still there: 1.6× the opening span is
        // 2.13 detents in total, and this is the second of them, reached at 1.28× the
        // anchor (1.11 detents) rather than at a full 1.25 beyond it.
        assertEquals(1, detents.steps(160f))
    }

    @Test
    fun `a quarter less span is one step smaller`() {
        val detents = detents()
        detents.steps(100f)

        // 0.76× is −1.23 detents.
        assertEquals(-1, detents.steps(76f))
        // 0.76× again is −0.23 from the anchor the step left behind.
        assertEquals(0, detents.steps(76f))
    }

    @Test
    fun `jitter inside a detent is nothing`() {
        val detents = detents()
        detents.steps(100f)

        // A finger report carries a fraction of a percent of noise; a size rewritten
        // on that would make the grid the host is told about chatter.
        assertEquals(0, detents.steps(101f))
        assertEquals(0, detents.steps(99f))
        assertEquals(0, detents.steps(110f))
        assertEquals(0, detents.steps(92f))
    }

    @Test
    fun `a long pinch keeps every step it crosses`() {
        val detents = detents()
        detents.steps(100f)

        // 2× is 3.11 detents. One report may not carry three, so the first carries
        // two and leaves the anchor at 1.5625×, which is exactly the two it reported.
        assertEquals(2, detents.steps(200f))
        // The third crosses on the next report, at 1.28× that anchor.
        assertEquals(1, detents.steps(200f))
        assertEquals(0, detents.steps(200f))
    }

    @Test
    fun `a re-placed finger cannot teleport the size`() {
        val detents = detents()
        detents.steps(100f)

        // A finger lifted and put down far away is one huge span — 10.3 detents here —
        // with no movement behind it. One report is bounded, so the size cannot jump
        // its whole range from a single frame; a deliberate spread still gets there,
        // one bounded report at a time.
        assertEquals(PinchDetents.MAX_STEPS_PER_REPORT, detents.steps(1000f))
    }

    @Test
    fun `a reset re-anchors, and a nonsense span is ignored`() {
        val detents = detents()
        detents.steps(100f)
        assertEquals(1, detents.steps(132f))

        detents.reset()
        assertEquals(0, detents.steps(132f))

        assertEquals(0, detents.steps(0f))
        assertEquals(0, detents.steps(-5f))
        assertEquals(0, detents.steps(Float.NaN))
    }
}
