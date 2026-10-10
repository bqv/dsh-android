package uk.xa0.dsh.net

import okhttp3.CertificatePinner
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Locale

/**
 * Certificate pinning for the host the app talks to.
 *
 * HTTPS on its own only ever said "a certificate authority vouched for this name".
 * That is a weaker statement than it sounds when the name points at somebody else's
 * edge: whoever terminates the connection for `dsh.example` is also whoever a CA
 * will issue to for `dsh.example`, because the validation lands on the same box
 * that answers the name. A pin replaces the question "does a CA like this
 * certificate?" with "is this the public key I checked once, by hand?" — and a
 * certificate is only useful to an attacker who also holds the private key that
 * key names.
 *
 * A pin names a **key in the chain, not necessarily the leaf's**. OkHttp passes a
 * connection when *any* certificate in it carries a pinned key, which is what makes
 * one mechanism serve both shapes of host this app meets: a public certificate
 * renews its leaf constantly, so pin the leaf and re-pin when it rotates; a private
 * CA's leaf rotates too, but the CA does not, so pin the CA's key and the pin
 * outlives every leaf it signs.
 *
 * The pin is SHA-256 over a certificate's SubjectPublicKeyInfo: the same value
 * HPKP, `network_security_config` and OkHttp's [CertificatePinner] all use, so a
 * fingerprint computed on the host with
 *
 * ```
 * openssl x509 -in cert.pem -pubkey -noout |
 *   openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64
 * ```
 *
 * can be compared character for character with what this app displays before it is
 * trusted.
 *
 * **Pins are runtime configuration, not constants.** A Let's Encrypt renewal that
 * does not reuse the key mints a new one every time (certbot needs `--reuse-key`
 * for that), so a compiled-in pin would be a time bomb that goes off while the user
 * is away from a build machine. The pin therefore lives beside the address in
 * [uk.xa0.dsh.data.DshConfig], where the person who rotated the key can replace it
 * from the setup screen — after checking the new fingerprint against the host.
 *
 * Pinning is also why the trust anchors in `res/xml/network_security_config.xml`
 * stay as they are: a pin is consulted *after* the chain validates, so a private
 * CA has to remain trusted for its pins to mean anything. The pin, not the anchor
 * list, is what refuses an impostor.
 */
object CertPins {

    /**
     * OkHttp and HPKP spell a pin `sha256/<base64>`; what this app stores and shows
     * is the bare base64 digest, because that is what `openssl | base64` prints.
     */
    private const val OKHTTP_PREFIX = "sha256/"

    /** The pin of a certificate's public key — the value a pin stores. */
    fun pinOf(publicKey: PublicKey): String = pinOf(publicKey.encoded)

    /** The same, for a certificate: what a user compares against `openssl` on the host. */
    fun pinOf(certificate: X509Certificate): String = pinOf(certificate.publicKey)

    /** The same, from an X.509 SubjectPublicKeyInfo blob. */
    fun pinOf(encodedPublicKey: ByteArray): String =
        Base64.getEncoder().encodeToString(sha256(encodedPublicKey))

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    /**
     * A pin in the form a person compares against `openssl` output: colon-separated
     * hex, uppercase. Base64 is only 44 characters, but `l/1I0O` and `l/1l0O` are
     * the same string to a human under pressure — which is the one moment this
     * readout exists for.
     */
    fun display(pin: String): String {
        val bytes = decodeOrNull(pin) ?: return pin
        return bytes.joinToString(":") { String.format(Locale.ROOT, "%02X", it) }
    }

    /**
     * True when [raw] is a bare SHA-256 digest in base64.
     *
     * The only shape this app stores. `CertificatePinner` accepts `sha1/` and
     * `sha256/` pins of several lengths, and a malformed value would either silently
     * pin nothing or stop the app at startup, so what comes off disk is checked
     * against one shape and dropped if it does not match — a preference the app
     * cannot read must not be able to take it down.
     */
    fun isWellFormed(raw: String): Boolean = decodeOrNull(raw)?.size == 32

    /**
     * The pin set as an OkHttp [CertificatePinner].
     *
     * OkHttp checks this while the connection is being established, before a single
     * byte of the request is written — which is why pinning belongs here and not in
     * an interceptor: an interceptor sees the response only after the request (with
     * its credentials) has already been sent to whoever answered the handshake.
     *
     * An entry whose pin is malformed is skipped rather than thrown over: the call
     * then behaves as if that host were unpinned, which is what it is.
     */
    fun pinner(pins: Map<String, String>): CertificatePinner {
        val builder = CertificatePinner.Builder()
        for ((host, pin) in pins) {
            if (host.isBlank() || !isWellFormed(pin)) continue
            builder.add(host, OKHTTP_PREFIX + pin.trim())
        }
        return builder.build()
    }

    /**
     * True when a failure is the pinner's refusal rather than anything about the network.
     *
     * OkHttp reports one as an `SSLPeerUnverifiedException` whose message begins
     * "Certificate pinning failure!", and — unlike a refused chain — it carries no
     * certificates at all. The caller therefore has to ask the host again if it wants
     * to show what was presented, which is why this is worth telling apart.
     */
    fun isPinFailure(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        // Bounded: a self-referencing cause chain is possible in principle, and this
        // runs on a failure path where looping forever is the one unacceptable answer.
        while (current != null && depth < 8) {
            if (current.message.orEmpty().contains("pinning failure", ignoreCase = true)) return true
            current = current.cause
            depth++
        }
        return false
    }

    private fun decodeOrNull(raw: String): ByteArray? =
        runCatching { Base64.getDecoder().decode(raw.trim()) }.getOrNull()
}
