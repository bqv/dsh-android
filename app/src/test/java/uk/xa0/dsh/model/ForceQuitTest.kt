package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Force quit copy, and the order of the three things it does.
 *
 * What is pinned here is the part a person reads under pressure: that the
 * confirmation says the *process* ends and nothing in flight survives, and that
 * the three verbs on one page — Close, Restart connection, Force quit — cannot be
 * mistaken for one another. The order of the shutdown is pinned too, because a
 * `START_STICKY` service stopped *after* the kill is a force quit the system
 * immediately undoes.
 *
 * It cannot prove the process actually died: `Process.killProcess` needs a
 * device, and the test harness replaces the kill with a lambda.
 */
class ForceQuitTest {

    @Test
    fun `the confirmation says the process ends, not just the connection`() {
        assertTrue(FORCE_QUIT_BODY.contains("process"))
        assertTrue(FORCE_QUIT_BODY.contains("ends"))
        assertFalse(
            "must not read as a transport restart",
            FORCE_QUIT_BODY.contains("reconnect at once"),
        )
    }

    @Test
    fun `the confirmation says nothing in flight survives`() {
        assertTrue(FORCE_QUIT_BODY.contains("nothing in flight survives"))
        // The two losses a reader has to know about before tapping.
        assertTrue(FORCE_QUIT_BODY.contains("turn being followed"))
        assertTrue(FORCE_QUIT_BODY.contains("approval or question"))
    }

    @Test
    fun `all three verbs are named in the confirmation`() {
        // Close, Restart connection and Force quit sit on one page. The words have
        // to separate them, or the pair of adjacent controls is a trap.
        assertTrue(FORCE_QUIT_BODY.contains("Close"))
        assertTrue(FORCE_QUIT_BODY.contains("Restart connection"))
        assertTrue(FORCE_QUIT_BODY.contains("Force quit"))
        assertTrue(FORCE_QUIT_NOTE.contains("Restart connection"))
    }

    @Test
    fun `the three labels do not read alike`() {
        assertFalse(FORCE_QUIT_ACTION == CONNECTION_RESTART_ACTION)
        assertFalse(FORCE_QUIT_ACTION == CLOSE_WORD)
        assertEquals("Force quit", FORCE_QUIT_ACTION)
    }

    @Test
    fun `the confirm button is a decision, not a repeat of the action`() {
        assertTrue(FORCE_QUIT_CONFIRM != FORCE_QUIT_ACTION)
        assertTrue(FORCE_QUIT_CONFIRM.contains("Force quit"))
        // And the way out is named for what it does: the app keeps running.
        assertEquals("Keep it running", FORCE_QUIT_CANCEL)
    }

    @Test
    fun `the service stops before the process is killed`() {
        val calls = mutableListOf<String>()
        runForceQuit(
            stopService = { calls += "stop" },
            withdrawNotifications = { calls += "withdraw" },
            killProcess = { calls += "kill" },
        )

        assertEquals(listOf("stop", "withdraw", "kill"), calls)
    }

    @Test
    fun `a failing service stop still kills the process`() {
        // The kill is the point of the action; a binder that refuses to stop the
        // service must not turn a requested force quit into a no-op.
        var killed = false
        runForceQuit(
            stopService = { error("service is gone") },
            withdrawNotifications = { error("no notification manager") },
            killProcess = { killed = true },
        )

        assertTrue(killed)
    }

    private companion object {
        /** The Close control's own word, from `ui/BackGate.kt`'s dialog copy. */
        const val CLOSE_WORD = "Close"
    }
}
