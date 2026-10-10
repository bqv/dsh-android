package uk.xa0.dsh.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The file itself: one document, replaced whole, and never trusted.
 *
 * No Android here on purpose. The store takes a [File], not a `Context`, so the part
 * of this feature that survives a process death can be tested on the JVM with a real
 * filesystem rather than a shadow of one.
 */
class WidgetSnapshotStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val at = 1_700_000_000_000L

    private fun store(): WidgetSnapshotStore =
        WidgetSnapshotStore(File(temp.root, "widget/snapshot.json"))

    private fun file(): File = File(temp.root, "widget/snapshot.json")

    private val snapshot = WidgetSnapshot(
        needsYou = WidgetReading(known = true, value = 2, asOf = at),
        working = WidgetReading(known = true, value = 3, asOf = at),
        subagents = WidgetReading(known = true, value = 1, asOf = at),
        kind = "APPROVAL",
        sessionId = "session-1",
    )

    @Test
    fun `the parent directory is made rather than assumed`() {
        assertFalse(file().parentFile.exists())
        store().write(snapshot)
        assertTrue(file().isFile)
    }

    @Test
    fun `what was written is what is read`() {
        store().write(snapshot)
        assertEquals(snapshot, store().read())
    }

    @Test
    fun `a file that is not there is no reading, not an error`() {
        assertNull(store().read())
    }

    @Test
    fun `a corrupt document reads as nothing known`() {
        file().parentFile.mkdirs()
        file().writeText("{ this is not a snapshot")
        assertNull(store().read())
        file().writeText("")
        assertNull(store().read())
    }

    @Test
    fun `the replacement leaves no scratch file behind`() {
        store().write(snapshot)
        store().write(snapshot.copy(sessionId = "session-2"))
        assertEquals("session-2", store().read()?.sessionId)
        // The temporary is the mechanism, not the state: anything left beside the real
        // file is litter, and a reader that picked it up would be reading a half-write.
        assertEquals(
            listOf("snapshot.json"),
            file().parentFile.listFiles()?.map { it.name }?.sorted(),
        )
    }
}
