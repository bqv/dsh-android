package uk.xa0.dsh.model

import java.io.ByteArrayOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Taking a file off the host and onto the phone.
 *
 * The app is a remote client, so "download" is not a copy from a local disk: it is
 * reading the file's bytes over the connection every other call rides, and handing them
 * to the platform's own save dialog. What this file holds is the two decisions that can
 * be made and tested without a socket — how a path becomes a name and a type the dialog
 * understands, and how a whole file is paged out of a host that will only ever answer
 * one bounded window per call.
 */

/**
 * What the phone's save dialog is offered for one path.
 *
 * [fileName] is the file's own last segment, unchanged: a name the host holds is the
 * name the reader expects to write, and sanitising it here would quietly rename a file
 * whose author chose the characters. A path with no segment at all falls back to
 * `download`, because the dialog refuses to open without one.
 *
 * [mimeType] is [previewFormatOf]'s answer carried through: a raster's own image type,
 * `image/svg+xml` for SVG, and for text one of the few types Android actually
 * registers, with `text/plain` for anything whose extension this app can name as a
 * language and `application/octet-stream` — the honest answer for bytes of unknown
 * kind — for everything else.
 */
data class DownloadSave(val fileName: String, val mimeType: String)

/** The dialog's name and type for one workspace path. */
fun downloadSaveOf(path: String): DownloadSave {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    return DownloadSave(
        fileName = name.ifBlank { "download" },
        mimeType = downloadMimeTypeOf(path),
    )
}

/** One bounded window of a file, the only shape the host will send. */
fun interface FileWindowReader {
    /** [offset] bytes into the file, at most [length] of them; short means end of file. */
    suspend fun read(path: String, offset: Long, length: Int): ByteArray
}

/** The whole of a file, or a refusal the reader can act on. */
sealed interface DownloadResult {

    /** The file's exact bytes, in order. */
    class Ready(val bytes: ByteArray) : DownloadResult {

        override fun equals(other: Any?): Boolean =
            other is Ready && other.bytes.contentEquals(bytes)

        override fun hashCode(): Int = bytes.contentHashCode()
    }

    /**
     * Nothing was fetched, and [message] says why in the host's own words.
     *
     * A refusal rather than a short [Ready]: a file that arrives half-written and is
     * saved as if complete is worse than one that says it could not be taken, and this
     * is the one outcome a paging loop could get wrong silently.
     */
    data class Refused(val message: String) : DownloadResult
}

/**
 * Fetches one file whole, reporting progress.
 *
 * [onProgress] carries bytes held so far and the file's total when the host reported
 * one, and is called after each window rather than per byte. It is `suspend` on
 * purpose: an implementation may be reading on a background dispatcher, and the only
 * place the progress is drawn is Compose state, so the hop back to the main thread
 * belongs to the implementation and not to a comment.
 */
fun interface FileDownloader {
    suspend fun download(path: String, onProgress: suspend (Long, Long?) -> Unit): DownloadResult
}

/**
 * How much of a file one `workspaceFiles/readBytes` call asks for.
 *
 * Two MiB is the host's own ceiling on a requested window — it refuses a larger request
 * outright rather than shortening it (`workspace-file/too-large`) — and it is the same
 * window the image preview already asks for, so the multipart shape this client parses
 * is the one already verified against the running host. Paging in windows is what makes
 * a download possible at all: there is no single call in this app's repertoire that
 * returns a file of any size.
 */
const val DOWNLOAD_WINDOW_BYTES = 2 * 1024 * 1024

/**
 * The largest file the app will take in one download.
 *
 * This is *the app's* ceiling, not the host's: paging could go on for a gigabyte, and
 * the bytes are held in memory until the save dialog has somewhere to put them. 32 MiB
 * is the host's own `readAll` limit, which makes it the largest file this host was
 * designed to hand over in one piece; anything above it is refused with its size rather
 * than silently cut short.
 */
const val MAX_DOWNLOAD_BYTES = 32 * 1024 * 1024

/**
 * Every byte of one file, window by window.
 *
 * The contract with [reader] is the host's, and it is the whole of the paging rule: a
 * window returns *at most* the length asked for, and a short window — including an
 * empty one — means the end of the file.
 *
 * [size] is the host's reported length when something asked it (`workspaceFiles/stat`).
 * When it is known the loop reads exactly that many bytes and stops, so the common case
 * never has to ask for a window past the end to discover it is finished; a file of
 * exactly the ceiling is therefore ordinary rather than special. When it is *not* known
 * the loop pages until a short window, and a file that fills the ceiling exactly costs
 * one single-byte read to tell "that is all of it" from "there is more" — because
 * mistaking those two is how a download arrives silently half-written.
 *
 * [size] is never trusted as the *answer*: a short window ends the read whatever the
 * reported length said.
 */
suspend fun readWholeFile(
    path: String,
    size: Int?,
    reader: FileWindowReader,
    window: Int = DOWNLOAD_WINDOW_BYTES,
    ceiling: Int = MAX_DOWNLOAD_BYTES,
    onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
): DownloadResult {
    val total = size?.takeIf { it >= 0 }?.toLong()
    if (total != null && total > ceiling) return DownloadResult.Refused(tooLarge(path, total, ceiling))

    val out = ByteArrayOutputStream(if (total != null) total.toInt() else 64 * 1024)
    var offset = 0L
    while (true) {
        // Cancellation lands here, between windows: the call in flight is a blocking
        // socket read the coroutine machinery cannot interrupt, so the worst case is
        // one window of latency before a Cancel takes effect.
        currentCoroutineContext().ensureActive()
        if (total != null && offset >= total) return DownloadResult.Ready(out.toByteArray())
        val remaining = ceiling - offset
        if (remaining <= 0L) {
            // The ceiling, and the length is unknown. One byte more would be over it,
            // so ask for one and let the answer decide: empty means the file is exactly
            // the ceiling and is whole; anything else means there is more, which is a
            // refusal and not a truncation.
            val probe = reader.read(path, offset, 1)
            return if (probe.isEmpty()) {
                DownloadResult.Ready(out.toByteArray())
            } else {
                DownloadResult.Refused(tooLarge(path, offset + probe.size, ceiling))
            }
        }
        val want = minOf(window.toLong(), remaining, total?.minus(offset) ?: Long.MAX_VALUE).toInt()
        val chunk = reader.read(path, offset, want)
        if (chunk.isEmpty()) return DownloadResult.Ready(out.toByteArray())
        out.write(chunk)
        offset += chunk.size
        onProgress(offset, total)
        if (chunk.size < want) return DownloadResult.Ready(out.toByteArray())
    }
}

/** The refusal a reader can act on: which file, how big, and against what limit. */
private fun tooLarge(path: String, size: Long, ceiling: Int): String =
    "${fileNameOf(path)} is ${formatSize(size)}, above the ${formatSize(ceiling.toLong())} download limit."

/** The one place a size is written for a reader, in English (GB). */
fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024 * 1024 -> "%.1f GB".format(bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024L * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024))
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    else -> "$bytes B"
}

/** The image types, from the one format table, so a rename cannot split the two. */
private fun downloadMimeTypeOf(path: String): String = when (previewFormatOf(path)) {
    PreviewFormat.PNG -> "image/png"
    PreviewFormat.JPEG -> "image/jpeg"
    PreviewFormat.WEBP -> "image/webp"
    PreviewFormat.GIF -> "image/gif"
    PreviewFormat.BMP -> "image/bmp"
    PreviewFormat.ICO -> "image/vnd.microsoft.icon"
    PreviewFormat.HEIC -> "image/heic"
    PreviewFormat.AVIF -> "image/avif"
    PreviewFormat.SVG -> "image/svg+xml"
    PreviewFormat.TEXT -> textMimeTypeOf(path)
}

/**
 * A type for a document, from its extension.
 *
 * `application/octet-stream` is the fallback and is not a failure: it is the correct
 * type for bytes whose kind this app cannot name, which is exactly the extensionless
 * file and the unknown suffix. Claiming `text/plain` for those would make the dialog
 * offer an extension the content may not want.
 */
private fun textMimeTypeOf(path: String): String {
    val name = path.substringAfterLast('/').substringAfterLast('\\')
    val dot = name.lastIndexOf('.')
    val extension = if (dot <= 0) "" else name.substring(dot + 1).lowercase()
    return when (extension) {
        "json", "jsonc" -> "application/json"
        "md", "markdown", "mdown" -> "text/markdown"
        "xml" -> "application/xml"
        "html", "htm" -> "text/html"
        "csv" -> "text/csv"
        "txt", "text", "log" -> "text/plain"
        else -> if (previewLanguageOf(path) != null) "text/plain" else "application/octet-stream"
    }
}
