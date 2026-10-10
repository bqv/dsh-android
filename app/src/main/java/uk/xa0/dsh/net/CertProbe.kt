package uk.xa0.dsh.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import java.net.InetSocketAddress
import java.security.cert.X509Certificate
import java.util.Locale
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * The chain a host offered, plus whether the system already vouches for it.
 *
 * The chain is what the user is asked to judge, and — for a private CA — which link
 * of it to accept: pinning the CA rather than the leaf is what survives the next
 * leaf renewal.
 */
data class CertReadout(
    val host: String,
    val chain: List<X509Certificate>,
    val systemTrusted: Boolean,
) {
    val leaf: X509Certificate? get() = chain.firstOrNull()

    fun pinOf(certificate: X509Certificate): String = CertPins.pinOf(certificate.publicKey)
}

/**
 * Reads what a host presents, without asking whether it should be trusted.
 *
 * Deliberately permissive: the one moment this runs is the moment the ordinary
 * client *refused*, so a validating handshake would fail here too and there would be
 * nothing to show. It performs a handshake and nothing else — no request, no
 * credentials, no cookie — so the only thing an accepted-nevermind certificate can
 * do with it is be looked at.
 *
 * Two callers: the setup screen's "check certificate", and the prompt that follows a
 * refusal. Both only ever display what comes back.
 */
object CertProbe {

    /** Long enough for a slow tunnel, short enough that a dead host is not a hang. */
    const val DEFAULT_TIMEOUT_MS = 8_000

    suspend fun probe(url: HttpUrl, timeoutMs: Int = DEFAULT_TIMEOUT_MS): Result<CertReadout> =
        withContext(Dispatchers.IO) { runCatching { read(url, timeoutMs) } }

    /** The pure half: a chain that has already been read becomes a readout. */
    fun readoutOf(
        host: String,
        chain: List<X509Certificate>,
        systemTrusted: Boolean,
    ): CertReadout = CertReadout(host, chain, systemTrusted)

    private fun read(url: HttpUrl, timeoutMs: Int): CertReadout {
        val host = url.host
        val port = if (url.port > 0) url.port else DEFAULT_HTTPS_PORT
        val socket = openSocket(host, port, timeoutMs)
        socket.use {
            it.startHandshake()
            val chain = it.session.peerCertificates.filterIsInstance<X509Certificate>()
            require(chain.isNotEmpty()) { "The host offered no certificate" }
            return readoutOf(host, chain, systemTrusts(chain))
        }
    }

    private fun openSocket(host: String, port: Int, timeoutMs: Int): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<TrustManager>(AcceptAnything), null)
        val socket = context.socketFactory.createSocket() as SSLSocket
        socket.soTimeout = timeoutMs
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        // SNI before the handshake, so a host that fronts several names answers for
        // this one. Absent for an IP literal, where SNIHostName refuses — and there
        // is nothing to send anyway.
        runCatching {
            val params = socket.sslParameters
            params.serverNames = listOf(SNIHostName(host))
            socket.sslParameters = params
        }
        return socket
    }

    /**
     * Whether the chain would have been accepted without any of this — advisory, for
     * the readout's own sentence. It is never a reason to trust a pin: the whole
     * point of a pin is that this answer can be yes for a certificate that is not
     * ours.
     */
    internal fun systemTrusts(chain: List<X509Certificate>): Boolean {
        val leaf = chain.firstOrNull() ?: return false
        return runCatching {
            CertTrust.systemTrust.checkServerTrusted(chain.toTypedArray(), authTypeOf(leaf))
        }.isSuccess
    }

    /**
     * The key exchange algorithm JSSE labels a chain with. Only the certificate's own
     * key type matters to a trust manager, and for TLS 1.3 the cipher suite name does
     * not carry it, so the certificate is the honest source.
     */
    private fun authTypeOf(leaf: X509Certificate): String =
        when (leaf.publicKey.algorithm.uppercase(Locale.ROOT)) {
            "EC", "ECDSA" -> "ECDHE_ECDSA"
            "DSA" -> "DHE_DSS"
            else -> "RSA"
        }

    /**
     * Present so the handshake completes and the chain becomes readable. It grants
     * nothing: [read] never sends a request, and the object is unreachable from any
     * code path that builds a client.
     */
    private object AcceptAnything : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private const val DEFAULT_HTTPS_PORT = 443
}
