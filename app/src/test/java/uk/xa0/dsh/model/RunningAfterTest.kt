package uk.xa0.dsh.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a roster sample is allowed to do to a running flag.
 *
 * Measured on the device: a parent with three live subagents reported `running=3` and
 * then `running=0` 105ms later, with the tree still holding all three, because the host
 * reports a child as running only while it is attached.
 */
class RunningAfterTest {

    @Test
    fun `the list can promote anything`() {
        assertTrue(runningAfter(sampleRunning = true, isSubagent = true, previousRunning = false, live = false, stopped = false))
        assertTrue(runningAfter(sampleRunning = true, isSubagent = false, previousRunning = false, live = false, stopped = false))
    }

    @Test
    fun `a live frame is authoritative in both directions`() {
        assertTrue(runningAfter(sampleRunning = false, isSubagent = true, previousRunning = false, live = true, stopped = false))
        assertFalse(runningAfter(sampleRunning = false, isSubagent = true, previousRunning = true, live = true, stopped = true))
    }

    @Test
    fun `an ordinary session still takes the list's word for stopped`() {
        assertFalse(runningAfter(sampleRunning = false, isSubagent = false, previousRunning = true, live = false, stopped = false))
    }

    @Test
    fun `a subagent the list has stopped mentioning keeps running`() {
        // The bug: this returned false, which is the chip flashing and dying.
        assertTrue(runningAfter(sampleRunning = false, isSubagent = true, previousRunning = true, live = false, stopped = false))
    }

    @Test
    fun `but a live frame that says stopped does stop it`() {
        assertFalse(runningAfter(sampleRunning = false, isSubagent = true, previousRunning = true, live = false, stopped = true))
    }

    @Test
    fun `a subagent never seen running stays stopped`() {
        assertFalse(runningAfter(sampleRunning = false, isSubagent = true, previousRunning = false, live = false, stopped = false))
    }
}
