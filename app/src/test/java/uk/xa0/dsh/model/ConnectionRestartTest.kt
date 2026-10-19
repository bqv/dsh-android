package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The words the About page uses for a transport restart.
 *
 * The state is deliberately small and the copy is the point: a restart takes a
 * moment, can land on either door, and can fail, and this app's recurring defect
 * is doing something deliberate and saying nothing. These tests pin that each of
 * those has its own sentence, that success names the door, and that failure
 * carries the app's own reason rather than a generic apology.
 *
 * They cannot prove the restart actually rebuilt a socket — that is
 * `DshViewModel.restartConnection` and [uk.xa0.dsh.net.DshClient.resetTransport],
 * neither of which is reachable from a JVM test without a host.
 */
class ConnectionRestartTest {

    @Test
    fun `an idle restart says nothing`() {
        // The standing explainer is drawn in this case; a second sentence saying
        // that nothing happened would be noise on the row a reader checks when
        // something is wrong.
        assertNull(connectionRestartNote(ConnectionRestart.Idle))
    }

    @Test
    fun `a running restart has a busy word and says what it is waiting on`() {
        assertEquals(CONNECTION_RESTART_BUSY_NOTE, connectionRestartNote(ConnectionRestart.Running))
        assertTrue(CONNECTION_RESTART_RUNNING.contains("Restarting"))
        assertTrue(CONNECTION_RESTART_BUSY_NOTE.contains("Both doors"))
        // The label and the line beneath it are different sentences; the same one
        // twice would read as a duplicated row.
        assertTrue(CONNECTION_RESTART_RUNNING != CONNECTION_RESTART_BUSY_NOTE)
    }

    @Test
    fun `success names the door the fresh dial used`() {
        val lanNote = connectionRestartNote(ConnectionRestart.Succeeded(lan = true))
        val publicNote = connectionRestartNote(ConnectionRestart.Succeeded(lan = false))

        assertEquals(CONNECTION_RESTART_DONE_LAN, lanNote)
        assertEquals(CONNECTION_RESTART_DONE_PUBLIC, publicNote)
        assertNotNull(lanNote)
        assertNotNull(publicNote)
        assertTrue("the two doors must not read the same", lanNote != publicNote)
        assertTrue(publicNote!!.contains("public"))
        assertTrue(lanNote!!.contains("LAN"))
    }

    @Test
    fun `failure carries the reason the app already has`() {
        val note = connectionRestartNote(ConnectionRestart.Failed("Could not reach the host"))

        assertEquals(connectionRestartFailure("Could not reach the host"), note)
        assertTrue(note!!.contains("Could not reach the host"))
        assertTrue("failure must not read as success", !note.contains("Connection restarted"))
    }

    @Test
    fun `no copy claims an exit, a quit or a process shutdown`() {
        // The two controls are easy to confuse, and confusing them is the bug this
        // pair of changes exists to fix: Close ends the screen, Restart rebuilds
        // the transport, and neither kills the process.
        val copy = listOf(
            CONNECTION_RESTART_ACTION,
            CONNECTION_RESTART_RUNNING,
            CONNECTION_RESTART_BUSY_NOTE,
            CONNECTION_RESTART_NOTE,
            CONNECTION_RESTART_DONE_LAN,
            CONNECTION_RESTART_DONE_PUBLIC,
            connectionRestartFailure("sample"),
        )
        copy.forEach { line ->
            assertFalse("claims an exit: $line", line.contains("Exit"))
            assertFalse("claims a quit: $line", line.contains("Quit", ignoreCase = true))
            assertFalse("claims a force-stop: $line", line.contains("force", ignoreCase = true))
        }
        assertTrue(
            "the explainer must say the app is not restarted",
            CONNECTION_RESTART_NOTE.contains("without restarting the app"),
        )
    }
}
