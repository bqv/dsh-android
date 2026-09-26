package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The workspace host-shell bootstrap and the two terminal seats.
 *
 * The host shell's whole reason to exist is that its mode is set *before* a terminal
 * is created in it — the host refuses a mode change while one is open, so a terminal
 * created one step early is permanently a confined shell behind a seat that says
 * "Full access". That is a one-line mistake with no visible symptom until a reader
 * tries to write outside the workspace, so the order is pinned here rather than left
 * to the call site.
 */
class HostShellTest {

    private val name = hostShellSessionName("dsh-android")
    private val workspaceId = "0c491239-c898-416f-8df0-d9efa6766843"

    // ------------------------------------------------------------------ the order

    @Test
    fun `the bootstrap walks create, mode and archive before a terminal is allowed`() {
        var progress = HostShellProgress()

        // Nothing exists yet, so the first step is the session that will hold the shell.
        assertEquals(HostShellStep.CreateSession(name, workspaceId), progress.next(name, workspaceId))
        assertFalse(progress.terminalAllowed)

        progress = progress.withSession("session-shell-1")
        // The session alone is not enough: a terminal here would be confined.
        assertFalse(progress.terminalAllowed)
        assertEquals(HostShellStep.SetAccessMode("session-shell-1"), progress.next(name, workspaceId))

        progress = progress.withModeSet()
        // Unconfined but browsable: the dedicated session would sit in the drawer.
        assertFalse(progress.terminalAllowed)
        assertEquals(HostShellStep.Archive("session-shell-1"), progress.next(name, workspaceId))

        progress = progress.withArchived()
        assertNull(progress.next(name, workspaceId))
        assertTrue(progress.terminalAllowed)
        assertEquals("session-shell-1", progress.sessionId)
    }

    @Test
    fun `the mode step is never skipped for an adopted session`() {
        // An id from local prefs is evidence that this app created the session, not
        // that the session is still unconfined. The walk must still re-assert the mode.
        val adopted = HostShellProgress().withSession("session-from-prefs")

        assertEquals(HostShellStep.SetAccessMode("session-from-prefs"), adopted.next(name, workspaceId))
        assertFalse(adopted.terminalAllowed)

        // And the re-assertion keeps the archive step after it.
        assertEquals(
            HostShellStep.Archive("session-from-prefs"),
            adopted.withModeSet().next(name, workspaceId),
        )
    }

    @Test
    fun `a terminal is never allowed without every step`() {
        val created = HostShellProgress().withSession("s")
        val modeOnly = created.withModeSet()
        val archiveOnly = created.withArchived()

        assertFalse(created.terminalAllowed)
        assertFalse(modeOnly.terminalAllowed)
        assertFalse(archiveOnly.terminalAllowed)
        // Archive before mode is the one interleaving the walk must not accept as done.
        assertNotEquals(
            HostShellStep.Archive("s"),
            archiveOnly.next(name, workspaceId),
        )
        assertTrue(created.withArchived().withModeSet().terminalAllowed)
    }

    // ------------------------------------------------------------------- the seats

    @Test
    fun `the host shell is the first seat and addresses its own session`() {
        val seats = terminalSeats(
            sessionId = "session-user",
            sessionPolicyLabel = "Workspace Write",
            hostShellSessionId = "session-shell-1",
        )

        assertEquals(listOf(TerminalSeat.HOST_SHELL, TerminalSeat.THIS_SESSION), seats.map { it.seat })
        // The host seat's terminal RPCs must address the archived shell session, and
        // the session seat's must address the session the user has open. Swapping them
        // would open a confined shell and call it the host shell.
        assertEquals("session-shell-1", seats[0].agentId)
        assertEquals("session-user", seats[1].agentId)
    }

    @Test
    fun `each seat is labelled with the policy in force in it`() {
        val seats = terminalSeats(
            sessionId = "session-user",
            sessionPolicyLabel = "Workspace Write",
            hostShellSessionId = "session-shell-1",
        )

        assertEquals("Host shell \u00b7 Full access", seats[0].label)
        assertEquals("This session \u00b7 Workspace Write", seats[1].label)
        // The host seat's label is the fixed full-access word even though the session
        // it is displayed over runs workspace-write; labelling it with the session's
        // policy would describe the wrong shell entirely.
        assertFalse(seats[0].label.contains("Workspace Write"))
    }

    @Test
    fun `a seat with no policy said is labelled access unknown rather than blank`() {
        val seats = terminalSeats("session-user", sessionPolicyLabel = "", hostShellSessionId = null)

        assertEquals("This session \u00b7 access unknown", seats[1].label)
        assertNull(seats[0].agentId)
    }

    // ---------------------------------------------------------------- persistence

    @Test
    fun `the workspace to host-shell mapping round-trips`() {
        val sessions = mapOf("ws-1" to "session-a", "ws-2" to "session-b")

        assertEquals(sessions, decodeHostShellSessions(encodeHostShellSessions(sessions)))
        assertEquals(emptyMap<String, String>(), decodeHostShellSessions(encodeHostShellSessions(emptyMap())))
        assertEquals(emptyMap<String, String>(), decodeHostShellSessions(null))
    }

    @Test
    fun `a malformed mapping line is dropped, not thrown`() {
        // A preference the app cannot read must not be able to stop it from starting.
        val decoded = decodeHostShellSessions(
            "ws-1\tsession-a\nnonsense\n\tno-workspace\nws-2\t\nws-3\tsession-c\n",
        )

        assertEquals(mapOf("ws-1" to "session-a", "ws-3" to "session-c"), decoded)
    }

    @Test
    fun `forgetting a workspace removes only that entry`() {
        val sessions = mapOf("ws-1" to "session-a", "ws-2" to "session-b")

        assertEquals(mapOf("ws-2" to "session-b"), sessions.withHostShellSession("ws-1", null))
        assertEquals(
            mapOf("ws-1" to "session-new", "ws-2" to "session-b"),
            sessions.withHostShellSession("ws-1", "session-new"),
        )
        assertEquals(sessions, sessions.withHostShellSession("ws-1", null).withHostShellSession("ws-1", "session-a"))
    }

    // -------------------------------------------------------------------- the copy

    @Test
    fun `a refused mode change says no terminal was created and repeats the host`() {
        val fact = hostShellIssueFact(HostShellIssue.MODE_REFUSED, "Close browser terminals before changing")

        assertTrue(fact.contains("No terminal was created"))
        // The host's own words, because "refused" without the reason is not a fact the
        // reader can act on.
        assertTrue(fact.contains("Close browser terminals before changing"))
    }

    @Test
    fun `a refusal without a host message is still a whole sentence`() {
        val fact = hostShellIssueFact(HostShellIssue.CREATE_REFUSED)

        assertTrue(fact.contains("No terminal was created"))
        assertFalse(fact.contains("The host said:"))
    }

    @Test
    fun `the host shell session is named for its workspace`() {
        assertEquals("Host shell \u00b7 dsh-android", hostShellSessionName("dsh-android"))
    }
}
