package uk.xa0.dsh.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.JobItem
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.JobObservation
import uk.xa0.dsh.model.JobOutput
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.JobOutputPanel
import uk.xa0.dsh.ui.components.JobsSeat
import uk.xa0.dsh.ui.components.JobsSheet
import uk.xa0.dsh.ui.components.NoticeRow
import uk.xa0.dsh.ui.components.ToolCallRow
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The two places a job's live output appears, rendered and then driven.
 *
 * A host probe can prove the frames arrive and a JVM test can prove the parsing;
 * neither can prove that a row exists to tap or that tapping it draws the output.
 * That is what this does, on Robolectric, without a device — the same harness
 * `ShellRowExpansionTest` uses for the shell rows.
 *
 * The clock is left auto-advancing, as `ShellRowExpansionTest` leaves it, and that
 * is load-bearing rather than incidental: a click only changes state, and the
 * recomposition that draws the change needs a frame. `waitForIdle` produces frames
 * only while the clock advances, so a test that freezes the clock (`autoAdvance =
 * false`) and then taps sees nothing appear — five of these tests failed exactly
 * that way. Nothing here holds a frame callback open, which is what makes the
 * default safe: the sheet reads a live row's duration at composition instead of
 * ticking it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class JobsUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val command = "for i in 1 2 3; do echo tick \$i; done"
    private val printed = "tick 1\ntick 2\n"

    private fun job(
        id: String = "bash-1",
        status: String = "running",
        total: Int = 0,
        progress: String? = null,
        detail: String? = null,
    ) = JobItem(
        id = id,
        kind = "bash",
        label = command,
        status = status,
        progress = progress,
        detail = detail,
        startedAt = System.currentTimeMillis() - 12_000,
        finishedAt = if (status == "running") null else System.currentTimeMillis(),
        output = JobOutput(total = total),
    )

    private fun observation(jobId: String = "bash-1", text: String = printed, streaming: Boolean = true) =
        JobObservation(jobId = jobId, text = text, streaming = streaming)

    // ---------------------------------------------------------------- the seat

    /**
     * The seat is the only route to the sheet, so it must exist for settled jobs too
     * — the web keeps its trigger while `visibleCount > 0`. A seat that vanished once
     * nothing was running made a session full of finished jobs look identical to one
     * that had never run any.
     */
    @Test
    fun `the seat stays while any job is visible`() {
        var opens = 0
        rule.setContent {
            DshTheme {
                JobsSeat(
                    jobs = listOf(job(id = "bash-done", status = "completed", detail = "exit code: 0", total = 9)),
                    finishedUnseen = false,
                    onClick = { opens++ },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("1 job").assertExists()
        rule.onNodeWithText("1 job").performClick()
        assertEquals(1, opens)
    }

    @Test
    fun `no jobs and no seat`() {
        rule.setContent {
            DshTheme { JobsSeat(jobs = emptyList(), finishedUnseen = false, onClick = {}) }
        }
        rule.waitForIdle()
        rule.onAllNodesWithText("1 job").assertCountEquals(0)
        rule.onAllNodesWithText("done").assertCountEquals(0)
    }

    // ------------------------------------------------------------- bottom sheet

    /**
     * The sheet lists a running job and, on a tap, opens its output — the panel the
     * whole feature exists for.
     */
    @Test
    fun `the sheet renders a live job and opens its output on a tap`() {
        val observed = mutableListOf<String?>()
        rule.setContent {
            DshTheme {
                JobsSheet(
                    jobs = listOf(job()),
                    output = observation(),
                    onObserve = { observed += it },
                    onKill = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()

        rule.onNodeWithText("Background jobs").assertExists()
        rule.onNodeWithText("Running").assertExists()
        rule.onNodeWithText(command).assertExists()
        // Nothing has been observed before the tap: output only flows while watched.
        assertTrue(observed.isEmpty())
        rule.onAllNodesWithText("tick 1", substring = true).assertCountEquals(0)

        rule.onNodeWithText(command).performClick()
        rule.waitForIdle()

        assertEquals(listOf("bash-1"), observed)
        rule.onNodeWithText("tick 1", substring = true).assertExists()
        rule.onNodeWithText("tick 2", substring = true).assertExists()

        // Collapsing releases the stream again. The expanded panel repeats the
        // command — the web heads its panel with `job.label` too — so the row is the
        // first of the nodes carrying it.
        rule.onAllNodesWithText(command).onFirst().performClick()
        rule.waitForIdle()
        assertEquals(listOf("bash-1", null), observed)
    }

    /**
     * A settled job with no retained bytes is a static row — the web's
     * `isObservable` — so tapping it must not open a stream, and must not offer to.
     * A settled job that *did* print is expandable.
     */
    @Test
    fun `a settled job that retained nothing is not expandable`() {
        val observed = mutableListOf<String?>()
        rule.setContent {
            DshTheme {
                JobsSheet(
                    jobs = listOf(job(id = "bash-empty", status = "completed", detail = "exit code: 0", total = 0)),
                    output = null,
                    onObserve = { observed += it },
                    onKill = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Finished 1").assertExists()
        rule.onNodeWithText(command).performClick()
        rule.waitForIdle()
        assertTrue("a silent settled job must not open a stream", observed.isEmpty())
    }

    @Test
    fun `a settled job that retained output is expandable`() {
        val observed = mutableListOf<String?>()
        rule.setContent {
            DshTheme {
                JobsSheet(
                    jobs = listOf(job(id = "bash-loud", status = "completed", detail = "exit code: 0", total = 137)),
                    output = observation("bash-loud"),
                    onObserve = { observed += it },
                    onKill = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText(command).performClick()
        rule.waitForIdle()
        assertEquals(listOf("bash-loud"), observed)
        rule.onNodeWithText("tick 1", substring = true).assertExists()
    }

    /** The kill is two-press, and the first press only arms it. */
    @Test
    fun `stopping a running job takes two presses`() {
        val killed = mutableListOf<String>()
        rule.setContent {
            DshTheme {
                JobsSheet(
                    jobs = listOf(job()),
                    output = null,
                    onObserve = {},
                    onKill = { killed += it },
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        assertTrue("nothing is killed by merely looking at it", killed.isEmpty())

        rule.onNodeWithContentDescription("Stop task $command").performClick()
        rule.waitForIdle()
        assertTrue("the first press arms rather than kills", killed.isEmpty())
        rule.onNodeWithText("Confirm stop").assertExists()

        rule.onNodeWithText("Confirm stop").performClick()
        rule.waitForIdle()
        assertEquals(listOf("bash-1"), killed)
    }

    /** `Clear` drops the finished section client-side, and only the finished one. */
    @Test
    fun `clear removes the finished rows and leaves the running one`() {
        rule.setContent {
            DshTheme {
                JobsSheet(
                    jobs = listOf(
                        job(id = "bash-live", status = "running"),
                        job(id = "bash-done", status = "completed", detail = "exit code: 0", total = 9),
                    ),
                    output = null,
                    onObserve = {},
                    onKill = {},
                    onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Finished 1").assertExists()

        rule.onNodeWithText("Clear").performClick()
        rule.waitForIdle()

        rule.onAllNodesWithText("Finished 1").assertCountEquals(0)
        rule.onNodeWithText("Running").assertExists()
    }

    // ---------------------------------------------------------------- the panel

    /** The gap and interruption notices render above the output, never as output. */
    @Test
    fun `the panel reports a lost head rather than printing it`() {
        rule.setContent {
            DshTheme {
                JobOutputPanel(
                    command = command,
                    view = JobObservation(jobId = "bash-1", text = printed, gapBefore = true, streaming = false),
                    running = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("… earlier output dropped …").assertExists()
        rule.onNodeWithText("tick 1", substring = true).assertExists()
    }

    @Test
    fun `the panel reports a broken stream`() {
        rule.setContent {
            DshTheme {
                JobOutputPanel(
                    command = command,
                    view = JobObservation(jobId = "bash-1", streaming = false, error = "stream reset"),
                    running = false,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("live output stream interrupted: stream reset").assertExists()
        rule.onNodeWithText("(no output)").assertExists()
    }

    // ------------------------------------------------------------- the chat log

    /**
     * The chat-log entry for a *live* job is the background shell call that started
     * it: its result names the job id, so opening the row opens that job's stream,
     * and the output is drawn under the launch it belongs to.
     */
    @Test
    fun `a background shell call opens its job's live output in the chat log`() {
        val observed = mutableListOf<String?>()
        val entry = ChatEntry.ToolCall(
            seq = 1,
            callId = "call_bg",
            name = "bash",
            arguments = """{"command":"$command","description":"watch the ticks","run_in_background":true}""",
            result = "started background job bash-1",
            isError = false,
            time = 1000L,
        )
        rule.setContent {
            DshTheme {
                ToolCallRow(
                    entry,
                    loadImage = { null },
                    jobOutput = observation(),
                    onObserveJob = { observed += it },
                )
            }
        }
        rule.waitForIdle()
        assertTrue(observed.isEmpty())
        rule.onAllNodesWithText("tick 1", substring = true).assertCountEquals(0)

        rule.onNodeWithText("watch the ticks").performClick()
        rule.waitForIdle()

        assertEquals(listOf("bash-1"), observed)
        rule.onNodeWithText("tick 1", substring = true).assertExists()
        // The launch itself is still there beside the output.
        rule.onNodeWithText("started background job bash-1", substring = true).assertExists()
    }

    /**
     * The other chat-log entry: a `tool-jobs` completion notice, which carries the
     * job id parsed out of its own prose and opens the same panel.
     */
    @Test
    fun `a job notice opens the retained output of the job it names`() {
        val observed = mutableListOf<String?>()
        val entry = ChatEntry.Notice(
            seq = 7,
            kind = "tool-jobs",
            text = "bash-1 · exit 0",
            time = 1000L,
            detail = "background job bash-1 (bash: $command) finished [status: completed, exit code: 0].",
            jobId = "bash-1",
        )
        rule.setContent {
            DshTheme {
                NoticeRow(
                    entry,
                    jobOutput = JobObservation(jobId = "bash-1", text = printed, streaming = false),
                    onObserveJob = { observed += it },
                )
            }
        }
        rule.waitForIdle()
        assertTrue(observed.isEmpty())

        rule.onNodeWithText("bash-1 · exit 0").performClick()
        rule.waitForIdle()

        assertEquals(listOf("bash-1"), observed)
        rule.onNodeWithText("tick 1", substring = true).assertExists()
    }

    /** A notice for something other than a job keeps its old behaviour: no panel. */
    @Test
    fun `an ordinary notice is not a job entry`() {
        val entry = ChatEntry.Notice(
            seq = 8,
            kind = "skill-invocation",
            text = "Skill",
            time = 1000L,
            detail = "loaded the skill",
        )
        rule.setContent { DshTheme { NoticeRow(entry) } }
        rule.waitForIdle()
        rule.onNodeWithText("Skill").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("loaded the skill").assertExists()
        rule.onAllNodesWithText("(no output)").assertCountEquals(0)
    }
}
