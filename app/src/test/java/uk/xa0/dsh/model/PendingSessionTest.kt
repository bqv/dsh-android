package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Deferred session creation, driven directly.
 *
 * The property the user asked for is "switching workspaces stops leaving empty
 * sessions behind". The host has no session-delete RPC, so the only way to hold
 * that property is to create nothing at all until a session is genuinely needed.
 * `DshViewModel` cannot be constructed in a JVM test (it is an `AndroidViewModel`),
 * so the decision itself lives in [SessionTargets] and is pinned here.
 *
 * [Seat] is a miniature of exactly the contract the ViewModel keeps: an intent
 * either opens/adopts an existing session, or records a target; and only the
 * first use of a recorded target can run the one call that adds a session. The
 * harness counts those calls, which is what lets "nothing was created" be an
 * assertion rather than a comment.
 */
class PendingSessionTest {

    private val workspaceId = "ws-1"
    private val workspacePath = "/w/a"

    private fun workspaceTarget(path: String? = workspacePath) =
        SessionTarget.Workspace(workspaceId, path)

    /**
     * The two-step life of the hero seat, as the ViewModel drives it.
     *
     * `newSession` is every eager path (drawer `+`, per-workspace `+`, the
     * workspace chip, "Open here"); `firstUse` is send / a command / a staged
     * file. [creates] counts the one call that can add a session row.
     */
    private class Seat(
        private val workspacePaths: Map<String, String>,
        val roster: MutableList<SessionTargetCandidate> = mutableListOf(),
    ) {
        var creates = 0
        private var nextId = 0

        fun newSession(
            target: SessionTarget,
            preset: String? = null,
            permission: String? = null,
        ): SessionIntentPlan = SessionTargets.intent(
            target = target,
            preset = preset,
            permission = permission,
            currentSessionId = null,
            candidates = roster,
        )

        fun firstUse(pending: PendingSessionTarget): String? =
            when (val plan = SessionTargets.materialize(pending, roster)) {
                is SessionTargetPlan.Reuse -> plan.sessionId
                is SessionTargetPlan.Adopt -> {
                    // The idempotent adopt: no new row, the membership moves.
                    for (i in roster.indices) {
                        val row = roster[i]
                        if (row.id == plan.sessionId) {
                            roster[i] = row.copy(workspaceIds = row.workspaceIds + plan.workspaceId)
                        }
                    }
                    plan.sessionId
                }

                is SessionTargetPlan.Create -> {
                    creates++
                    val id = "created-$creates"
                    val path = plan.workspaceId?.let { workspacePaths[it] } ?: plan.cwd
                    roster += candidate(
                        id = id,
                        cwd = path,
                        workspaceIds = setOfNotNull(plan.workspaceId),
                    )
                    id
                }
            }
    }

    private fun seat(vararg existing: SessionTargetCandidate) = Seat(
        workspacePaths = mapOf(workspaceId to workspacePath),
        roster = existing.toMutableList(),
    )

    // ------------------------------------------------- nothing before first use

    @Test
    fun `a new-session intent on an empty roster records the target and creates nothing`() {
        val seat = seat()
        val intent = seat.newSession(workspaceTarget())
        assertTrue(intent is SessionIntentPlan.Record)
        assertEquals(workspaceTarget(), (intent as SessionIntentPlan.Record).pending.target)
        assertEquals(0, seat.creates)
        assertTrue(seat.roster.isEmpty())
    }

    @Test
    fun `leaving the hero after recording a target leaves no session behind`() {
        // The reported flow: New Session in W1, then New Session in W2, then leave.
        // Under eager creation that is two orphaned blanks; under deferral it is
        // still zero sessions, which is the whole property.
        val seat = seat()
        val first = seat.newSession(workspaceTarget())
        val second = seat.newSession(SessionTarget.Workspace("ws-2", "/w/b"))
        assertTrue(first is SessionIntentPlan.Record)
        assertTrue(second is SessionIntentPlan.Record)
        assertEquals(0, seat.creates)
        assertTrue(seat.roster.isEmpty())
    }

    @Test
    fun `the first use creates exactly one session, in the recorded target`() {
        val seat = seat()
        val recorded = seat.newSession(workspaceTarget()) as SessionIntentPlan.Record
        assertEquals("created-1", seat.firstUse(recorded.pending))
        assertEquals(1, seat.creates)
        assertEquals(workspaceId, seat.roster.single().workspaceIds.single())
    }

    @Test
    fun `a second use reuses the session the first use created, creating nothing more`() {
        val seat = seat()
        val recorded = seat.newSession(workspaceTarget()) as SessionIntentPlan.Record
        seat.firstUse(recorded.pending)
        val second = seat.firstUse(recorded.pending)
        assertEquals("created-1", second)
        assertEquals(1, seat.creates)
        assertTrue(SessionTargets.materialize(recorded.pending, seat.roster) is SessionTargetPlan.Reuse)
    }

    @Test
    fun `a blank that appeared while the seat was pending is adopted, not duplicated`() {
        val seat = seat()
        val recorded = seat.newSession(workspaceTarget()) as SessionIntentPlan.Record
        // Another client (or the host) put a blank at the target in the meantime.
        seat.roster += candidate("theirs", cwd = workspacePath)
        assertEquals("theirs", seat.firstUse(recorded.pending))
        assertEquals(0, seat.creates)
    }

    @Test
    fun `a blank member of the target workspace that appeared meanwhile is reused`() {
        val seat = seat()
        val recorded = seat.newSession(workspaceTarget()) as SessionIntentPlan.Record
        seat.roster += candidate("member", cwd = workspacePath, workspaceIds = setOf(workspaceId))
        assertEquals("member", seat.firstUse(recorded.pending))
        assertEquals(0, seat.creates)
    }

    // ------------------------------------------------------- reuse stays intact

    @Test
    fun `recording on a workspace whose blank exists opens it and creates nothing`() {
        val seat = seat(candidate("member", cwd = workspacePath, workspaceIds = setOf(workspaceId)))
        val intent = seat.newSession(workspaceTarget())
        assertEquals(SessionIntentPlan.Open("member"), intent)
        assertEquals(0, seat.creates)
    }

    @Test
    fun `recording on a loose blank rooted at the target adopts it and creates nothing`() {
        val seat = seat(candidate("loose", cwd = workspacePath))
        val intent = seat.newSession(workspaceTarget())
        assertEquals(SessionIntentPlan.Adopt("loose", workspaceId), intent)
        assertEquals(0, seat.creates)
    }

    @Test
    fun `recording on a typed directory with its own blank reuses it`() {
        val seat = seat(candidate("there", cwd = "/src"))
        val intent = seat.newSession(SessionTarget.Directory("/src"))
        assertEquals(SessionIntentPlan.Open("there"), intent)
        assertEquals(0, seat.creates)
    }

    @Test
    fun `a blank in another directory is not adopted across, so the seat is recorded`() {
        // The host's identity check is exact-directory, so a blank in W1 can never
        // become W2's session — the target is recorded rather than a duplicate born.
        val seat = seat(candidate("s1", cwd = "/w/a", workspaceIds = setOf("ws-1")))
        val intent = seat.newSession(SessionTarget.Workspace("ws-2", "/w/b"))
        assertTrue(intent is SessionIntentPlan.Record)
        assertEquals(0, seat.creates)
    }

    @Test
    fun `a session with history is never reused, so the seat is recorded`() {
        val seat = seat(candidate("used", cwd = workspacePath, blank = false, workspaceIds = setOf(workspaceId)))
        val intent = seat.newSession(workspaceTarget())
        assertTrue(intent is SessionIntentPlan.Record)
        assertEquals(0, seat.creates)
    }

    // ------------------------------------------------- the hero's other chips

    @Test
    fun `the recorded seat carries the preset and permission chosen on the hero`() {
        val seat = seat()
        val intent = seat.newSession(workspaceTarget(), preset = "minimal", permission = "danger-full-access")
        val recorded = intent as SessionIntentPlan.Record
        assertEquals("minimal", recorded.pending.preset)
        assertEquals("danger-full-access", recorded.pending.permission)
    }

    @Test
    fun `a directory target is recorded with its path, and materialises there`() {
        val seat = seat()
        val recorded = seat.newSession(SessionTarget.Directory("/src")) as SessionIntentPlan.Record
        assertEquals(0, seat.creates)
        assertEquals("created-1", seat.firstUse(recorded.pending))
        assertEquals("/src", seat.roster.single().cwd)
        assertNull(seat.roster.single().workspaceIds.singleOrNull())
    }

    // --------------------------------------------------------- permission parse

    @Test
    fun `a permission command line names the mode`() {
        assertEquals("danger-full-access", permissionCommandMode("/permission danger-full-access"))
        assertEquals("read-only", permissionCommandMode("  /permission   read-only  "))
    }

    @Test
    fun `a line that is not a permission command has no mode`() {
        assertNull(permissionCommandMode("/permission"))
        assertNull(permissionCommandMode("/permission a b"))
        assertNull(permissionCommandMode("/plan off"))
        assertNull(permissionCommandMode("permission read-only"))
        assertNull(permissionCommandMode(""))
    }
}

private fun candidate(
    id: String,
    cwd: String?,
    blank: Boolean = true,
    isSubagent: Boolean = false,
    archived: Boolean = false,
    workspaceIds: Set<String> = emptySet(),
) = SessionTargetCandidate(
    id = id,
    cwd = cwd,
    blank = blank,
    isSubagent = isSubagent,
    archived = archived,
    workspaceIds = workspaceIds,
)
