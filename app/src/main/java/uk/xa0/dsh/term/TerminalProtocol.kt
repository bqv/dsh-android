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
 * The retained terminal a re-opening panel should adopt, or null to open a fresh one.
 *
 * `terminal/list` is how the panel adopts what the host still holds, and adopting is
 * what stops a restart from leaving an orphan behind on every visit. But a terminal
 * whose state is not `RUNNING` has no stream left to hold open: the host still answers
 * a `follow` with a snapshot of its last screen, so the panel painted that screen as
 * *connected* with no notice at all, and the first keystroke then published "another
 * attachment took input control" — a read-only mystery about a shell that had simply
 * stopped.
 *
 * Reproduced on `emulator-5554` (2026-09-26, build `8134ce6`): open the Shell tab of a
 * subject session, `exit`, `am force-stop`, relaunch, open Shell again. The bar read
 * `bash` / `running` with no notice (the prior screen's title and the stopped-shell
 * sentence were both gone), and one keystroke produced the sentence "Another attachment
 * took input control, so this panel is read-only. Reconnect to take it back." — with
 * `terminalWrite dropped: … phase=CONNECTED attachment=adff6cef… controller=adff6cef…
 * state=EXITED` in the log. The controller *was* this attachment; only the state was
 * wrong.
 *
 * So only a `RUNNING` terminal may be adopted. `UNKNOWN` is not running either, and
 * guessing at a state the host did not name is how the dead panel was born.
 */
fun terminalToAdopt(retained: List<TerminalInfo>): TerminalInfo? =
    retained.firstOrNull { it.state == TerminalState.RUNNING }

/**
 * The retained terminals a panel opening a shell should retire.
 *
 * These are exactly the terminals [terminalToAdopt] refuses, and they are the reason
 * "skip the dead ones" needs a second half: a terminal that has stopped can never be
 * adopted again, so leaving it in the list would make every `exit`-and-reopen allocate
 * one more shell until the host answers `terminal/limit-reached` — a fresh dead end in
 * place of the old one. The host accepts `terminal/close` on an exited terminal and
 * drops it from `terminal/list` (verified against the host, 2026-09-26).
 *
 * A terminal that failed to *start* is retired for the same reason: it has no screen
 * worth keeping and cannot run.
 */
fun terminalReapList(retained: List<TerminalInfo>): List<TerminalInfo> =
    retained.filter {
        it.state == TerminalState.EXITED || it.state == TerminalState.FAILED
    }

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
    // Shown only once the attach loop's bounded re-attach retries are spent: while
    // it is retrying, the panel is DISCONNECTED with no issue. So this must name the
    // action, not narrate a restart that has already given up.
    TerminalIssue.OUTPUT_INVALID -> "The terminal output lost its sequence. Reconnect for a fresh screen."
    TerminalIssue.UNKNOWN -> "The terminal call failed."
}

// ------------------------------------------------------------------ attachment

/** What the view model must do after [TerminalAttachmentSession.accept]. */
sealed interface TerminalFrameAction {
    /** A complete screen: publish `CONNECTED`, then feed [screen] to the emulator. */
    data class Repaint(val info: TerminalInfo, val screen: String) : TerminalFrameAction

    /** Ordered output, already fed to the emulator. Bump the panel's revision. */
    data object Output : TerminalFrameAction

    /** Metadata only; [info] is the newest the host reported. */
    data class Metadata(val info: TerminalInfo) : TerminalFrameAction
}

/** A fresh attachment to open, and how long to wait before opening it. */
data class TerminalRestart(val attachmentId: String, val delayMs: Long)

/**
 * Bounded retry/backoff for an attachment the host broke.
 *
 * One restart is often the whole repair: the host answers a new `follow` with a
 * fresh snapshot. A host that answers *every* re-attach with another gap, or ends
 * the stream immediately after each snapshot, would otherwise spin, so the budget
 * is finite. It is spent and never refilled by a frame — a snapshot→end loop must
 * not be able to top it up — and is reset only by a fresh user-initiated attach.
 */
class TerminalReattachPolicy(
    private val maxAttempts: Int = 3,
    private val baseDelayMs: Long = 1_000L,
    private val maxDelayMs: Long = 8_000L,
) {
    /** Restarts already asked for. */
    var attempts: Int = 0
        private set

    /** The wait before the next attachment, or null once the budget is spent. */
    fun next(): Long? {
        if (attempts >= maxAttempts) return null
        val delay = (baseDelayMs shl attempts).coerceAtMost(maxDelayMs)
        attempts++
        return delay
    }
}

/**
 * One attachment's screen state: the emulator, the ordering gate, the resize gate
 * and the copy of input control.
 *
 * Plain Kotlin — no Android and no JSON — because `DshViewModel` is an
 * `AndroidViewModel` and cannot be constructed on the JVM. W1 (a model that never
 * learned the panel's grid) and W2 (a broken sequence that latched forever) both
 * lived in that untested gap; this is the half the JVM tests can drive.
 *
 * **Grid rule (W1).** The model is the reader's window onto the PTY, so it must be
 * the width the incoming bytes were wrapped for.
 *  - A snapshot was laid out for the grid in its own `info`, so a snapshot always
 *    sizes the model to that grid before the screen is fed in.
 *  - Between snapshots the *panel's measurement* is authoritative: it is the only
 *    thing that knows how much screen the user can actually see, and the resize it
 *    triggers is already in flight, so the model must not lag behind it.
 *  - A `state` frame is metadata and must not move the model off a measured grid.
 *    The panel routinely measures — and sends its resize — before the host
 *    confirms it, so the frame still carrying the old grid is the *older* report,
 *    and the host rejects an oversized grid rather than clamping one: a
 *    disagreement here is a stale report, not a new truth. Before the panel has
 *    measured, the host's grid is the only source and is adopted.
 */
class TerminalAttachmentSession(
    val emulator: TerminalEmulator,
    private val environment: TerminalEnvironmentInfo,
    info: TerminalInfo,
    private val reattach: TerminalReattachPolicy = TerminalReattachPolicy(),
) {

    /** The attachment the host is following. [restart] rotates it to re-take input. */
    var attachmentId: String = newAttachmentId()
        private set

    private val gate = TerminalStreamGate()
    private val resizeGate = TerminalResizeGate()

    var info: TerminalInfo? = info
        private set

    /** The grid the panel last asked for; null until it has measured. */
    var measured: Pair<Int, Int>? = null
        private set

    init {
        resizeGate.adopt(info)
    }

    /** True while this attachment owns input; see [isWritable]. */
    val writable: Boolean get() = isWritable(info, attachmentId)

    /** True once the host has said the shell is no longer running. */
    val stopped: Boolean
        get() = info?.state == TerminalState.EXITED || info?.state == TerminalState.FAILED

    /**
     * Records the panel's measurement and sizes the model to it (W1).
     *
     * Returns the grid to put on the wire, or null when the host already has it.
     */
    fun measure(columns: Int, rows: Int): Pair<Int, Int>? {
        val (cols, limitRows) = clampGrid(columns, rows, environment)
        measured = cols to limitRows
        emulator.resize(cols, limitRows)
        return if (resizeGate.shouldSend(cols, limitRows)) cols to limitRows else null
    }

    /**
     * Accepts one frame.
     *
     * Throws [TerminalProtocolException] when the stream breaks the ordering
     * contract; the caller's answer to that is [restart].
     */
    fun accept(frame: TerminalFrame): TerminalFrameAction = when (gate.accept(frame)) {
        TerminalGateOutcome.NEW_GENERATION -> {
            val snapshot = frame as TerminalFrame.Snapshot
            // The screen was serialised for the host's grid; match it before feeding.
            emulator.resize(snapshot.info.cols, snapshot.info.rows)
            emulator.resetForSnapshot()
            resizeGate.adopt(snapshot.info)
            info = snapshot.info
            TerminalFrameAction.Repaint(snapshot.info, snapshot.screen)
        }

        TerminalGateOutcome.OUTPUT -> {
            emulator.append((frame as TerminalFrame.Output).data)
            TerminalFrameAction.Output
        }

        TerminalGateOutcome.STATE -> {
            val state = (frame as TerminalFrame.State).info
            resizeGate.adopt(state)
            info = state
            if (measured == null) emulator.resize(state.cols, state.rows)
            TerminalFrameAction.Metadata(state)
        }
    }

    /**
     * The repair for a stream the host broke (W2).
     *
     * A sequence gap leaves a hole in the screen, and the only honest recovery is a
     * *fresh attachment*: the host answers a new `follow` with a fresh snapshot,
     * which re-baselines the sequence — that is what makes the "restarted from a
     * fresh screen" copy true. The id rotates because the host gives input to the
     * newest attachment, so a re-attach is also how the panel genuinely takes
     * control back. Both gates reset, so the next frame must be a snapshot.
     *
     * Returns null once the retry budget is spent, so a broken host cannot spin.
     */
    fun restart(): TerminalRestart? {
        val delay = reattach.next() ?: return null
        gate.reset()
        resizeGate.clear()
        attachmentId = newAttachmentId()
        return TerminalRestart(attachmentId, delay)
    }

    private fun newAttachmentId(): String = java.util.UUID.randomUUID().toString()
}
