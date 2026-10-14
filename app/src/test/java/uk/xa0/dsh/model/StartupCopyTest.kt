package uk.xa0.dsh.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two sentences a start-up step is allowed to be.
 *
 * The defect these are written around was not a wording preference. The loading
 * screen was handed a free string and the event multiplexer's own socket trace was
 * wired straight into it, so a reader starting the app was told things like
 * `item s2 baseline (#1)` — a stream id and a frame type, which are facts about the
 * protocol and no facts at all about what the app is doing for them. `StartupStep`
 * plus `StartupCopy` replaces that string, and the assertions below are the ones
 * that would have failed on it:
 *
 *  * no line of copy may *be* a line off the socket, tested against the traces the
 *    running multiplexer has actually produced on the handset;
 *  * every step must name the host, and every step but
 *    [uk.xa0.dsh.model.StartupStep.FINDING_HOST] must say a wait is what is
 *    happening — those waits are network round trips and a reader is owed that fact
 *    rather than left to guess at a queue name, while the route resolver may answer
 *    from a cache and so may not claim one;
 *  * the steps must not read alike, so a reader can tell where the start-up is.
 *
 * ## What this cannot show
 *
 * Nothing here can show that the copy is *readable* on a phone, and nothing here
 * can show how long any of it is on screen. It also cannot see `DshViewModel`, so it
 * cannot show that the step is set at the right moment; it only fixes the words that
 * a step may be rendered with.
 */
class StartupCopyTest {

    private val steps = StartupStep.values().toList()

    /**
     * Lines the multiplexer has really written, copied verbatim from the handset
     * (`adb logcat -s DshMux:D`). The `item` ones are the shape that reached the
     * screen; the `open` ones are the same trace a moment earlier.
     */
    private val wireTraces = listOf(
        "Event socket connected (gen 1)",
        "open s1 -> job/list (gen 1)",
        "open s2 -> session/follow (gen 1)",
        "open s3 -> workspace/follow (gen 1)",
        "item s1 rows (#1)",
        "item s2 snapshot (#1)",
        "item s3 ready (#1)",
        "item s3 waterfall (#2)",
        "item s4 baseline (#1)",
        "item s5 baseline (#1)",
        "item s6 projection (#2)",
        "item s7 upsert (#2)",
        "item s1 emit (#3)",
        "item s4 assistant-stream (#2)",
        "Event socket closed: 1008 duplicate stream",
    )

    /**
     * The frame types the host names on the wire, which must never appear in copy.
     *
     * Kept as a list rather than a regex so that a failure names the word that got
     * through.
     */
    private val wireWords = listOf(
        "baseline", "snapshot", "projection", "upsert", "archived", "waterfall",
        "assistant-stream", "retained", "emit", "rows", "streamId",
    )

    private fun everyLine(step: StartupStep): List<String> =
        listOf(StartupCopy.title(step), StartupCopy.detail(step))

    @Test
    fun `every step has something to say`() {
        steps.forEach { step ->
            val title = StartupCopy.title(step)
            val detail = StartupCopy.detail(step)
            assertTrue("$step has no title", title.isNotBlank())
            assertTrue("$step has no detail", detail.isNotBlank())
            assertEquals("$step's title has loose whitespace", title, title.trim())
            assertEquals("$step's detail has loose whitespace", detail, detail.trim())
        }
    }

    @Test
    fun `no step's copy is a line off the socket`() {
        steps.forEach { step ->
            everyLine(step).forEach { line ->
                assertFalse(
                    "$step renders the mux's own trace: $line",
                    wireTraces.contains(line),
                )
                assertFalse(
                    "$step's copy is shaped like a trace line: $line",
                    Regex("^(item|open|Event socket) ").containsMatchIn(line),
                )
                assertFalse(
                    "$step's copy carries a stream id: $line",
                    Regex("\\bs\\d+\\b").containsMatchIn(line),
                )
                assertFalse(
                    "$step's copy counts frames: $line",
                    line.contains("(#"),
                )
                wireWords.forEach { word ->
                    assertFalse(
                        "$step's copy names the wire's own word \"$word\": $line",
                        line.contains(word, ignoreCase = true),
                    )
                }
            }
        }
    }

    @Test
    fun `every step names the host, and only the real waits claim a wait`() {
        steps.forEach { step ->
            val detail = StartupCopy.detail(step)
            assertTrue("$step's detail does not name the host: $detail", detail.contains("host"))
            if (step == StartupStep.FINDING_HOST) {
                // The route resolver answers from a cache within its time to live, so
                // this is the one step that may not claim the host is being waited on.
                assertFalse(
                    "$step is cached as often as it is probed, so it may not claim a wait: $detail",
                    detail.contains("Waiting"),
                )
            } else {
                assertTrue(
                    "$step's detail does not say what is being waited on: $detail",
                    detail.contains("Waiting"),
                )
            }
        }
    }

    @Test
    fun `the steps can be told apart`() {
        val titles = steps.map { StartupCopy.title(it) }
        val details = steps.map { StartupCopy.detail(it) }
        assertTrue("two steps share a title: $titles", titles.size == titles.toSet().size)
        assertTrue("two steps share a detail: $details", details.size == details.toSet().size)
    }
}
