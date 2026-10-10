package uk.xa0.dsh.net

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The app's certificate handling, against three throwaway certificates generated with
 * openssl — a self-signed leaf, a private CA, and a leaf that CA signed.
 *
 * The pins below were computed by `openssl` rather than by this code, so the first
 * test is a real cross-check of the one number the whole scheme rests on: if the app
 * and the host disagree about what a fingerprint is, a pin is worse than useless.
 *
 * Nothing here needs a network: a trust manager is a pure function of a chain and a
 * keystore, which is exactly why the decision was put there.
 */
class CertTrustTest {

    @Test
    fun `a pin is the sha256 of the subject public key info`() {
        assertEquals(SELF_SIGNED_PIN, CertPins.pinOf(selfSigned()))
        assertEquals(LOCAL_CA_PIN, CertPins.pinOf(localCa()))
        assertEquals(LEAF_PIN, CertPins.pinOf(leaf()))
    }

    @Test
    fun `a pin is displayed as colon separated hex so it can be compared by eye`() {
        assertEquals(SELF_SIGNED_HEX, CertPins.display(SELF_SIGNED_PIN))
        assertEquals(LEAF_HEX, CertPins.display(LEAF_PIN))
    }

    @Test
    fun `only a 32 byte digest counts as a pin`() {
        assertTrue(CertPins.isWellFormed(SELF_SIGNED_PIN))
        assertEquals(false, CertPins.isWellFormed("not base64 at all !!"))
        assertEquals(false, CertPins.isWellFormed(Base64.getEncoder().encodeToString(ByteArray(16))))
    }

    @Test
    fun `a host with nothing accepted is refused and the chain comes back with the refusal`() {
        val trust = CertTrust()
        val failure = assertThrows(CertificateException::class.java) {
            trust.trustManager().checkServerTrusted(arrayOf(leaf(), localCa()), "ECDHE_ECDSA")
        }
        val carried = generateSequence(failure as Throwable?) { it.cause }
            .filterIsInstance<CertTrustException>()
            .firstOrNull()
        assertNotNull("the refusal must carry the chain it refused", carried)
        assertEquals(2, carried!!.chain.size)
    }

    @Test
    fun `accepting the signing CA anchors every leaf it signed`() {
        val trust = CertTrust()
        trust.accept("leaf.test.example", localCa())

        // Accepted as an anchor, not compared as a fingerprint: the platform's own
        // path validation runs, which is what keeps expiry and key usage meaningful
        // for a host the user vouched for by hand.
        trust.trustManager().checkServerTrusted(arrayOf(leaf(), localCa()), "ECDHE_ECDSA")

        assertEquals(LOCAL_CA_PIN, trust.entryFor("leaf.test.example")?.pin)
        assertEquals(mapOf("leaf.test.example" to LOCAL_CA_PIN), trust.pins())
    }

    @Test
    fun `accepting one certificate anchors only that certificate`() {
        val trust = CertTrust()
        trust.accept("self.test.example", selfSigned())

        trust.trustManager().checkServerTrusted(arrayOf(selfSigned()), "ECDHE_ECDSA")
        // The CA is not what was accepted, so the chain it signed is still nobody the
        // app knows — trust does not spread to a different issuer by accident.
        assertThrows(CertificateException::class.java) {
            trust.trustManager().checkServerTrusted(arrayOf(leaf(), localCa()), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `forgetting a host takes the anchor away with the entry`() {
        val trust = CertTrust()
        trust.accept("self.test.example", selfSigned())
        trust.forget("self.test.example")

        assertNull(trust.entryFor("self.test.example"))
        assertEquals(emptyMap<String, String>(), trust.pins())
        assertThrows(CertificateException::class.java) {
            trust.trustManager().checkServerTrusted(arrayOf(selfSigned()), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `replace drops an entry the stored state no longer has`() {
        val trust = CertTrust(listOf(AcceptedCert("a.test.example", 1L, selfSigned())))
        trust.replace(listOf(AcceptedCert("b.test.example", 2L, localCa())))

        assertNull(trust.entryFor("a.test.example"))
        assertNotNull(trust.entryFor("b.test.example"))
    }

    @Test
    fun `entries survive a round trip through the store, malformed lines and all`() {
        val stored = listOf(
            AcceptedCert("Self.Test.Example", 1_700_000_000_000L, selfSigned()),
            AcceptedCert("leaf.test.example", 1_700_000_001_000L, localCa()),
        )
        val raw = CertTrust.encode(stored) + "\ngarbage\nno-tabs-here\nx\tnope\tAAAA"

        val decoded = CertTrust.decode(raw)
        assertEquals(2, decoded.size)
        assertEquals("self.test.example", decoded[0].host)
        assertEquals(selfSigned(), decoded[0].certificate)
        assertEquals(1_700_000_001_000L, decoded[1].acceptedAt)
        assertEquals(localCa(), decoded[1].certificate)
    }

    @Test
    fun `hosts are matched case insensitively because hostnames are`() {
        val trust = CertTrust()
        trust.accept("Leaf.Test.Example", localCa())
        assertNotNull(trust.entryFor("leaf.test.example"))
    }

    @Test
    fun `the pinner enforces a pin per host and ignores a malformed one`() {
        val pinner = CertPins.pinner(
            mapOf(
                "leaf.test.example" to LEAF_PIN,
                "broken.test.example" to "not-a-pin",
            ),
        )
        assertEquals(1, pinner.findMatchingPins("leaf.test.example").size)
        assertEquals(0, pinner.findMatchingPins("broken.test.example").size)
        assertEquals(0, pinner.findMatchingPins("other.test.example").size)
        // A pin is scoped to its own host: another host is not made answerable by it.
        assertEquals(0, pinner.findMatchingPins("self.test.example").size)
    }

    @Test
    fun `a pinner refusal is recognisable, an ordinary refusal is not`() {
        assertTrue(
            CertPins.isPinFailure(
                IOException(
                    "Certificate pinning failure!\n  Peer certificate chain:\n" +
                        "    sha256/aaaa…: CN=impostor.example",
                ),
            ),
        )
        // A pin failure arrives wrapped, and the walk has to find it anyway.
        assertTrue(CertPins.isPinFailure(IOException("handshake failed", IOException("Certificate pinning failure!"))))
        assertEquals(
            false,
            CertPins.isPinFailure(CertificateException("unable to find valid certification path to requested target")),
        )
    }

    private fun certificate(base64Der: String): X509Certificate =
        CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(Base64.getDecoder().decode(base64Der)))
            as X509Certificate

    private fun selfSigned() = certificate(SELF_SIGNED_DER)
    private fun localCa() = certificate(LOCAL_CA_DER)
    private fun leaf() = certificate(LEAF_DER)

    private companion object {
        const val SELF_SIGNED_DER = "MIIBpzCCAU6gAwIBAgIUZ3kEn8PqxA8bc8VYKDKoUgJ8ntcwCgYIKoZIzj0EAwIwGzEZMBcGA1UEAwwQcGluLnRlc3QuZXhhbXBsZTAeFw0yNjEwMTAyMDM1MTZaFw0zNjEwMDcyMDM1MTZaMBsxGTAXBgNVBAMMEHBpbi50ZXN0LmV4YW1wbGUwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASi6sVKY0VhjOiU/VEI4FnHJewtpsTkFXAl09PBjkcv8mwuc7rRKcsKE1Ag8nTk1dCJsSrMkAdS2Zl5F5lYt/lNo3AwbjAdBgNVHQ4EFgQUkC9mxiPwLlUf0milc/V/5yWTwxowHwYDVR0jBBgwFoAUkC9mxiPwLlUf0milc/V/5yWTwxowDwYDVR0TAQH/BAUwAwEB/zAbBgNVHREEFDASghBwaW4udGVzdC5leGFtcGxlMAoGCCqGSM49BAMCA0cAMEQCIG7SJSlEbKBNGGlT0W0A4B/9HDg3ItGNiTfp+fgw8YIcAiBV9M0J61gSuE+/yi6nbsvzCEpjgV0p7pFLudQAIqWDHw=="
        const val LOCAL_CA_DER = "MIIBlTCCATugAwIBAgIUa75VZAyPrN735vGcpbktThW4o1swCgYIKoZIzj0EAwIwGDEWMBQGA1UEAwwNVGVzdCBMb2NhbCBDQTAeFw0yNjEwMTAyMDM1MTZaFw0zNjEwMDcyMDM1MTZaMBgxFjAUBgNVBAMMDVRlc3QgTG9jYWwgQ0EwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAASKmraRb7SBX7XKCIxpcFZeKuOcUUryV8qVUanMuHL6wHfMUCtZyI1mt2mi54UBkVfwZhmZjfYi1V8HdBkP8UC3o2MwYTAdBgNVHQ4EFgQUEly8TIhS6LpT4YSbJjmsvBNhudAwHwYDVR0jBBgwFoAUEly8TIhS6LpT4YSbJjmsvBNhudAwDwYDVR0TAQH/BAUwAwEB/zAOBgNVHQ8BAf8EBAMCAQYwCgYIKoZIzj0EAwIDSAAwRQIgY534UZihXBrf+HrvHrnbfFW005YlvSgEiEJo/E8vID4CIQCsRK2UKjT3sj8STms5di5OqsdHHlE4qX+MhibtPnAfKA=="
        const val LEAF_DER = "MIIBZTCCAQugAwIBAgIBAjAKBggqhkjOPQQDAjAYMRYwFAYDVQQDDA1UZXN0IExvY2FsIENBMB4XDTI2MTAxMDIwMzUxNloXDTI3MTAxMDIwMzUxNlowHDEaMBgGA1UEAwwRbGVhZi50ZXN0LmV4YW1wbGUwWTATBgcqhkjOPQIBBggqhkjOPQMBBwNCAAQbEoqyh6fwPW0F3d+Fn3ymVG16aU9JabCGdHbWFM8El42rvpYzKY5fybyDGt8I0ICuzne/YDAeVCCyfOWfuKNvo0IwQDAdBgNVHQ4EFgQUUvJpJ36yFtkxoNwOfLS9qcrlnnMwHwYDVR0jBBgwFoAUEly8TIhS6LpT4YSbJjmsvBNhudAwCgYIKoZIzj0EAwIDSAAwRQIhAM7H9HQ4JdZvC2WXRQoyYAST435IOVTHeDxGUc8XDsb3AiBYJkU2vgqt2THxgrNHKTyp9N3ulscCcli77ih4bywLDQ=="

        /** `openssl x509 -pubkey | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64` */
        const val SELF_SIGNED_PIN = "yKw58qbdY/v42DSsCUtpJ/rTlZ3L884KvSzFOsfy1/w="
        const val LOCAL_CA_PIN = "7NpkwaN8q6F2ruJPmJ+F3kjPrdzdbuvzE5+3DZ+f0sY="
        const val LEAF_PIN = "RCTZc7b7AIr5kqrLeY/3+kiyCNBuysHckOxb5W/j82k="

        /** The same three digests as `xxd -p`, for the display test. */
        const val SELF_SIGNED_HEX =
            "C8:AC:39:F2:A6:DD:63:FB:F8:D8:34:AC:09:4B:69:27:FA:D3:95:9D:CB:F3:CE:0A:BD:2C:C5:3A:C7:F2:D7:FC"
        const val LEAF_HEX =
            "44:24:D9:73:B6:FB:00:8A:F9:92:AA:CB:79:8F:F7:FA:48:B2:08:D0:6E:CA:C1:DC:90:EC:5B:E5:6F:E3:F3:69"
    }
}
