package uk.xa0.dsh.widget

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The snapshot's wire shape, and the one distinction the whole widget rests on.
 *
 * `unknown` and `zero` are the same number and must never be the same statement — the
 * Router tab's "not asked" versus "reported as empty" rule, applied to a homescreen.
 * These tests are the place that is pinned down, because a launcher is the one surface
 * where nobody is present to notice the difference.
 */
class WidgetSnapshotTest {

    private val at = 1_700_000_000_000L

    @Test
    fun `a confirmed zero is not the same thing as never having looked`() {
        val confirmed = WidgetSnapshot(needsYou = WidgetReading(known = true, value = 0, asOf = at))
        val never = WidgetSnapshot(needsYou = WidgetReading(known = false, value = 0, asOf = 0L))

        assertTrue(confirmed.needsYou.known)
        assertTrue(confirmed.needsYou.everSeen)
        // The same value, and the publisher must still treat them as a change: collapsing
        // these two would let a lost connection be drawn as "nothing needs you".
        assertFalse(confirmed.sameValuesAs(never))
        assertFalse(never.sameValuesAs(confirmed))
        assertFalse(never.needsYou.everSeen)
    }

    @Test
    fun `one reading can be withdrawn while the other is still standing`() {
        val snapshot = WidgetSnapshot(
            needsYou = WidgetReading(known = false, value = 2, asOf = at),
            working = WidgetReading(known = true, value = 3, asOf = at),
        )
        assertFalse(snapshot.sameValuesAs(snapshot.copy(needsYou = snapshot.needsYou.copy(known = true))))
        assertTrue(snapshot.sameValuesAs(snapshot.copy(needsYou = snapshot.needsYou.copy(asOf = at + 60_000))))
    }

    @Test
    fun `the stamp is the oldest reading still on screen`() {
        val snapshot = WidgetSnapshot(
            needsYou = WidgetReading(known = true, value = 1, asOf = at),
            working = WidgetReading(known = true, value = 2, asOf = at - 90_000),
            subagents = WidgetReading(known = true, value = 0, asOf = at + 5_000),
        )
        // The weakest reading bounds every claim above it, so it is the one that stamps.
        assertEquals(at - 90_000, snapshot.confirmedAt)
    }

    @Test
    fun `a reading nobody ever saw does not stamp anything`() {
        assertEquals(null, WidgetSnapshot.NEVER.confirmedAt)
        // Withdrawn but once seen: the instant is still worth printing, because it is
        // the last one at which the app could honestly say anything at all.
        val withdrawn = WidgetSnapshot(
            needsYou = WidgetReading(known = false, value = 0, asOf = at),
            working = WidgetReading(known = false, value = 0, asOf = 0L),
        )
        assertEquals(at, withdrawn.confirmedAt)
    }

    @Test
    fun `a snapshot survives a round trip`() {
        val snapshot = WidgetSnapshot(
            needsYou = WidgetReading(known = true, value = 2, asOf = at),
            working = WidgetReading(known = true, value = 3, asOf = at + 1),
            subagents = WidgetReading(known = true, value = 1, asOf = at + 2),
            kind = "PLAN_REVIEW",
            sessionId = "session-a771fb9b",
        )
        val decoded = WidgetSnapshot.decode(snapshot.encode())
        assertEquals(snapshot, decoded)
    }

    @Test
    fun `a reading with no names behind it round trips as null, not as the word null`() {
        val decoded = WidgetSnapshot.decode(WidgetSnapshot.NEVER.encode())
        assertEquals(WidgetSnapshot.NEVER, decoded)
        assertNull(decoded?.kind)
        assertNull(decoded?.sessionId)
    }

    @Test
    fun `nothing unreadable is ever allowed to throw`() {
        assertNull(WidgetSnapshot.decode(null))
        assertNull(WidgetSnapshot.decode(""))
        assertNull(WidgetSnapshot.decode("   "))
        assertNull(WidgetSnapshot.decode("not json at all"))
        // A document torn in half by a process death: the reader is a broadcast receiver
        // and must degrade to "nothing known" rather than take the launcher down.
        assertNull(WidgetSnapshot.decode(WidgetSnapshot.NEVER.encode().dropLast(12)))
        // A shape this build does not know is not a shape it may guess at.
        assertNull(WidgetSnapshot.decode(JSONObject().put("v", 99).toString()))
        assertNull(WidgetSnapshot.decode(JSONObject().put("v", 1).toString()))
        assertNull(
            WidgetSnapshot.decode(
                JSONObject()
                    .put("v", 1)
                    .put("needsYou", JSONObject().put("known", true))
                    .toString(),
            ),
        )
    }

    @Test
    fun `the version in the document is the version this build reads`() {
        val json = JSONObject(WidgetSnapshot.NEVER.encode())
        assertEquals(WidgetSnapshot.VERSION, json.getInt("v"))
    }
}
