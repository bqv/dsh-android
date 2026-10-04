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
    fun `something that is not multipart is refused rather than guessed at`() {
        assertNull(boundaryOf("{\"type\":\"server-response\"}".toByteArray()))
        assertNull(multipartBytes("not multipart at all".toByteArray(), boundary))
    }
}
