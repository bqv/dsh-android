package uk.xa0.dsh

import uk.xa0.dsh.term.TerminalEnvironmentInfo
import uk.xa0.dsh.term.TerminalInfo
import uk.xa0.dsh.term.TerminalIssue

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
