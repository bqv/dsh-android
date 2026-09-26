package uk.xa0.dsh.term

/**
 * The unconfined **host shell**: one dedicated Session per Workspace, created only to
 * carry a terminal, whose sandbox mode is `danger-full-access` so its shell is a real
 * one on the host.
 *
 * Why a second session at all. `terminal/create` derives the shell's confinement from
 * the *Session's* policy and has no per-terminal override
 * (`terminal-controller/src/index.ts`: `if (policy.mode !== 'danger-full-access')
 * argv = sandbox.confine(argv, policy)`). Under `workspace-write` that wraps the shell
 * in bwrap with `--ro-bind / /` and `--unshare-pid`, so the whole filesystem is
 * read-only to it and `htop`/`ps` see a private PID namespace. The user's own session
 * therefore cannot host an unconfined shell without the app changing the mode of the
 * session they are working in — which is the one thing this feature must never do.
 *
 * So the host shell is a Session of its own, and its mode is set **before** any
 * terminal in it exists, because the host refuses a mode change while one is open
 * ("Close browser terminals before changing the Session sandbox mode", verified).
 *
 * Nothing in this file touches Android, `org.json` or the network: the order of the
 * bootstrap, the seat resolution and the persisted mapping are the parts most likely
 * to be got wrong, and they are therefore the parts the JVM tests pin down.
 */

/** The one preset that makes a shell unconfined. Mirrors `FULL_ACCESS_PRESET`. */
const val HOST_SHELL_PRESET: String = "danger-full-access"

/** The canonical copy for [HOST_SHELL_PRESET] (see `ui-permission-presets`). */
const val HOST_SHELL_POLICY_LABEL: String = "Full access"

/**
 * The host shell Session's name: `Host shell · <workspace>`.
 *
 * Named by the workspace rather than the id, because an archived session is not
 * browsable and this name is what the user sees if they ever restore it from the
 * drawer's Archived list — the only way in, now that the app reaches the session by
 * a remembered id instead.
 */
fun hostShellSessionName(workspace: String): String = "Host shell \u00b7 $workspace"

/** The two seats the terminal panel offers for one session. */
enum class TerminalSeat {
    /** The unconfined shell of the session's workspace, in its own archived session. */
    HOST_SHELL,

    /** The session's own terminal, confined by whatever policy that session runs. */
    THIS_SESSION,
}

/**
 * One bootstrap step.
 *
 * The steps are a type rather than a sequence of calls so the order can be walked —
 * and tested — by one function, and so a caller that has not finished the mode step
 * has no terminal step to reach for.
 */
sealed interface HostShellStep {

    /** `session/create`: the dedicated shell session, rooted at the workspace. */
    data class CreateSession(val name: String, val workspaceId: String) : HostShellStep

    /** `commands/execute` `/permission danger-full-access` against that session. */
    data class SetAccessMode(val sessionId: String) : HostShellStep

    /** `workspace/archiveSession`: hide it, so it never appears in the drawer. */
    data class Archive(val sessionId: String) : HostShellStep
}

/**
 * What the workspace's host-shell bootstrap has established so far.
 *
 * The three facts are separate rather than one "ready" flag because the walk needs to
 * resume in the middle of them: a session that exists but is not yet unconfined must
 * go to [SetAccessMode], never to a terminal.
 */
data class HostShellProgress(
    /** The shell session's id, or null until `session/create` has answered. */
    val sessionId: String? = null,
    /** `danger-full-access` has been written to that session's log. */
    val modeSet: Boolean = false,
    /** The session is archived, so it does not sit in the drawer. */
    val archived: Boolean = false,
) {

    /**
     * True only once every step has run.
     *
     * This is the gate `terminal/create` must pass. A terminal created before
     * [modeSet] would be a *confined* shell — silently a different thing from the
     * seat the panel labels "Host shell · Full access" — and one created before
     * [archived] leaves the dedicated session sitting in the drawer as a session the
     * user did not ask for.
     */
    val terminalAllowed: Boolean
        get() = sessionId != null && modeSet && archived

    /**
     * The next step, or null once [terminalAllowed].
     *
     * **Order.** `create → set mode → archive`, always, and the walk cannot express
     * anything else: the mode step needs the id only `create` produces, and it is
     * also what wakes the session's agent, so there is no separate materializing call
     * to forget. The terminal itself is *not* a step here: [terminalAllowed] is the
     * only thing that unlocks it, and the caller checks it.
     */
    fun next(name: String, workspaceId: String): HostShellStep? = when {
        sessionId == null -> HostShellStep.CreateSession(name, workspaceId)
        !modeSet -> HostShellStep.SetAccessMode(sessionId)
        !archived -> HostShellStep.Archive(sessionId)
        else -> null
    }

    /**
     * Adopts an id from local prefs without claiming its mode is set.
     *
     * The remembered mapping is only evidence that this app created the session; the
     * walk still re-runs the mode step, which is a no-op on the host when the mode is
     * already `danger-full-access` (the preset write appends nothing when the knob
     * already matches) and therefore cannot be refused as a change while a terminal is
     * open.
     */
    fun withSession(id: String): HostShellProgress = HostShellProgress(sessionId = id)

    fun withModeSet(): HostShellProgress = copy(modeSet = true)

    fun withArchived(): HostShellProgress = copy(archived = true)
}

/** Why the workspace's host shell could not be materialized. */
enum class HostShellIssue {
    /** The session is in no registered Workspace, so there is no root to open in. */
    NO_WORKSPACE,

    /** `session/create` was refused. */
    CREATE_REFUSED,

    /** `/permission danger-full-access` was refused. */
    MODE_REFUSED,

    /** `workspace/archiveSession` was refused. */
    ARCHIVE_REFUSED,

    /** The remembered host-shell session is no longer on the host. */
    SESSION_GONE,

    /** The remembered mapping was built by another host, or the host lost the session. */
    NO_LIVE_AGENT,
}

/**
 * The fact to state for [issue], with the host's own words appended when there are any.
 *
 * These are the sentences the panel prints instead of a spinner. Each one names what
 * did *not* happen as well as what failed, because the honest report of a refused mode
 * change is that no terminal was created — not that a terminal is coming.
 */
fun hostShellIssueFact(issue: HostShellIssue, detail: String? = null): String {
    val sentence = when (issue) {
        HostShellIssue.NO_WORKSPACE ->
            "This session is not a member of a workspace, so there is no workspace root to open an unconfined " +
                "shell in. Use this session's own terminal, or move the session into a workspace."
        HostShellIssue.CREATE_REFUSED ->
            "The host refused to create the workspace's host-shell session. No terminal was created."
        HostShellIssue.MODE_REFUSED ->
            "The host refused to give the host shell full access, so it would have been a confined shell. " +
                "No terminal was created in it."
        HostShellIssue.ARCHIVE_REFUSED ->
            "The host refused to archive the host-shell session, so it would sit in the drawer as a session " +
                "you did not ask for. No terminal was created in it."
        HostShellIssue.SESSION_GONE ->
            "The host no longer has this workspace's host-shell session. Reopen this tab to build a new one."
        HostShellIssue.NO_LIVE_AGENT ->
            "The host has no live agent for this workspace's host-shell session, so no shell could be " +
                "created in it. Use this session's own terminal, or try again from the host shell seat."
    }
    return if (detail.isNullOrBlank()) sentence else "$sentence The host said: $detail"
}

/**
 * The chip's copy for one seat.
 *
 * The policy is part of the *name*, which is what replaces the old "this terminal is
 * sandboxed" notice: a reader who wants to know what the shell in front of them can
 * touch reads it off the seat they selected, rather than being told once and then
 * having to remember.
 */
fun seatLabel(seat: TerminalSeat, policyLabel: String): String = when (seat) {
    TerminalSeat.HOST_SHELL -> "Host shell \u00b7 $policyLabel"
    TerminalSeat.THIS_SESSION -> "This session \u00b7 $policyLabel"
}

/** One seat as the panel offers it. */
data class SeatOption(
    val seat: TerminalSeat,
    /** The chip's copy, carrying the policy in force in that seat. */
    val label: String,
    /**
     * The session id terminal RPCs address for this seat — the host shell's own
     * session for [TerminalSeat.HOST_SHELL], or null until it exists.
     */
    val agentId: String?,
)

/**
 * The seats the terminal panel offers for one session, in display order.
 *
 * The host shell comes **first** because it is the default seat: an unconfined shell
 * is the point of the panel, and the session's own terminal is the deliberate second
 * choice next to it. Its policy word is fixed, because the bootstrap is what makes it
 * true — the seat is only offered as unconfined when the mode step has run.
 */
fun terminalSeats(
    sessionId: String,
    sessionPolicyLabel: String,
    hostShellSessionId: String?,
): List<SeatOption> = listOf(
    SeatOption(
        seat = TerminalSeat.HOST_SHELL,
        label = seatLabel(TerminalSeat.HOST_SHELL, HOST_SHELL_POLICY_LABEL),
        agentId = hostShellSessionId,
    ),
    SeatOption(
        seat = TerminalSeat.THIS_SESSION,
        label = seatLabel(
            TerminalSeat.THIS_SESSION,
            sessionPolicyLabel.ifBlank { "access unknown" },
        ),
        agentId = sessionId,
    ),
)

// ------------------------------------------------------------------- persistence

/**
 * The workspace → host-shell session mapping, as one `workspace-id<TAB>session-id` line
 * per entry.
 *
 * A text encoding rather than JSON because `ConfigStore` is the one place in the app
 * that holds client-local preferences, and `org.json` there would make the mapping
 * untestable on the JVM (the stub `android.jar` throws). Workspace and session ids are
 * `[\w-]`, so a tab is unambiguous and needs no escaping; a malformed line is dropped
 * rather than throwing, because a preference the app cannot read must not be able to
 * stop it from starting.
 */
fun encodeHostShellSessions(sessions: Map<String, String>): String = sessions.entries
    .filter { it.key.isNotBlank() && it.value.isNotBlank() }
    .joinToString("\n") { "${it.key}\t${it.value}" }

fun decodeHostShellSessions(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    val sessions = LinkedHashMap<String, String>()
    for (line in raw.split('\n')) {
        val tab = line.indexOf('\t')
        if (tab <= 0) continue
        val workspaceId = line.substring(0, tab)
        val sessionId = line.substring(tab + 1)
        if (workspaceId.isNotBlank() && sessionId.isNotBlank()) sessions[workspaceId] = sessionId
    }
    return sessions
}

/**
 * The mapping with one workspace's host shell remembered (or, for a blank [sessionId],
 * forgotten — which is what an id the host no longer knows has to become).
 */
fun Map<String, String>.withHostShellSession(workspaceId: String, sessionId: String?): Map<String, String> =
    if (sessionId.isNullOrBlank()) this - workspaceId else this + (workspaceId to sessionId)
