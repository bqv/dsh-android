package uk.xa0.dsh.net

/**
 * The payload of the first part of a `multipart/form-data` body.
 *
 * Hand-written because the app needs exactly one thing from multipart: the bytes between
 * the first part's header block and the next boundary. 0.2.0 answers
 * `workspaceFiles/readBytes` this way — holding the file's bytes in a part named
 * `bytes-0` — where the app still expected JSON with base64, which is why the Files
 * panel displayed an HTTP response body over PNG chunks instead of a picture.
 *
 * Top level rather than a client method so it can be tested on its own: it is pure, and
 * nothing about it needs a socket.
 */
/**
     * The payload of the first part of a `multipart/form-data` body.
     *
     * Written by hand rather than with a multipart library because the app needs
     * exactly one thing from it: the bytes between the first part's header block and
     * the next boundary. The host sends one part, named `bytes-0`.
     */
fun multipartBytes(body: ByteArray, boundary: String): ByteArray? {
        val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
        val first = indexOf(body, delimiter, 0) ?: return null
        // The header block ends at the blank line after the part's headers.
        val headersStart = first + delimiter.size
        val headersEnd = indexOf(body, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1), headersStart) ?: return null
        val dataStart = headersEnd + 4
        val next = indexOf(body, delimiter, dataStart) ?: return null
        // The delimiter is preceded by CRLF, which belongs to the framing.
        var end = next
        if (end >= 2 && body[end - 2] == '\r'.code.toByte() && body[end - 1] == '\n'.code.toByte()) end -= 2
        if (end < dataStart) return null
        return body.copyOfRange(dataStart, end)
    }

    /** The boundary of a `multipart/form-data` body, read from its own first line. */
    fun boundaryOf(body: ByteArray): String? {
        val head = body.toString(Charsets.ISO_8859_1)
        if (!head.startsWith("--")) return null
        val end = head.indexOf("\r\n")
        if (end <= 2) return null
        return head.substring(2, end).trim().takeIf { it.isNotEmpty() }
    }

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
        if (needle.isEmpty()) return null
        var i = from
        outer@ while (i <= haystack.size - needle.size) {
            var j = 0
            while (j < needle.size) {
                if (haystack[i + j] != needle[j]) { i++; continue@outer }
                j++
            }
            return i
        }
        return null
    }
