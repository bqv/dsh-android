package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.SessionItem
import uk.xa0.dsh.ui.indexSubagentRollups

/**
 * The membership push: `api-session/added` / `api-session/removed`, and the ordering rule
 * that keeps a pull from undoing one.
 *
 * The measurement this exists for, from the device: a child created 59 s before a probe was
 * absent from the app's 1,891-row roster while the host listed 1,892, and its parent's
 * lineage sheet read `running=1` against the host's 2 for that whole minute — nothing but a
 * whole-world pull could add a row, and on an idle parent that can take minutes.
 */
class SessionRosterTest {

    private fun session(
        id: String,
        updatedAt: Long = 0L,
        running: Boolean = false,
        parent: String? = null,
    ) = SessionItem(
        id = id,
        title = id,
        cwd = "/tmp",
        updatedAt = updatedAt,
        running = running,
        isSubagent = parent != null,
        parentSessionId = parent,
    )

    // ------------------------------------------------------------- the roster fold

    @Test
    fun `an added row lands immediately, in the roster's own order`() {
        val roster = listOf(session("old", updatedAt = 10), session("middle", updatedAt = 20))
        val out = roster.upsertSession(session("new", updatedAt = 30))
        assertEquals(listOf("new", "middle", "old"), out.map { it.id })
    }

    @Test
    fun `a re-announced id is replaced, never duplicated`() {
        // The host re-announces `added` when an agent is created or disposed, so this is
        // the common case: the row arrives again with a fresh running/agentAvailable.
        val roster = listOf(session("child", updatedAt = 5, running = false, parent = "p"))
        val out = roster.upsertSession(session("child", updatedAt = 9, running = true, parent = "p"))
        assertEquals(1, out.size)
        assertTrue(out.single().running)
    }

    @Test
    fun `a removal drops the row, and an unknown id changes nothing`() {
        val roster = listOf(session("a"), session("b"))
        assertEquals(listOf("b"), roster.removeSession("a").map { it.id })
        assertSame(roster, roster.removeSession("never-listed"))
    }

    // ------------------------------------------------- the ordering rule, over epochs

    @Test
    fun `a row added during a pull's flight survives that pull`() {
        val hold = MembershipHold()
        val pull = 7L
        hold.remember(session("child"), running = true, epoch = pull)
        val survivors = hold.applyPull(pull)
        assertEquals(listOf("child"), survivors.map { it.row.id })
        assertTrue(survivors.single().running)
    }

    @Test
    fun `and is released by a pull requested after it`() {
        val hold = MembershipHold()
        hold.remember(session("child"), running = true, epoch = 7)
        assertTrue(hold.applyPull(8).isEmpty())
        assertTrue(hold.isEmpty())
    }

    @Test
    fun `a removal outranks a pull still in flight`() {
        val hold = MembershipHold()
        hold.remember(session("child"), running = true, epoch = 7)
        hold.forget("child")
        assertTrue(hold.applyPull(7).isEmpty())
    }

    @Test
    fun `the hold and the running book share one counter, so the two cannot drift`() {
        val book = RunningBook()
        val pull = book.beginPull()
        assertEquals(pull, book.epoch())
        assertEquals(pull + 1, book.beginPull())
    }

    // ------------------------------------------------------------- the idle parent

    /**
     * The case the whole task is about: nothing arrives but membership. No pull has ever
     * been applied and no status frame has been seen, and the child must still be a row
     * the header chip counts as running.
     */
    @Test
    fun `membership alone makes a child visible and running on an idle parent`() {
        val book = RunningBook()
        // The `api-session/added` payload's own sample for the child.
        book.observe("child", true)

        val roster = listOf(session("parent"))
            .upsertSession(session("child", running = true, parent = "parent"))
            .map { it.withRunning(book) }

        val rollup = indexSubagentRollups(roster).getValue("parent")
        assertEquals(1, rollup.total)
        assertEquals(1, rollup.running)
        assertEquals(listOf("child"), roster.filter { it.parentSessionId == "parent" && it.running }.map { it.id })
    }

    /**
     * And the hazard, on the same path: the membership frame schedules a pull, that pull's
     * cut predates the child's start, and the start's own `api-session/status` frame lands
     * while it is in flight. The pull must not be able to erase the row or the flag.
     */
    @Test
    fun `a membership-triggered pull cannot lose the start frame it raced`() {
        val book = RunningBook()
        val hold = MembershipHold()

        val pull = book.beginPull()
        // The added frame: a row, but the host has not called the child running yet.
        book.observe("child", false)
        hold.remember(session("child", running = false, parent = "parent"), running = false, epoch = book.epoch())
        // The child starts while that pull is still in flight.
        book.frame("child", true)
        // The pull lands with a cut that predates the start, and knows nothing of the child.
        book.applyPull(pull, mapOf("parent" to false))
        for (member in hold.applyPull(pull)) book.observe(member.row.id, member.running)

        assertTrue(book.running("child"))
        // And the row itself is still there to be counted.
        val roster = listOf(session("parent"), session("child", parent = "parent")).map { it.withRunning(book) }
        assertEquals(1, indexSubagentRollups(roster).getValue("parent").running)
    }

    /**
     * A snapshot is not a transition. The `added` sample is what a pull would have served,
     * so the pull that follows is at or after it and must win in *both* directions —
     * otherwise a child that stopped before the cut would be held running for a cycle.
     */
    @Test
    fun `a membership snapshot cannot outlive the pull that retires it`() {
        val book = RunningBook()
        book.observe("child", true)
        assertTrue(book.running("child"))
        book.applyPull(book.beginPull(), mapOf("child" to false))
        assertFalse(book.running("child"))
    }

    @Test
    fun `a removal forgets both the frame and the sample`() {
        val book = RunningBook()
        book.observe("child", true)
        book.frame("child", true)
        assertTrue(book.running("child"))
        book.forget("child")
        assertFalse(book.running("child"))
        // Even a pull that has not run again cannot resurrect it.
        assertFalse(book.running("child"))
    }
}
