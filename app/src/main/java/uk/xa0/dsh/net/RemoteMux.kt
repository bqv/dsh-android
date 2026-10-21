package uk.xa0.dsh.net

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import uk.xa0.dsh.model.AnswerDelivery
import uk.xa0.dsh.model.HostVersion
import uk.xa0.dsh.model.hostVersionFromReadyFrame
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Posts one outcome for the waterfall the call belongs to, and answers with what the
 * host did with it.
 *
 * A waterfall can be answered more than once — an approval whose post was refused
 * stays on screen and is answered again — so this is a call the handler makes, not a
 * value it returns.
 */
typealias AnswerSink = suspend (outcome: JSONObject) -> AnswerDelivery

/** One frame delivered by a logical remote stream. */
sealed interface StreamEvent {
    data class Item(val value: JSONObject) : StreamEvent
    data class Failure(val code: String, val message: String) : StreamEvent
    data object End : StreamEvent
}

enum class MuxState { IDLE, CONNECTING, OPEN, CLOSED }

/**
 * Multiplexer for DSH's Remote stream socket (`/api/remote.mux`).
 *
 * The host carries *every* logical stream over one WebSocket:
 *   client -> `{"type":"open","streamId":..,"endpoint":..,"payload":{"args":..}}`
 *   host   -> `{"type":"item","streamId":..,"value":..}` / `error` / `end`
 *
 * Two endpoint families matter to this app: `$events` (the forwarded Cordis event
 * stream, which also delivers host→client *waterfalls*) and `session/follow`
 * (transcript snapshot + live deltas). Registrations are replayed after a
 * reconnect so a dropped socket does not silently freeze the UI.
 *
 * Frames are buffered in a [Channel], not a SharedFlow. A SharedFlow with no
 * replay drops emissions that arrive before its subscriber attaches, and the
 * host answers a loopback socket fast enough that the opening snapshot can beat
 * the forwarding coroutine — which silently lost whole transcripts.
 */
class RemoteMux(
    /**
     * The client to dial with, asked for at dial time rather than captured.
     *
     * A client is rebuilt when the accepted certificates change — OkHttp takes its
     * certificate pinner at construction — and a socket that captured the old one
     * would keep dialling with the old pins, which is the one thing a pin must never
     * do.
     */
    private val client: () -> OkHttpClient,
    private val url: HttpUrl,
) {

    private class Registration(
        val streamId: String,
        val endpoint: String,
        val args: JSONObject,
        val channel: Channel<StreamEvent>,
    ) {
        /**
         * Socket generation whose `open` frame this registration has already been
         * sent on. The host closes the whole socket with 1008 "invalid Remote
         * stream request" if the same streamId is opened twice, so this must be
         * exactly one send per generation.
         */
        var sentGeneration: Long = -1
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val registrations = ConcurrentHashMap<String, Registration>()
    private val itemCount = ConcurrentHashMap<String, Int>()
    private val nextId = AtomicLong(0)

    /** Bumped on every successful socket open; streams re-open once per generation. */
    private val generation = AtomicLong(0)
    private val connectLock = Mutex()

    private val _state = MutableStateFlow(MuxState.IDLE)
    val state: StateFlow<MuxState> = _state.asStateFlow()

    @Volatile
    private var socket: WebSocket? = null

    /**
     * Set once by [shutdown]. A replacement mux is built on every config change,
     * so this instance must never dial again; the supervisor and every late
     * `ensureConnected` caller check it.
     */
    @Volatile
    private var closed = false

    @Volatile
    private var openSignal: CompletableDeferred<Unit>? = null

    @Volatile
    private var eventClientId: String? = null

    /**
     * When the host last *said* something on this socket, or 0 before the first frame.
     *
     * Every logical stream this app can hold — the forwarded host events, the control
     * stream, the workspace stream, the followed session, jobs and terminals — rides
     * this one socket, so a frame arriving is evidence that the host is doing something
     * somewhere. That is the whole of what makes it usable as an app-wide activity
     * signal: no per-session stream can speak for the world.
     *
     * It counts text frames only, and that is load-bearing rather than incidental. The
     * client dials with `pingInterval(20, SECONDS)` (see `DshClient.buildClients`), and
     * OkHttp handles ping, pong and close as WebSocket *control frames* inside the
     * socket rather than through [Listener.onMessage]. A socket kept alive by nothing but
     * its pings therefore looks quiet here — which is the honest reading, because a live
     * TCP connection is not activity.
     */
    private val lastFrameAt = AtomicLong(0L)

    /**
     * The instant of the last host frame, or 0 before the first.
     *
     * Read by the widget's activity reading: it is a fact about this app's evidence
     * rather than about any one session, and it costs one store per frame to keep.
     */
    fun lastHostFrameAt(): Long = lastFrameAt.get()

    init {
        supervise()
    }

    /**
     * Keeps one socket alive for the process's lifetime.
     *
     * Nothing else retries: a registration's `open` frame is sent once per socket
     * generation, and a generation only advances in [Listener.onOpen]. So when the
     * socket dies — a `ping` timeout on a sleeping phone is the common one — every
     * stream stays dead and the transcript freezes with the last snapshot it had,
     * which looks exactly like a host that stopped working. This loop is what turns
     * that into a reconnect.
     */
    private fun supervise() {
        scope.launch {
            var backoff = 1_000L
            // `closed` is what ends this otherwise-process-lifetime loop. Without
            // it a shut-down mux keeps re-dialling the old host — with a cleared
            // cookie jar after sign-out — once per backoff forever.
            while (!closed) {
                if (_state.value != MuxState.OPEN) {
                    val connected = runCatching { ensureConnected() }
                        .getOrElse { log("Reconnect failed: ${it.message}") }
                    // ensureConnected() returns without a result, so judge by state.
                    if (_state.value == MuxState.OPEN) {
                        backoff = 1_000L
                    } else {
                        delay(backoff)
                        backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
                    }
                } else {
                    backoff = 1_000L
                    delay(SUPERVISE_INTERVAL_MS)
                }
            }
        }
    }

    /** Set by the app so waterfall frames can be answered on the same session. */
    @Volatile
    var postEventResult: (suspend (JSONObject) -> Unit)? = null

    /**
     * Called for host→client waterfalls with the whole frame, so the handler can
     * see `event`, `eventId`, `agentId` and the projected `request`.
     *
     * It is also handed [AnswerSink], and that is a deliberate widening of what used
     * to be "return one outcome": an approval whose answer the host refuses has to
     * stay on screen and be answerable again, so the handler needs to post more than
     * once, and to be told what the host did with each post. Returning the outcome to
     * be posted here could not express either — the post happened once, after the
     * handler had already given up its answer, and its reply was discarded.
     *
     * A handler that never calls [AnswerSink] leaves the host waiting; one that calls
     * it and ignores the result is free to, but an approval is not.
     */
    @Volatile
    var onWaterfall: (suspend (frame: JSONObject, send: AnswerSink) -> Unit)? = null

    /**
     * Called when the host withdraws a waterfall it already sent — its own
     * timeout, or another answerer getting there first. The handler must drop the
     * card: answering a settled eventId is a swallowed no-op, so leaving it up
     * only invites a tap that does nothing.
     */
    @Volatile
    var onWaterfallCancel: ((eventId: String) -> Unit)? = null

    /**
     * Called with the host's `clientId` each time a forwarded-event generation
     * opens — the first connect and every reconnect alike.
     *
     * It is the one push that names what the host is still holding: as the
     * `$events` stream opens, the gateway re-announces every pending waterfall to
     * the new generation (`dsh-api-gateway/lib/index.js`, `openRemoteEvents`), and
     * a waterfall it has settled is not pending and so is not named. The handler
     * uses that roster to reconcile the cards it already has.
     */
    @Volatile
    var onEventsGeneration: ((clientId: String) -> Unit)? = null

    /**
     * Called with the host's own DSH version from the `$events` stream's opening
     * `ready` item, once per connection generation.
     *
     * Separate from [onEventsGeneration] because the two answer different questions
     * and have different owners: that one names the waterfall roster and belongs to
     * [uk.xa0.dsh.AttentionCenter], while this one is a fact about the host that the
     * About page prints. On 0.2.0-rc.2 the frame carries `host: { home }` and nothing
     * else, so the handler is told [HostVersion.NotReported] rather than nothing at
     * all — see [uk.xa0.dsh.model.HostVersion] for the evidence and why the two
     * "absent" cases are kept apart.
     *
     * A reader that arrives after the frame has already been folded ([hostVersion])
     * has missed this callback, and that is the common case: the connection service
     * opens the stream before any ViewModel exists.
     */
    @Volatile
    var onHostVersion: ((HostVersion) -> Unit)? = null

    /**
     * The last version a host named in a `ready` frame, retained so a reader that
     * wires [onHostVersion] after the frame has already arrived can seed itself
     * rather than showing "not known" until the next reconnect.
     *
     * It lives and dies with the instance, and [uk.xa0.dsh.DshClient] builds a fresh
     * mux whenever the endpoint moves: a replacement mux is a different host to ask,
     * so it starts at [HostVersion.Unknown] rather than inheriting the previous one's.
     */
    @Volatile
    var hostVersion: HostVersion = HostVersion.Unknown
        private set

    suspend fun ensureConnected() {
        if (closed) return
        if (_state.value == MuxState.OPEN) return
        connectLock.withLock {
            if (closed) return
            if (_state.value == MuxState.OPEN) return
            val signal = CompletableDeferred<Unit>()
            openSignal = signal
            _state.value = MuxState.CONNECTING
            val request = Request.Builder().url(url).build()
            socket = client().newWebSocket(request, Listener())
            val connected = withTimeoutOrNull(20_000) { signal.await() }
            if (connected == null) {
                _state.value = MuxState.CLOSED
                log("Timed out opening the event socket")
            }
        }
    }

    fun openStream(endpoint: String, args: JSONObject): Flow<StreamEvent> = callbackFlow {
        // A collector that attaches after shutdown has nothing to wait for, and a
        // permanently suspended flow is exactly the leak `closed` exists to avoid.
        if (closed) {
            close()
            return@callbackFlow
        }
        val streamId = "s" + nextId.incrementAndGet()
        // UNLIMITED so the socket reader never blocks and nothing is dropped
        // before the collector attaches.
        val channel = Channel<StreamEvent>(Channel.UNLIMITED)
        val registration = Registration(streamId, endpoint, args, channel)
        registrations[streamId] = registration

        val pump = scope.launch {
            for (event in channel) {
                trySend(event)
                if (event is StreamEvent.End) break
            }
            // The registration is finished — an `end`/`error` frame, or shutdown
            // closing the channel. Completing the flow is what lets its collector
            // (the attention loop) unwind instead of waiting on a dead stream.
            close()
        }

        scope.launch {
            runCatching { ensureConnected() }
                .onFailure { log("Socket connect failed for $endpoint: ${it.message}") }
            sendOpenIfNeeded(registration)
        }

        awaitClose {
            registrations.remove(streamId)
            channel.close()
            pump.cancel()
            runCatching {
                socket?.send(JSONObject().put("type", "cancel").put("streamId", streamId).toString())
            }
        }
    }

    /**
     * Ends this mux for good.
     *
     * Closing the registrations completes their flows, so collectors unwind; the
     * `closed` flag stops the supervisor and any racing [ensureConnected] from
     * re-dialling a host this instance no longer belongs to.
     *
     * The socket is **cancelled**, not closed. `close()` sends a close frame and
     * waits for the peer to answer it, and a host that never answers is exactly
     * the wedge a deliberate teardown exists for: the underlying connection would
     * sit in OkHttp's pool (and its call in the dispatcher) until the handshake
     * timed out or forever. Cancellation shuts the socket down now, with no
     * dependency on anything at the far end.
     *
     * The instance's coroutines go with it. The supervisor loop checks `closed`
     * between delays, so left alone it can stay alive for its longest backoff; a
     * stream pump is ended by its channel; and an [ensureConnected] caller already
     * waiting on `openSignal` is woken here rather than sitting out its full 20 s
     * timeout for a socket this call has just ended. Nothing on a retired mux has
     * an outcome worth delivering, so the whole scope is cancelled.
     */
    fun shutdown() {
        closed = true
        registrations.values.forEach { it.channel.close() }
        registrations.clear()
        runCatching { socket?.cancel() }
        socket = null
        _state.value = MuxState.CLOSED
        // Wake a dial that is waiting on the handshake; it re-reads `closed` and
        // returns without opening another socket. The listener's own completion of
        // this deferred is a no-op after the first.
        openSignal?.complete(Unit)
        openSignal = null
        scope.cancel()
    }

    /**
     * Sends a registration's `open` frame at most once per socket generation.
     *
     * Two hazards are handled here. A frame queued before the handshake completes
     * `send()`s successfully but is not yet deliverable, so nothing is marked as
     * sent until the socket is genuinely OPEN. And the opening launcher races the
     * reconnect replay in [Listener.onOpen]; the generation check under the
     * registration's monitor collapses that race to a single send.
     */
    private fun sendOpenIfNeeded(registration: Registration) {
        if (_state.value != MuxState.OPEN) return
        synchronized(registration) {
            val current = generation.get()
            if (registration.sentGeneration == current) return
            val frame = JSONObject()
                .put("type", "open")
                .put("streamId", registration.streamId)
                .put("endpoint", registration.endpoint)
                .put("payload", JSONObject().put("args", registration.args))
            val sent = runCatching { socket?.send(frame.toString()) }.getOrNull()
            if (sent == true) {
                registration.sentGeneration = current
                log("open ${registration.streamId} -> ${registration.endpoint} (gen $current)")
            } else {
                log("open deferred ${registration.endpoint} (socket not ready)")
            }
        }
    }

    private fun deliver(streamId: String, event: StreamEvent) {
        registrations[streamId]?.channel?.trySend(event)
    }

    /**
     * Records one line of the mux's own trace.
     *
     * Logcat only, deliberately. This text is diagnostics — stream ids, frame
     * types, socket generations — and it is not a description of what the app is
     * doing that any screen may show; it was once wired into the loading screen and
     * told a reader starting the app `item s2 baseline (#1)`.
     */
    private fun log(message: String) {
        Log.d(TAG, message)
    }

    private inner class Listener : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
            val gen = generation.incrementAndGet()
            _state.value = MuxState.OPEN
            openSignal?.complete(Unit)
            log("Event socket connected (gen $gen)")
            // Re-open every live stream once for this generation.
            registrations.values.forEach { sendOpenIfNeeded(it) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            // Any host frame, before it is even parsed: the host speaking is the fact
            // the widget's activity reading is made of, and a frame this client cannot
            // read is still the host talking.
            lastFrameAt.set(System.currentTimeMillis())
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (frame.optString("type")) {
                "item" -> {
                    val value = frame.optJSONObject("value") ?: return
                    val streamId = frame.optString("streamId")
                    when (value.optString("type")) {
                        "ready" -> {
                            val id = value.optString("clientId")
                            if (id.isNotEmpty()) {
                                eventClientId = id
                                // A new generation is also the roster of what the
                                // host still holds, delivered before anything else
                                // on the stream.
                                onEventsGeneration?.invoke(id)
                            }
                            // The same item is the whole of the host's identity
                            // frame, so the version is folded from it here rather
                            // than from a call of its own. Retained as well as
                            // announced: a reader may not be listening yet.
                            val version = hostVersionFromReadyFrame(value)
                            hostVersion = version
                            onHostVersion?.invoke(version)
                        }
                        "waterfall" -> handleWaterfall(value)
                        // The host withdraws a settled waterfall as a stream ITEM
                        // whose value type is "cancel" (the framing every other
                        // downward frame uses). Handling it only as a top-level
                        // frame below left the card and its notification up
                        // forever when another client answered the waterfall.
                        "cancel" -> {
                            val eventId = value.optString("eventId")
                            if (eventId.isNotEmpty()) onWaterfallCancel?.invoke(eventId)
                        }
                    }
                    // Log only the head of each stream; token deltas would flood.
                    val seen = itemCount.merge(streamId, 1, Int::plus) ?: 1
                    if (seen <= 3) log("item $streamId ${value.optString("type")} (#$seen)")
                    deliver(streamId, StreamEvent.Item(value))
                }

                "error" -> {
                    val streamId = frame.optString("streamId")
                    val error = frame.optJSONObject("error")
                    val code = error?.optString("code").orEmpty().ifEmpty { "stream/error" }
                    val message = error?.optString("message").orEmpty().ifEmpty { "Stream failed" }
                    log("Stream error $streamId: $code $message")
                    deliver(streamId, StreamEvent.Failure(code, message))
                    registrations.remove(streamId)?.channel?.close()
                }

                "end" -> {
                    val streamId = frame.optString("streamId")
                    deliver(streamId, StreamEvent.End)
                    registrations.remove(streamId)?.channel?.close()
                }

                "cancel" -> {
                    // The host withdrawing a waterfall. Our own stream cancels
                    // carry a streamId, not an eventId, so this cannot collide.
                    val eventId = frame.optString("eventId")
                    if (eventId.isNotEmpty()) onWaterfallCancel?.invoke(eventId)
                }
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
            log("Event socket failed: ${t.message}")
            _state.value = MuxState.CLOSED
            socket = null
            // Do not end the logical streams: ensureConnected() replays them.
            openSignal?.complete(Unit)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            // 1008 here usually means the host rejected a duplicate stream open.
            log("Event socket closed: $code ${reason.take(120)}")
            _state.value = MuxState.CLOSED
            socket = null
            openSignal?.complete(Unit)
        }
    }

    /**
     * A waterfall is a host round-trip that *must* be answered or the agent stalls.
     * Approvals are answered by the user; anything else delegates back to the
     * host's own chain, which is the safe default for this client.
     *
     * The handler owns *what* to send and *when*; this method owns the delivery and
     * reports back what happened, so a handler can retry. It does not retire the
     * waterfall: the host's `cancel` is what says a waterfall is settled, and it is
     * the only thing that does.
     */
    private fun handleWaterfall(value: JSONObject) {
        val eventId = value.optString("eventId")
        val clientId = eventClientId ?: return
        val event = value.optString("event")
        val post = postEventResult ?: return
        scope.launch {
            val send: AnswerSink = { outcome ->
                val payload = JSONObject()
                    .put("clientId", clientId)
                    .put("eventId", eventId)
                    .put("outcome", outcome)
                try {
                    post(payload)
                    AnswerDelivery.Accepted
                } catch (e: CancellationException) {
                    // A dead scope is not a host that refused: the handler must not be
                    // told to retry into a coroutine that is being torn down.
                    throw e
                } catch (e: DshRpcException) {
                    // The host understood the call and declined it. Long bodies are
                    // truncated: this text ends up on a card the reader is looking at.
                    log("Host refused the answer to $event: ${e.code} ${e.message}")
                    AnswerDelivery.Refused(e.message.take(160))
                } catch (e: Throwable) {
                    log("Failed to answer $event: ${e.message}")
                    AnswerDelivery.Unreachable(e.message?.take(160) ?: e.javaClass.simpleName)
                }
            }
            val handler = onWaterfall
            runCatching {
                if (handler == null) {
                    // Nothing claimed this waterfall, so it cannot be answered here.
                    // Delegating is the only safe default: the host's own chain
                    // settles it rather than leaving the agent blocked on silence.
                    send(delegated())
                } else {
                    handler(value, send)
                }
            }.onFailure { failure ->
                log("Waterfall handler failed for $event: ${failure.message}")
                // The handler gave up without settling anything. Delegate, so a bug in
                // it is a refused request rather than an agent blocked on silence; a
                // duplicate outcome for an already-settled waterfall is a no-op there.
                runCatching { send(delegated()) }
            }
        }
    }

    private fun delegated(): JSONObject = JSONObject().put("kind", "next")

    private companion object {
        const val TAG = "DshMux"

        /** How often to check a healthy socket is still open. */
        const val SUPERVISE_INTERVAL_MS = 5_000L

        /** Cap on reconnect backoff, so a host that comes back is picked up promptly. */
        const val MAX_BACKOFF_MS = 15_000L
    }
}
