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
 * The captured reply has two parts, not one: that `bytes-0` payload and a `metadata`
 * part carrying the RPC envelope. Both readings live here — the first part is what the
 * image preview needs, and the named payload parts are what a download needs — and
 * `net/MultipartTest.kt` asserts the captured body's shape from its own bytes.
 *
 * Hand-written rather than with a multipart library because the app needs exactly one
 * thing from it: the bytes of the parts that carry them. Top level, and not a method on
 * the client, so it can be tested on its own — it is pure and nothing about it needs a
 * socket.
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

/** One part of a multipart body: its declared name, and its payload. */
data class MultipartPart(val name: String?, val bytes: ByteArray) {

    override fun equals(other: Any?): Boolean =
        other is MultipartPart && other.name == name && other.bytes.contentEquals(bytes)

    override fun hashCode(): Int = 31 * (name?.hashCode() ?: 0) + bytes.contentHashCode()
}

/**
 * Every part of a multipart body, in the order it holds them.
 *
 * [multipartBytes] answers "the bytes in the first part", which is all a single-part
 * reply needs and *not* all a download can trust: the part the host names is `bytes-0`,
 * and that numbering is a promise that a larger answer may arrive split across
 * `bytes-0`, `bytes-1` and so on. Taking the first of those would hand the reader a
 * truncated file that looks whole, which is the one failure a download must not have —
 * so a window is reassembled from every `bytes-` part.
 *
 * The live reply is not single-part *either*, which is why this exists rather than
 * [multipartBytes] being widened: the capture in `net/MultipartTest.kt` is `bytes-0`
 * with the payload followed by a `metadata` part holding the RPC envelope. Reading
 * "the first part" happens to be right there and is right for the wrong reason; a
 * reassembly that took every part would append the envelope's JSON to the file.
 *
 * A body whose parts are unnamed, or named for something else, comes back whole rather
 * than as an empty list: those parts are still the answer, and dropping them would turn
 * an unfamiliar shape into a silent zero-byte download.
 */
fun multipartParts(body: ByteArray, boundary: String): List<MultipartPart> {
    val delimiter = "--$boundary".toByteArray(Charsets.ISO_8859_1)
    val headerEnd = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
    val parts = mutableListOf<MultipartPart>()
    var search = 0
    while (true) {
        val start = indexOf(body, delimiter, search) ?: break
        var cursor = start + delimiter.size
        // `--` immediately after a delimiter is the closing marker, not a part.
        if (cursor + 1 < body.size &&
            body[cursor] == '-'.code.toByte() &&
            body[cursor + 1] == '-'.code.toByte()
        ) {
            break
        }
        if (cursor + 1 < body.size &&
            body[cursor] == '\r'.code.toByte() &&
            body[cursor + 1] == '\n'.code.toByte()
        ) {
            cursor += 2
        }
        val headersEnd = indexOf(body, headerEnd, cursor) ?: break
        val headers = body.copyOfRange(cursor, headersEnd).toString(Charsets.ISO_8859_1)
        val dataStart = headersEnd + 4
        val next = indexOf(body, delimiter, dataStart) ?: break
        var end = next
        if (end >= 2 && body[end - 2] == '\r'.code.toByte() && body[end - 1] == '\n'.code.toByte()) end -= 2
        if (end < dataStart) break
        parts += MultipartPart(PART_NAME.find(headers)?.groupValues?.get(1), body.copyOfRange(dataStart, end))
        search = next
    }
    return parts
}

/**
 * The bytes of one window, from every part that claims to hold them.
 *
 * The filter is by part name because that is the only thing distinguishing a payload
 * from whatever else the host may put in the envelope; when no part carries a `bytes-`
 * name there is nothing to filter on, so every part is taken in order, as
 * [multipartParts] documents.
 */
fun multipartByteParts(body: ByteArray, boundary: String): ByteArray {
    val parts = multipartParts(body, boundary)
    val named = parts.filter { it.name?.startsWith("bytes-") == true }
    val chosen = named.ifEmpty { parts }
    if (chosen.size == 1) return chosen[0].bytes
    val out = ByteArray(chosen.sumOf { it.bytes.size })
    var at = 0
    for (part in chosen) {
        part.bytes.copyInto(out, at)
        at += part.bytes.size
    }
    return out
}

/** `name="bytes-0"` in a part's `Content-Disposition`, whatever else the header holds. */
private val PART_NAME = Regex("name=\"([^\"]*)\"")
