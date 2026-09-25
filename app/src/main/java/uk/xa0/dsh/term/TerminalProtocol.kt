package uk.xa0.dsh.term

/**
 * The terminal RPC's own vocabulary, plus the attachment rules the web client
 * (`terminal-controller/src/client/model.ts`) enforces.
 *
 * Everything here is plain Kotlin with no `org.json` and no Android import: this
 * is the half of the feature that is *most* likely to be wrong — a generation that
 * does not start with a snapshot, or an output frame whose sequence has a gap,
 * means the screen is silently wrong forever — and it is therefore the half the
 * JVM unit tests pin down.
 */

enum class TerminalState { RUNNING, EXITED, FAILED, UNKNOWN }

/** `WebTerminalInfo` from the generated descriptor. */
data class TerminalInfo(
    val id: String,
    val title: String,
    val shell: String,
    val cwd: String,
    val cols: Int,
    val rows: Int,
    val state: TerminalState,
    val exitCode: Int? = null,
    val error: String? = null,
    /** The attachment that currently owns input; absent when nothing is attached. */
    val controllerId: String? = null,
)

/** `TerminalEnvironment`: the host's grid limits. */
data class TerminalEnvironmentInfo(
    val cwd: String,
    val maxInputBytes: Int,
    val maxCols: Int,
    val maxRows: Int,
    val scrollback: Int,
)

data class TerminalShellInfo(val path: String, val args: List<String>, val name: String)

/** `TerminalFrame`: what a `terminal/follow` stream carries. */
sealed interface TerminalFrame {
    /** A complete bounded screen. Every attachment — and every reconnect — begins here. */
    data class Snapshot(val sequence: Long, val screen: String, val info: TerminalInfo) : TerminalFrame

    /**
     * Ordered output. `data` is raw PTY text and must be fed to the emulator as
     * UTF-8 bytes, exactly as the web client feeds it to xterm: it can split a
     * UTF-8 sequence or an escape sequence across frames.
     */
    data class Output(val sequence: Long, val data: String) : TerminalFrame

    data class State(val info: TerminalInfo) : TerminalFrame
}

fun TerminalFrame.infoOrNull(): TerminalInfo? = when (this) {
    is TerminalFrame.Snapshot -> info
    is TerminalFrame.Output -> null
    is TerminalFrame.State -> info
}

/** Raised when a stream breaks the reference client's ordering contract. */
class TerminalProtocolException(message: String) : Exception(message)

/** What the caller must do about an accepted frame. */
enum class TerminalGateOutcome {
    /** A new stream generation: reset the emulator, then feed the snapshot screen. */
    NEW_GENERATION,

    /** Same generation; feed the bytes. */
    OUTPUT,

    /** Metadata only. */
    STATE,
}

/**
 * Enforces the ordering the host's `BrowserTerminal` guarantees and the web client
 * relies on.
 *
 * The invariant that matters: a stream *always* begins with a complete screen, and
 * every later output frame continues the previous one by exactly one sequence
 * number. A gap means the app missed output, and a screen with a silent hole in it
 * is worse than an honest failure — this is the web client's `invalidOutput`.
 *
 * **Generation boundary.** The web client gets an explicit `item.generation` from
 * its own stream supervisor. `RemoteMux` in this app does not surface one: its
 * `StreamEvent.Item` carries the value alone. So the boundary is taken to be the
 * snapshot itself, which is sound here because the host sends exactly one snapshot
 * per attachment (`BrowserTerminal.follow` yields it first) and a `?1049`-style
 * replay after a reconnect therefore always starts with one. Consequence: a
 * generation that began with an *output* frame instead of a snapshot would only be
 * caught by the sequence check. Surfacing the mux's `generation` on `StreamEvent`
 * is the change that would close that gap, and it was not needed for this MVP.
 */
class TerminalStreamGate {

    /** Snapshots seen, i.e. attachments this gate has resynchronised. */
    var generation: Int = -1
        private set

    var sequence: Long = 0
        private set

    /** Latest known metadata; the state frames keep this current. */
    var info: TerminalInfo? = null
        private set

    fun accept(frame: TerminalFrame): TerminalGateOutcome = when (frame) {
        is TerminalFrame.Snapshot -> {
            generation++
            // A snapshot's own `sequence` becomes the baseline for this generation.
            sequence = frame.sequence
            info = frame.info
            TerminalGateOutcome.NEW_GENERATION
        }

        is TerminalFrame.Output -> {
            if (generation < 0) {
                throw TerminalProtocolException("Terminal output generation is missing its screen snapshot")
            }
            if (frame.sequence != sequence + 1) {
                throw TerminalProtocolException("Terminal output sequence has a gap")
            }
            sequence = frame.sequence
            TerminalGateOutcome.OUTPUT
        }

        is TerminalFrame.State -> {
            if (generation < 0) {
                throw TerminalProtocolException("Terminal output generation is missing its screen snapshot")
            }
            info = frame.info
            TerminalGateOutcome.STATE
        }
    }

    fun reset() {
        generation = -1
        sequence = 0
        info = null
    }
}

/**
 * Input control follows the attachment, not the terminal.
 *
 * A newer `follow` takes input and an older one goes read-only, so a panel that
 * merely *thinks* it is attached would type into a shell that is no longer
 * listening. The host states the answer in `controllerId`.
 */
fun isWritable(info: TerminalInfo?, attachmentId: String?): Boolean =
    info != null && attachmentId != null &&
        info.state == TerminalState.RUNNING && info.controllerId == attachmentId

/**
 * Drops resizes that would not change the grid.
 *
 * The panel is measured on every layout pass and the soft keyboard changes the
 * height on every open and close, so without this the PTY would take a
 * `terminal/resize` for every frame it already has. The reference client's
 * `if (state.info.cols === cols && state.info.rows === rows) return` is the same
 * rule, plus the grid reported by `state` frames.
 */
class TerminalResizeGate {

    private var cols = -1
    private var rows = -1

    /** Adopts the grid the host says it has, without sending anything. */
    fun adopt(info: TerminalInfo) {
        cols = info.cols
        rows = info.rows
    }

    fun shouldSend(columns: Int, rows: Int): Boolean {
        if (columns == this.cols && rows == this.rows) return false
        this.cols = columns
        this.rows = rows
        return true
    }

    fun clear() {
        cols = -1
        rows = -1
    }
}

/**
 * The queued-input budget, mirroring the reference client's `queuedInput`.
 *
 * The host refuses a single write over `maxInputBytes`; the web client also counts
 * input that is *queued* behind an in-flight write, because a fast typist on a slow
 * link could otherwise exceed the same limit across several requests.
 */
class TerminalInputBudget(private val maxBytes: Int) {

    var pending: Int = 0
        private set

    /** Reserves room for [bytes], or refuses so the caller can surface `inputFull`. */
    fun offer(bytes: Int): Boolean {
        if (bytes > maxBytes || pending + bytes > maxBytes) return false
        pending += bytes
        return true
    }

    fun release(bytes: Int) {
        pending = (pending - bytes).coerceAtLeast(0)
    }
}

/** The host's grid limits, applied to a measured panel. */
fun clampGrid(
    columns: Int,
    rows: Int,
    environment: TerminalEnvironmentInfo?,
): Pair<Int, Int> {
    val maxCols = environment?.maxCols ?: Int.MAX_VALUE
    val maxRows = environment?.maxRows ?: Int.MAX_VALUE
    // 2 columns is the host's own floor (`dimensions()` rejects cols < 2).
    return columns.coerceIn(2, maxCols.coerceAtLeast(2)) to rows.coerceIn(1, maxRows.coerceAtLeast(1))
}

/** A failure the panel can state as a fact. */
enum class TerminalIssue {
    NO_LIVE_AGENT,
    LIMIT_REACHED,
    READ_ONLY,
    NOT_RUNNING,
    MISSING_TERMINAL,
    INPUT_FULL,
    ATTACHMENT_ENDED,
    OUTPUT_INVALID,
    UNKNOWN,
}

/**
 * Maps a host error code onto the fact it actually represents.
 *
 * `gateway/lookup-not-found` in particular must not be shown as "connecting":
 * it means this session has no live agent, which is permanent until the session
 * is started, and an eternal spinner is the wrong answer to a permanent fact.
 */
fun terminalIssueOf(code: String, reason: String?): TerminalIssue = when (code) {
    "gateway/lookup-not-found" -> TerminalIssue.NO_LIVE_AGENT
    "terminal/limit-reached" -> TerminalIssue.LIMIT_REACHED
    "terminal/control-unavailable" -> when (reason) {
        "read-only" -> TerminalIssue.READ_ONLY
        "not-running" -> TerminalIssue.NOT_RUNNING
        else -> TerminalIssue.UNKNOWN
    }
    "terminal/missing" -> TerminalIssue.MISSING_TERMINAL
    else -> TerminalIssue.UNKNOWN
}

/** The sentence the panel prints for [issue]. Facts, not reassurances. */
fun terminalIssueFact(issue: TerminalIssue, limit: Int? = null): String = when (issue) {
    TerminalIssue.NO_LIVE_AGENT ->
        "This session has no live agent on the host, so there is no shell to attach to. " +
            "Send a prompt to start the session, then reopen this tab."
    TerminalIssue.LIMIT_REACHED ->
        "This session already holds the host's limit of ${limit ?: 8} terminals. Close one to open another."
    TerminalIssue.READ_ONLY ->
        "Another attachment took input control, so this panel is read-only. Reconnect to take it back."
    TerminalIssue.NOT_RUNNING -> "The shell in this terminal has stopped."
    TerminalIssue.MISSING_TERMINAL -> "This terminal is no longer in the session's list."
    TerminalIssue.INPUT_FULL -> "Keystrokes are queuing faster than the host accepts them, and some were dropped."
    TerminalIssue.ATTACHMENT_ENDED -> "The host ended this terminal's output stream."
    TerminalIssue.OUTPUT_INVALID -> "The terminal output lost its sequence, so it was restarted from a fresh screen."
    TerminalIssue.UNKNOWN -> "The terminal call failed."
}
