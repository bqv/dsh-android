package uk.xa0.dsh.model

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Paging a whole file out of a host that answers one window at a time.
 *
 * The property that matters most here is not that the happy path reassembles bytes —
 * it is that no path through this loop can return a *short* [DownloadResult.Ready]. A
 * download that arrives half-written and is saved under the reader's own file name is
 * the failure this feature exists to avoid, and every one of these tests is about
 * telling "that is the end of the file" apart from "there is more of it".
 *
 * [FileWindowReader] is the seam that makes that testable on the JVM: the real one is
 * `workspaceFiles/readBytes` over a socket, and the rule it implements — at most the
 * requested length, short means end — is the host's, not this client's.
 */
class FileDownloadTest {

    /** A reader over a fixed byte array, honouring "short read means end of file". */
    private class FakeReader(private val data: ByteArray) : FileWindowReader {
        val windows = mutableListOf<Pair<Long, Int>>()

        override suspend fun read(path: String, offset: Long, length: Int): ByteArray {
            windows += offset to length
            if (offset >= data.size) return ByteArray(0)
            val end = minOf(offset + length, data.size.toLong()).toInt()
            return data.copyOfRange(offset.toInt(), end)
        }
    }

    private fun bytes(size: Int) = ByteArray(size) { (it % 251).toByte() }

    private fun read(
        data: ByteArray,
        size: Int?,
        window: Int,
        ceiling: Int = MAX_DOWNLOAD_BYTES,
        onProgress: suspend (Long, Long?) -> Unit = { _, _ -> },
    ): Pair<DownloadResult, FakeReader> {
        val reader = FakeReader(data)
        val result = runBlocking { readWholeFile("/w/notes.txt", size, reader, window, ceiling, onProgress) }
        return result to reader
    }

    private fun ready(result: DownloadResult): ByteArray {
        assertTrue("expected Ready, got $result", result is DownloadResult.Ready)
        return (result as DownloadResult.Ready).bytes
    }

    @Test
    fun `a file whose size is known is read window by window, exactly`() {
        val data = bytes(10)
        val (result, reader) = read(data, size = 10, window = 4)

        assertArrayEquals(data, ready(result))
        assertEquals(listOf(0L to 4, 4L to 4, 8L to 2), reader.windows)
    }

    /**
     * With no reported size the loop has only the host's short window to go on — which
     * is exactly the case `workspaceFiles/stat` failing produces, and it must not
     * change the answer.
     */
    @Test
    fun `a file with no reported size pages until a short window`() {
        val data = bytes(10)
        val (result, reader) = read(data, size = null, window = 4)

        assertArrayEquals(data, ready(result))
        assertEquals(listOf(0L to 4, 4L to 4, 8L to 2), reader.windows)
    }

    @Test
    fun `an empty file is ready with no bytes and no request`() {
        val (result, reader) = read(ByteArray(0), size = 0, window = 4)

        assertEquals(0, ready(result).size)
        assertEquals(emptyList<Pair<Long, Int>>(), reader.windows)
    }

    /** A size exactly at the ceiling is ordinary when the host stated it: no probe. */
    @Test
    fun `a file exactly at the ceiling is whole, and is not probed`() {
        val data = bytes(8)
        val (result, reader) = read(data, size = 8, window = 4, ceiling = 8)

        assertArrayEquals(data, ready(result))
        assertEquals(listOf(0L to 4, 4L to 4), reader.windows)
    }

    /**
     * The same file with no stated size costs one single-byte read to prove there is
     * nothing after it — the price of never guessing, and paid only at the boundary.
     */
    @Test
    fun `a file exactly at the ceiling with no size is probed once and is whole`() {
        val data = bytes(8)
        val (result, reader) = read(data, size = null, window = 4, ceiling = 8)

        assertArrayEquals(data, ready(result))
        assertEquals(listOf(0L to 4, 4L to 4, 8L to 1), reader.windows)
    }

    @Test
    fun `a file above the ceiling is refused before a byte moves`() {
        val (result, reader) = read(bytes(100), size = 100, window = 4, ceiling = 8)

        assertTrue(result is DownloadResult.Refused)
        val message = (result as DownloadResult.Refused).message
        assertTrue(message, message.contains("100 B"))
        assertTrue(message, message.contains("download limit"))
        assertEquals(emptyList<Pair<Long, Int>>(), reader.windows)
    }

    /** The unknown-size overflow: refused, with nothing handed back to be saved. */
    @Test
    fun `a file above the ceiling with no size is refused, not truncated`() {
        val (result, reader) = read(bytes(12), size = null, window = 4, ceiling = 8)

        assertTrue(result is DownloadResult.Refused)
        assertEquals(listOf(0L to 4, 4L to 4, 8L to 1), reader.windows)
    }

    /**
     * A shorter window than asked for — including an empty one — ends the read whatever
     * the reported length said. A file that shrank mid-download is a shorter download,
     * not a failure, and never a longer one.
     */
    @Test
    fun `a short window ends the read even when the size promised more`() {
        val (result, reader) = read(bytes(4), size = 10, window = 4)

        assertEquals(4, ready(result).size)
        assertEquals(listOf(0L to 4, 4L to 4), reader.windows)
    }

    @Test
    fun `progress carries the running count and the total`() {
        val total = mutableListOf<Pair<Long, Long?>>()
        val data = bytes(10)
        val (result, _) = read(data, size = 10, window = 4) { done, size -> total += done to size }

        assertArrayEquals(data, ready(result))
        assertEquals(listOf(4L to 10L, 8L to 10L, 10L to 10L), total)
    }

    @Test
    fun `progress has no total when the host did not state one`() {
        val total = mutableListOf<Pair<Long, Long?>>()
        val (result, _) = read(bytes(3), size = null, window = 4) { done, size -> total += done to size }

        assertEquals(3, ready(result).size)
        assertEquals(listOf(3L to null), total)
    }

    @Test
    fun `the save dialog is offered the file's own name and a type from its format`() {
        assertEquals(DownloadSave("shot.png", "image/png"), downloadSaveOf("/w/out/shot.png"))
        assertEquals(DownloadSave("shot.JPG", "image/jpeg"), downloadSaveOf("shot.JPG"))
        assertEquals(DownloadSave("icon.svg", "image/svg+xml"), downloadSaveOf("/w/icon.svg"))
        assertEquals(DownloadSave("REPORT.md", "text/markdown"), downloadSaveOf("/w/out/REPORT.md"))
        assertEquals(DownloadSave("data.json", "application/json"), downloadSaveOf("/w/data.json"))
        assertEquals(DownloadSave("Page.html", "text/html"), downloadSaveOf("/w/Page.html"))
        assertEquals(DownloadSave("run.log", "text/plain"), downloadSaveOf("/w/run.log"))
        // A language this app can name is a text file, whatever else it may be.
        assertEquals(DownloadSave("App.kt", "text/plain"), downloadSaveOf("/w/src/App.kt"))
    }

    /**
     * A file of unknown kind is `application/octet-stream` — the correct answer, not a
     * failure — and an extensionless name keeps the name it has.
     */
    @Test
    fun `an unknown kind and an extensionless name are still offered honestly`() {
        assertEquals(DownloadSave("Makefile", "application/octet-stream"), downloadSaveOf("/w/Makefile"))
        assertEquals(DownloadSave("blob.bin", "application/octet-stream"), downloadSaveOf("/w/blob.bin"))
        assertEquals(DownloadSave("archive.tar.gz", "application/octet-stream"), downloadSaveOf("/w/archive.tar.gz"))
    }

    /** Windows-style paths reach the panel too, and a path with no segment still opens a dialog. */
    @Test
    fun `the last segment is the name on either separator`() {
        assertEquals("notes.md", downloadSaveOf("C:\\work\\notes.md").fileName)
        assertEquals("download", downloadSaveOf("/").fileName)
        assertEquals("download", downloadSaveOf("").fileName)
    }
}
