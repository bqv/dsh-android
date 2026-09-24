package uk.xa0.dsh.net

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

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
    private val client: OkHttpClient,
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
     * see `event`, `eventId`, `agentId` and the projected `request`. Return an
     * outcome, or null to delegate to the host's own chain.
     */
    @Volatile
    var onWaterfall: (suspend (frame: JSONObject) -> JSONObject?)? = null

    /**
     * Called when the host withdraws a waterfall it already sent — its own
     * timeout, or another answerer getting there first. The handler must drop the
     * card: answering a settled eventId is a swallowed no-op, so leaving it up
     * only invites a tap that does nothing.
     */
    @Volatile
    var onWaterfallCancel: ((eventId: String) -> Unit)? = null

    @Volatile
    var onLog: ((String) -> Unit)? = null

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
            socket = client.newWebSocket(request, Listener())
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
     */
    fun shutdown() {
        closed = true
        registrations.values.forEach { it.channel.close() }
        registrations.clear()
        runCatching { socket?.close(1000, "client closing") }
        socket = null
        _state.value = MuxState.CLOSED
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

    private fun log(message: String) {
        Log.d(TAG, message)
        onLog?.invoke(message)
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
            val frame = runCatching { JSONObject(text) }.getOrNull() ?: return
            when (frame.optString("type")) {
                "item" -> {
                    val value = frame.optJSONObject("value") ?: return
                    val streamId = frame.optString("streamId")
                    when (value.optString("type")) {
                        "ready" -> eventClientId = value.optString("clientId")
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
     */
    private fun handleWaterfall(value: JSONObject) {
        val eventId = value.optString("eventId")
        val clientId = eventClientId ?: return
        val event = value.optString("event")
        val post = postEventResult ?: return
        scope.launch {
            val outcome = runCatching {
                onWaterfall?.invoke(value) ?: JSONObject().put("kind", "next")
            }.getOrElse { JSONObject().put("kind", "next") }
            val payload = JSONObject()
                .put("clientId", clientId)
                .put("eventId", eventId)
                .put("outcome", outcome)
            runCatching { post(payload) }
                .onFailure { log("Failed to answer $event: ${it.message}") }
        }
    }

    private companion object {
        const val TAG = "DshMux"

        /** How often to check a healthy socket is still open. */
        const val SUPERVISE_INTERVAL_MS = 5_000L

        /** Cap on reconnect backoff, so a host that comes back is picked up promptly. */
        const val MAX_BACKOFF_MS = 15_000L
    }
}
