package uk.xa0.dsh.model

/**
 * What a session has cost, as a range rather than a figure.
 *
 * DeepSeek bills the same tokens at two rates — peak and off-peak — and the panel
 * cannot know which one a past request landed in without a timestamp per request the
 * host does not send. So the honest answer is the two ends: the same tokens priced
 * off-peak and priced at peak. Anything else would be a single number pretending to
 * a precision the data does not have.
 *
 * Dollars, because that is the currency of the price list this table is copied from;
 * a display currency would need an exchange rate, and inventing one per render is
 * worse than naming the one the prices are published in.
 */
data class TokenRates(
    /** US$ per 1M input tokens that hit the cache. */
    val cacheHitInput: Double,
    /** US$ per 1M input tokens that missed it — including what is written to it. */
    val cacheMissInput: Double,
    /** US$ per 1M output tokens. */
    val output: Double,
)

/** Off-peak is half of peak: DeepSeek's own pricing note, not a guess. */
const val OFF_PEAK_RATIO: Double = 0.5

/**
 * The provider id the table is for.
 *
 * Only this one: the app knows DeepSeek's published list, and a made-up price for
 * another provider would be worse than no estimate at all.
 */
const val PRICED_PROVIDER: String = "deepseek-official"

/**
 * Where the numbers below come from, and when they were read.
 *
 * Kept as a string to be *shown* in the panel, because a price is only as good as its
 * date: DeepSeek has cut these rates more than once, and a reader looking at a stale
 * estimate should be able to see that it is stale.
 */
const val PRICE_SOURCE: String = "deepseek pricing, read 2026-09-30"

private val FLASH = TokenRates(cacheHitInput = 0.006, cacheMissInput = 0.3, output = 1.2)
private val PRO = TokenRates(cacheHitInput = 0.044, cacheMissInput = 1.32, output = 3.96)

/**
 * The published rates, by model id.
 *
 * The retired ids are here on purpose: the host still accepts `deepseek-v4-flash` and
 * `deepseek-v4-flash-vision-exp`, serves them with the same model, and bills them at
 * the Flash price — so a session that has been running since before the rename is
 * priced rather than silently excluded.
 */
private val RATES: Map<String, TokenRates> = mapOf(
    "deepseek-flash" to FLASH,
    "deepseek-v4-flash" to FLASH,
    "deepseek-v4-flash-vision-exp" to FLASH,
    "deepseek-v4-pro" to PRO,
)

/**
 * The rates for one route, or null when this app has no published price for it.
 *
 * Null is the whole safety property: an unpriced route draws no cost row at all,
 * rather than a number derived from someone else's prices.
 */
fun tokenRatesFor(provider: String?, model: String?): TokenRates? {
    if (provider.isNullOrBlank() || !provider.equals(PRICED_PROVIDER, ignoreCase = true)) return null
    return RATES[model?.lowercase()?.trim()]
}

/**
 * The two ends of the estimate for the given counts.
 *
 * Cache-write tokens are billed as a cache *miss*: the host's own meter counts them
 * separately, but the published list has no third input rate, and a write is an input
 * token that did not come from the cache.
 */
data class CostRange(val offPeak: Double, val peak: Double) {
    val currency: String get() = "$"
}

fun costRange(
    cacheHitTokens: Long,
    cacheMissTokens: Long,
    outputTokens: Long,
    rates: TokenRates,
): CostRange {
    val million = 1_000_000.0
    val peak = (cacheHitTokens / million) * rates.cacheHitInput +
        (cacheMissTokens / million) * rates.cacheMissInput +
        (outputTokens / million) * rates.output
    return CostRange(offPeak = peak * OFF_PEAK_RATIO, peak = peak)
}

/**
 * `$0.42 – $0.84`, or a single figure when the two ends round together.
 *
 * Two decimals below a dollar and three above it: a long session costs dollars, and
 * a fresh one costs cents — `$0.01 – $0.03` is a reading, `$0.010 – $0.030` is noise.
 * A range that rounds to the same number at its ends is printed once, because
 * `$1.23 – $1.23` reads as a mistake rather than as a certainty.
 */
fun formatCostRange(range: CostRange): String {
    // Below half of the smallest unit printed, the estimate *is* zero at this
    // precision: `$0.0000` for a few hundred cached tokens reads as a broken
    // figure rather than as the nothing it is.
    if (range.peak < 0.00005) return "${range.currency}0.00"
    val low = formatCost(range.offPeak, range.peak)
    val high = formatCost(range.peak, range.peak)
    return if (low == high) high else "$low – $high"
}

private fun formatCost(value: Double, peak: Double): String {
    val decimals = if (peak < 0.1) 4 else if (peak < 1.0) 3 else 2
    return "$" + "%.${decimals}f".format(value)
}
