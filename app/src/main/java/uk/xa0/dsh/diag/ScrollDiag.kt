package uk.xa0.dsh.diag

import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Latent scroll diagnostics.
 *
 * The app's scrolling has been rebuilt more than once from somebody's account of
 * the symptom — "it jumps", "it fights my finger" — and each rebuild swapped one
 * story for another, because the account is always thinner than the gesture.
 * This records what actually happened, on the device, so a rebuild can start from
 * the gesture instead.
 *
 * It is *latent* by construction: it draws nothing, changes no layout, consumes
 * no pointer event, never scrolls anything, and appears nowhere in the UI. It
 * keeps a bounded ring in memory, prints the notable records to logcat under
 * [TAG], and appends the whole stream to a rotating file under `files/diag/`.
 *
 * Reading it:
 *
 *     adb logcat -d -s DshScroll:I
 *     adb shell run-as uk.xa0.dsh.debug cat files/diag/scroll.jsonl
 *
 * The record kinds, and what each one is evidence of:
 *
 *  * `surf`  — a scroll surface appeared, with its extent.
 *  * `down`  — a finger landed, with its position in the surface and as a
 *              fraction of that surface's width and height. The fraction is what
 *              identifies a strip at an edge eating the gesture.
 *  * `up`    — the gesture ended: how far the finger actually travelled, how
 *              long it took, whether a child scrollable consumed it, and
 *              whether the surface's own offset moved at all. `child=1` with
 *              `moved=0` is a gesture the surface never saw.
 *  * `prog`  — a mark dropped immediately before the app moves the list itself.
 *              A `shift` carrying a `tag` is that move; without a tag, nothing
 *              in the app asked for it.
 *  * `shift` — the position changed while no finger was down. Carries the index
 *              delta, the offset delta, how the item count changed, how much of
 *              the visible set survived, and the millisecond gap since the last
 *              sample. `ditem` non-zero with `di` matching it is an ordinary
 *              insertion; `di` with `ditem=0` is the list moving with no new
 *              content to explain it.
 *  * `jank`  — more than [JANK_MS] between samples while the position was
 *              changing, i.e. a visible stall during a scroll.
 */
object ScrollDiag {

    const val TAG = "DshScroll"

    /** A gap between position samples longer than this is a visible stall. */
    private const val JANK_MS = 150L

    /** How long a `prog` mark stays attributable to a `shift`. */
    private const val MARK_TTL_MS = 500L

    /** A pixel-scrolled surface stepping further than this in one sample jumped. */
    private const val PX_JUMP = 600

    /** Records kept in memory, for a dump without touching the file. */
    private const val RING = 4096

    /** The log file is rotated past this. */
    private const val MAX_FILE_BYTES = 512L * 1024

    /** Records per second ceiling; a stream is worth reading, a flood is not. */
    private const val RATE_PER_SECOND = 400
    private const val RATE_BURST = 200

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "scroll-diag").apply { isDaemon = true }
    }

    private var dir: File? = null
    private var out: File? = null
    private var outBytes = 0L
    private val writeLock = Any()
    private val queue = ArrayDeque<String>()
    private var flushing = false

    private val ring = ArrayDeque<String>(RING)
    private val ringLock = Any()

    private val downCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val marks = ConcurrentHashMap<String, Mark>()
    private val watches = ConcurrentHashMap<String, Watch>()

    private val start = SystemClock.uptimeMillis()

    private class Mark(val tag: String, val at: Long)

    private class Watch {
        var index = -1
        var offset = 0
        var items = 0
        var keys: List<String> = emptyList()
        var at = 0L
    }

    /** Token bucket; diagnostics must never be the thing that costs a frame. */
    private var tokens = RATE_BURST.toDouble()
    private var lastTokens = SystemClock.uptimeMillis()
    private var dropped = 0

    /** Called once from [uk.xa0.dsh.DshApplication]; a no-op without a directory. */
    fun attach(base: File) {
        if (dir != null) return
        val d = File(base, "diag")
        if (!d.exists() && !d.mkdirs()) return
        dir = d
        synchronized(writeLock) {
            val existing = File(d, "scroll.jsonl")
            if (existing.length() > MAX_FILE_BYTES) {
                existing.renameTo(File(d, "scroll.1.jsonl"))
            }
            out = File(d, "scroll.jsonl")
            outBytes = out?.length() ?: 0L
        }
        record(
            "meta",
            "v" to uk.xa0.dsh.BuildConfig.VERSION_NAME,
            "code" to uk.xa0.dsh.BuildConfig.VERSION_CODE,
        )
    }

    /** True while a finger is down on [surface]. */
    fun pointerDown(surface: String): Boolean = (downCounts[surface]?.get() ?: 0) > 0

    // ---- events -----------------------------------------------------------

    fun surface(surface: String, on: Boolean, items: Int, viewportPx: Int) {
        record("surf", "s" to surface, "on" to on, "items" to items, "vp" to viewportPx)
    }

    fun gestureDown(surface: String, x: Float, y: Float, widthPx: Int, heightPx: Int) {
        downCounts.getOrPut(surface) { AtomicInteger() }.incrementAndGet()
        record(
            "down",
            "s" to surface,
            "x" to x.roundToInt(),
            "y" to y.roundToInt(),
            "fx" to fraction(x, widthPx),
            "fy" to fraction(y, heightPx),
        )
    }

    fun gestureUp(
        surface: String,
        dx: Float,
        dy: Float,
        ms: Long,
        moved: Boolean,
        childConsumed: Boolean,
        offsetBefore: Int,
        offsetAfter: Int,
    ) {
        downCounts[surface]?.decrementAndGet()
        record(
            "up",
            "s" to surface,
            "dx" to dx.roundToInt(),
            "dy" to dy.roundToInt(),
            "ms" to ms,
            "moved" to if (moved) 1 else 0,
            "child" to if (childConsumed) 1 else 0,
            "d" to (offsetAfter - offsetBefore),
            notable = childConsumed && !moved,
        )
    }

    /**
     * Marks the moment just before the app itself moves [surface]'s list. Always
     * notable: a `shift` that follows a mark came from that call site, and one
     * that does not came from somewhere nobody is looking.
     */
    fun prog(surface: String, tag: String) {
        marks[surface] = Mark(tag, SystemClock.uptimeMillis())
        record(
            "prog",
            "s" to surface,
            "tag" to tag,
            "g" to if (pointerDown(surface)) 1 else 0,
            notable = true,
        )
    }

    /**
     * One position sample. Cheap and inert when nothing moved, so a caller can
     * hand every emission of a `snapshotFlow` straight in.
     */
    fun watch(
        surface: String,
        index: Int,
        offset: Int,
        keys: List<String>,
        items: Int,
        viewportPx: Int,
        scrolling: Boolean,
    ) {
        val w = watches.getOrPut(surface) { Watch() }
        val now = SystemClock.uptimeMillis()
        val first = w.index < 0
        if (first) {
            w.index = index
            w.offset = offset
            w.items = items
            w.keys = keys
            w.at = now
            record("surf", "s" to surface, "on" to true, "items" to items, "vp" to viewportPx)
            return
        }

        val di = index - w.index
        val doff = offset - w.offset
        val ditems = items - w.items
        if (di != 0 || doff != 0) {
            val kept = sharedRatio(w.keys, keys)
            note(
                surface = surface,
                index = index,
                di = di,
                doff = doff,
                ditems = ditems,
                kept = kept,
                dt = now - w.at,
                scrolling = scrolling,
                keys = keys,
                // Two rows in one step is the list jumping under the reader.
                threshold = 2,
            )
            // Only a move advances the clock. `dt` measures the gap between
            // position changes, so counting idle time would file the first
            // scroll after a pause as a stall.
            w.at = now
        }

        w.index = index
        w.offset = offset
        w.items = items
        w.keys = keys
    }

    /**
     * The same watch for a surface that scrolls in pixels and has no items —
     * a plain `Column` with `verticalScroll`. [value] is its scroll offset; a
     * single step larger than [PX_JUMP] with nothing driving it is the same
     * evidence a two-row step is on a list.
     */
    fun watchPx(surface: String, value: Int, scrolling: Boolean) {
        val w = watches.getOrPut(surface) { Watch() }
        val now = SystemClock.uptimeMillis()
        if (w.index < 0) {
            w.index = 0
            w.offset = value
            w.items = 0
            w.keys = emptyList()
            w.at = now
            record("surf", "s" to surface, "on" to true, "items" to 0, "vp" to 0)
            return
        }
        val d = value - w.offset
        if (d != 0) {
            note(
                surface = surface,
                index = value,
                di = 0,
                doff = d,
                ditems = 0,
                kept = 1f,
                dt = now - w.at,
                scrolling = scrolling,
                keys = emptyList(),
                threshold = Int.MAX_VALUE,
                pxJump = abs(d) >= PX_JUMP,
            )
            w.at = now
        }
        w.offset = value
    }

    private fun note(
        surface: String,
        index: Int,
        di: Int,
        doff: Int,
        ditems: Int,
        kept: Float,
        dt: Long,
        scrolling: Boolean,
        keys: List<String>,
        threshold: Int,
        pxJump: Boolean = false,
    ) {
        val mark = marks[surface]
        val attributed = mark?.takeIf { fresh(it.at, SystemClock.uptimeMillis(), MARK_TTL_MS) }?.tag
        val down = pointerDown(surface)
        val big = jumped(di, kept, threshold, pxJump)
        val common = arrayOf(
            "s" to surface,
            "di" to di,
            "doff" to doff,
            "ditem" to ditems,
            "dt" to dt,
            "kept" to ratioPercent(kept),
            "g" to if (down) 1 else 0,
            "sc" to if (scrolling) 1 else 0,
            "i" to index,
            "keys" to keys.take(8).joinToString(","),
        )
        when {
            // The app moved the list while a finger was on it, or while a fling
            // was still running: the one shape a reader describes as "it fights
            // my finger", and the one a description never pins to a call site.
            attributed != null && (down || scrolling) ->
                record("fight", *common, "tag" to attributed, notable = true)

            attributed != null ->
                if (big) record("move", *common, "tag" to attributed, notable = true)

            down || scrolling ->
                if (dt > JANK_MS) record("jank", *common)

            // No finger, no fling, and no mark from anything in the app: the
            // list moved on its own.
            big -> record("shift", *common, notable = true)

            dt > JANK_MS -> record("jank", *common)
        }
    }

    /** Everything kept in memory, oldest first. */
    fun dump(): List<String> = synchronized(ringLock) { ring.toList() }

    // ---- plumbing ---------------------------------------------------------

    private fun record(kind: String, vararg pairs: Pair<String, Any?>, notable: Boolean = false) {
        val now = SystemClock.uptimeMillis()
        if (!takeToken(now)) {
            dropped++
            return
        }
        if (dropped > 0) {
            val n = dropped
            dropped = 0
            emit("{\"t\":${now - start},\"k\":\"drop\",\"n\":$n}", notable = false)
        }
        emit(build(kind, now, pairs), notable)
    }

    private fun build(kind: String, now: Long, pairs: Array<out Pair<String, Any?>>): String {
        val sb = StringBuilder(160)
        sb.append("{\"t\":").append(now - start)
        sb.append(",\"k\":\"").append(kind).append('"')
        for ((k, v) in pairs) {
            if (v == null) continue
            sb.append(",\"").append(k).append("\":")
            when (v) {
                is Int, is Long, is Boolean, is Float, is Double -> sb.append(v.toString())
                else -> sb.append('"').append(v.toString().replace('"', '\'')).append('"')
            }
        }
        sb.append('}')
        return sb.toString()
    }

    private fun emit(line: String, notable: Boolean) {
        synchronized(ringLock) {
            if (ring.size >= RING) ring.removeFirst()
            ring.addLast(line)
        }
        if (notable) Log.i(TAG, line)
        val target = dir ?: return
        synchronized(writeLock) {
            queue.addLast(line)
            if (flushing) return
            flushing = true
        }
        io.execute {
            val batch: List<String>
            synchronized(writeLock) {
                batch = queue.toList()
                queue.clear()
                flushing = false
            }
            val f = out ?: return@execute
            val bytes = batch.sumOf { it.length + 1 }
            try {
                if (outBytes + bytes > MAX_FILE_BYTES) {
                    f.renameTo(File(target, "scroll.1.jsonl"))
                    out = File(target, "scroll.jsonl")
                    outBytes = 0L
                }
                (out ?: f).appendText(batch.joinToString("\n", postfix = "\n"))
                outBytes += bytes
            } catch (_: Throwable) {
                // Diagnostics never take the app down with them.
            }
        }
    }

    private fun takeToken(now: Long): Boolean {
        val elapsed = now - lastTokens
        lastTokens = now
        tokens = (tokens + elapsed * RATE_PER_SECOND / 1000.0).coerceAtMost(RATE_BURST.toDouble())
        if (tokens < 1.0) return false
        tokens -= 1.0
        return true
    }
}
