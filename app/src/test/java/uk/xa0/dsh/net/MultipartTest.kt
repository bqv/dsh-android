package uk.xa0.dsh.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reading the bytes out of the multipart body 0.2.0 sends.
 *
 * `workspaceFiles/readBytes` answers `multipart/form-data` now, and every other part of
 * the client expects JSON: read as text it became an HTTP body shown to the reader
 * (`Content-Disposition: form-data; name="bytes-0"` over PNG chunks) where a picture
 * belonged. This is the one piece of multipart the app needs.
 */
class MultipartTest {

    private val boundary = "----formdata-undici-082210784995"

    private fun body(vararg parts: Pair<String, ByteArray>): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        parts.forEach { (headers, data) ->
            out.write("--$boundary\r\n$headers\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            out.write(data)
            out.write("\r\n".toByteArray(Charsets.ISO_8859_1))
        }
        out.write("--$boundary--\r\n".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private val png = byteArrayOf(
        0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(),
        0x0D, 0x0A, 0x1A, 0x0A,
    )

    @Test
    fun `the boundary is read from the bodys own first line`() {
        assertEquals(boundary, boundaryOf(body("Content-Type: application/octet-stream" to png)))
    }

    @Test
    fun `the first parts bytes come back exactly`() {
        val framed = body(
            "Content-Disposition: form-data; name=\"bytes-0\"; filename=\"blob\"\r\n" +
                "Content-Type: application/octet-stream" to png,
        )
        assertArrayEquals(png, multipartBytes(framed, boundary))
    }

    @Test
    fun `bytes that look like framing survive`() {
        // A payload containing its own CRLF is the case a naive split gets wrong.
        val awkward = "line\r\n--not-the-boundary\r\nline".toByteArray()
        assertArrayEquals(awkward, multipartBytes(body("Content-Type: text/plain" to awkward), boundary))
    }

    @Test
    fun `the response the host actually sends is parsed`() {
        // Captured from the live host: `workspaceFiles/readBytes` for a real PNG, byte
        // for byte. A hand-built body proves the arithmetic; this proves the shape, and
        // the two disagreed once already.
        val real = javaClass.getResourceAsStream("/multipart-real.bin")?.readBytes()
            ?: error("fixture missing")
        val boundary = boundaryOf(real) ?: error("no boundary in ${real.size} bytes")
        val data = multipartBytes(real, boundary) ?: error("no part in ${real.size} bytes")
        assertEquals(885, data.size)
        assertArrayEquals(
            byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()),
            data.copyOfRange(0, 4),
        )
    }

    @Test
    fun `something that is not multipart is refused rather than guessed at`() {
        assertNull(boundaryOf("{\"type\":\"server-response\"}".toByteArray()))
        assertNull(multipartBytes("not multipart at all".toByteArray(), boundary))
    }

    /**
     * A window the host split across numbered parts is the whole window.
     *
     * The part the live host names is `bytes-0`, and the numbering is the only evidence
     * there can be more than one. Taking just the first would hand a reader a truncated
     * file that looks complete — the one outcome a download must not have — so every
     * `bytes-` part is taken, in the order the body holds them.
     */
    @Test
    fun `numbered byte parts are reassembled in order`() {
        val first = "the first half ".toByteArray()
        val second = "and the second".toByteArray()
        val framed = body(
            "Content-Disposition: form-data; name=\"bytes-0\"; filename=\"blob\"\r\n" +
                "Content-Type: application/octet-stream" to first,
            "Content-Disposition: form-data; name=\"bytes-1\"; filename=\"blob\"\r\n" +
                "Content-Type: application/octet-stream" to second,
        )
        assertArrayEquals(first + second, multipartByteParts(framed, boundary))
    }

    /** A single part is that part, which is the shape the image preview already parses. */
    @Test
    fun `a single byte part is returned as it stands`() {
        val framed = body("Content-Disposition: form-data; name=\"bytes-0\"" to png)
        assertArrayEquals(png, multipartByteParts(framed, boundary))
    }

    /**
     * A part that is not a payload is never mixed into one.
     *
     * When any part names itself `bytes-`, that naming is the filter — an envelope that
     * also carries metadata must not have the metadata appended to the file.
     */
    @Test
    fun `a part named for something else is left out when bytes parts exist`() {
        val framed = body(
            "Content-Disposition: form-data; name=\"meta\"\r\n" +
                "Content-Type: application/json" to "{\"bytes\":4}".toByteArray(),
            "Content-Disposition: form-data; name=\"bytes-0\"" to png,
        )
        assertArrayEquals(png, multipartByteParts(framed, boundary))
    }

    /**
     * And when nothing is named `bytes-0`, the parts are still the answer: dropping
     * them would turn an unfamiliar envelope into a silent zero-byte download.
     */
    @Test
    fun `unnamed parts are taken whole rather than dropped`() {
        val framed = body("Content-Type: application/octet-stream" to png)
        assertArrayEquals(png, multipartByteParts(framed, boundary))
    }

    @Test
    fun `the real captured body parses the same either way`() {
        val real = javaClass.getResourceAsStream("/multipart-real.bin")?.readBytes()
            ?: error("fixture missing")
        val boundary = boundaryOf(real) ?: error("no boundary in ${real.size} bytes")
        val parts = multipartParts(real, boundary)
        assertEquals(1, parts.size)
        assertEquals("bytes-0", parts.single().name)
        assertArrayEquals(multipartBytes(real, boundary)!!, multipartByteParts(real, boundary))
    }

    @Test
    fun `a body that is not multipart comes back empty rather than invented`() {
        assertEquals(emptyList<MultipartPart>(), multipartParts("not multipart at all".toByteArray(), boundary))
        assertEquals(0, multipartByteParts("not multipart at all".toByteArray(), boundary).size)
    }
}
