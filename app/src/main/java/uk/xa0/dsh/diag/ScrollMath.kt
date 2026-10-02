package uk.xa0.dsh.diag

import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The judgements [ScrollDiag] makes about a position sample, lifted out of the
 * recorder so they can be tested without a device. Everything here is pure:
 * numbers in, verdict out, no clocks and no Android.
 */

/** How much of the previous visible set is still visible; 1.0 when unknown. */
internal fun sharedRatio(before: List<String>, now: List<String>): Float {
    if (before.isEmpty() || now.isEmpty()) return 1f
    val set = now.toHashSet()
    return before.count { it in set }.toFloat() / before.size.toFloat()
}

/** Whether a step is large enough to be worth recording rather than smoothed over. */
internal fun jumped(di: Int, kept: Float, threshold: Int, pxJump: Boolean): Boolean =
    abs(di) >= threshold || kept < 0.4f || pxJump

/** Whether a `prog` mark still explains a sample that arrived [ttl] later. */
internal fun fresh(markAt: Long, now: Long, ttl: Long): Boolean =
    now >= markAt && now - markAt <= ttl

/** A ratio as a whole percent, so a record stays short. */
internal fun ratioPercent(kept: Float): Int = (kept * 100).roundToInt()

/** A pixel position as a fraction of its extent, to two places. */
internal fun fraction(v: Float, extent: Int): Float =
    if (extent <= 0) 0f else (v / extent * 100f).roundToInt() / 100f
