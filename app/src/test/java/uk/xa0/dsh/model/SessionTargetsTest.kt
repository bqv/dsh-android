package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The session-target decision, driven directly.
 *
 * `DshViewModel` is an `AndroidViewModel`, so no JVM test can construct it — the
 * same reason the terminal logic has its own suite. This is the rule that decides
 * whether a "New Session" tap reuses, adopts or creates, extracted into plain
 * Kotlin so it can be pinned.
 */
class SessionTargetsTest {

    private val workspaceId = "ws-1"

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

    private fun planWorkspace(
        path: String?,
        currentSessionId: String?,
        candidates: List<SessionTargetCandidate>,
        id: String = workspaceId,
    ) = SessionTargets.plan(SessionTarget.Workspace(id, path), currentSessionId, candidates)

    // ---------------------------------------------------------------- adopt

    @Test
    fun `adopts the blank you are in when the workspace is its own directory`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "s1",
            candidates = listOf(candidate("s1", cwd = "/w/a")),
        )
        assertEquals(SessionTargetPlan.Adopt("s1", workspaceId), plan)
    }

    @Test
    fun `does not adopt across directories, and reuses the target's own blank instead`() {
        // The reported litter: a blank born in /w/a, then a pick of /w/b. The host
        // refuses the adopt (its identity check is the stored cwd against the
        // workspace path), so the plan must not name it — it names /w/b's blank.
        val plan = planWorkspace(
            path = "/w/b",
            currentSessionId = "s1",
            candidates = listOf(
                candidate("s1", cwd = "/w/a"),
                candidate("s2", cwd = "/w/b", workspaceIds = setOf(workspaceId)),
            ),
        )
        assertEquals(SessionTargetPlan.Reuse("s2"), plan)
    }

    @Test
    fun `does not adopt across directories when the target has no blank, so it creates`() {
        val plan = planWorkspace(
            path = "/w/b",
            currentSessionId = "s1",
            candidates = listOf(candidate("s1", cwd = "/w/a")),
        )
        assertEquals(SessionTargetPlan.Create(workspaceId, null), plan)
    }

    @Test
    fun `adopts an ungrouped blank rooted at the target instead of duplicating it`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = null,
            candidates = listOf(candidate("loose", cwd = "/w/a")),
        )
        assertEquals(SessionTargetPlan.Adopt("loose", workspaceId), plan)
    }

    @Test
    fun `the blank you are in beats a loose blank at the same directory`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "mine",
            candidates = listOf(
                candidate("other", cwd = "/w/a"),
                candidate("mine", cwd = "/w/a"),
            ),
        )
        assertEquals(SessionTargetPlan.Adopt("mine", workspaceId), plan)
    }

    @Test
    fun `adoption needs an exact cwd match, because the host compares the strings`() {
        // A trailing slash canonicalises to the same directory but is not the
        // string the host checks, so it must plan a create rather than an adopt
        // the host would answer with `session/conflict`.
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "s1",
            candidates = listOf(candidate("s1", cwd = "/w/a/")),
        )
        assertEquals(SessionTargetPlan.Create(workspaceId, null), plan)
    }

    // ---------------------------------------------------------------- reuse

    @Test
    fun `reuses a blank the target workspace already accounts`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = null,
            candidates = listOf(candidate("member", cwd = "/w/a", workspaceIds = setOf(workspaceId))),
        )
        assertEquals(SessionTargetPlan.Reuse("member"), plan)
    }

    @Test
    fun `membership alone reuses a blank even when its cwd string differs`() {
        // The registry has already decided the session belongs to this workspace;
        // re-deriving that from a cwd spelling would miss it and create a duplicate.
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = null,
            candidates = listOf(candidate("member", cwd = "/w/a/", workspaceIds = setOf(workspaceId))),
        )
        assertEquals(SessionTargetPlan.Reuse("member"), plan)
    }

    @Test
    fun `reuses a member even before the registry path has loaded`() {
        val plan = planWorkspace(
            path = null,
            currentSessionId = null,
            candidates = listOf(candidate("member", cwd = "/w/a", workspaceIds = setOf(workspaceId))),
        )
        assertEquals(SessionTargetPlan.Reuse("member"), plan)
    }

    @Test
    fun `the blank you are in is reused, not adopted again, when it is already a member`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "s1",
            candidates = listOf(candidate("s1", cwd = "/w/a", workspaceIds = setOf(workspaceId))),
        )
        assertEquals(SessionTargetPlan.Reuse("s1"), plan)
    }

    // ----------------------------------------------------------- never reuse

    @Test
    fun `never reuses a session that has history`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "s1",
            candidates = listOf(
                candidate("s1", cwd = "/w/a", blank = false),
                candidate("s2", cwd = "/w/a", blank = false, workspaceIds = setOf(workspaceId)),
            ),
        )
        assertEquals(SessionTargetPlan.Create(workspaceId, null), plan)
    }

    @Test
    fun `never adopts the session you are in once it has history`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "s1",
            candidates = listOf(candidate("s1", cwd = "/w/a", blank = false)),
        )
        assertEquals(SessionTargetPlan.Create(workspaceId, null), plan)
    }

    @Test
    fun `skips an archived blank`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "s1",
            candidates = listOf(
                candidate("s1", cwd = "/w/a", archived = true),
                candidate("s2", cwd = "/w/a", archived = true, workspaceIds = setOf(workspaceId)),
            ),
        )
        assertEquals(SessionTargetPlan.Create(workspaceId, null), plan)
    }

    @Test
    fun `skips a subagent blank`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "child",
            candidates = listOf(
                candidate("child", cwd = "/w/a", isSubagent = true),
                candidate("member", cwd = "/w/a", isSubagent = true, workspaceIds = setOf(workspaceId)),
            ),
        )
        assertEquals(SessionTargetPlan.Create(workspaceId, null), plan)
    }

    @Test
    fun `a blank belonging to another workspace is not this workspace's blank`() {
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = null,
            candidates = listOf(candidate("other", cwd = "/w/other", workspaceIds = setOf("ws-2"))),
        )
        assertEquals(SessionTargetPlan.Create(workspaceId, null), plan)
    }

    // ------------------------------------------------------------- directory

    @Test
    fun `reuses a blank at the typed directory`() {
        val plan = SessionTargets.plan(
            SessionTarget.Directory("/src"),
            currentSessionId = null,
            candidates = listOf(candidate("s1", cwd = "/src")),
        )
        assertEquals(SessionTargetPlan.Reuse("s1"), plan)
    }

    @Test
    fun `creates at a typed directory with no blank`() {
        val plan = SessionTargets.plan(
            SessionTarget.Directory("/src"),
            currentSessionId = null,
            candidates = listOf(candidate("s1", cwd = "/elsewhere")),
        )
        assertEquals(SessionTargetPlan.Create(null, "/src"), plan)
    }

    @Test
    fun `a typed directory ignores membership and matches the directory`() {
        val plan = SessionTargets.plan(
            SessionTarget.Directory("/src"),
            currentSessionId = null,
            candidates = listOf(candidate("s1", cwd = "/src", workspaceIds = setOf("ws-2"))),
        )
        assertEquals(SessionTargetPlan.Reuse("s1"), plan)
    }

    @Test
    fun `a typed directory never reuses a session with history`() {
        val plan = SessionTargets.plan(
            SessionTarget.Directory("/src"),
            currentSessionId = "s1",
            candidates = listOf(candidate("s1", cwd = "/src", blank = false)),
        )
        assertEquals(SessionTargetPlan.Create(null, "/src"), plan)
    }

    @Test
    fun `a candidate with no cwd cannot be adopted or reused`() {
        // The host's attach validation refuses a header with no cwd outright, so a
        // loose session without one is never the target's blank.
        val plan = planWorkspace(
            path = "/w/a",
            currentSessionId = "s1",
            candidates = listOf(candidate("s1", cwd = null)),
        )
        assertTrue(plan is SessionTargetPlan.Create)
    }
}
