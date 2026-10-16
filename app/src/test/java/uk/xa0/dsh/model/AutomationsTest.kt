package uk.xa0.dsh.model

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host's scheduled-task catalog, and the two things this client reads off it.
 *
 * The plugin change this file guards against is not a rename: the old rule recognised a
 * run by an id prefix (`dsh-automation-session-…`) that no longer exists, and the
 * replacement signal is a *field* — each task in `schedule/catalog` names the session it
 * delivers into. These pin that the mark comes from the field and not from the id, that
 * an ended task stops marking, and that a malformed row is skipped rather than repaired.
 */
class AutomationsTest {

    /** One catalog row, in the host's own shape (the fields this client reads plus noise). */
    private fun row(id: String, sessionId: String, status: String) = """
        {"id":"$id","kind":"daily","title":"t","prompt":"p","time":"09:00:00.000",
         "timeZone":"Europe/Helsinki","scheduledAt":"2026-10-17T06:00:00.000Z",
         "sessionId":"$sessionId","status":"$status"}
    """.trimIndent()

    private fun catalog(vararg rows: String) = JSONArray("[${rows.joinToString(",")}]")

    @Test
    fun `a plain session id is the signal, not any prefix`() {
        val tasks = automationTasksFrom(
            catalog(row("schedule-1", "session-523588f5", "active")),
        )
        assertEquals(1, tasks.size)
        assertEquals("session-523588f5", tasks[0].sessionId)
        assertTrue(tasks[0].active)
    }

    @Test
    fun `the retired dsh-automation prefix is just an ordinary session id`() {
        // The id shape no longer means anything: what marks the session is the task's
        // `sessionId` field. A task bound to a legacy-shaped id still counts, and a
        // session with no task does not become special by looking like one.
        val tasks = automationTasksFrom(
            catalog(row("schedule-1", "dsh-automation-session-7eaa1963", "active")),
        )
        assertEquals("dsh-automation-session-7eaa1963", tasks[0].sessionId)
        assertEquals(mapOf("dsh-automation-session-7eaa1963" to 1), activeTaskCounts(tasks))
    }

    @Test
    fun `active only, and both statuses are read`() {
        val tasks = automationTasksFrom(
            catalog(
                row("schedule-1", "session-a", "active"),
                row("schedule-2", "session-a", "inactive"),
                row("schedule-3", "session-b", "active"),
            ),
        )
        assertEquals(3, tasks.size)
        // An ended one-shot task must stop marking its session, which is what keeps the
        // single reminder a reader set once from leaving a permanent clock on the row.
        assertEquals(mapOf("session-a" to 1, "session-b" to 1), activeTaskCounts(tasks))
    }

    @Test
    fun `several tasks in one session are counted, not collapsed`() {
        val tasks = automationTasksFrom(
            catalog(
                row("schedule-1", "session-a", "active"),
                row("schedule-2", "session-a", "active"),
            ),
        )
        assertEquals(mapOf("session-a" to 2), activeTaskCounts(tasks))
    }

    @Test
    fun `a row with no task id or no session is skipped, not repaired`() {
        val tasks = automationTasksFrom(
            catalog(
                row("", "session-a", "active"),
                row("schedule-2", "", "active"),
                row("schedule-3", "session-b", "active"),
            ),
        )
        assertEquals(1, tasks.size)
        assertEquals("session-b", tasks[0].sessionId)
    }

    @Test
    fun `no catalog at all marks nothing`() {
        assertEquals(emptyList<AutomationTask>(), automationTasksFrom(null))
        assertEquals(emptyMap<String, Int>(), activeTaskCounts(emptyList()))
    }

    @Test
    fun `the mark is singular for one task and plural otherwise`() {
        assertEquals("", scheduledTaskMarkLabel(0))
        assertEquals("1 scheduled task", scheduledTaskMarkLabel(1))
        assertEquals("2 scheduled tasks", scheduledTaskMarkLabel(2))
    }
}
