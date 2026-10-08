package uk.xa0.dsh.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import uk.xa0.dsh.JobItem

/**
 * The background-job wire rules, pinned against frames the live host actually sent.
 *
 * Every JSON literal in here was copied out of a real `job/list` or `job/follow`
 * frame captured from the running 0.2.0-rc.2 host — not written from the shipped
 * `typert.remote-client.js` descriptors, which this host disagrees with. That
 * distinction is the whole point of the test file: the descriptors were what the
 * app trusted when jobs stopped appearing.
 *
 * `DshViewModel` cannot be constructed in a JVM test (it is an `AndroidViewModel`),
 * so the parsing, ordering, duration and observation-accumulator decisions all live
 * in [JobsWire] and [JobTail] and are exercised directly here.
 */
class JobsTest {

    // ---------------------------------------------------------------- parsing

    /** A `job/list` row for a finished job, verbatim from the host's `rows` frame. */
    private val completedJson = JSONObject(
        """
        {"id":"bash-939","kind":"bash","label":"cd /home/user/var/work/tullki && exec tools/build",
         "owner":"session-f289fb68-7de5-4ab8-972e-37c7d0158271","status":"completed",
         "detail":"exit code: 0","startedAt":1791427572805,"finishedAt":1791427741806,
         "output":{"total":3597,"earliest":0}}
        """.trimIndent(),
    )

    /** A live row with a progress line and a spill file, as the host reports them. */
    private val runningJson = JSONObject(
        """
        {"id":"bash-7695","kind":"bash","label":"timeout 30 node scan.mjs",
         "owner":"1d76d2d5-7b8d-4651-b2e5-a07d01fa30bc","status":"running",
         "progress":"3/10","startedAt":1791476057168,
         "output":{"total":249,"earliest":16,"spillPaths":["/tmp/dsh-subprocess-x/stdout.log"]}}
        """.trimIndent(),
    )

    @Test
    fun parsesEveryFieldOfARow() {
        val job = JobsWire.jobOf(runningJson)
        assertEquals("bash-7695", job.id)
        assertEquals("bash", job.kind)
        assertEquals("timeout 30 node scan.mjs", job.label)
        assertEquals("1d76d2d5-7b8d-4651-b2e5-a07d01fa30bc", job.owner)
        assertEquals("running", job.status)
        assertEquals("3/10", job.progress)
        assertNull(job.detail)
        assertEquals(1791476057168L, job.startedAt)
        assertNull(job.finishedAt)
        assertEquals(249, job.output.total)
        assertEquals(16, job.output.earliest)
        assertEquals(listOf("/tmp/dsh-subprocess-x/stdout.log"), job.output.spillPaths)
    }

    @Test
    fun aSettledRowCarriesItsFinishAndItsTerminalDetail() {
        val job = JobsWire.jobOf(completedJson)
        assertEquals(1791427741806L, job.finishedAt)
        assertEquals("exit code: 0", job.detail)
        assertNull(job.progress)
    }

    /**
     * The host omits `finishedAt`, `owner` and `output.spillPaths` while they do not
     * apply, so absence — not a sentinel — is what a fresh row looks like. A parser
     * that defaulted `finishedAt` to 0 would date every live job to 1970 and give it
     * a fifty-six-year duration.
     */
    @Test
    fun omittedFieldsStayAbsent() {
        val sparse = JobsWire.jobOf(JSONObject("""{"id":"bash-1","kind":"bash","label":"x","status":"running","startedAt":5}"""))
        assertNull(sparse.finishedAt)
        assertNull(sparse.owner)
        assertNull(sparse.progress)
        assertNull(sparse.detail)
        assertTrue(sparse.output.spillPaths.isEmpty())
        assertEquals(0, sparse.output.total)
    }

    @Test
    fun anEmptyLabelIsKeptEmptyRatherThanGuessedFromTheKind() {
        // The web renders `label` with no fallback; substituting the kind here made a
        // row read "bash bash" the one time a producer supplied no label.
        val job = JobsWire.jobOf(JSONObject("""{"id":"w-1","kind":"workflow","label":"","status":"running","startedAt":1}"""))
        assertEquals("", job.label)
    }

    @Test
    fun aWholeRosterDecodesAndUnreadableEntriesAreSkipped() {
        val array = org.json.JSONArray()
            .put(completedJson)
            .put(JSONObject.NULL)
            .put(runningJson)
        val jobs = JobsWire.jobsOf(array)
        assertEquals(listOf("bash-939", "bash-7695"), jobs.map { it.id })
        assertTrue(JobsWire.jobsOf(null).isEmpty())
    }

    // ------------------------------------------------------------ row semantics

    private fun job(
        id: String,
        status: String = "running",
        startedAt: Long = 1L,
        finishedAt: Long? = null,
        total: Int = 0,
        earliest: Int = 0,
        detail: String? = null,
        progress: String? = null,
    ) = JobItem(
        id = id,
        kind = "bash",
        label = id,
        status = status,
        progress = progress,
        detail = detail,
        startedAt = startedAt,
        finishedAt = finishedAt,
        output = JobOutput(total = total, earliest = earliest),
    )

    @Test
    fun livenessIsRunningOrStoppingAndNothingElse() {
        assertTrue(JobsWire.isLive(job("a", "running")))
        assertTrue(JobsWire.isLive(job("a", "stopping")))
        for (settled in listOf("completed", "killed", "failed")) {
            assertFalse(JobsWire.isLive(job("a", settled)))
        }
    }

    /**
     * The threshold the web uses, and the one an empty panel hung on: a *settled*
     * job is only expandable when it actually retained bytes. `output.total` is the
     * next chunk's offset, so 0 means nothing was ever written — an empty terminal
     * for a job that printed nothing is worse than a static row.
     */
    @Test
    fun observabilityIsLiveOrRetainedOutput() {
        assertTrue("a live job may still print", JobsWire.isObservable(job("a", "running", total = 0)))
        assertTrue("a settled job with bytes", JobsWire.isObservable(job("a", "completed", total = 1)))
        assertFalse("a settled job with nothing", JobsWire.isObservable(job("a", "completed", total = 0)))
        // `earliest` alone is not output: retention can leave a non-zero earliest over
        // an empty ring, which must not make a silent job look readable.
        assertFalse(JobsWire.isObservable(job("a", "completed", total = 0, earliest = 500)))
    }

    @Test
    fun detailPrefersTheLiveProgressLine() {
        assertEquals("3/10", JobsWire.detailOf(job("a", progress = "3/10", detail = "exit code: 0")))
        assertEquals("exit code: 0", JobsWire.detailOf(job("a", detail = "exit code: 0")))
        assertNull(JobsWire.detailOf(job("a")))
    }

    /**
     * Live rows first in start order, then settled rows newest-first — the web's
     * `ordered`, whose tie-break exists so the order never depends on the host's map
     * iteration. The first half is what puts the job you care about under the finger
     * instead of at the bottom of a long history.
     */
    @Test
    fun orderingPutsLiveWorkFirstThenTheNewestSettled() {
        val rows = listOf(
            job("done-old", "completed", startedAt = 10, finishedAt = 100),
            job("live-late", "running", startedAt = 40),
            job("done-new", "killed", startedAt = 20, finishedAt = 300),
            job("live-early", "running", startedAt = 30),
            job("stopping", "stopping", startedAt = 50),
        )
        assertEquals(
            listOf("live-early", "live-late", "stopping", "done-new", "done-old"),
            JobsWire.ordered(rows).map { it.id },
        )
    }

    @Test
    fun settledRowsThatFinishedTogetherFallBackToStartOrder() {
        val rows = listOf(
            job("b", "completed", startedAt = 20, finishedAt = 100),
            job("a", "completed", startedAt = 10, finishedAt = 100),
        )
        assertEquals(listOf("a", "b"), JobsWire.ordered(rows).map { it.id })
    }

    // ------------------------------------------------------------- presentation

    @Test
    fun statusVocabularyMatchesTheWebDictionary() {
        assertEquals("running", JobsWire.statusLabel("running"))
        assertEquals("stopping", JobsWire.statusLabel("stopping"))
        assertEquals("completed", JobsWire.statusLabel("completed"))
        // The web's `status.killed` is "cancelled", not "killed".
        assertEquals("cancelled", JobsWire.statusLabel("killed"))
        assertEquals("failed", JobsWire.statusLabel("failed"))
    }

    @Test
    fun dotSemanticsMatchTheWebsDotState() {
        assertEquals("ongoing", JobsWire.dotState("running"))
        assertEquals("warning", JobsWire.dotState("stopping"))
        assertEquals("done", JobsWire.dotState("completed"))
        assertEquals("warning", JobsWire.dotState("killed"))
        assertEquals("error", JobsWire.dotState("failed"))
        assertEquals("idle", JobsWire.dotState("something-new"))
    }

    /** At most two adjacent units, and `killed`-by-hand jobs still read as durations. */
    @Test
    fun durationsUseTwoAdjacentUnits() {
        assertEquals("0s", JobsWire.duration(0))
        assertEquals("12s", JobsWire.duration(12_400))
        assertEquals("3m 12s", JobsWire.duration(192_000))
        assertEquals("1h 3m", JobsWire.duration(3_780_000))
        // A clock skew that puts the finish before the start must clamp, not print a
        // negative duration.
        assertEquals("0s", JobsWire.duration(-5_000))
    }

    // ------------------------------------------------- job/follow accumulation

    /**
     * A fresh generation already past byte zero is looking at a head that was
     * evicted before this client attached, which is a gap even though nothing was
     * dropped while watching.
     */
    @Test
    fun aFreshObservationPastTheHeadStartsWithAGap() {
        val view = JobTail.opened(previous = null, jobId = "bash-1", from = 512, earliest = 0)
        assertTrue(view.gapBefore)
        assertTrue(view.streaming)
        assertEquals("bash-1", view.jobId)
    }

    @Test
    fun resumingAGenerationKeepsTheTextItAlreadyHad() {
        val first = JobObservation(jobId = "bash-1", text = "tick 1\n", cursor = 7)
        val reopened = JobTail.opened(first, "bash-1", from = 7, earliest = 0)
        assertEquals("tick 1\n", reopened.text)
        assertFalse("a mid-stream resume is not a gap", reopened.gapBefore)
    }

    @Test
    fun retentionAheadOfTheRequestedOffsetIsAGap() {
        val view = JobTail.opened(previous = null, jobId = "bash-1", from = 100, earliest = 400)
        assertTrue(view.gapBefore)
    }

    @Test
    fun outputChunksConcatenateAndTheCursorFollowsTheFrame() {
        val opened = JobTail.opened(null, "bash-1", from = 0, earliest = 0)
        val appended = JobTail.append(opened, listOf("tick 1\n", "tick 2\n"), lossy = false, chunkGap = false)
            .copy(cursor = 14)
        assertEquals("tick 1\ntick 2\n", appended.text)
        assertFalse(appended.gapBefore)
        assertEquals(14, appended.cursor)
    }

    @Test
    fun lossyAndGapMarkersReachTheViewAsANotice() {
        val opened = JobTail.opened(null, "bash-1", from = 0, earliest = 0)
        assertTrue(JobTail.append(opened, listOf("x"), lossy = true, chunkGap = false).gapBefore)
        assertTrue(JobTail.append(opened, listOf("x"), lossy = false, chunkGap = true).gapBefore)
    }

    /**
     * The bounded tail: past the limit the head is dropped, and the reader is told.
     * The cut is nudged off a low surrogate so a two-unit character is never split —
     * a lone surrogate draws as a replacement glyph on the last line of exactly the
     * output somebody is watching.
     */
    @Test
    fun theTailIsBoundedAndNeverSplitsASurrogatePair() {
        val opened = JobTail.opened(null, "bash-1", from = 0, earliest = 0)
        val long = "a".repeat(10) + "\uD83D\uDE00" + "b".repeat(10)
        val view = JobTail.append(opened, listOf(long), lossy = false, chunkGap = false, limit = 5)
        assertEquals("bbbbb", view.text)
        assertTrue(view.gapBefore)

        // Cutting at the low half of the emoji: the boundary moves forward one unit,
        // so the character is dropped whole rather than left half-there.
        val pairTail = JobTail.append(opened, listOf(long), lossy = false, chunkGap = false, limit = 11)
        assertEquals("bbbbbbbbbb", pairTail.text)
        assertFalse("a split surrogate must not survive", pairTail.text.first().isLowSurrogate())
    }

    @Test
    fun settlementAndFailureAreTerminalButKeepTheOutput() {
        val streaming = JobTail.append(JobTail.opened(null, "bash-1", 0, 0), listOf("hello"), false, false)
        val settled = JobTail.settled(streaming)
        assertFalse(settled.streaming)
        assertEquals("hello", settled.text)
        assertNull(settled.error)

        val failed = JobTail.failed(settled, "bash-1", "stream reset")
        assertFalse(failed.streaming)
        assertEquals("stream reset", failed.error)
        assertEquals("hello", failed.text)

        // A failure before any frame still produces a view, so the panel can say why
        // it is empty rather than showing nothing at all.
        val cold = JobTail.failed(null, "bash-2", "boom")
        assertEquals("bash-2", cold.jobId)
        assertEquals("boom", cold.error)
        assertFalse(cold.streaming)
    }

    // ------------------------------------------------- chat-log entry anchoring

    /**
     * The exact literal `dsh-tool-bash` writes for a background call is the chat
     * log's only handle on the job. Anything else — a foreground result, a rejected
     * background call, an error — must yield no handle rather than a guess.
     */
    @Test
    fun onlyABackgroundLaunchAnswersWithAJobId() {
        assertEquals("bash-7695", backgroundJobIdOf("started background job bash-7695"))
        assertEquals("bash-7695", backgroundJobIdOf("  started background job bash-7695\n"))
        assertNull(backgroundJobIdOf("exit code: 0"))
        assertNull(backgroundJobIdOf("background jobs unavailable: load @deepseek-ai/dsh-jobs and @deepseek-ai/dsh-tool-jobs"))
        assertNull(backgroundJobIdOf("started background job"))
        assertNull(backgroundJobIdOf(""))
        assertNull(backgroundJobIdOf(null))
    }
}
