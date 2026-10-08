package uk.xa0.dsh.model

import org.json.JSONArray
import org.json.JSONObject
import uk.xa0.dsh.JobItem

/**
 * The wire and presentation rules behind background jobs, ported from the web's
 * `@deepseek-ai/dsh-api-job-controller` and `@deepseek-ai/dsh-client-ui-jobs`.
 *
 * ## Why this exists at all
 *
 * Before 0.2.0 the host published a job roster for **every** session inside the
 * `session/control` baseline, so this app could read jobs off a stream it already
 * had. The running 0.2.0-rc.2 host does not: probed directly, its control baseline
 * carries `projections` and nothing else (the app's `jobs` and `queues` blocks were
 * both gone). Jobs now arrive on their own Remote namespace, so the roster is a
 * **stream you have to open**, one per session:
 *
 *  * `job/list`   — `{request:{sessionId}}` → `{type:'rows', jobs:[JobView]}`
 *    whole-set frames, pushed on every lifecycle change;
 *  * `job/follow` — `{request:{sessionId?, jobId, from?}}` → `opened` / `output` /
 *    `status`, then the stream ends;
 *  * `job/kill`   — `{request:{sessionId, jobId}}` → `{outcome}`.
 *
 * Every name, key and frame shape below was read off the live host, not off the
 * shipped `typert.*.js` descriptors — which is the whole reason the feature had
 * silently disappeared. See `docs/JOBS.md` for the probe transcripts.
 *
 * Nothing here touches Android or the network, so all of it is unit-testable.
 */
object JobsWire {

    /**
     * Status marker semantics, exactly as the web's `dotState`: `stopping` and
     * `killed` share the attention colour because both mean the work ended (or is
     * ending) on request rather than on its own.
     */
    fun dotState(status: String): String = when (status) {
        "running" -> "ongoing"
        "stopping" -> "warning"
        "completed" -> "done"
        "killed" -> "warning"
        "failed" -> "error"
        else -> "idle"
    }

    /** The web's `statusLabel`; `killed` reads "cancelled", as its dictionary has it. */
    fun statusLabel(status: String): String = when (status) {
        "running" -> "running"
        "stopping" -> "stopping"
        "completed" -> "completed"
        "killed" -> "cancelled"
        "failed" -> "failed"
        else -> status
    }

    /** The web's `isLive`. */
    fun isLive(job: JobItem): Boolean = job.status == "running" || job.status == "stopping"

    /**
     * Whether a row offers an output panel: every live job (its output may still
     * arrive) and a settled one that left retained output behind.
     *
     * `output.total` is the offset the *next* chunk would start at, and the host
     * always reports it — a settled job that wrote nothing has `total == 0` and
     * stays a static row rather than opening an empty terminal.
     */
    fun isObservable(job: JobItem): Boolean = isLive(job) || job.output.total > 0

    /** The one-line qualifier beside the status: live progress while running, the terminal reason once settled. */
    fun detailOf(job: JobItem): String? = job.progress ?: job.detail

    /**
     * Live rows first in start order, then settled rows newest-first, with the same
     * tie-break the web uses so the order never depends on the host's map iteration.
     */
    fun ordered(jobs: List<JobItem>): List<JobItem> = jobs.sortedWith { left, right ->
        val liveLeft = isLive(left)
        if (liveLeft != isLive(right)) {
            if (liveLeft) -1 else 1
        } else if (liveLeft) {
            left.startedAt.compareTo(right.startedAt)
        } else {
            val finished = (right.finishedAt ?: right.startedAt).compareTo(left.finishedAt ?: left.startedAt)
            if (finished != 0) finished else left.startedAt.compareTo(right.startedAt)
        }
    }

    /**
     * Elapsed time in at most two adjacent units, mirroring the web's
     * `duration.hours` / `duration.minutes` / `duration.seconds`. A job that
     * outlives an hour is already exceptional, so hours is the widest unit — the
     * figure stays in hours rather than growing a day vocabulary no producer
     * reaches.
     */
    fun duration(elapsedMs: Long): String {
        val total = (elapsedMs / 1000L).coerceAtLeast(0L)
        val seconds = total % 60
        val minutes = (total / 60) % 60
        val hours = total / 3600
        return when {
            hours > 0 -> "${hours}h ${minutes}m"
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    }

    /** One `JobView` off the wire. */
    fun jobOf(json: JSONObject): JobItem = JobItem(
        id = json.str("id"),
        kind = json.str("kind"),
        // The web renders `label` with no fallback: a producer that supplies none
        // gets an empty row line, and the id is what actually names the job.
        label = json.str("label"),
        owner = json.str("owner").takeIf { it.isNotEmpty() },
        status = json.str("status"),
        progress = json.str("progress").takeIf { it.isNotEmpty() },
        detail = json.str("detail").takeIf { it.isNotEmpty() },
        startedAt = json.long("startedAt"),
        finishedAt = if (json.has("finishedAt")) json.long("finishedAt") else null,
        output = json.obj("output")?.let { output ->
            JobOutput(
                total = output.int("total"),
                earliest = output.int("earliest"),
                spillPaths = output.arr("spillPaths")?.let { paths ->
                    (0 until paths.length()).map { paths.optString(it) }
                }.orEmpty(),
            )
        } ?: JobOutput(),
    )

    /** One `rows` frame's whole set. An absent or unreadable array is "no jobs". */
    fun jobsOf(array: JSONArray?): List<JobItem> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::jobOf)
        }
    }
}

/** The output ring's coordinates, as `JobView.output` reports them. */
data class JobOutput(
    /** Offset the next chunk starts at; 0 while nothing was written. */
    val total: Int = 0,
    /** Oldest retained byte, greater than zero exactly when retention dropped the head. */
    val earliest: Int = 0,
    /** Complete-stream spill files a reader below [earliest] can still be pointed at. */
    val spillPaths: List<String> = emptyList(),
)

/**
 * One job's live view, as `job/follow` accumulates it.
 *
 * [text] is a *bounded tail*, not the whole output: 128 KiB of UTF-16 code units,
 * the web's `RENDER_TAIL_LIMIT`. Past that the head is dropped and [gapBefore] is
 * set, which is what the panel renders as "… earlier output dropped …".
 */
data class JobObservation(
    val jobId: String,
    val text: String = "",
    val gapBefore: Boolean = false,
    /** True while the observation stream is open and the job has not settled. */
    val streaming: Boolean = true,
    /** Terminal observation failure, when the stream ended abnormally. */
    val error: String? = null,
    /**
     * The resume offset a later generation asks from.
     *
     * It rides the *state*, not a side table, because it is only meaningful for a
     * generation that is being replaced: the web keeps a parallel `cursor` next to
     * its view for the same reason.
     */
    val cursor: Int? = null,
)

/**
 * The accumulator behind [JobObservation], a line-for-line port of the web's
 * `ClientJobsModel` observation half.
 *
 * Split out as pure functions because every one of its decisions is a rule the
 * web already made and that a test can pin without a socket: the resume cursor,
 * the gap inference, and the bounded-tail cut.
 */
object JobTail {

    /** Bounded per-job render tail, in UTF-16 code units — the web's `RENDER_TAIL_LIMIT`. */
    const val RENDER_TAIL_LIMIT: Int = 128 * 1024

    /**
     * Install or reset observation state when a generation's `opened` anchor arrives.
     *
     * A fresh generation whose `from` is already past zero is looking at a head
     * that was evicted before this client ever attached, so it is a gap even
     * though nothing was dropped *while watching*.
     */
    fun opened(previous: JobObservation?, jobId: String, from: Int, earliest: Int): JobObservation {
        val freshPastHead = (previous == null || previous.text.isEmpty()) && from > 0
        return JobObservation(
            jobId = jobId,
            text = previous?.text.orEmpty(),
            gapBefore = (previous?.gapBefore ?: false) || from < earliest || freshPastHead,
            streaming = true,
            error = previous?.error,
        )
    }

    /**
     * Append one coalesced `output` frame, then trim the tail back to the bound.
     *
     * The cut is nudged off a low surrogate so a two-unit character is never split
     * — a lone surrogate renders as a replacement glyph and corrupts the last line
     * of exactly the output someone is watching.
     */
    fun append(
        previous: JobObservation,
        chunks: List<String>,
        lossy: Boolean,
        chunkGap: Boolean,
        limit: Int = RENDER_TAIL_LIMIT,
    ): JobObservation {
        var text = previous.text + chunks.joinToString("")
        var gapBefore = previous.gapBefore || lossy || chunkGap
        if (text.length > limit) {
            var cut = text.length - limit
            val unit = text[cut]
            if (unit.isLowSurrogate()) cut += 1
            text = text.substring(cut)
            gapBefore = true
        }
        return previous.copy(text = text, gapBefore = gapBefore)
    }

    /** The terminal `status` frame: the ring is drained, so the view stops streaming. */
    fun settled(previous: JobObservation): JobObservation = previous.copy(streaming = false)

    /** A terminal observation failure. */
    fun failed(previous: JobObservation?, jobId: String, error: String): JobObservation =
        (previous ?: JobObservation(jobId = jobId)).copy(streaming = false, error = error)
}

/**
 * The job id a background shell call started, read back out of its own result.
 *
 * `dsh-tool-bash` answers a background call with the literal
 * `started background job <id>`, so the transcript row that launched a job is also
 * the chat-log anchor for watching it. Returns null for anything else — a
 * foreground call, a rejected background call ("background jobs unavailable: …"),
 * or a result that has not arrived yet.
 */
fun backgroundJobIdOf(result: String?): String? {
    val text = result ?: return null
    val match = BACKGROUND_JOB.matchEntire(text.trim()) ?: return null
    return match.groupValues[1]
}

private val BACKGROUND_JOB = Regex("started background job\\s+([A-Za-z0-9_-]+)")

/**
 * The `command` a shell tool call was given, out of its recorded arguments.
 *
 * The output panel is labelled with the command, as the web's `TerminalBlock` is
 * (`command: job.label`). A job's own label *is* that command, but a chat-log entry
 * only has the call's arguments — and a panel headed with the whole argument JSON
 * is a worse label than no label. Null when the arguments are not a shell call's.
 */
fun shellCommandOf(arguments: String?): String? {
    val raw = arguments ?: return null
    val parsed = runCatching { JSONObject(raw) }.getOrNull() ?: return null
    return parsed.optString("command").takeIf { it.isNotEmpty() }
}
