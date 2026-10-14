package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.SessionItem

/**
 * The whole-family token and cost reading behind the usage panel.
 *
 * What a unit test can pin here is the arithmetic and the *disclosure*: that the sum covers
 * the open session and every descendant however deep, that no member is counted twice, and
 * that an omission, an unpriced member and the arithmetic's own terms each reach the reader
 * rather than being folded into a quieter number.
 *
 * What it cannot pin is the wire, and that is the load-bearing half: that a live
 * `session/list` row carries `tokenUsage` and `modelSelection` at all, that 669 of the 1,287
 * subagent rows carry neither, and that a spawned child's figure is its own while a forked
 * one's is not. Those were read from the running host by hand (see `docs/HANDOFF.md` note
 * 23), not asserted here — a green unit suite says nothing about them.
 */
class FamilyUsageTest {

    private val flash = ModelRef("deepseek-official", "deepseek-flash")
    private val local = ModelRef("dsh-local", "qwen2-1.5b")

    /** One member's own reported figure. Tokens are the four buckets the fold totals. */
    private fun usage(
        uncached: Long = 0,
        cached: Long = 0,
        write: Long = 0,
        output: Long = 0,
    ) = SessionStats(
        hasStats = true,
        steps = 1,
        hasUsage = true,
        uncachedInputTokens = uncached,
        cacheReadTokens = cached,
        cacheWriteTokens = write,
        outputTokens = output,
    )

    private fun session(
        id: String,
        parent: String? = null,
        usage: SessionStats? = null,
        model: ModelRef? = flash,
    ) = SessionItem(
        id = id,
        title = id,
        cwd = "/tmp",
        updatedAt = 0L,
        running = false,
        isSubagent = parent != null,
        parentSessionId = parent,
        usage = usage,
        lastUsedModel = model,
    )

    //  a (the open session)
    //  ├── b
    //  │    └── d
    //  └── c
    private val family = listOf(
        session("a", usage = usage(uncached = 1_000)),
        session("b", parent = "a", usage = usage(uncached = 2_000)),
        session("c", parent = "a", usage = usage(uncached = 3_000)),
        session("d", parent = "b", usage = usage(uncached = 4_000)),
        session("unrelated", usage = usage(uncached = 999_000)),
    )

    private fun fold(
        sessions: List<SessionItem> = family,
        rootId: String = "a",
        own: SessionStats? = usage(uncached = 1_000),
        ownRates: TokenRates? = tokenRatesFor("deepseek-official", "deepseek-flash"),
        catalog: SubagentCatalog? = null,
    ) = familyUsage(sessions, rootId, own, ownRates, catalog)

    @Test
    fun `the total is the open session and every descendant, however deep`() {
        val total = fold()
        // 1,000 + 2,000 + 3,000 + 4,000, with the grandchild d counted through b.
        assertEquals(10_000L, total.tokens)
        assertEquals(3, total.members)
        assertEquals(3, total.counted)
        assertEquals(4, total.inFigure)
    }

    /** A whole and priced family qualifies nothing except the arithmetic's own terms. */
    @Test
    fun `a whole and priced family says nothing extra`() {
        val total = fold()
        assertEquals("Family, as reported", total.label)
        assertEquals("3", total.memberCountText)
        assertEquals(listOf(METHOD_NOTE), total.notes)
        assertNotNull(total.cost)
    }

    /** Each member's tokens are priced at its own route, and the parts sum. */
    @Test
    fun `the family cost is the sum of the parts`() {
        val cost = fold().cost
        assertNotNull(cost)
        // Flash's miss rate is ¥2 per 1M, so 10,000 uncached tokens peak at ¥0.02.
        assertEquals(0.02, cost!!.peak, 1e-9)
        assertEquals(0.01, cost.offPeak, 1e-9)
    }

    @Test
    fun `a child with its own route is priced at that route, not its parent's`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            // Pro's miss rate is ¥9 per 1M against Flash's ¥2.
            session(
                "b",
                parent = "a",
                usage = usage(uncached = 1_000),
                model = ModelRef("deepseek-official", "deepseek-v4-pro"),
            ),
        )
        val cost = fold(sessions = sessions).cost
        assertNotNull(cost)
        assertEquals(0.002 + 0.009, cost!!.peak, 1e-9)
    }

    /**
     * The omission: a member whose usage the host has not reported contributes nothing,
     * and the panel says whose tokens are missing rather than leaning on the count alone.
     */
    @Test
    fun `an unreported child is named as an omission`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            session("b", parent = "a", usage = null),
        )
        val total = fold(sessions = sessions)
        assertEquals(1, total.members)
        assertEquals(0, total.counted)
        assertEquals(1, total.unreported)
        assertEquals(1_000L, total.tokens)
        // The count is the disclosure of the boundary: 0 of 1 are inside the figure.
        assertEquals("0 of 1", total.memberCountText)
        assertEquals(
            listOf(
                "The host has not reported usage for 1 subagent, so their tokens are " +
                    "not in this figure.",
                METHOD_NOTE,
            ),
            total.notes,
        )
    }

    @Test
    fun `several unreported children are named in the plural`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            session("b", parent = "a", usage = null),
            session("c", parent = "a", usage = null),
        )
        val total = fold(sessions = sessions)
        assertEquals(2, total.unreported)
        assertTrue(total.notes.first().startsWith("The host has not reported usage for 2 subagents"))
    }

    /**
     * The over-count this fold cannot decide for itself: a `fork` subagent's log is
     * seeded with a copy of its parent's prefix, and the `tokenUsage` fold has no reset
     * at that cut — so its figure already contains the parent's tokens. No `session/list`
     * field says which subagents those are, so the figure is summed and the method note
     * carries the property. These are the measured numbers of real fork child
     * `630d0054`: 40,847,940 inherited tokens over 126 steps, 7,373,046 of its own over
     * 13, reported as one figure of 48,220,986.
     */
    @Test
    fun `a forked child's figure is summed and the method note says what that means`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            session("b", parent = "a", usage = usage(uncached = 48_220_986)),
        )
        val total = fold(sessions = sessions)
        assertEquals(1, total.counted)
        assertEquals(1_000L + 48_220_986L, total.tokens)
        assertEquals(listOf(METHOD_NOTE), total.notes)
        assertTrue("the note names the case", total.notes.any { it.contains("fork") })
    }

    /** A lineage that loops terminates, and still credits each session exactly once. */
    @Test
    fun `a cyclic lineage counts each session once`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            session("b", parent = "a", usage = usage(uncached = 2_000)),
            session("c", parent = "b", usage = usage(uncached = 3_000)),
            // A second row for b, re-parented under its own descendant.
            session("b", parent = "c", usage = usage(uncached = 2_000)),
        )
        val total = fold(sessions = sessions)
        assertEquals(2, total.members)
        assertEquals(2, total.counted)
        // b's 2,000 is paid for once, not twice.
        assertEquals(1_000L + 2_000L + 3_000L, total.tokens)
    }

    /** The same session named by two roster rows must not be paid for twice. */
    @Test
    fun `a duplicated roster row counts once`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            session("b", parent = "a", usage = usage(uncached = 2_000)),
            session("b", parent = "a", usage = usage(uncached = 2_000)),
        )
        val total = fold(sessions = sessions)
        assertEquals(1, total.members)
        assertEquals(1, total.counted)
        assertEquals(3_000L, total.tokens)
    }

    /**
     * Money is not shown when any member of it is unpriced: a subtotal that quietly
     * drops a model is money-shaped. The panel gets the count and the reason instead.
     */
    @Test
    fun `an unpriced member withholds the family cost and names it`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            session("b", parent = "a", usage = usage(uncached = 2_000), model = local),
        )
        val total = fold(sessions = sessions)
        assertNull(total.cost)
        assertEquals(1, total.unpriced)
        assertEquals(2, total.inFigure)
        assertEquals(
            listOf(
                "Family cost is not shown: no published price for 1 of the 2 sessions counted.",
                METHOD_NOTE,
            ),
            total.notes,
        )
        // The token figure is unaffected: tokens are counted, money is not guessed.
        assertEquals(3_000L, total.tokens)
    }

    /** A member whose route the host never reported has no price either. */
    @Test
    fun `a member with no reported route has no price`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            session("b", parent = "a", usage = usage(uncached = 2_000), model = null),
        )
        val total = fold(sessions = sessions)
        assertNull(total.cost)
        assertEquals(1, total.unpriced)
        assertTrue(total.notes.first().startsWith("Family cost is not shown:"))
    }

    /** An unpriced route for the open session withholds the family cost as it already does its own. */
    @Test
    fun `an unpriced open session withholds the family cost`() {
        val sessions = listOf(
            session("a", usage = usage(uncached = 1_000)),
            session("b", parent = "a", usage = usage(uncached = 2_000)),
        )
        val total = familyUsage(sessions, "a", usage(uncached = 1_000), ownRates = null)
        assertNull(total.cost)
        assertEquals(1, total.unpriced)
        assertEquals(2, total.inFigure)
    }

    /** No subagents means nothing was left out — but the arithmetic still needs saying. */
    @Test
    fun `a session with no subagents sums only itself`() {
        val sessions = listOf(session("a", usage = usage(uncached = 1_000)))
        val total = fold(sessions = sessions)
        assertEquals(0, total.members)
        assertEquals(0, total.counted)
        assertEquals(1_000L, total.tokens)
        // Still no claim of a pure sum: the note is about the arithmetic, not about size.
        assertEquals(listOf(METHOD_NOTE), total.notes)
        assertEquals(0.002, total.cost!!.peak, 1e-9)
    }

    /** The live figure outranks the roster's own row for the same session. */
    @Test
    fun `the live figure wins over the roster row`() {
        val sessions = listOf(session("a", usage = usage(uncached = 5_000)))
        val total = familyUsage(
            sessions,
            "a",
            usage(uncached = 1_000),
            ownRates = tokenRatesFor("deepseek-official", "deepseek-flash"),
        )
        assertEquals(1_000L, total.tokens)
    }

    /** With no live figure yet, the roster's own row is the reading rather than nothing. */
    @Test
    fun `the roster row stands in until the live figure lands`() {
        val sessions = listOf(session("a", usage = usage(uncached = 5_000)))
        val total = familyUsage(
            sessions,
            "a",
            own = null,
            ownRates = tokenRatesFor("deepseek-official", "deepseek-flash"),
        )
        assertEquals(5_000L, total.tokens)
        assertEquals(1, total.inFigure)
    }

    /**
     * A live payload that carried `sessionStats` and no `tokenUsage` must not blank a
     * figure the roster still holds: they are the same projection, and either may be the
     * fresher one.
     */
    @Test
    fun `a live figure with no usage falls back to the roster row`() {
        val sessions = listOf(session("a", usage = usage(uncached = 5_000)))
        val noTokens = SessionStats(hasStats = true, steps = 3, hasUsage = false)
        val total = familyUsage(
            sessions,
            "a",
            noTokens,
            tokenRatesFor("deepseek-official", "deepseek-flash"),
        )
        assertEquals(5_000L, total.tokens)
    }

    /**
     * The catalog can name a direct child the roster pull has not delivered yet — the
     * roster is a whole-world read and a child can be spawned mid-turn. It is a member,
     * and it is one the host has not reported usage for, which is exactly what it is.
     */
    @Test
    fun `a catalog child the roster has not delivered is a member with no usage`() {
        val sessions = listOf(session("a", usage = usage(uncached = 1_000)))
        val total = fold(sessions = sessions, catalog = catalogOf("ghost"))
        assertEquals(1, total.members)
        assertEquals(0, total.counted)
        assertEquals(1, total.unreported)
        assertEquals(1_000L, total.tokens)
        assertEquals("0 of 1", total.memberCountText)
    }

    /** A catalog child the roster already delivers is the same member, not a second one. */
    @Test
    fun `a catalog child already in the roster is not counted twice`() {
        assertEquals(3, fold(catalog = catalogOf("b")).members)
    }

    /** A child the catalog could not describe is not evidence of a child. */
    @Test
    fun `a catalog diagnostic is not a member`() {
        val catalog = SubagentCatalog(
            entries = listOf(SubagentCatalogEntry.Diagnostic("d1", "descriptor unavailable")),
            parentAvailable = null,
        )
        assertEquals(3, fold(catalog = catalog).members)
    }

    private fun catalogOf(vararg childIds: String) = SubagentCatalog(
        entries = childIds.map { SubagentCatalogEntry.Child(it, "continuable", it, hasChildren = false) },
        parentAvailable = null,
    )
}
