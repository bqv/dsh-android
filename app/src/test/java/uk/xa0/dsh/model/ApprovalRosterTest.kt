package uk.xa0.dsh.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reconciliation rule: what a fresh forwarded-event generation says about the
 * cards already held.
 *
 * The host re-announces every waterfall it is still holding to a newly opened
 * `$events` stream, and names nothing it has settled. These tests pin the three
 * relationships that follow, because each one is a way a card could either linger
 * for ever or be taken while the agent is still blocked:
 *
 *  * a card the host named in the new generation is **not** settled;
 *  * a card it did not name is settled;
 *  * a window opened by an older generation cannot settle a card a newer one has
 *    named — the two windows overlap in time and only the current one decides.
 *
 * ## What these cannot prove
 *
 * That the host really re-announces what it holds and really refuses to cancel the
 * client that answered: both are read from the gateway
 * (`dsh-api-gateway/lib/index.js`, `openRemoteEvents` and `finishRemoteEvent`), not
 * exercised here. Nor that the window the app gives the batch is long enough on a
 * real link — this class has no clock and no wire; the caller owns the window, and a
 * re-announcement later than it raises the card again rather than being lost.
 */
class ApprovalRosterTest {

    @Test
    fun `a card the host re-announces is not settled`() {
        val roster = ApprovalRoster()
        val first = roster.onGeneration()
        roster.onAnnounced("e1")
        assertFalse("the host is still holding it", roster.settled("e1", first))

        val second = roster.onGeneration()
        roster.onAnnounced("e1")
        assertFalse("and still is on the next generation", roster.settled("e1", second))
    }

    @Test
    fun `a card the host does not name is settled`() {
        val roster = ApprovalRoster()
        roster.onGeneration()
        roster.onAnnounced("answered")
        roster.onSettled("answered")
        roster.onAnnounced("pending")

        val next = roster.onGeneration()
        roster.onAnnounced("pending")
        assertFalse("the pending one is still the host's", roster.settled("pending", next))
        assertTrue("the answered one is gone", roster.settled("answered", next))
    }

    @Test
    fun `a card named in an older generation is settled by the new one`() {
        val roster = ApprovalRoster()
        roster.onGeneration()
        roster.onAnnounced("e1")
        roster.onAnnounced("e2")

        val next = roster.onGeneration()
        roster.onAnnounced("e2")
        assertTrue("the host dropped it between generations", roster.settled("e1", next))
        assertFalse(roster.settled("e2", next))
    }

    @Test
    fun `a newer generation's window supersedes an older one`() {
        val roster = ApprovalRoster()
        val first = roster.onGeneration()
        roster.onAnnounced("e1")

        // The window for `first` decides while a second generation is opening. It
        // must not take a card the newer generation is about to speak for.
        val second = roster.onGeneration()
        assertFalse("an old window has nothing to say", roster.settled("e1", first))
        roster.onAnnounced("e1")
        assertFalse(roster.settled("e1", second))
    }

    @Test
    fun `settling forgets the pairing`() {
        val roster = ApprovalRoster()
        val generation = roster.onGeneration()
        roster.onAnnounced("e1")
        roster.onSettled("e1")
        assertTrue("a settled card is not spoken for", roster.settled("e1", generation))

        // A fresh announcement puts it back: the host raising the same waterfall
        // again is a new card, not a resurrected one.
        roster.onAnnounced("e1")
        assertFalse(roster.settled("e1", generation))
    }

    @Test
    fun `a cleared roster forgets every pairing`() {
        val roster = ApprovalRoster()
        val generation = roster.onGeneration()
        roster.onAnnounced("e1")
        roster.clear()
        assertTrue("the cards it described are gone too", roster.settled("e1", generation))
    }
}
