package uk.xa0.dsh.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.ui.indexSubagentRollups

/**
 * Where a child's running state is read off the wire, and what one roster row is
 * allowed to carry.
 *
 * The measurement this is built on, from the running host (`session/list`, ~1,870 rows):
 * every row whose agent was bound and running read `running: true, agentAvailable: true`;
 * every row with `running: false` was either attached-but-idle (`agentAvailable: true`)
 * or cold with no agent at all (`summarizeCold` hard-codes
 * `running: false, agentAvailable: false`). **No row was ever observed working while
 * the host said `running: false`.**
 *
 * `subagentTiming.active` — an open `turn/start` in the child's own journal — is
 * therefore *not* a liveness signal, and the case that proves it is a crash: after the
 * box went down mid-build and the host restarted, two children of
 * `session-48c441ec-3038-43a3-8777-e1ce7699135c` (`fd23b122…`, `dcd6240b…`) read
 * `running: false, agentAvailable: false` **with `active` present**, its `through`
 * frozen 64–65 minutes earlier at the moment of the crash, while children that really
 * were running in the same snapshot had `through` under a minute old. Reading `active`
 * as liveness would paint those two dead children as running forever.
 */
class DurableRunningTest {

    private fun row(
        running: Boolean,
        timing: JSONObject? = null,
        agentAvailable: Boolean = running,
    ): JSONObject {
        val values = JSONObject()
        if (timing != null) values.put("subagentTiming", timing)
        return JSONObject()
            .put("sessionId", "s")
            .put("running", running)
            .put("agentAvailable", agentAvailable)
            .put("projections", JSONObject().put("values", values))
    }

    private fun active(since: Long = 1L, through: Long = 2L) =
        JSONObject().put("settledMs", 0).put("active", JSONObject().put("since", since).put("through", through))

    @Test
    fun `the roster's own agent sample is the durable answer`() {
        assertTrue(durableRunningOf(row(running = true)))
        assertFalse(durableRunningOf(row(running = false)))
    }

    /**
     * The crash orphan. `active` is present, there is no agent, and the turn never
     * closed — the child is dead and must not be shown as working.
     */
    @Test
    fun `an open turn with no agent is a corpse, not a running child`() {
        assertFalse(durableRunningOf(row(running = false, timing = active(), agentAvailable = false)))
    }

    @Test
    fun `a settled child is not running`() {
        assertFalse(
            durableRunningOf(
                row(running = false, timing = JSONObject().put("settledMs", 4945003).put("lastTurnCompleted", true)),
            ),
        )
    }

    @Test
    fun `a turn that ended without completing is still ended`() {
        // Measured on the host: `lastTurnCompleted: false` with no `active` — the child
        // stopped mid-turn, and `active` is cleared by any `turn/end`.
        assertFalse(
            durableRunningOf(
                row(running = false, timing = JSONObject().put("settledMs", 101027).put("lastTurnCompleted", false)),
            ),
        )
    }

    @Test
    fun `an absent, null or malformed timing projection claims nothing`() {
        assertFalse(durableRunningOf(row(running = false)))
        assertFalse(durableRunningOf(row(running = false, timing = JSONObject().put("settledMs", 0).put("active", JSONObject.NULL))))
        assertFalse(durableRunningOf(row(running = false, timing = JSONObject().put("settledMs", 0).put("active", "running"))))
    }

    @Test
    fun `a projection that says active cannot promote a stopped session`() {
        // The regression this pins: `active` alone once meant "running".
        assertFalse(durableRunningOf(row(running = false, timing = active(), agentAvailable = true)))
    }

    @Test
    fun `the projection may be absent altogether`() {
        assertFalse(durableRunningOf(JSONObject().put("sessionId", "s").put("running", false)))
    }

    // ------------------------------------------------------------- the row's flag

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
    fun `a row takes the book's answer in both directions`() {
        val book = RunningBook()
        book.applyPull(book.beginPull(), mapOf("stale" to false, "live" to true))
        assertFalse(session("stale", running = true).withRunning(book).running)
        assertTrue(session("live", running = false).withRunning(book).running)
    }

    @Test
    fun `a row that already agrees is not rebuilt`() {
        val book = RunningBook()
        book.applyPull(book.beginPull(), mapOf("a" to true))
        val row = session("a", running = true)
        assertSame(row, row.withRunning(book))
    }

    /**
     * One flag, two readers — which is the guarantee the old code could not make.
     *
     * The header chip draws `indexSubagentRollups(...).running`; the lineage sheet draws
     * the descendants whose `running` is set. Both are derived from the same rows here,
     * so the chip's number and the sheet's list are the same fact counted and listed.
     */
    @Test
    fun `the header chip's count is the lineage sheet's rows`() {
        val book = RunningBook()
        // Two children the host reports working, one it does not.
        book.applyPull(
            book.beginPull(),
            mapOf("parent" to false, "c1" to true, "c2" to true, "c3" to false),
        )
        val roster = listOf(
            session("parent"),
            session("c1", parent = "parent"),
            session("c2", parent = "parent"),
            session("c3", parent = "parent"),
        ).map { it.withRunning(book) }

        val rollup = indexSubagentRollups(roster).getValue("parent")
        val sheetRows = roster.filter { it.parentSessionId == "parent" && it.running }
        assertEquals(3, rollup.total)
        assertEquals(2, rollup.running)
        assertEquals(sheetRows.size, rollup.running)
        assertEquals(listOf("c1", "c2"), sheetRows.map { it.id })
    }
}
