package uk.xa0.dsh

import uk.xa0.dsh.term.TerminalEnvironmentInfo
import uk.xa0.dsh.term.TerminalInfo
import uk.xa0.dsh.term.TerminalIssue
import uk.xa0.dsh.term.TerminalState

/**
 * Where the session terminal panel is in its life.
 *
 * Mirrors the web client's `TerminalViewState.phase`, with this client's habit of
 * naming the states the user can actually see: `DISCONNECTED` is a socket the app
 * will retry, `FAILED` is a fact it has to report.
 */
enum class TerminalPhase {
    /** The tab is not open. */
    IDLE,
    LOADING,
    CREATING,
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    CLOSING,
    CLOSED,
    FAILED,
}

/**
 * The session terminal panel's state.
 *
 * Deliberately *not* carrying the screen: a repainting `htop` rewrites thousands of
 * cells a second, and copying a 100×40 grid into an immutable state object on every
 * frame would allocate the whole grid per repaint. The screen lives in a
 * `TerminalEmulator` the Canvas reads in place, and [revision] is what tells it to
 * redraw — see `DshViewModel.terminalScreen()`.
 */
data class TerminalUiState(
    val sessionId: String? = null,
    val phase: TerminalPhase = TerminalPhase.IDLE,
    val environment: TerminalEnvironmentInfo? = null,
    /** `terminal/list` for this session, so a retained terminal is reused, not replaced. */
    val terminals: List<TerminalInfo> = emptyList(),
    val active: TerminalInfo? = null,
    /** True only while this panel's attachment owns input. */
    val writable: Boolean = false,
    val issue: TerminalIssue? = null,
    val error: String? = null,
    /** `terminal/limit-reached` carries the host's limit; it belongs in the sentence. */
    val limit: Int? = null,
    val revision: Long = 0L,
) {
    val title: String get() = active?.title.orEmpty()
}

/**
 * The phase a `state` frame's [TerminalInfo] implies, or null when the frame is
 * metadata only and the panel already knows the phase.
 *
 * An exited shell stays `DISCONNECTED` rather than `CLOSED`: its last screen is
 * the evidence of what happened, and the placeholder would throw it away. A shell
 * that failed to start has no such screen to keep, so it goes to `FAILED`.
 */
fun terminalPhaseFor(info: TerminalInfo): TerminalPhase? = when (info.state) {
    TerminalState.RUNNING -> TerminalPhase.CONNECTED
    TerminalState.EXITED -> TerminalPhase.DISCONNECTED
    TerminalState.FAILED -> TerminalPhase.FAILED
    TerminalState.UNKNOWN -> null
}

/**
 * The fact to state for a terminal that has stopped, or null while it runs.
 *
 * `NOT_RUNNING` used to be reachable only from a *refused write*, so a shell the
 * reader ended with `exit` left the bar saying "running" and typing doing nothing.
 */
fun terminalStopIssue(info: TerminalInfo): TerminalIssue? = when (info.state) {
    TerminalState.EXITED, TerminalState.FAILED -> TerminalIssue.NOT_RUNNING
    else -> null
}

/**
 * The host's own words for a stopped terminal — the exit code, or the failure it
 * reported. Carried in [TerminalUiState.error].
 */
fun terminalStopDetail(info: TerminalInfo): String? = when (info.state) {
    TerminalState.EXITED ->
        info.exitCode?.let { "The shell exited with code $it." } ?: "The shell exited."
    TerminalState.FAILED -> info.error ?: "The shell failed to start."
    else -> null
}
