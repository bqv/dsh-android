package uk.xa0.dsh.net

/**
 * Reading the bytes out of a `multipart/form-data` body.
 *
 * 0.2.0 answers `workspaceFiles/readBytes` this way — the file in a part named `bytes-0`
 * — where the app still expected JSON with base64. Read as text it became an HTTP body
 * shown to the reader, `Content-Disposition: form-data; name="bytes-0"` over PNG chunks,
 * because the JSON parse failed and the failure message *is* the body's first 400
 * characters.
 *
 * Hand-written rather than with a multipart library because the app needs exactly one
 * thing from it: the bytes between the first part's header block and the next boundary.
 * Top level, and not a method on the client, so it can be tested on its own — it is pure
 * and nothing about it needs a socket.
 */

/** The boundary of a body, read from its own first line: `--<boundary>` then CRLF. */
fun boundaryOf(body: ByteArray): String? {
    val head = body.toString(Charsets.ISO_8859_1)
    if (!head.startsWith("--")) return null
    val end = head.indexOf("\r\n")
    if (end <= 2) return null
    return head.substring(2, end).trim().takeIf { it.isNotEmpty() }
}

/** The payload of the first part, or null when [body] is not multipart. */
fun multipartBytes(body: ByteArray, boundary: String): ByteArray? {
    val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    val first = indexOf(body, delimiter, 0) ?: return null
    // The part's headers end at the blank line before its payload.
    val headersStart = first + delimiter.size
    val headersEnd = indexOf(body, "\r\n\r\n".toByteArray(Charsets.ISO_8859_1), headersStart) ?: return null
    val dataStart = headersEnd + 4
    val next = indexOf(body, delimiter, dataStart) ?: return null
    // The next delimiter is preceded by CRLF, which belongs to the framing and not to
    // the payload — the case a naive split gets wrong.
    var end = next
    if (end >= 2 && body[end - 2] == '\r'.code.toByte() && body[end - 1] == '\n'.code.toByte()) end -= 2
    if (end < dataStart) return null
    return body.copyOfRange(dataStart, end)
}

private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int? {
    if (needle.isEmpty()) return null
    var i = from
    outer@ while (i <= haystack.size - needle.size) {
        var j = 0
        while (j < needle.size) {
            if (haystack[i + j] != needle[j]) {
                i++
                continue@outer
            }
            j++
        }
        return i
    }
    return null
}
