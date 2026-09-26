package uk.xa0.dsh.model

/**
 * The "start a session here" decision, as pure Kotlin.
 *
 * The host fixes a session's working directory at `session/create` and offers no
 * "change cwd" RPC, so every workspace/directory pick has to answer the same
 * question: is there already a session that *is* the one the user means, or does
 * one have to be born? Answering it in one place is what keeps the drawer from
 * accumulating abandoned blank sessions — every entry point (the drawer's New
 * Session, a per-workspace `+`, the composer's workspace chip, the directory
 * browser's "Open here") used to carry its own, slightly different, reuse check.
 *
 * The rule, in order:
 *
 *  1. **Adopt the blank you are already looking at** when the host will accept
 *     it, i.e. its stored `cwd` is exactly the target Workspace's path and the
 *     Workspace does not account it yet. `session/create` with *both* `sessionId`
 *     and `workspaceId` is an idempotent adopt: it resumes the existing session,
 *     checks its directory against the Workspace, and attaches it to the group.
 *     Nothing new is created and nothing is left behind.
 *  2. **Reuse a blank the target Workspace already accounts** — the session this
 *     intent means is already there. Opening it issues no call at all.
 *  3. **Adopt a blank rooted at the target path that is not accounted yet** (it
 *     was created at a bare `cwd`, or before the registry reached this client).
 *     Leaving it Ungrouped and creating a second session at the same directory is
 *     exactly the duplicate this rule exists to prevent.
 *  4. **Create**, and only then.
 *
 * Since the host has no session-delete RPC, even this rule cannot make a wrong
 * "New Session" tap recoverable — an orphaned blank stays a blank forever. So the
 * intent entry point ([intent]) does not run step 4 at all: it hands the caller a
 * [SessionIntentPlan.Record] instead, and step 4 is deferred to the seat's first
 * real use ([materialize]).
 *
 * A session that is not [SessionTargetCandidate.blank] is never touched: it has
 * history, its directory is part of that history, and "New Session" must not
 * silently hand the reader somebody else's conversation. Subagent children and
 * archived sessions are skipped for the same reason the drawer hides them.
 *
 * ## What this rule cannot do
 *
 * Adoption is **same-directory only**. `ApiSessionAgentController.ensureSession`
 * (host `packages/api/session-controller/src/agent.ts`) rejects the request with
 * `session/conflict` when the stored header `cwd` is not string-equal to the
 * Workspace path, *before* `WorkspaceEntity.attachSession` — whose check is the
 * canonical form of the same comparison — is ever reached. So a blank sitting in
 * workspace A cannot be moved into workspace B, and the step-1 branch only fires
 * when the target directory is where the session already lives. The roster's
 * `cwd` and the registry's `path` are compared as exact strings for that reason:
 * a non-canonical spelling of the same directory is not adoptable, even though
 * it would pass `attachSession`, because the earlier check refuses it first.
 */
data class SessionTargetCandidate(
    val id: String,
    /** The stored session header's directory; null when the header carries none. */
    val cwd: String?,
    /** The host still calls this session provisional (`title: null`, no turn yet). */
    val blank: Boolean,
    val isSubagent: Boolean,
    val archived: Boolean,
    /** Workspace ids the host accounts this session to (its `sessionIds`). */
    val workspaceIds: Set<String> = emptySet(),
)

/** The directory a "new session" intent targets. */
sealed interface SessionTarget {
    /**
     * A registered Workspace, addressed by id.
     *
     * [path] is the registry's canonical path, or null until this client has seen
     * the Workspace. The id alone is still enough to create into — the host
     * resolves the directory — which is why the id form is what a pick reports.
     */
    data class Workspace(val id: String, val path: String?) : SessionTarget

    /** A directory the user typed that no loaded Workspace owns. */
    data class Directory(val path: String) : SessionTarget
}

/**
 * The new-session seat **before any session exists**: a recorded target, no
 * `session/create` behind it.
 *
 * This is the app's answer to the host's missing session-delete RPC. A session
 * cannot be unmade, so the only way not to litter one is never to make the seat
 * into one until the user actually needs a session. `currentSessionId == null` is
 * deliberately *not* overloaded for this: a null open session also means "cold
 * start with nothing to reopen" and "the screen while a session is being swapped",
 * and every one of those would then look like a pending seat. Modelling it as its
 * own value makes "there is nothing behind this hero" a fact a caller can test.
 *
 * [preset] and [permission] are the hero's own configuration seats. Both need a
 * session id to have any effect (`agentPresets/select`, `/permission`), so on a
 * pending seat they are *recorded* here and applied in the same order at
 * materialisation — a chip tap must not be what creates the session.
 */
data class PendingSessionTarget(
    /** Where the session will be created when the seat is first used. */
    val target: SessionTarget,
    /** `agentPreset` to create it with; null means the host's own default. */
    val preset: String? = null,
    /** Access mode to apply right after creation; null means leave the default. */
    val permission: String? = null,
)

/**
 * What one "start a session here" intent does, now that creation is deferred.
 *
 * This is [SessionTargetPlan] pushed one level up: it keeps the plan's reuse and
 * adopt rules intact and replaces its one eager branch — `Create` — with a
 * [Record] that issues nothing at all. Every path that used to call
 * `session/create` eagerly (the drawer's New Session, a per-workspace `+`, the
 * composer's workspace chip, the browser's "Open here") runs this instead, so
 * "create nothing until first use" is decided in one place.
 */
sealed interface SessionIntentPlan {
    /** A blank already is this intent: open it. Issues no `session/create`. */
    data class Open(val sessionId: String) : SessionIntentPlan

    /**
     * The host holds a blank at the target directory: adopt it by both ids (an
     * idempotent `session/create` that adds no session).
     */
    data class Adopt(val sessionId: String, val workspaceId: String) : SessionIntentPlan

    /** Nothing fits yet: show the hero with this seat and issue nothing. */
    data class Record(val pending: PendingSessionTarget) : SessionIntentPlan
}

/** How one "new session" intent resolves: one `session/create`, or none. */
sealed interface SessionTargetPlan {
    /**
     * `session/create` with both ids: idempotent adopt into [workspaceId].
     * No second session is born and the adopted one is not left behind.
     */
    data class Adopt(val sessionId: String, val workspaceId: String) : SessionTargetPlan

    /** Open [sessionId], which already is what the intent means. Issues no call. */
    data class Reuse(val sessionId: String) : SessionTargetPlan

    /**
     * `session/create` without a `sessionId` — the only branch that adds a
     * session. Exactly one of [workspaceId] / [cwd] is non-null, mirroring the
     * host's "one or the other, never both" rule.
     */
    data class Create(val workspaceId: String?, val cwd: String?) : SessionTargetPlan
}

object SessionTargets {

    /**
     * Resolves one "start a session" intent.
     *
     * @param target - the Workspace (by id) or directory the intent names.
     * @param currentSessionId - the session on screen, which step 1 may adopt.
     * @param candidates - the host's current roster, with membership as the
     * Workspace registry reports it.
     */
    fun plan(
        target: SessionTarget,
        currentSessionId: String?,
        candidates: List<SessionTargetCandidate>,
    ): SessionTargetPlan {
        val blanks = candidates.filter { it.blank && !it.isSubagent && !it.archived }
        return when (target) {
            is SessionTarget.Workspace -> planWorkspace(target, currentSessionId, blanks)
            is SessionTarget.Directory -> planDirectory(target.path, blanks)
        }
    }

    /**
     * Resolves one "start a session" intent **without creating anything**.
     *
     * A blank that fits is opened or adopted exactly as [plan] decides; the only
     * branch that would have added a session ([SessionTargetPlan.Create]) is turned
     * into [SessionIntentPlan.Record], which carries the target forward instead.
     * [preset] and [permission] ride along so the hero's chips survive the wait.
     *
     * "Nothing is created before first use" is this function's whole contract: the
     * returned value names a session id only when one already exists.
     */
    fun intent(
        target: SessionTarget,
        preset: String? = null,
        permission: String? = null,
        currentSessionId: String?,
        candidates: List<SessionTargetCandidate>,
    ): SessionIntentPlan = when (val resolved = plan(target, currentSessionId, candidates)) {
        is SessionTargetPlan.Adopt -> SessionIntentPlan.Adopt(resolved.sessionId, resolved.workspaceId)
        is SessionTargetPlan.Reuse -> SessionIntentPlan.Open(resolved.sessionId)
        is SessionTargetPlan.Create -> SessionIntentPlan.Record(
            PendingSessionTarget(target = target, preset = preset, permission = permission),
        )
    }

    /**
     * What the **first real use** of a deferred seat does: the same decision,
     * re-run against the roster as it is *now*.
     *
     * [currentSessionId] is deliberately null. The hero holds no session, so there
     * is nothing to adopt from the seat itself; the choice is between reusing a
     * blank the host has meanwhile acquired at the target and creating one. The
     * roster is re-read rather than remembered because a materialisation can be
     * seconds after the tap, and the reuse rules must hold at the moment the one
     * call that can add a row is actually made.
     */
    fun materialize(
        pending: PendingSessionTarget,
        candidates: List<SessionTargetCandidate>,
    ): SessionTargetPlan = plan(pending.target, currentSessionId = null, candidates)

    private fun planWorkspace(
        target: SessionTarget.Workspace,
        currentSessionId: String?,
        blanks: List<SessionTargetCandidate>,
    ): SessionTargetPlan {
        // 1. The blank we are looking at follows us into this Workspace when its
        //    stored cwd already is this Workspace's directory. That is the one
        //    case the host's identity check accepts, and it is preferred over
        //    opening a different blank because it leaves no session behind.
        val current = blanks.firstOrNull { it.id == currentSessionId }
        if (current != null && target.path != null && current.cwd == target.path &&
            target.id !in current.workspaceIds
        ) {
            return SessionTargetPlan.Adopt(current.id, target.id)
        }
        // 2. A blank the Workspace already accounts is the session this intent
        //    means. Membership alone is the test: the host only accounts a
        //    session to a Workspace whose directory matches, and re-deriving that
        //    from the roster's cwd string would miss a member spelled
        //    differently, which reads as "no blank there" and creates a duplicate.
        blanks.firstOrNull { target.id in it.workspaceIds }?.let {
            return SessionTargetPlan.Reuse(it.id)
        }
        // 3. A blank rooted here but not accounted yet is adopted rather than
        //    duplicated. Exact string equality is deliberate: the host compares
        //    `header.cwd` to the Workspace path byte-for-byte in `ensureSession`.
        if (target.path != null) {
            blanks.firstOrNull { it.cwd == target.path }?.let {
                return SessionTargetPlan.Adopt(it.id, target.id)
            }
        }
        return SessionTargetPlan.Create(target.id, null)
    }

    private fun planDirectory(path: String, blanks: List<SessionTargetCandidate>): SessionTargetPlan {
        // A blank at exactly this directory is already the session the user
        // means, whether or not some Workspace accounts it: opening it puts them
        // in the right directory either way.
        blanks.firstOrNull { it.cwd != null && it.cwd == path }?.let {
            return SessionTargetPlan.Reuse(it.id)
        }
        return SessionTargetPlan.Create(null, path)
    }
}

/**
 * The access mode named by a `/permission <mode>` line, or null when the line is
 * not that command.
 *
 * The hero has no session for `commands/execute` to run in, and access is one of
 * the two configuration seats the pending hero owns. Recognising the command here
 * is what lets `/permission` be **recorded** on the pending seat (and applied at
 * materialisation) instead of being the act that creates the session. Anything
 * that is not exactly this two-token form — bare `/permission`, another command,
 * prose the reader typed — returns null, so the caller materialises as usual.
 */
fun permissionCommandMode(line: String): String? {
    val parts = line.trim().split(WHITESPACE)
    if (parts.size != 2 || parts[0] != "/permission") return null
    return parts[1].takeIf { it.isNotBlank() }
}

private val WHITESPACE = Regex("\\s+")
