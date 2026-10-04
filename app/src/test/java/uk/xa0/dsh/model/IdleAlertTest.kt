package uk.xa0.dsh.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Who is worth a "Done" notification when a session stops.
 *
 * The bar is whether anybody is waiting on it. A subagent's turn belongs to its
 * parent's; an automation run is unattended by definition, and an hourly one would
 * otherwise post twenty-four notifications a day for work the reader deliberately set
 * going and walked away from.
 */
class IdleAlertTest {

    @Test
    fun `an ordinary session is worth telling the reader about`() {
        assertTrue(warrantsIdleAlert("session-abc123", subagent = false))
    }

    @Test
    fun `a subagent is not`() {
        assertFalse(warrantsIdleAlert("session-abc123", subagent = true))
    }

    @Test
    fun `an automation run is not`() {
        assertFalse(warrantsIdleAlert("dsh-automation-session-1759600000-abc", subagent = false))
    }

    @Test
    fun `a subagent of an automation run is not, by either rule`() {
        assertFalse(warrantsIdleAlert("session-child", subagent = true))
        assertFalse(warrantsIdleAlert("dsh-automation-session-child", subagent = false))
    }

    @Test
    fun `an ordinary session whose id merely mentions automation is still worth it`() {
        // The prefix test is exact rather than a `contains`, so a session that happens
        // to have the word in its id is not silently muted.
        assertTrue(warrantsIdleAlert("session-dsh-automation-notes", subagent = false))
    }
}
