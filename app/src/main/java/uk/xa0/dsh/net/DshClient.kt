package uk.xa0.dsh.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import uk.xa0.dsh.data.DshConfig
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.cert.X509Certificate
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Raised when the host rejects a request because the session is gone. */
class DshAuthException(message: String = "Not authenticated") : Exception(message)

/** Raised when the host answered a gateway call with a structured error. */
class DshRpcException(
    val code: String,
    override val message: String,
    val details: JSONObject?,
) : Exception(message)

/**
 * The host could not be reached at all — a timeout, a dropped socket, a bad route.
 *
 * Distinct from [DshAuthException] on purpose: a slow or briefly-down host used to
 * be reported as "you are not signed in", which threw the user back to the setup
 * screen and lost the session they were reading.
 */
class DshUnreachableException(message: String = "Could not reach the host") : Exception(message)

/**
 * The host presented a certificate this app will not accept.
 *
 * Its own type because it is the one connect failure the user can act on: the chain
 * that was offered travels with it, so the screen that reports it can show the
 * fingerprint and offer to trust it — no second connection needed to find out what
 * was there.
 */
class DshCertificateException(
    message: String,
    val host: String,
    val chain: List<X509Certificate>,
    /** The certificate accepted for this host before, when there was one. */
    val accepted: AcceptedCert?,
) : Exception(message)

/** Outcome of a connectivity probe, used by the setup screen. */
sealed interface ProbeResult {
    data class Ok(val serverName: String) : ProbeResult
    data object NeedsLogin : ProbeResult

    /** The host answered, but with a status this client does not recognise. */
    data class Failed(val message: String) : ProbeResult

    /** No answer at all: the request never completed. */
    data class Unreachable(val message: String) : ProbeResult

    /**
     * The handshake was refused and this app has nothing accepted for the host — the
     * first-use case, which becomes an acceptance rather than a failure.
     */
    data class CertificateUnknown(
        val host: String,
        val chain: List<X509Certificate>,
    ) : ProbeResult

    /**
     * The handshake was refused and this host *does* have an accepted certificate, so
     * either the host rotated its key or somebody is in the middle. The two are
     * indistinguishable from here, which is exactly why the user is asked.
     */
    data class CertificateChanged(
        val host: String,
        val chain: List<X509Certificate>,
        val accepted: AcceptedCert,
    ) : ProbeResult
}

/**
 * Thin client for the DSH host's public HTTP surface.
 *
 * Unary calls ride `POST /api/<method>` with the harness envelope:
 * `{"type":"client-request","rpcId":..,"method":..,"payload":{"args":{..}}}`
 * and answer `{"type":"server-response","rpcId":..,"result":{"ok":true,"value":..}}`.
 * Argument keys are the *service method parameter names on the host* — so
 * `session/list` takes `_request` while `session/prompt` takes `request`.
 *
 * Nothing here is DSH-plugin specific: the app is an ordinary browser-shaped
 * client of the same endpoints the web UI uses.
 */
class DshClient(context: Context) {

    private val appContext = context.applicationContext

    /**
     * Process-scoped, like the client itself: route refreshes are triggered by
     * the OS's connectivity callbacks and by failed calls, neither of which has
     * a coroutine of its own to run in.
     */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val cookieJar = PersistentCookieJar(context)

    /**
     * What this app accepts, and therefore what its clients enforce.
     *
     * Owned here because this is the object that builds sockets; persisted through
     * [uk.xa0.dsh.data.ConfigStore] by whoever listens to [onTrustChanged].
     */
    val trust = CertTrust()

    /**
     * The clients for one pin set.
     *
     * Only the pins force a rebuild: OkHttp takes its [okhttp3.CertificatePinner] at
     * construction time, while the socket factory handed to it stays the same object
     * for the life of the process — the trust manager behind it consults the anchors
     * at every handshake (see [CertTrust]).
     */
    private class Clients(
        val base: OkHttpClient,
        val probe: OkHttpClient,
        val stream: OkHttpClient,
        val noRedirect: OkHttpClient,
        val unary: OkHttpClient,
    )

    @Volatile
    private var clients: Clients = buildClients()

    private fun buildClients(): Clients {
        val base = OkHttpClient.Builder()
            .cookieJar(cookieJar)
            .connectTimeout(15, TimeUnit.SECONDS)
            // Without an explicit read timeout OkHttp's 10s default applies, and a busy
            // host — or the session-reference resolver, which walks every session — trips
            // it constantly. `callTimeout` still bounds the whole call.
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)
            // The app's own anchors are asked first and the system store second, which
            // is what lets all three shapes of host work: a public certificate with no
            // ceremony at all, a self-signed one once it has been accepted, and a host
            // that has been accepted pinned to the key that was accepted.
            .sslSocketFactory(trust.socketFactory(), trust.trustManager())
            .certificatePinner(CertPins.pinner(trust.pins()))
            .build()

        /**
         * The short-timeout client a *candidate* is tested with.
         *
         * Distinct from `base` (and `noRedirect`) because the resolver probes
         * addresses that are expected to be absent most of the time — the LAN door
         * on a phone that is out of the house. A 15s connect timeout there would
         * stall every refresh for a quarter of a minute; a second is enough for a
         * host on the same wire to answer and short enough that a miss is cheap.
         */
        val probe = base.newBuilder()
            .connectTimeout(1, TimeUnit.SECONDS)
            .readTimeout(1, TimeUnit.SECONDS)
            .callTimeout(1, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /**
         * Long-lived calls (the WebSocket mux) must not be subject to a read timeout.
         *
         * The mux handshake is also the one request whose *refusal* has nowhere else
         * to surface: a browser-style redirect or an opaque socket failure leaves the
         * socket re-dialling forever while every screen still looks READY. So the
         * handshake is checked here and a 401/403 (or a redirect to the sign-in page)
         * is reported through [onStreamAuthFailure], which the app routes through the
         * same auth funnel as any unary call.
         */
        val stream = base.newBuilder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.code == 401 || response.code == 403 || isAuthRedirect(response)) {
                    onStreamAuthFailure?.invoke()
                }
                response
            }
            .build()

        val noRedirect = base.newBuilder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        /**
         * The gateway calls' client: `base` plus a route-failure hook.
         *
         * A call that fails at the transport is the one signal that the chosen
         * endpoint stopped working — a stale LAN choice is exactly this — and it
         * heals by re-probing. Only the unary gateway calls carry the hook: the
         * stream client must not reroute on every socket blip (it re-dials on its
         * own), and `httpClient` fetches internet pictures, whose failure says
         * nothing about the host. A call that *answered* something which is not a
         * DSH envelope trips the same hook from [rpcRaw], with the other kind,
         * because that is the other shape of a wrong door — see [onRouteFailure].
         */
        val unary = base.newBuilder()
            .addInterceptor { chain ->
                try {
                    chain.proceed(chain.request())
                } catch (e: IOException) {
                    onRouteFailure(DoorFault.Transport)
                    throw e
                }
            }
            .build()

        return Clients(base = base, probe = probe, stream = stream, noRedirect = noRedirect, unary = unary)
    }

    /**
     * The app's choice of the LAN door or the public one. Re-seeded from each
     * config; see [applyConfig] and [refreshRoute].
     */
    private val endpoint = EndpointResolver(
        isDshHost = { url -> answersAsDshHost(url) },
        localIpv4 = { localIpv4Address() },
    )

    /** Epoch millis of the last route-failure invalidation; see [onRouteFailure]. */
    @Volatile
    private var lastRouteFailureAt = 0L

    /**
     * Called when a refresh moved traffic to a different endpoint.
     *
     * Mirrors [onStreamAuthFailure]: the client can see that the route changed,
     * but only the app knows what to do about it — the socket it was holding
     * belonged to the old address (this client has already dropped it), and the
     * streams and session list want rebuilding against the new one. Null when no
     * UI exists, in which case the mux simply redials on demand.
     */
    @Volatile
    var onRouteChanged: (() -> Unit)? = null

    /**
     * Called just before the stored username and password go to the host.
     *
     * The client owns the fact; the caller owns the words. Without this the two
     * halves of a sign-in were one wait to the screen — "asking whether this device
     * is still signed in" and "the host asked for credentials and they are on their
     * way" happen inside the same call and are different things to be waiting on.
     * Invoked on the caller's dispatcher, before the request is dispatched.
     */
    @Volatile
    var onSignIn: (() -> Unit)? = null

    /**
     * The event socket's client, from the current family.
     *
     * Read through the family rather than captured, so a client rebuilt for a new
     * pin set is the one the multiplexer dials with — see [RemoteMux], which asks for
     * the client on every dial for the same reason.
     */
    private fun streamClient(): OkHttpClient = clients.stream

    /**
     * Called when the event socket's handshake was refused for auth reasons.
     * Set by the view model (the only place that can heal it); null when no UI
     * exists, in which case the socket simply keeps retrying as before.
     */
    @Volatile
    var onStreamAuthFailure: (() -> Unit)? = null

    private fun isAuthRedirect(response: Response): Boolean {
        if (response.code != 301 && response.code != 302 && response.code != 303 && response.code != 307) {
            return false
        }
        val location = response.header("Location").orEmpty().lowercase()
        return location.contains("/auth/") || location.contains("login")
    }

    @Volatile
    var config: DshConfig = DshConfig()
        private set

    @Volatile
    var mux: RemoteMux? = null
        private set

    /**
     * Called when the accepted certificates change, so the app can persist them.
     *
     * Set by the application, the only holder of [uk.xa0.dsh.data.ConfigStore]; null
     * with no store attached, in which case the anchor set lives and dies with the
     * process — still better than trusting nothing.
     */
    @Volatile
    var onTrustChanged: (() -> Unit)? = null

    /**
     * Seed the anchor set from stored state, before the first request.
     *
     * The clients are rebuilt because the pins are derived from the anchors and OkHttp
     * takes its pinner when the client itself is built.
     */
    fun setAcceptedCerts(entries: List<AcceptedCert>) {
        trust.replace(entries)
        clients = buildClients()
    }

    fun applyConfig(config: DshConfig) {
        this.config = config
        mux?.shutdown()
        mux = null
        endpoint.update(
            primary = httpUrlOf(config.baseUrl),
            lan = httpUrlOf(lanCandidate(config)),
        )
        // The route watcher's lifetime is the configuration's: with no host there
        // is nothing to route to, and a default-network callback left registered
        // would keep waking the process for changes it cannot use.
        if (config.isConfigured) watchConnectivity() else stopWatchingConnectivity()
        // Prime the cache for whoever is listening, but do not reroute: the
        // config change itself has already dropped the multiplexer, and the
        // caller of applyConfig owns the reconnect that follows — which resolves
        // again, through [dialRoute], before it dials.
        scope.launch { runCatching { refreshRoute() } }
    }

    /**
     * Tear the transport down so the next attempt builds it again.
     *
     * The deliberate, in-process counterpart to a force-stop: the configuration,
     * the cookie jar, the trust anchors and every other piece of process state are
     * untouched, and only what was learned about *this* connection is dropped.
     *
     * Two things go. The multiplexer is shut down and forgotten, which ends every
     * logical stream on it — the event stream's registrations, the followed
     * session, the control and workspace streams, the terminal attachment — and
     * stops its supervisor from re-dialling a socket this action has declared dead.
     * And [EndpointResolver.restart] forgets the door holds, the cached winner and
     * the last probed answer, because those are exactly the records that can wedge
     * a route: a wrong-answer verdict survives every clock change, and a cached
     * winner can be an answer about a wire that is gone.
     *
     * It does **not** resolve on its own. The caller owns the fresh dial (the view
     * model's connect), which probes both doors and builds the multiplexer on the
     * answer — resolving here as well would ask the same question twice.
     *
     * The route-failure cooldown goes too: it bounded probes for the calls the
     * previous transport was making, and a connection that is one call old should
     * not inherit its reluctance.
     */
    fun resetTransport() {
        synchronized(this) {
            endpoint.restart()
            lastRouteFailureAt = 0L
            mux?.shutdown()
            mux = null
        }
    }

    /**
     * The LAN address to try for this config: the explicit one, or — for an
     * install that predates the field — the primary URL itself when it is
     * already a private address, so a LAN-only setup keeps working untouched.
     */
    private fun lanCandidate(config: DshConfig): String =
        config.lanUrl.ifBlank {
            config.baseUrl.takeIf { isPrivateLanHost(hostOf(it)) }.orEmpty()
        }

    private fun httpUrlOf(raw: String): HttpUrl? =
        raw.takeIf { it.isNotBlank() }?.let { normalizeBase(it).toHttpUrlOrNull() }

    /**
     * The address a setup field names, normalised exactly as this client normalises
     * it — so the certificate the user is shown is the certificate of the host the
     * app would actually dial, scheme defaulting and all.
     */
    fun endpointFor(raw: String): HttpUrl? = httpUrlOf(raw)

    /** The remote-event multiplexer for the active config, created on first use. */
    fun mux(): RemoteMux {
        mux?.let { return it }
        synchronized(this) {
            mux?.let { return it }
            val created = RemoteMux(client = { streamClient() }, url = apiEventsUrl())
            mux = created
            return created
        }
    }

    /**
     * The address every other URL derives from — the *resolved* endpoint, not the
     * configured one, so one unary call, the event socket, the probe and a pasted
     * cookie all follow the route the resolver chose.
     *
     * Synchronous by contract: this runs while a request is being built, and
     * [EndpointResolver.peek] answers from cache without ever probing.
     */
    fun baseUrl(): HttpUrl? = endpoint.peek()

    /** True when [baseUrl] is the LAN door rather than the public one. */
    fun isLanRoute(): Boolean = endpoint.isLan

    /**
     * Why the LAN door is being avoided right now, or null when it is not; see
     * [EndpointResolver.lanDoorFault].
     *
     * Published beside the route itself so a public route can say why it is
     * public: the LAN door failed a real call. Synchronous and offline, so the
     * About page can read it with the socket down like every other row there.
     */
    fun lanDoorFault(): DoorFault? = endpoint.lanDoorFault

    /**
     * Re-resolve the endpoint now and prime the cache. Suspends for the probes,
     * so a caller with a connect to make can resolve first and dial the resolved
     * door rather than dial the public one and reroute a moment later.
     *
     * Does **not** notify: this is the "a config just changed" path, and the
     * caller is about to rebuild the wiring and dial anyway — [applyConfig] has
     * already dropped the multiplexer, so a second nudge here would race the
     * caller's own connect. [requestRouteRefresh] is the path that notifies, and
     * [recheckRoute] the one that reports the move back to its own caller.
     *
     * Honours the cache: a winner resolved inside the TTL is returned as it is.
     * That is right for priming and wrong for a dial, which is why [dialRoute]
     * exists.
     */
    suspend fun refreshRoute() {
        resolveRoute(notify = false)
    }

    /**
     * The route for a dial that is about to happen: probed now, in LAN-first
     * order, rather than remembered.
     *
     * A connect, a reconnect and a fresh open are all questions about the wire as
     * it is at that moment. The cached winner is an answer about the wire as it
     * was, on a network the process may not have seen a callback for since — a
     * phone that has been in a pocket, a host that has restarted, a Wi-Fi
     * reassociation nobody reported. So this forces the probe whatever the TTL
     * says, and reports whether *this* resolution moved the route, which is what
     * tells the caller to rebuild the multiplexer the old address owned.
     *
     * [forgive] is for a dial a person asked for: their "try again" outranks every
     * hold the resolver recorded, including a wrong-answer verdict, because it is
     * evidence the app cannot obtain on its own; see
     * [EndpointResolver.forgiveByUser]. Every automatic dial passes false, so a
     * door that is being avoided stays avoided and a captive portal costs one
     * failed attempt per request a human made rather than one per retry.
     *
     * Does not notify, like [refreshRoute]: the caller owns its own rewire.
     */
    suspend fun dialRoute(forgive: Boolean = false): Boolean {
        if (forgive) {
            Log.d(ROUTE_TAG, "a user asked again: clearing every door hold")
            endpoint.forgiveByUser()
        }
        endpoint.invalidate()
        return resolveRoute(notify = false)
    }

    /**
     * Whether a LAN candidate exists for this config at all; see
     * [EndpointResolver.hasLanCandidate]. False means no probe of the LAN door can
     * ever run, which is a missing setting rather than a failed door.
     */
    fun hasLanCandidate(): Boolean = endpoint.hasLanCandidate

    /**
     * A fresh look at the route after an absence, for the one caller whose reason
     * for asking is that time passed: the process coming back to the foreground.
     *
     * Forces the probe rather than trusting the cache. What the cache holds is an
     * answer about the network the process went to sleep on, and the callbacks
     * that would have replaced it are exactly what a frozen process cannot be
     * relied on to have handled — delivering them is best-effort, not a guarantee.
     *
     * Does not notify, like [refreshRoute]: the caller owns its own rewire, so it
     * is told instead. Returns true when this resolution moved the route, in which
     * case it has already dropped the multiplexer every stream was bound to and
     * the caller must rebuild before it dials again.
     *
     * This is the one path that forgives a *transport* hold on its own account: a
     * resume is a deliberate "ask again after an absence", so the LAN door's
     * transport failure — the shape a stale socket or a host restart leaves — is
     * retried now rather than after the hold it earned. A door that answered real
     * traffic with something that was not DSH is never forgiven here, and the
     * forgiveness itself is bounded; see [EndpointResolver.grantRetry]. The only
     * thing that lifts a wrong-answer verdict is a person's own action, through
     * [dialRoute] with `forgive`; see [EndpointResolver.forgiveByUser].
     */
    suspend fun recheckRoute(): Boolean {
        endpoint.grantRetry()
        endpoint.invalidate()
        return resolveRoute(notify = false)
    }

    /**
     * Fire-and-forget re-resolution that *does* reroute: for the callers with no
     * coroutine of their own — a connectivity callback, a failed call.
     */
    fun requestRouteRefresh() {
        scope.launch { runCatching { resolveRoute(notify = true) } }
    }

    /**
     * Probe and, for a move, drop the socket bound to the old address: shut the
     * multiplexer down and null it so the next [mux] dials the new endpoint.
     *
     * A notifying caller also hears about it through [onRouteChanged]; a
     * config-apply caller owns its own reconnect and only needs the stale socket
     * gone. "Moved" comes from this resolution's own answer, so two refreshes
     * racing the same probe cannot both act on it.
     */
    private suspend fun resolveRoute(notify: Boolean): Boolean {
        val choice = endpoint.resolve()
        if (!choice.changed) return false
        Log.d(ROUTE_TAG, "route moved to ${endpoint.peek()} (lan=${endpoint.isLan})")
        mux?.shutdown()
        mux = null
        if (notify) onRouteChanged?.invoke()
        return true
    }

    /** The registered default-network watcher, or null when no host is configured. */
    @Volatile
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    /** Which of the callback's reports is news; see [ConnectivityGate]. */
    private val connectivityGate = ConnectivityGate()

    /**
     * Re-resolve whenever the OS reports the default network changing — the moment
     * a phone leaves the house, joins a hotspot, or comes back to the LAN.
     *
     * Registered for exactly as long as there is a host to route to: [applyConfig]
     * is the only caller, and it stops the watcher when the address is cleared. A
     * default-network callback is a standing registration with the system that
     * outlives every screen and wakes the process for each change, so leaving one
     * in place with nothing configured would be a leak paid for in battery and not
     * only in memory.
     *
     * What counts as a change is [ConnectivityGate]'s job: the default network
     * arriving or going, plus the two moments it becomes one that can carry the
     * route. `onAvailable` alone is not enough — it can arrive before the network
     * is routable, and a probe that fails there must not be the last word. Only
     * the *default* network is watched, and that is not a shortcut: a request only
     * leaves by the default network, so a LAN candidate is unreachable unless the
     * Wi-Fi carrying it is the default one.
     */
    private fun watchConnectivity() {
        if (connectivityCallback != null) return
        val manager = runCatching { appContext.getSystemService(ConnectivityManager::class.java) }
            .getOrNull() ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = routeMayHaveMoved()

            override fun onLost(network: Network) {
                connectivityGate.lost()
                routeMayHaveMoved()
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val usable = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (connectivityGate.capabilities(usable)) routeMayHaveMoved()
            }

            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                val addressed = link.linkAddresses.any { it.address is Inet4Address }
                if (connectivityGate.addressed(addressed)) routeMayHaveMoved()
            }
        }
        // A device with no connectivity service (or a test JVM) simply keeps the
        // event-driven refresh paths; there is nothing to report.
        runCatching { manager.registerDefaultNetworkCallback(callback) }
            .onSuccess { connectivityCallback = callback }
    }

    /** Stop watching; for the moment the configured address is cleared. */
    private fun stopWatchingConnectivity() {
        val callback = connectivityCallback ?: return
        connectivityCallback = null
        connectivityGate.lost()
        runCatching {
            appContext.getSystemService(ConnectivityManager::class.java)
                ?.unregisterNetworkCallback(callback)
        }
    }

    /**
     * The default network moved in a way that can change the route: drop every
     * answer attached to the old wire — the cached winner *and* any door that
     * failed on it — and probe again.
     */
    private fun routeMayHaveMoved() {
        endpoint.networkChanged()
        requestRouteRefresh()
    }

    /**
     * The phone's own IPv4, for the resolver's subnet rewrite. Site-local only:
     * a carrier address says nothing about where the host might be.
     */
    private fun localIpv4Address(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()

    /**
     * "Is a DSH host here?" — the same answer [probe] gives, reduced to the two
     * outcomes that prove a host is present. A host that *refuses* an
     * unauthenticated `GET /` is still a host: the LAN door answers 401 before
     * `/auth/login`, and a public door redirects to it. Requiring 200 would make
     * the LAN candidate unwinnable and the whole feature a no-op.
     */
    private suspend fun answersAsDshHost(url: HttpUrl): Boolean =
        when (probeAt(url, clients.probe)) {
            is ProbeResult.Ok, ProbeResult.NeedsLogin -> true
            // A host that answered with a certificate nobody has vouched for yet is
            // still a host, and the LAN door is exactly where that happens: it is a
            // private address with a private certificate, and the probe that follows
            // is what accepts it. Counting it as absent would make such a door
            // unreachable forever, because the only probe that trusts on first use
            // runs against the winner the resolver has already picked.
            is ProbeResult.CertificateUnknown -> true
            else -> false
        }

    private fun normalizeBase(raw: String): String {
        val trimmed = raw.trim()
        val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "http://$trimmed"
        }
        return if (withScheme.endsWith("/")) withScheme else "$withScheme/"
    }

    private fun apiUrl(method: String): HttpUrl {
        val root = baseUrl() ?: throw DshAuthException("No server configured")
        return root.newBuilder().addPathSegments("api").addPathSegments(method).build()
    }

    private fun apiEventsUrl(): HttpUrl {
        val root = baseUrl() ?: throw DshAuthException("No server configured")
        return root.newBuilder().addPathSegments("api").addPathSegments("remote.mux").build()
    }

    /** Installs a manually pasted cookie (used for direct-to-carrier setups). */
    fun installManualCookie() {
        val raw = config.manualCookie.trim()
        if (raw.isEmpty()) return
        val root = baseUrl() ?: return
        cookieJar.setRawCookie(root, raw)
    }

    suspend fun probe(): ProbeResult {
        val root = baseUrl() ?: return ProbeResult.Failed("No server URL configured")
        val first = probeAt(root)
        if (first !is ProbeResult.CertificateUnknown) return first
        // Trust on first use. The system store does not vouch for this chain and the
        // app has nothing recorded for this host, so the certificate it has just
        // offered becomes that host's anchor — and its pin. The acceptance is
        // permanent through [onTrustChanged]; before that fires, only this process
        // knows about it.
        val leaf = first.chain.firstOrNull()
            ?: return ProbeResult.Unreachable("The host offered no certificate")
        trust.accept(first.host, leaf)
        clients = buildClients()
        onTrustChanged?.invoke()
        return probeAt(root)
    }

    /** [probe] against a given address and client, for the resolver's candidates. */
    private suspend fun probeAt(root: HttpUrl, client: OkHttpClient = clients.noRedirect): ProbeResult =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(root).get().build()
                client.newCall(request).execute().use { response ->
                    when (response.code) {
                        200 -> ProbeResult.Ok(response.header("Server").orEmpty())
                        401, 403 -> ProbeResult.NeedsLogin
                        302, 303 -> ProbeResult.NeedsLogin
                        else -> ProbeResult.Failed("Server answered HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                // A refused handshake is not an unreachable host: it is a question for
                // the user, and the chain that raised it travels out on the exception.
                val chain = e.offeredChain()
                if (!chain.isNullOrEmpty()) {
                    return@withContext when (val accepted = trust.entryFor(root.host)) {
                        null -> ProbeResult.CertificateUnknown(root.host, chain)
                        else -> ProbeResult.CertificateChanged(root.host, chain, accepted)
                    }
                }
                // A *pin* failure is the impostor case: the trust manager was satisfied
                // — usually because a public CA vouched for the impostor's certificate
                // — and only the pin refused it. That check carries no chain, so one is
                // read to show. Only ever for a host that has something accepted: an
                // unpinned host has no pin to fail.
                val accepted = trust.entryFor(root.host)
                val shown = if (accepted != null && CertPins.isPinFailure(e)) {
                    CertProbe.probe(root).getOrNull()?.chain.orEmpty()
                } else {
                    emptyList()
                }
                if (!shown.isNullOrEmpty()) {
                    ProbeResult.CertificateChanged(root.host, shown, accepted!!)
                } else {
                    ProbeResult.Unreachable(e.message ?: e.javaClass.simpleName)
                }
            }
        }

    /**
     * Signs in through the auth proxy. On success the proxy answers 303 to `/`
     * and sets `dsh_auth_session`; a bad credential redirects to
     * `/auth/login?error=1`, and a locked account adds `locked=1`.
     */
    suspend fun login(username: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        val root = baseUrl() ?: return@withContext Result.failure(DshAuthException("No server URL configured"))
        try {
            val body = FormBody.Builder()
                .add("provider", "password")
                .add("username", username)
                .add("password", password)
                .build()
            val request = Request.Builder()
                .url(root.newBuilder().addPathSegments("auth/login").build())
                .post(body)
                .build()
            clients.noRedirect.newCall(request).execute().use { response ->
                val location = response.header("Location").orEmpty()
                when {
                    location.contains("locked=1") ->
                        Result.failure(DshAuthException("Too many attempts — the host locked this account briefly."))
                    location.contains("captcha=1") || location.contains("error=1") ->
                        Result.failure(DshAuthException("Invalid username or password."))
                    response.code == 303 || response.code == 302 -> Result.success(Unit)
                    response.code == 200 -> Result.success(Unit)
                    else -> Result.failure(DshAuthException("Login failed (HTTP ${response.code})"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Signs in, retrying once against a possibly-changed session, then verifies. */
    suspend fun ensureAuthenticated(): Result<Unit> {
        installManualCookie()
        return when (val probe = probe()) {
            is ProbeResult.Ok -> Result.success(Unit)
            // Only a host that *refused* us may send the user back to sign-in.
            // "It did not answer" and "it answered oddly" are transient, and the
            // caller keeps the user where they were.
            is ProbeResult.Failed -> Result.failure(DshUnreachableException(probe.message))
            is ProbeResult.Unreachable -> Result.failure(DshUnreachableException(probe.message))

            // A certificate question is neither a sign-in problem nor a transient
            // one, so it gets its own outcome: the setup screen is the only place it
            // can be answered, and it is where this failure lands.
            is ProbeResult.CertificateUnknown -> Result.failure(
                DshCertificateException(
                    message = "The host's certificate has not been accepted yet.",
                    host = probe.host,
                    chain = probe.chain,
                    accepted = null,
                ),
            )

            is ProbeResult.CertificateChanged -> Result.failure(
                DshCertificateException(
                    message = "The certificate for ${probe.host} is not the one accepted " +
                        "for it. Check the new fingerprint before trusting it.",
                    host = probe.host,
                    chain = probe.chain,
                    accepted = probe.accepted,
                ),
            )

            ProbeResult.NeedsLogin -> {
                if (config.username.isBlank()) {
                    Result.failure(DshAuthException("The host requires a login."))
                } else {
                    onSignIn?.invoke()
                    login(config.username, config.password).mapCatching { }
                }
            }
        }
    }

    /** One unary gateway call. Throws [DshRpcException] on a structured host error. */
    suspend fun rpc(method: String, args: JSONObject = JSONObject()): JSONObject =
        (rpcRaw(method, args) as? JSONObject) ?: JSONObject()

    /**
     * The same call, returning `value` untouched.
     *
     * `fileReferences/list` and `sessionReferenceResolver/candidates` answer with a
     * bare JSON *array*, which [rpc] cannot carry — it reads `value` as an object
     * and would quietly hand back an empty one.
     *
     * The client's read timeout is deliberately long (see [base]): the
     * session-reference resolver walks the whole session corpus and answers in tens
     * of seconds on a large workspace.
     */
    suspend fun rpcRaw(
        method: String,
        args: JSONObject = JSONObject(),
    ): Any? =
        withContext(Dispatchers.IO) {
            val envelope = JSONObject()
                .put("type", "client-request")
                .put("rpcId", UUID.randomUUID().toString())
                .put("method", method)
                .put("payload", JSONObject().put("args", args))

            val request = Request.Builder()
                .url(apiUrl(method))
                .post(envelope.toString().toRequestBody(JSON))
                .build()

            clients.unary.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw DshAuthException("Session expired (HTTP ${response.code})")
                }
                if (text.isBlank()) throw DshRpcException("http/${response.code}", "Empty response from host", null)

                val parsed = runCatching { JSONObject(text) }.getOrElse {
                    // Content that is not JSON. Whether that is a verdict on the
                    // door or the host's own surface failing the call is what the
                    // status says; see [doorFaultForAnswer]. A captive portal
                    // answers its page with 200, which is the case the verdict is
                    // for; a 502 from the auth gate while the carrier restarts is
                    // not, and treating it as one set the LAN door aside until the
                    // network changed.
                    onRouteFailure(doorFaultForAnswer(response.code))
                    throw DshRpcException("http/${response.code}", text.take(400), null)
                }
                val result = parsed.optJSONObject("result")
                    ?: run {
                        // JSON, but not this protocol's envelope — the same question
                        // as above, answered the same way.
                        onRouteFailure(doorFaultForAnswer(response.code))
                        throw DshRpcException("protocol", "Malformed response envelope", null)
                    }

                if (!result.optBoolean("ok")) {
                    val error = result.optJSONObject("error")
                    throw DshRpcException(
                        code = error?.optString("code").orEmpty().ifEmpty { "unknown" },
                        message = error?.optString("message").orEmpty().ifEmpty { "Host rejected the call" },
                        details = error?.optJSONObject("details"),
                    )
                }
                result.opt("value")?.takeIf { it != JSONObject.NULL }
            }
        }

    /**
     * One unary gateway call whose answer is **not** JSON.
     *
     * `workspaceFiles/readBytes` answers `multipart/form-data` on 0.2.0, holding the
     * file's bytes in its first part. [rpcRaw] reads the body as a string and, when it
     * will not parse as an envelope, throws with the first 400 characters as the
     * message — which is why the Files panel used to display an HTTP response body
     * (`Content-Disposition: form-data; name="bytes-0"` over PNG chunks) where a
     * picture belonged.
     *
     * A *failed* call still answers JSON, so that is checked first and its structured
     * error is raised as usual.
     */
    suspend fun rpcBytes(method: String, args: JSONObject = JSONObject()): ByteArray =
        withContext(Dispatchers.IO) {
            val envelope = JSONObject()
                .put("type", "client-request")
                .put("rpcId", UUID.randomUUID().toString())
                .put("method", method)
                .put("payload", JSONObject().put("args", args))

            val request = Request.Builder()
                .url(apiUrl(method))
                .post(envelope.toString().toRequestBody(JSON))
                .build()

            clients.unary.newCall(request).execute().use { response ->
                val type = response.header("content-type").orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw DshAuthException("Session expired (HTTP ${response.code})")
                }
                val body = response.body?.bytes() ?: ByteArray(0)
                if (type.contains("json", ignoreCase = true)) {
                    val text = body.toString(Charsets.UTF_8)
                    val parsed = runCatching { JSONObject(text) }.getOrNull()
                    val result = parsed?.optJSONObject("result")
                    if (result != null && !result.optBoolean("ok")) {
                        val error = result.optJSONObject("error")
                        throw DshRpcException(
                            code = error?.optString("code").orEmpty().ifEmpty { "unknown" },
                            message = error?.optString("message").orEmpty().ifEmpty { "Host rejected the call" },
                            details = error?.optJSONObject("details"),
                        )
                    }
                    throw DshRpcException("protocol", "Expected bytes, got JSON", null)
                }
                body
            }
        }

    /**
     * The chosen door failed in use — in the way [kind] names, which decides how it
     * comes back.
     *
     * A timeout, a dropped socket, no route: that is precisely what a stale LAN
     * choice looks like, the address having answered a minute ago and the phone
     * having since left the network, and it is [DoorFault.Transport]. An answer
     * that is not a DSH envelope is the *other* shape, and the one a captive portal
     * produces: it answers every address with its own page, so the cheap `GET /`
     * the resolver probes with succeeds and only a real gateway call reveals that
     * nothing here speaks DSH. Which of the two a non-envelope answer is depends on
     * the status that carried it — see [doorFaultForAnswer], which is what keeps the
     * host's own `404` and the auth gate's `502` from being read as a verdict on the
     * door and setting the LAN aside until the network changes.
     *
     * Either way the door is recorded before the cache is dropped, so the
     * re-resolution that follows lands on the other one instead of choosing the
     * broken door again.
     *
     * Rate-limited, but only for the probe: an unreachable host fails *every*
     * call, and invalidating per failure would turn one outage into a probe round
     * per request. Recording per failure is deliberate and is not what this bounds
     * — the resolver counts a failure it is already avoiding as the same one, and
     * one outage must not be able to hide behind the cooldown.
     */
    private fun onRouteFailure(kind: DoorFault) {
        val door = endpoint.peek()
        Log.w(ROUTE_TAG, "$kind recorded against $door")
        endpoint.noteFailure(door, kind)
        val now = System.currentTimeMillis()
        if (now - lastRouteFailureAt < ROUTE_FAILURE_COOLDOWN_MS) return
        lastRouteFailureAt = now
        endpoint.invalidate()
        requestRouteRefresh()
    }

    /** The OkHttp client, for the one caller that fetches a picture from the internet. */
    fun httpClient(): okhttp3.OkHttpClient = clients.base

    /** Convenience wrapper matching the host's `_request`-style parameter naming. */
    suspend fun listSessions(): JSONObject = rpc("session/list", JSONObject().put("_request", JSONObject()))

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()

        /**
         * The tag the route decisions are left under.
         *
         * The resolver is deliberately invisible when it works, which is what makes
         * "the LAN got stuck again" unanswerable after the fact: the screen says
         * Public without saying whether the LAN door was probed and missed, or set
         * aside and not re-asked. These lines are the difference, and they are the
         * only route state that leaves the process.
         */
        private const val ROUTE_TAG = "DshRoute"

        /** At most one route-failure re-probe per this window; see [onRouteFailure]. */
        private const val ROUTE_FAILURE_COOLDOWN_MS = 5_000L
    }
}

/**
 * The chain a refused handshake offered, if this failure is one of ours.
 *
 * JSSE wraps the trust manager's `CertificateException` in an `SSLHandshakeException`
 * and keeps it as the cause, so the chain reaches a caller three frames above the
 * handshake that saw it. Walking the causes rather than remembering the last chain on
 * the trust manager is deliberate: the resolver probes two addresses at once, and a
 * field would let one host's certificates answer for the other's failure.
 */
private fun Throwable.offeredChain(): List<X509Certificate>? {
    var current: Throwable? = this
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (current is CertTrustException) return current.chain
        current = current.cause
        depth++
    }
    return null
}

private const val MAX_CAUSE_DEPTH = 8
