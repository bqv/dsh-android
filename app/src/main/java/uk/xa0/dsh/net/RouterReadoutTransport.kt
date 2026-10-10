package uk.xa0.dsh.net

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import uk.xa0.dsh.model.ROUTER_READOUT_NO_HTTP
import uk.xa0.dsh.model.ROUTER_READOUT_TERMINAL_ID
import uk.xa0.dsh.model.ROUTER_READOUT_TIMEOUT_MS
import uk.xa0.dsh.model.RouterModelStatus
import uk.xa0.dsh.model.RouterProbeResult
import uk.xa0.dsh.model.RouterReadoutMarkers
import uk.xa0.dsh.model.RouterSlotsReadout
import uk.xa0.dsh.model.RouterTarget
import uk.xa0.dsh.model.classifyReadout
import uk.xa0.dsh.model.modelStatusesOf
import uk.xa0.dsh.model.parseReadoutChunk
import uk.xa0.dsh.model.parseSlotsChunk
import uk.xa0.dsh.model.residentModelForSlots
import uk.xa0.dsh.model.routerReadoutReadLine
import uk.xa0.dsh.model.routerReadoutSlotsLine
import uk.xa0.dsh.model.routerReadoutSyncLine
import uk.xa0.dsh.model.routerReadoutSyncMarker
import uk.xa0.dsh.model.routerRootOf
import uk.xa0.dsh.model.slotsContextSizeOf
import uk.xa0.dsh.model.slotsOf
import uk.xa0.dsh.term.TerminalInfo
import uk.xa0.dsh.term.readoutTerminalToAdopt
import uk.xa0.dsh.term.readoutTerminalsToRetire
import java.util.UUID

/** What one attempt at the terminal readout produced. */
sealed interface RouterReadoutAttempt {

    /**
     * The readout ran to a verdict about the router — which may be "the router did
     * not answer", because that is a fact about the router and belongs beside every
     * other reading of it.
     */
    data class Measured(val result: RouterProbeResult) : RouterReadoutAttempt

    /**
     * The readout could not be *made*.
     *
     * Deliberately not a verdict on the router: no live agent, a refused terminal, a
     * host that will not open a stream. The tab prints this beside a reachability
     * answer the host reached some other way, because "we could not look" and "it
     * said nothing" are different facts.
     */
    data class Unavailable(val reason: String) : RouterReadoutAttempt
}

/**
 * The router's load and prefill state, read by running `curl` on the host through a
 * **terminal attachment this app opens in the session and never presents**.
 *
 * ## What this class is, and what it is not
 *
 * It is not the Shell tab and it does not share the Shell tab's terminal. It owns one
 * terminal per session, under the reserved id [ROUTER_READOUT_TERMINAL_ID], and
 * `DshViewModel.startTerminal` filters that id out of everything the Shell tab lists
 * or adopts — so neither can take the other's seat. That filter is the entire cost of
 * the readout being invisible; see [uk.xa0.dsh.model.isRouterReadoutTerminal].
 *
 * ## One attachment, many reads
 *
 * Attaching costs a `terminal/follow` round trip plus the sync exchange; a *read*
 * costs one write and the output that follows. Measured on the host 2026-10-10
 * against the live `llamb` router (`:55556`, one model resident): attach + sync 46 ms,
 * then `/props` + `/v1/models` in one write 24 ms, and the `/slots` write a further
 * 19 ms. So the attachment is opened once and kept, and the terminal is never closed
 * between reads: the second refresh pays neither the create nor the attach.
 *
 * ## The stream, and what makes a read trustworthy
 *
 * `terminal/follow` is collected into a channel of raw output. A read writes one line
 * and waits for a marker. The markers are unambiguous only because the sync line turns
 * the PTY's **echo** off and silences the interactive prompt; were the echo to come
 * back, the typed line would duplicate every marker, [parseReadoutChunk] requires each
 * marker exactly once, and the read is reported as unreadable rather than guessed at.
 *
 * A timeout is a *failed* read, never a hanging one: [ROUTER_READOUT_TIMEOUT_MS]
 * bounds every wait, and the attachment is dropped afterwards, so the next attempt
 * starts from a fresh stream instead of one with a hole in it.
 */
class RouterReadoutTransport(
    private val scope: CoroutineScope,
    private val terminals: TerminalClient,
) {

    private val mutex = Mutex()

    private var attached: Attachment? = null

    private class Attachment(
        val agentId: String,
        val terminalId: String,
        val attachmentId: String,
        val outputs: Channel<String>,
        /** Completed by the first `snapshot`, which is what proves the attachment exists. */
        val ready: CompletableDeferred<Unit>,
        val job: Job,
    )

    /** Detaches. The host terminal itself is deliberately left alive to be re-adopted. */
    suspend fun close() = mutex.withLock { detach() }

    /**
     * Read the router once, for [target], through the session [agentId].
     *
     * Serialised: two refreshes can arrive in the same moment (the tab opening while a
     * manual refresh lands), and two writes interleaved on one PTY would frame each
     * other's output into nonsense.
     */
    suspend fun read(target: RouterTarget, agentId: String): RouterReadoutAttempt = mutex.withLock {
        val root = routerRootOf(target.provider.baseURL)
            ?: return@withLock RouterReadoutAttempt.Unavailable(
                "The profile's endpoint (${target.provider.baseURL}) has no host and port " +
                    "to build a router URL from.",
            )
        val attachment = try {
            attach(agentId)
        } catch (error: Throwable) {
            detach()
            return@withLock RouterReadoutAttempt.Unavailable(readoutReason(error))
        }
        return@withLock runCatching { readOnce(root, attachment) }.fold(
            onSuccess = { it },
            onFailure = { error ->
                // The stream is no longer trustworthy: a timed-out write may still
                // answer later, and that answer would be read as the next one's.
                detach()
                RouterReadoutAttempt.Unavailable(readoutReason(error))
            },
        )
    }

    // ------------------------------------------------------------------ reading

    private suspend fun readOnce(root: String, attachment: Attachment): RouterReadoutAttempt {
        val sink = StringBuilder()
        write(attachment, sink, routerReadoutReadLine(root), RouterReadoutMarkers.READ_END)

        val chunk = parseReadoutChunk(sink.toString())
            ?: return RouterReadoutAttempt.Measured(
                // Nothing to ask about: a marker or a status was unreadable, and
                // `classifyReadout` words each of those cases itself.
                classifyReadout(null, null, System.currentTimeMillis()),
            )
        // The listing decides whether a second request may be made at all, so this
        // parse runs before the decision and never after it.
        val resident = modelStatusesIn(chunk.modelsBody)?.let(::residentModelForSlots)
            ?: return RouterReadoutAttempt.Measured(
                classifyReadout(chunk, null, System.currentTimeMillis()),
            )
        val slots = runCatching { readSlots(root, resident, attachment) }
            .getOrElse { RouterSlotsReadout(reason = readoutReason(it)) }
        return RouterReadoutAttempt.Measured(
            classifyReadout(chunk, slots, System.currentTimeMillis()),
        )
    }

    private suspend fun readSlots(
        root: String,
        resident: String,
        attachment: Attachment,
    ): RouterSlotsReadout {
        val sink = StringBuilder()
        write(
            attachment, sink,
            routerReadoutSlotsLine(root, resident),
            RouterReadoutMarkers.SLOTS_END,
        )
        val (body, http) = parseSlotsChunk(sink.toString())
            ?: return RouterSlotsReadout(
                reason = "the /slots answer for $resident could not be read from the " +
                    "terminal stream",
            )
        if (http != 200) {
            return RouterSlotsReadout(
                reason = "the router answered /slots for $resident with " + when (http) {
                    null, ROUTER_READOUT_NO_HTTP -> "no answer at all"
                    else -> "HTTP $http"
                },
            )
        }
        val entries = slotsOf(body)
            ?: return RouterSlotsReadout(
                reason = "the router's /slots answer for $resident was not slot state",
            )
        return RouterSlotsReadout(
            entries = entries,
            model = resident,
            contextSize = slotsContextSizeOf(body),
        )
    }

    /** One write, then wait for its marker. [sink] is this read's own accumulated output. */
    private suspend fun write(
        attachment: Attachment,
        sink: StringBuilder,
        command: String,
        marker: String,
    ) {
        sink.setLength(0)
        terminals.write(attachment.agentId, attachment.terminalId, attachment.attachmentId, command)
        if (!await(marker, sink, attachment)) {
            throw RouterReadoutTimeoutException(marker, attachment.outputs.isClosedForReceive)
        }
    }

    private suspend fun await(
        marker: String,
        sink: StringBuilder,
        attachment: Attachment,
    ): Boolean = withTimeoutOrNull(ROUTER_READOUT_TIMEOUT_MS) {
        while (sink.indexOf(marker) < 0) {
            val chunk = attachment.outputs.receiveCatching().getOrNull()
                ?: return@withTimeoutOrNull false
            sink.append(chunk)
        }
        true
    } == true

    // ----------------------------------------------------------------- attaching

    private suspend fun attach(agentId: String): Attachment {
        attached?.let { current ->
            if (current.agentId == agentId) return current
            detach()
        }
        val info = ensureTerminal(agentId)
        val attachmentId = UUID.randomUUID().toString()
        val outputs = Channel<String>(Channel.UNLIMITED)
        val ready = CompletableDeferred<Unit>()
        val job = scope.launch {
            runCatching {
                terminals.follow(agentId, info.id, attachmentId).collect { event ->
                    when (event) {
                        is StreamEvent.Item -> feed(event.value, outputs, ready)
                        is StreamEvent.Failure -> outputs.close()
                        StreamEvent.End -> outputs.close()
                    }
                }
            }
            outputs.close()
        }
        val attachment = Attachment(agentId, info.id, attachmentId, outputs, ready, job)
        // The stream must be live before anything is written: the host hands input to
        // the newest attachment, and a write that arrives first has no seat to land in.
        if (withTimeoutOrNull(ROUTER_READOUT_ATTACH_TIMEOUT_MS) { ready.await() } == null) {
            job.cancel()
            throw RouterReadoutAttachException()
        }
        attached = attachment
        // Echo off and prompt silenced, once per attachment. Idempotent, so
        // re-attaching to a terminal that already had it costs one round trip and
        // changes nothing.
        val sink = StringBuilder()
        val nonce = UUID.randomUUID().toString().take(8)
        terminals.write(agentId, info.id, attachmentId, routerReadoutSyncLine(nonce))
        if (!await(routerReadoutSyncMarker(nonce), sink, attachment)) {
            job.cancel()
            throw RouterReadoutAttachException()
        }
        return attachment
    }

    private fun feed(value: JSONObject, outputs: Channel<String>, ready: CompletableDeferred<Unit>) {
        when (value.optString("type")) {
            "snapshot" -> {
                ready.complete(Unit)
                // The snapshot is the screen as it stands, and it may hold markers from
                // an earlier read. The sync sentinel is unique per attachment, so it is
                // the sync line — never the snapshot — that ends this window; dropping
                // the snapshot here is what stops a stale marker satisfying the wait.
            }

            "output" -> outputs.trySend(value.optString("data"))

            "state" -> {
                val state = value.optJSONObject("info")?.optString("state")
                if (state == "exited" || state == "failed") outputs.close()
            }
        }
    }

    /**
     * The reserved readout terminal for [agentId], adopted when it is still running
     * and created otherwise.
     *
     * Adoption is what keeps one terminal per session instead of one per refresh, and
     * only a `RUNNING` terminal may be adopted — the same rule the Shell tab learned
     * the hard way, and for the same reason: the host answers a `follow` on a stopped
     * shell with a snapshot of its last screen, which would read as a live readout.
     */
    private suspend fun ensureTerminal(agentId: String): TerminalInfo {
        val existing = runCatching { terminals.list(agentId) }.getOrDefault(emptyList())
        readoutTerminalToAdopt(existing)?.let { return it }
        // A reserved terminal that is not running can never be adopted again, and
        // because the id is fixed rather than random it is also a blocker: the host
        // refuses to create an identity it still holds. It has to be retired first or
        // the readout could never come back after its shell died. `terminal/close` is
        // accepted on an exited terminal and drops it from `terminal/list`; a failure
        // here is not fatal, because `create` is then simply refused and the tab
        // reports that in its own words.
        for (stale in readoutTerminalsToRetire(existing)) {
            runCatching { terminals.close(agentId, stale.id) }
        }
        return terminals.create(
            agentId = agentId,
            id = ROUTER_READOUT_TERMINAL_ID,
            // Wide and tall, so the host's own screen model keeps a long listing on
            // one line rather than wrapping it.
            columns = ROUTER_READOUT_COLUMNS,
            rows = ROUTER_READOUT_ROWS,
            shellPath = null,
        )
    }

    private fun detach() {
        attached?.let { current ->
            current.job.cancel()
            current.outputs.close()
        }
        attached = null
    }

    /** The model listing inside a `/v1/models` body, or null when the body is not one. */
    private fun modelStatusesIn(modelsBody: String): List<RouterModelStatus>? =
        runCatching { modelStatusesOf(JSONObject(modelsBody).getJSONArray("data")) }.getOrNull()

    private companion object {
        const val ROUTER_READOUT_COLUMNS = 200
        const val ROUTER_READOUT_ROWS = 50

        /** A fresh attachment answers with a snapshot promptly; this bounds the wait. */
        const val ROUTER_READOUT_ATTACH_TIMEOUT_MS = 10_000L
    }
}

/** The read did not answer within its bound. */
class RouterReadoutTimeoutException(
    val marker: String,
    val streamClosed: Boolean,
) : Exception("timed out waiting for $marker (streamClosed=$streamClosed)")

/** The attachment never came up. */
class RouterReadoutAttachException : Exception("the terminal attachment did not open")

/** The host's or the app's own words for a readout that could not be made. */
private fun readoutReason(error: Throwable): String = when (error) {
    is RouterReadoutAttachException -> "the terminal attachment did not open"

    is RouterReadoutTimeoutException -> if (error.streamClosed) {
        "the terminal stream ended before the router answered"
    } else {
        "the router did not answer within ${ROUTER_READOUT_TIMEOUT_MS / 1000} seconds"
    }

    else -> error.message ?: "the readout failed"
}
