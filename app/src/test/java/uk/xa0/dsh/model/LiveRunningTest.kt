package uk.xa0.dsh.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ordering rule between a roster cut and a live `api-session/status` frame.
 *
 * This replaces the pair of heuristics that used to answer the same question —
 * `runningAfter`'s "a subagent keeps its previous flag" and `staleLiveRunning`'s "an
 * id the list omits is unknown" — which disagreed with each other on the device. The
 * measurement they were written around: a parent with three live children reported
 * `running=3` and then `running=0` 105 ms later, with the tree still holding all three.
 *
 * Every test below is one half of that rule, stated as behaviour rather than as an
 * implementation detail: what a frame may do to a cut, and what a cut may do to a frame.
 */
class LiveRunningTest {

    @Test
    fun `a pull on its own answers, and a true sample means running`() {
        val book = RunningBook()
        book.applyPull(book.beginPull(), mapOf("a" to true, "b" to false))
        assertTrue(book.running("a"))
        assertFalse(book.running("b"))
    }

    @Test
    fun `an id no pull has ever named is not running`() {
        val book = RunningBook()
        assertFalse(book.running("never-seen"))
    }

    @Test
    fun `a frame that arrives after the pull outranks it, in both directions`() {
        val book = RunningBook()
        book.applyPull(book.beginPull(), mapOf("a" to true, "b" to false))
        // A start the pull was too early to carry; and a stop the pull was too early to
        // carry. Both land after the cut, so both are believed.
        book.frame("b", true)
        book.frame("a", false)
        assertTrue(book.running("b"))
        assertFalse(book.running("a"))
    }

    /**
     * The stale-overlay half: a frame collected before the read was even asked for
     * describes a transition the read's cut already contains, so it must not survive
     * the answer. This is what stopped a backgrounded app keeping a long-dead "running"
     * alive forever, which used to need a clock and a list scan to clear.
     */
    @Test
    fun `a frame older than the newest pull is subsumed by it`() {
        val book = RunningBook()
        book.frame("a", true)
        book.applyPull(book.beginPull(), mapOf("a" to false))
        assertFalse(book.running("a"))
    }

    /**
     * The reason the epoch is taken at *request* time.
     *
     * A pull whose cut predates three children starting answers `false` for all three —
     * the host's list was computed before their agents attached — while a frame for each
     * arrives during that read. Those frames are newer than the cut the answer carries,
     * so the children stay running instead of blinking out and back.
     */
    @Test
    fun `a frame that lands while a pull is in flight survives that pull`() {
        val book = RunningBook()
        val pull = book.beginPull()
        book.frame("c1", true)
        book.frame("c2", true)
        book.frame("c3", true)
        book.applyPull(pull, mapOf("c1" to false, "c2" to false, "c3" to false))
        assertTrue(book.running("c1"))
        assertTrue(book.running("c2"))
        assertTrue(book.running("c3"))
    }

    @Test
    fun `and is retired by the next pull, which is at or after it`() {
        val book = RunningBook()
        val first = book.beginPull()
        book.frame("c1", true)
        book.applyPull(first, mapOf("c1" to false))
        assertTrue(book.running("c1"))
        book.applyPull(book.beginPull(), mapOf("c1" to false))
        assertFalse(book.running("c1"))
    }

    @Test
    fun `a frame is never lost between two pulls, even the stop that follows the start`() {
        val book = RunningBook()
        book.applyPull(book.beginPull(), emptyMap())
        val pull = book.beginPull()
        book.frame("c1", true)
        book.frame("c1", false)
        book.applyPull(pull, emptyMap())
        // The latest frame in the epoch is the newest transition known, so the stop wins
        // rather than the start being replayed.
        assertFalse(book.running("c1"))
    }

    @Test
    fun `a pull that stops mentioning an id drops its claim`() {
        val book = RunningBook()
        book.applyPull(book.beginPull(), mapOf("gone" to true))
        assertTrue(book.running("gone"))
        book.applyPull(book.beginPull(), emptyMap())
        assertFalse(book.running("gone"))
    }

    /**
     * A slow answer must not put an out-of-date cut in front of a newer one: two
     * refreshes can overlap, and the second is the one that saw more of the log.
     */
    @Test
    fun `a pull older than one already applied is ignored`() {
        val book = RunningBook()
        val older = book.beginPull()
        val newer = book.beginPull()
        book.applyPull(newer, mapOf("a" to true))
        book.applyPull(older, mapOf("a" to false))
        assertTrue(book.running("a"))
    }

    @Test
    fun `before any pull a frame is the only evidence, so it answers`() {
        val book = RunningBook()
        book.frame("early", true)
        assertTrue(book.running("early"))
    }

    /**
     * The whole regression, end to end: three children whose starts the app was too late
     * to see, a pull whose cut predates them answering `false`, the answer staying true —
     * then a real stop arriving on the next cut and being believed.
     */
    @Test
    fun `three children starting under a stale pull do not flash and die`() {
        val book = RunningBook()
        // The roster read that first saw them attached.
        book.applyPull(book.beginPull(), mapOf("c1" to true, "c2" to true, "c3" to true))
        assertTrue(listOf("c1", "c2", "c3").all { book.running(it) })

        // 105ms later: a read taken before they started answers false for all three,
        // while the "started" frames land during its flight.
        val pull = book.beginPull()
        book.frame("c1", true)
        book.frame("c2", true)
        book.frame("c3", true)
        book.applyPull(pull, mapOf("c1" to false, "c2" to false, "c3" to false))
        assertTrue(listOf("c1", "c2", "c3").all { book.running(it) })

        // One of them really stops: the frame says so during the next read, whose cut
        // then agrees.
        val settled = book.beginPull()
        book.frame("c1", false)
        book.applyPull(settled, mapOf("c1" to false, "c2" to true, "c3" to true))
        assertFalse(book.running("c1"))
        assertTrue(book.running("c2"))
        assertTrue(book.running("c3"))
    }

    /**
     * The counterexample that bounds the whole design: a pull that says `false` for an id
     * is the *last* word once it lands, whatever frames it contains. A dead child whose
     * journal has an open turn is not resurrected by anything here — that reading lives in
     * `durableRunningOf`, and it reads `running`.
     */
    @Test
    fun `a pull that lands after the last frame is believed`() {
        val book = RunningBook()
        val first = book.beginPull()
        book.applyPull(first, mapOf("dead" to true))
        book.frame("dead", false)
        book.applyPull(book.beginPull(), mapOf("dead" to false))
        assertFalse(book.running("dead"))
    }

    @Test
    fun `clear forgets every claim`() {
        val book = RunningBook()
        book.applyPull(book.beginPull(), mapOf("a" to true))
        book.frame("b", true)
        book.clear()
        assertFalse(book.running("a"))
        assertFalse(book.running("b"))
    }
}
