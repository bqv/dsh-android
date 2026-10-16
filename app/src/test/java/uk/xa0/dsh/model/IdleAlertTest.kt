package uk.xa0.dsh.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is worth a "Done" notification when a session stops.
 *
 * The bar is whether anybody is waiting on it. A subagent's turn belongs to its
 * parent's; a session an active schedule delivers into is driven by deliveries that
 * happen unattended, and the hourly kind would otherwise post twenty-four notifications
 * a day for work the reader deliberately set going and walked away from.
 *
 * The signal is the host's task catalog, not the session's name. Under the retired
 * third-party plugin a run *was* a whole session with a `dsh-automation-session-…` id;
 * the host's Schedule subsystem reuses an ordinary session instead, so the count is what
 * mutes it and a legacy-looking id on its own mutes nothing.
 */
class IdleAlertTest {

    @Test
    fun `an ordinary session is worth telling the reader about`() {
        assertTrue(warrantsIdleAlert(subagent = false, scheduledTaskCount = 0))
    }

    @Test
    fun `a subagent is not`() {
        assertFalse(warrantsIdleAlert(subagent = true, scheduledTaskCount = 0))
    }

    @Test
    fun `a session an active schedule delivers into is not`() {
        assertFalse(warrantsIdleAlert(subagent = false, scheduledTaskCount = 1))
        assertFalse(warrantsIdleAlert(subagent = false, scheduledTaskCount = 3))
    }

    @Test
    fun `a subagent of a scheduled session is not, by either rule`() {
        assertFalse(warrantsIdleAlert(subagent = true, scheduledTaskCount = 1))
        // A child of a scheduled session is not itself bound to the task, so only the
        // subagent rule mutes it — which is enough, and is the point of keeping both.
        assertFalse(warrantsIdleAlert(subagent = true, scheduledTaskCount = 0))
    }

    @Test
    fun `an id that merely looks like an old automation run mutes nothing`() {
        // The prefix test is gone: the id shape carries no meaning now, and the count is
        // the whole signal. A session with such an id and no task is an ordinary session
        // and is worth the buzz.
        assertTrue(warrantsIdleAlert(subagent = false, scheduledTaskCount = 0))
    }
}
