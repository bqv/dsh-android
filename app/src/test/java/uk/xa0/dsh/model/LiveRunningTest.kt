package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Test
import uk.xa0.dsh.SessionItem

/**
 * Which live-running claims the roster is allowed to disprove.
 *
 * Measured on the phone: a session with three running subagents reported `running=3` and
 * then `running=0` within 105ms, with `descendants=3` throughout. The tree was fine; the
 * live claims were being deleted for arriving before the rows they describe.
 */
class LiveRunningTest {

    private fun session(id: String, running: Boolean = false, parent: String? = null) = SessionItem(
        id = id,
        title = id,
        cwd = "/tmp",
        updatedAt = 0L,
        running = running,
        isSubagent = parent != null,
        parentSessionId = parent,
    )

    @Test
    fun `an id the list does not mention is unknown, not stale`() {
        // The bug: a child's status frame landing before its roster row was discarded.
        assertEquals(emptySet<String>(), staleLiveRunning(setOf("early"), emptyList(), null, quiet = true))
    }

    @Test
    fun `a subagent is never stale, whatever the list says`() {
        val list = listOf(session("child", running = false, parent = "parent"))
        assertEquals(emptySet<String>(), staleLiveRunning(setOf("child"), list, null, quiet = true))
    }

    @Test
    fun `an ordinary session the list says is not running is stale`() {
        val list = listOf(session("done", running = false))
        assertEquals(setOf("done"), staleLiveRunning(setOf("done"), list, current = null, quiet = true))
    }

    @Test
    fun `an ordinary session the list says is running is kept`() {
        val list = listOf(session("busy", running = true))
        assertEquals(emptySet<String>(), staleLiveRunning(setOf("busy"), list, current = null, quiet = true))
    }

    @Test
    fun `the open session is kept while the stream is not quiet`() {
        val list = listOf(session("open", running = false))
        assertEquals(emptySet<String>(), staleLiveRunning(setOf("open"), list, current = "open", quiet = false))
        assertEquals(setOf("open"), staleLiveRunning(setOf("open"), list, current = "open", quiet = true))
    }
}
