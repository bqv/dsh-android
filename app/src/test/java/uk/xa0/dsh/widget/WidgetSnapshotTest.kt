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
            active = WidgetReading(known = true, value = 1, asOf = at + 3),
            running = listOf(
                WidgetSession("session-a771fb9b", "Fix the login bug"),
                WidgetSession("session-b9b", "Widget sessions"),
            ),
            kind = "PLAN_REVIEW",
            sessionId = "session-a771fb9b",
        )
        val decoded = WidgetSnapshot.decode(snapshot.encode())
        assertEquals(snapshot, decoded)
    }

    /**
     * The activity reading decides which word the headline draws, so it is part of what
     * the document says about the world — not an instant like `asOf` that a fresher
     * renewal may move for free.
     */
    @Test
    fun `an activity change is a change to what the card says`() {
        val quiet = WidgetSnapshot(active = WidgetReading(known = true, value = 0, asOf = at))
        val talking = quiet.copy(active = WidgetReading(known = true, value = 1, asOf = at))

        assertFalse(quiet.sameValuesAs(talking))
        assertFalse(talking.sameValuesAs(quiet))
        // And unknown is a third state, not a zero.
        assertFalse(quiet.sameValuesAs(quiet.copy(active = WidgetReading(known = false, value = 0, asOf = at))))
        // A fresher instant for the same activity still is not news.
        assertTrue(talking.sameValuesAs(talking.copy(active = talking.active.copy(asOf = at + 60_000))))
    }

    /**
     * The activity reading bounds the card because it is drawn: the headline's word comes
     * from it. Leaving it out of [WidgetSnapshot.confirmedAt] would leave the top line as
     * the one claim the footer no longer covers.
     */
    @Test
    fun `the activity reading is part of the oldest-reading bound`() {
        val snapshot = WidgetSnapshot(
            needsYou = WidgetReading(known = true, value = 0, asOf = at),
            working = WidgetReading(known = true, value = 1, asOf = at),
            subagents = WidgetReading(known = false, value = 0, asOf = 0L),
            active = WidgetReading(known = true, value = 1, asOf = at - 90_000),
        )
        assertEquals(at - 90_000, snapshot.confirmedAt)
    }

    /**
     * The document written by the build before this change has no `running` key at all.
     * It must still decode: the readings are the whole of what it claims, and "no names
     * were written down" is a state this build can draw honestly (the count alone).
     * Refusing it would blank every widget already on a homescreen for no gain — which is
     * exactly why [WidgetSnapshot.VERSION] was not bumped when the list was added.
     *
     * The same document has no `active` key either, and that is a reading this build
     * must not invent: it decodes as never confirmed, so the copy layer falls back to the
     * roster's own word rather than drawing an activity claim the old document never made.
     */
    @Test
    fun `an older document with no running list still reads, with no names in it`() {
        val older = JSONObject(
            WidgetSnapshot(
                needsYou = WidgetReading(known = true, value = 1, asOf = at),
                working = WidgetReading(known = true, value = 3, asOf = at),
                subagents = WidgetReading(known = false, value = 0, asOf = 0L),
                active = WidgetReading(known = true, value = 1, asOf = at),
                kind = "APPROVAL",
            ).encode(),
        )
        older.remove("running")
        older.remove("active")

        val decoded = WidgetSnapshot.decode(older.toString())
        assertEquals(
            "the readings are the whole of what the older document claims, and they still stand",
            3,
            decoded?.working?.value,
        )
        assertTrue(decoded?.working?.known == true)
        assertTrue(decoded?.running?.isEmpty() == true)
        assertTrue("an absent activity reading is 'nobody said', not a confirmation", decoded?.active?.known == false)
        assertFalse(decoded?.active?.everSeen == true)
    }

    /**
     * An absent `active` key is an older document and is read; a *present* one that is not
     * a reading is a document contradicting itself, and is refused like any other
     * unreadable shape. Guessing here would be a claim about the world drawn from a byte
     * nobody wrote — and the safe direction is not available, because "not a reading" also
     * covers a value that was *meant* to assert something.
     */
    @Test
    fun `a present but unreadable activity reading is refused rather than guessed at`() {
        val partial = JSONObject(WidgetSnapshot.NEVER.encode())
        partial.put("active", JSONObject().put("known", true))
        assertNull(WidgetSnapshot.decode(partial.toString()))

        val notAnObject = JSONObject(WidgetSnapshot.NEVER.encode())
        notAnObject.put("active", 1)
        assertNull(WidgetSnapshot.decode(notAnObject.toString()))

        // JSON null, on the other hand, is how a writer says "nothing here", and reads as
        // the same absent key an older document has.
        val nulled = JSONObject(WidgetSnapshot.NEVER.encode())
        nulled.put("active", JSONObject.NULL)
        val decoded = WidgetSnapshot.decode(nulled.toString())
        assertTrue(decoded?.active?.known == false)
        assertFalse("never confirmed is not the same as confirmed zero", decoded?.active?.everSeen == true)
    }

    @Test
    fun `a document carrying more names than the card can draw is bounded on read`() {
        val many = JSONObject(WidgetSnapshot.NEVER.encode())
        many.put(
            "running",
            org.json.JSONArray().apply {
                repeat(6) { put(WidgetSession("session-$it", "Session $it").toJson()) }
            },
        )

        val decoded = WidgetSnapshot.decode(many.toString())
        assertEquals(WidgetSnapshot.MAX_NAMED_SESSIONS, decoded?.running?.size)
        assertEquals("session-0", decoded?.running?.first()?.id)
    }

    @Test
    fun `a row that cannot be tapped is refused rather than drawn as a title`() {
        val broken = JSONObject(WidgetSnapshot.NEVER.encode())
        broken.put(
            "running",
            org.json.JSONArray().apply {
                put(WidgetSession("", "No id").toJson())
                put(JSONObject().put("title", "No id either"))
                put(WidgetSession("session-ok", "Fine").toJson())
            },
        )

        assertEquals(
            listOf("session-ok"),
            WidgetSnapshot.decode(broken.toString())?.running?.map { it.id },
        )
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

    /**
     * The on-disk version, pinned to a literal — which is the point, because bumping it is
     * not an internal detail. A document whose `v` differs from [WidgetSnapshot.VERSION] is
     * refused outright, so a bump silently drops **every widget already on a homescreen**
     * to "not known" until the app runs again and rewrites the file. That is a deliberate
     * act and it should cost an edit here; asserting `VERSION` against a document that
     * `encode` wrote from `VERSION` would instead be the same number twice and could not
     * fail for any reason.
     *
     * It is still 1 after `running` was added, on purpose: an optional key is readable in
     * both directions (see the two tests above), so this change does not meet the bar for
     * costing every placed widget its reading. A future change that makes an existing
     * document *unreadable* is the one that has to pay an edit here.
     */
    @Test
    fun `the on-disk version is pinned, because a bump blanks every placed widget`() {
        assertEquals(1, WidgetSnapshot.VERSION)
        assertEquals(1, JSONObject(WidgetSnapshot.NEVER.encode()).getInt("v"))

        val bumped = JSONObject(WidgetSnapshot.NEVER.encode()).put("v", 2)
        assertNull("a document this build does not know must not be guessed at", WidgetSnapshot.decode(bumped.toString()))

        val unversioned = JSONObject(WidgetSnapshot.NEVER.encode())
        unversioned.remove("v")
        assertNull(WidgetSnapshot.decode(unversioned.toString()))
    }
}
