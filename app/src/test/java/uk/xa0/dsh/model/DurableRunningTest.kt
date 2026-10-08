package uk.xa0.dsh.model

import org.json.JSONArray
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
 * The measurement this is built on, from the running host (`session/list`, 1,864 rows):
 * four children read `running: true`, and all four also carried
 * `subagentTiming.active`. Zero rows read `running: false` with `active` present, i.e.
 * the two agree — and the projection is the one that survives a cold read, because the
 * host's `summarizeCold` hard-codes `running: false, agentAvailable: false` for every
 * session it has not attached.
 */
class DurableRunningTest {

    private fun row(
        running: Boolean,
        timing: JSONObject? = null,
    ): JSONObject {
        val values = JSONObject()
        if (timing != null) values.put("subagentTiming", timing)
        return JSONObject()
            .put("sessionId", "s")
            .put("running", running)
            .put("projections", JSONObject().put("values", values))
    }

    private fun active(since: Long = 1L, through: Long = 2L) =
        JSONObject().put("settledMs", 0).put("active", JSONObject().put("since", since).put("through", through))

    @Test
    fun `the attached-agent sample alone is enough`() {
        assertTrue(durableRunningOf(row(running = true)))
    }

    /**
     * The whole point: a child the host serves cold reads `running: false` while its own
     * journal still has a `turn/start` with no `turn/end`.
     */
    @Test
    fun `an open turn outranks a cold running false`() {
        assertTrue(durableRunningOf(row(running = false, timing = active())))
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
        // Two live children and one finished: `active` present on the two, absent on the
        // third, with the host's summary saying false for all three because they are cold.
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
