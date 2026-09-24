package uk.xa0.dsh.net

import android.content.Context
import kotlinx.coroutines.Dispatchers
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

/** Outcome of a connectivity probe, used by the setup screen. */
sealed interface ProbeResult {
    data class Ok(val serverName: String) : ProbeResult
    data object NeedsLogin : ProbeResult

    /** The host answered, but with a status this client does not recognise. */
    data class Failed(val message: String) : ProbeResult

    /** No answer at all: the request never completed. */
    data class Unreachable(val message: String) : ProbeResult
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

    val cookieJar = PersistentCookieJar(context)

    private val base: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieJar)
        .connectTimeout(15, TimeUnit.SECONDS)
        // Without an explicit read timeout OkHttp's 10s default applies, and a busy
        // host — or the session-reference resolver, which walks every session — trips
        // it constantly. `callTimeout` still bounds the whole call.
        .readTimeout(60, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
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
    val streamClient: OkHttpClient = base.newBuilder()
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

    private val noRedirect: OkHttpClient = base.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    @Volatile
    var config: DshConfig = DshConfig()
        private set

    @Volatile
    var mux: RemoteMux? = null
        private set

    fun applyConfig(config: DshConfig) {
        this.config = config
        mux?.shutdown()
        mux = null
    }

    /** The remote-event multiplexer for the active config, created on first use. */
    fun mux(): RemoteMux {
        mux?.let { return it }
        synchronized(this) {
            mux?.let { return it }
            val created = RemoteMux(streamClient, apiEventsUrl())
            mux = created
            return created
        }
    }

    fun baseUrl(): HttpUrl? = config.baseUrl.takeIf { it.isNotBlank() }?.let {
        normalizeBase(it).toHttpUrlOrNull()
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

    suspend fun probe(): ProbeResult = withContext(Dispatchers.IO) {
        val root = baseUrl() ?: return@withContext ProbeResult.Failed("No server URL configured")
        try {
            val request = Request.Builder().url(root).get().build()
            noRedirect.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> ProbeResult.Ok(response.header("Server").orEmpty())
                    401, 403 -> ProbeResult.NeedsLogin
                    302, 303 -> ProbeResult.NeedsLogin
                    else -> ProbeResult.Failed("Server answered HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            ProbeResult.Unreachable(e.message ?: e.javaClass.simpleName)
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
            noRedirect.newCall(request).execute().use { response ->
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

            ProbeResult.NeedsLogin -> {
                if (config.username.isBlank()) {
                    Result.failure(DshAuthException("The host requires a login."))
                } else {
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

            base.newCall(request).execute().use { response ->
                val text = response.body?.string().orEmpty()
                if (response.code == 401 || response.code == 403) {
                    throw DshAuthException("Session expired (HTTP ${response.code})")
                }
                if (text.isBlank()) throw DshRpcException("http/${response.code}", "Empty response from host", null)

                val parsed = runCatching { JSONObject(text) }.getOrElse {
                    throw DshRpcException("http/${response.code}", text.take(400), null)
                }
                val result = parsed.optJSONObject("result")
                    ?: throw DshRpcException("protocol", "Malformed response envelope", null)

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

    /** Convenience wrapper matching the host's `_request`-style parameter naming. */
    suspend fun listSessions(): JSONObject = rpc("session/list", JSONObject().put("_request", JSONObject()))

    companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
