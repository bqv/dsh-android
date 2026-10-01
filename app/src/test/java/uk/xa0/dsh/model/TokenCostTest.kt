package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cost estimate, pinned against the published list it was copied from.
 *
 * Two things are worth pinning rather than eyeballing on a phone: the arithmetic
 * per token class — a cache hit is 50× cheaper than a miss on Flash, so a wrong
 * class is a wrong answer by orders of magnitude — and that an unpriced route
 * produces *nothing*, because that is what keeps a guessed number off the screen.
 */
class TokenCostTest {

    private val flash = tokenRatesFor("deepseek-official", "deepseek-flash")!!
    private val pro = tokenRatesFor("deepseek-official", "deepseek-v4-pro")!!

    /** One million of each class, priced from the published Flash rates. */
    @Test
    fun `a million of each class costs the published rates`() {
        val range = costRange(1_000_000, 1_000_000, 1_000_000, flash)
        // peak: 0.006 + 0.3 + 1.2
        assertEquals(1.506, range.peak, 1e-9)
        // off-peak is half of peak
        assertEquals(0.753, range.offPeak, 1e-9)
    }

    /** The cache-hit rate is the one that makes a long session cheap; keep it honest. */
    @Test
    fun `cache hits are billed at the hit rate, not the miss rate`() {
        val hits = costRange(cacheHitTokens = 1_000_000, cacheMissTokens = 0, outputTokens = 0, rates = flash)
        val misses = costRange(cacheHitTokens = 0, cacheMissTokens = 1_000_000, outputTokens = 0, rates = flash)
        assertEquals(0.006, hits.peak, 1e-9)
        assertEquals(0.3, misses.peak, 1e-9)
        assertTrue("a hit must be far cheaper than a miss", hits.peak < misses.peak / 10)
    }

    @Test
    fun `pro is priced from its own column`() {
        val range = costRange(0, 1_000_000, 0, pro)
        assertEquals(1.32, range.peak, 1e-9)
        assertEquals(0.66, range.offPeak, 1e-9)
    }

    /** The retired ids are served and billed as Flash, so they must be priced. */
    @Test
    fun `retired model ids are priced as flash`() {
        assertEquals(flash, tokenRatesFor("deepseek-official", "deepseek-v4-flash"))
        assertEquals(flash, tokenRatesFor("deepseek-official", "deepseek-v4-flash-vision-exp"))
        assertEquals(flash, tokenRatesFor("deepseek-official", "DeepSeek-Flash"))
    }

    /**
     * The safety property: anything this app has no published price for draws no row.
     * A local model has no price; a made-up one would be a lie with a currency sign.
     */
    @Test
    fun `an unpriced route has no rates`() {
        assertNull(tokenRatesFor("dsh-local", "qwen2-1.5b"))
        assertNull(tokenRatesFor("free-groq", "llama-3.3-70b"))
        assertNull(tokenRatesFor("deepseek-official", "deepseek-v5-unknown"))
        assertNull(tokenRatesFor(null, "deepseek-flash"))
        assertNull(tokenRatesFor("deepseek-official", null))
    }

    @Test
    fun `an empty session estimates zero`() {
        val range = costRange(0, 0, 0, flash)
        assertEquals(0.0, range.peak, 0.0)
        assertEquals("$0.00", formatCostRange(range))
        // So does one whose whole spend is below the precision printed: a few hundred
        // cached tokens is not a figure, it is nothing to the cent.
        assertEquals("$0.00", formatCostRange(costRange(500, 0, 0, flash)))
    }

    /** Two decimals for dollars, three for cents, four when it is a fraction of one. */
    @Test
    fun `the format scales with the size of the figure`() {
        assertEquals("$1.51 – $3.01", formatCostRange(CostRange(1.506, 3.012)))
        assertEquals("$0.075 – $0.151", formatCostRange(CostRange(0.0753, 0.1506)))
        assertEquals("$0.0075 – $0.0151", formatCostRange(CostRange(0.00753, 0.01506)))
    }

    /**
     * A range whose ends print the same is printed once: `$0.00 – $0.00` reads as a
     * bug, and a very cheap session genuinely has one answer.
     */
    @Test
    fun `a range that rounds together is printed once`() {
        assertEquals("$0.00", formatCostRange(CostRange(0.0000001, 0.0000002)))
        // A thousand cache-hit tokens on Flash is a fraction of a cent: both ends print
        // the same four-decimal figure, so the reading is one value and not a range.
        val tiny = formatCostRange(costRange(1_000, 0, 0, flash))
        assertTrue(tiny.startsWith("$"))
        assertTrue("no dash at this size", !tiny.contains("–"))
    }
}
