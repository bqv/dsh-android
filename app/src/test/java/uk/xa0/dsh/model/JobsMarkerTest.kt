package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.JobItem

/**
 * The background-jobs seat's finished marker, driven directly.
 *
 * `DshViewModel` cannot be constructed in a JVM test (it is an `AndroidViewModel`),
 * so the two decisions that made the meter read as global live in [JobsMarker]
 * and are pinned here:
 *
 *  - the transition must be computed against the observed session's own previous
 *    list, never against the list that happened to be published last;
 *  - the dot belongs to the session that settled, never to the session on screen.
 *
 * [JobsSeat] is a miniature of exactly the ViewModel contract: a per-session list
 * map, a per-session unseen set, one open session, and a published (session list,
 * dot) pair. It is the shape that lets "the marker did not move" be an assertion
 * instead of a comment.
 */
class JobsMarkerTest {

    private fun job(id: String, status: String = "running") = JobItem(
        id = id,
        kind = "subagent",
        label = id,
        status = status,
        detail = null,
        startedAt = 1L,
        finishedAt = if (status == "running" || status == "stopping") null else 2L,
    )

    private val running = listOf(job("a"))
    private val finished = listOf(job("a", "completed"))

    // ------------------------------------------------------------- the rule

    @Test
    fun settledOnlyWhenARunningJobStops() {
        assertTrue(JobsMarker.settled(running, finished))
        assertTrue(JobsMarker.settled(listOf(job("a"), job("b")), finished))
    }

    @Test
    fun firstObservationNeverSettles() {
        // A session seen for the first time has no previous list to transition
        // from, whether the host now reports rows or none at all.
        assertFalse(JobsMarker.settled(emptyList(), emptyList()))
        assertFalse(JobsMarker.settled(emptyList(), finished))
        assertFalse(JobsMarker.settled(emptyList(), running))
    }

    @Test
    fun stillRunningJobsDoNotSettle() {
        assertFalse(JobsMarker.settled(listOf(job("a"), job("b")), listOf(job("a", "completed"), job("b"))))
        // A brand new job arriving is not a finish either.
        assertFalse(JobsMarker.settled(emptyList(), running))
    }

    @Test
    fun observedAddsOnlyTheSettlingSession() {
        val afterB = JobsMarker.observed(emptySet(), "B", running, finished)
        assertEquals(setOf("B"), afterB)
        val afterA = JobsMarker.observed(afterB, "A", running, running)
        assertEquals(setOf("B"), afterA)
        // Idempotent: re-observing the same settle does not churn the set.
        assertSame(afterB, JobsMarker.observed(afterB, "B", running, finished))
    }

    @Test
    fun unseenForNeedsASession() {
        assertTrue(JobsMarker.unseenFor(setOf("A"), "A"))
        assertFalse(JobsMarker.unseenFor(setOf("A"), "B"))
        assertFalse(JobsMarker.unseenFor(setOf("A"), null))
        assertFalse(JobsMarker.unseenFor(emptySet(), "A"))
    }

    @Test
    fun seenRetiresOneSessionOnly() {
        assertEquals(setOf("A"), JobsMarker.seen(setOf("A", "B"), "B"))
        assertEquals(setOf("A", "B"), JobsMarker.seen(setOf("A", "B"), null))
        assertEquals(setOf("A", "B"), JobsMarker.seen(setOf("A", "B"), "C"))
    }

    // ------------------------------------------------- the wiring miniature

    /**
     * The regression the user hit: a job finishing in one session lit the seat
     * of the session on screen. Both parts of the fix are exercised — the dot is
     * per session, and the transition is judged against each session's own list.
     */
    @Test
    fun finishedInAnotherSessionDoesNotLightTheOpenSeat() {
        val seat = JobsSeat()
        seat.open("A")
        seat.observe("A", running)
        seat.observe("B", running) // a job started elsewhere
        seat.observe("B", finished) // ...and finished while A was on screen

        assertEquals("A's published list", running, seat.publishedJobs)
        assertFalse("A's seat must not carry B's marker", seat.publishedDot)

        seat.open("B")
        assertTrue("B's own seat carries the marker", seat.publishedDot)

        // Coming back to A must not show B's marker: the dot is keyed by session.
        seat.open("A")
        assertFalse("A's seat is still clear after B settled", seat.publishedDot)
    }

    /**
     * The first `jobs` frame after a switch used to be compared against the
     * previous session's published list, so a list that never ran anything could
     * light the dot. The transition is judged per session instead.
     */
    @Test
    fun firstObservationOfASessionNeverLightsItsSeat() {
        val seat = JobsSeat()
        seat.open("A")
        seat.observe("A", running) // last list published belongs to A
        seat.open("B")
        seat.observe("B", emptyList()) // B's first observation: nothing ran

        assertFalse(seat.publishedDot)
        assertEquals(emptyList<JobItem>(), seat.publishedJobs)
    }

    @Test
    fun switchingSessionsPublishesThatSessionsOwnList() {
        val seat = JobsSeat()
        seat.observe("A", running)
        seat.open("A")
        assertEquals(running, seat.publishedJobs)

        val bRunning = listOf(job("b"))
        seat.observe("B", bRunning)
        seat.open("B")
        assertEquals(bRunning, seat.publishedJobs)
        assertFalse(seat.publishedDot)
    }

    @Test
    fun pendingHeroPublishesEmptyAndNoDot() {
        val seat = JobsSeat()
        seat.open("A")
        seat.observe("A", running)
        seat.observe("A", finished)
        assertTrue(seat.publishedDot)

        seat.open(null) // the deferred-creation hero
        assertEquals(emptyList<JobItem>(), seat.publishedJobs)
        assertFalse(seat.publishedDot)

        seat.open("A") // and the marker is still there on the way back
        assertTrue(seat.publishedDot)
    }

    @Test
    fun markSeenClearsOnlyTheViewedSession() {
        val seat = JobsSeat()
        seat.observe("A", running)
        seat.observe("A", finished)
        seat.observe("B", running)
        seat.observe("B", finished)

        seat.open("A")
        assertTrue(seat.publishedDot)
        seat.markSeen()
        assertFalse(seat.publishedDot)

        seat.open("B")
        assertTrue("B's dot survives A being read", seat.publishedDot)
    }

    @Test
    fun baselineObservationCanSettleASessionNeverViewed() {
        val seat = JobsSeat()
        // Before the reconnect the host had reported B running.
        seat.observe("B", running)
        // The reconnect baseline now reports B finished, while A is on screen.
        seat.open("A")
        seat.observe("B", finished)

        seat.open("B")
        assertTrue("a job that finished while B was off screen lights B", seat.publishedDot)
    }

    /**
     * The ViewModel's job wiring in miniature: `recordJobs` then, for the open
     * session only, `publishJobsForCurrent`.
     */
    private class JobsSeat {
        private val bySession = mutableMapOf<String, List<JobItem>>()
        private var unseen: Set<String> = emptySet()
        private var openSessionId: String? = null
        var publishedJobs: List<JobItem> = emptyList()
            private set
        var publishedDot: Boolean = false
            private set

        fun open(sessionId: String?) {
            openSessionId = sessionId
            publish()
        }

        fun observe(sessionId: String, items: List<JobItem>) {
            val previous = bySession[sessionId].orEmpty()
            bySession[sessionId] = items
            unseen = JobsMarker.observed(unseen, sessionId, previous, items)
            if (sessionId == openSessionId) publish()
        }

        fun markSeen() {
            unseen = JobsMarker.seen(unseen, openSessionId)
            publish()
        }

        private fun publish() {
            publishedJobs = openSessionId?.let { bySession[it] }.orEmpty()
            publishedDot = JobsMarker.unseenFor(unseen, openSessionId)
        }
    }
}
