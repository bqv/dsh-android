package uk.xa0.dsh.term

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the host shell is rooted, and the `session/create` call that follows from it.
 *
 * An unconfined shell needs a *directory*, not a Workspace: the host resolves
 * `workspace?.path ?? request.cwd ?? defaultCwd` and derives the confinement from the
 * Session's policy, so a Session the app created at a bare `cwd` can have the same
 * shell there. A reader who lands on the panel for a session in no Workspace must get
 * that shell by default, not a refusal sentence — which is what these pin.
 */
class HostShellRootTest {

    private val workspaceId = "0c491239-c898-416f-8df0-d9efa6766843"
    private val cwd = "/home/user/var/work/dsh-android"

    // ------------------------------------------------------------------ the choice

    @Test
    fun `a session in a workspace is rooted at that workspace`() {
        val root = hostShellRoot(workspaceId, "dsh-android", "/srv/somewhere-else")

        assertEquals(HostShellRoot.Workspace(workspaceId, "dsh-android"), root)
        // The workspace's key is its id, because that is what prefs written before the
        // cwd form existed already hold, and what the host resolves the path from.
        assertEquals(workspaceId, root?.key)
        assertEquals("Host shell \u00b7 dsh-android", hostShellSessionName(root!!.label))
    }

    @Test
    fun `the workspace wins over the session's own cwd`() {
        // Membership makes a Workspace's host shell shared by its sessions. Rooting a
        // member at its own cwd instead would build a second shell for a directory that
        // already has one, and the two would drift apart when the Workspace moved.
        val root = hostShellRoot(workspaceId, "dsh-android", "/elsewhere")

        assertTrue(root is HostShellRoot.Workspace)
        assertNotEquals("/elsewhere", root?.key)
    }

    @Test
    fun `a session in no workspace is rooted at its own cwd`() {
        // The defect this covers: the panel's default seat used to be a failure
        // sentence for every session the app creates at a bare cwd.
        val root = hostShellRoot(workspaceId = null, workspaceTitle = null, cwd = cwd)

        assertEquals(HostShellRoot.Cwd(cwd), root)
        assertEquals(cwd, root?.key)
        assertEquals("Host shell \u00b7 /home/user/var/work/dsh-android", hostShellSessionName(root!!.label))
    }

    @Test
    fun `no workspace and no cwd has no root to open a shell in`() {
        assertNull(hostShellRoot(workspaceId = null, workspaceTitle = null, cwd = null))
        assertNull(hostShellRoot(workspaceId = null, workspaceTitle = null, cwd = ""))
        // Blank is the same absence, and a key of spaces would be a root nothing can
        // create a session at.
        assertNull(hostShellRoot(workspaceId = null, workspaceTitle = null, cwd = "   "))
    }

    @Test
    fun `a workspace with no title is named by its cwd, then by its id`() {
        // A WorkspaceItem's title already falls back to the path's basename, so a blank
        // one means the app was handed a Workspace without either; the name must still
        // be something a reader can tell apart from another archived session.
        assertEquals(
            HostShellRoot.Workspace(workspaceId, cwd),
            hostShellRoot(workspaceId, workspaceTitle = null, cwd = cwd),
        )
        assertEquals(
            HostShellRoot.Workspace(workspaceId, workspaceId),
            hostShellRoot(workspaceId, workspaceTitle = "  ", cwd = null),
        )
    }

    // ---------------------------------------------------------------- the wire call

    @Test
    fun `a cwd root sends cwd and a workspace root sends workspaceId`() {
        assertEquals(
            mapOf("workspaceId" to workspaceId),
            hostShellCreateRequest(HostShellRoot.Workspace(workspaceId, "dsh-android")),
        )
        assertEquals(mapOf("cwd" to cwd), hostShellCreateRequest(HostShellRoot.Cwd(cwd)))
    }

    @Test
    fun `the create request never names both a workspace and a cwd`() {
        // `session/create` answers `gateway/bad-request` for the pair
        // (session-controller/src/commands.ts:88), and a request that named both would
        // be refused rather than resolved to either.
        val roots = listOf(
            HostShellRoot.Workspace(workspaceId, "dsh-android"),
            HostShellRoot.Cwd(cwd),
        )

        for (root in roots) {
            val request = hostShellCreateRequest(root)
            assertEquals(1, request.size)
            assertTrue(request.containsKey("workspaceId") != request.containsKey("cwd"))
        }
    }

    // ------------------------------------------------------------------- the walk

    @Test
    fun `the walk's create step carries the cwd itself when there is no workspace`() {
        // The walk is driven by the root key, so a no-workspace session creates the
        // shell at its own cwd rather than being sent down a workspace-only path.
        val root = hostShellRoot(null, null, cwd)!!
        val name = hostShellSessionName(root.label)

        assertEquals(HostShellStep.CreateSession(name, cwd), HostShellProgress().next(name, root.key))
    }

    // ------------------------------------------------------------- the persistence

    @Test
    fun `a cwd key survives the prefs encoding`() {
        // The remembered mapping is the only way back to an archived session, and a cwd
        // is the first key that can contain the encoding's separator candidates.
        val sessions = mapOf(cwd to "session-shell-at-cwd", workspaceId to "session-shell-ws")

        assertEquals(sessions, decodeHostShellSessions(encodeHostShellSessions(sessions)))
        assertEquals(
            mapOf(workspaceId to "session-shell-ws"),
            decodeHostShellSessions(encodeHostShellSessions(sessions)).withHostShellSession(cwd, null),
        )
    }
}
