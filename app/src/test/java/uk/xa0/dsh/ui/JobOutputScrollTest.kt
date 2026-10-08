package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.JobObservation
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.JobOutputPanel
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The output panel scrolls, and it follows its own tail.
 *
 * Both are behaviours a screenshot cannot settle and a device should not be needed
 * for: "you can see the last ~15 lines and cannot reach the rest" is either true or
 * false of the composition, and so is "it stopped following when I scrolled up".
 *
 * The geometry is fixed — a 300dp-wide, 600dp-tall scene, the panel's own 224dp cap
 * — so "at the tail" and "reached the top" are arithmetic rather than properties of
 * whatever the test happens to run on. The drag shape is [TailFollow]'s own harness
 * (`scroll/TailFollowTest.kt`), for the same reason: the follow is that component's
 * behaviour, and testing it through a different gesture would be testing something
 * else.
 *
 * The list is found by `hasScrollAction` rather than by a test tag in the component:
 * the scene holds one scrollable, so the query is unambiguous, and production code
 * keeps no hook that exists only for a test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class JobOutputScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private val panelWidth = 300

    /** Everything a test can change from outside the composition. */
    private class Scene {
        var text by mutableStateOf("")
        var running by mutableStateOf(true)
    }

    @Composable
    private fun Panel(scene: Scene) {
        DshTheme {
            Box(Modifier.size(width = panelWidth.dp, height = 600.dp)) {
                JobOutputPanel(
                    command = "translate the 12 chunks",
                    view = JobObservation(jobId = "bash-1", text = scene.text, streaming = true),
                    running = scene.running,
                    jobId = "bash-1",
                )
            }
        }
    }

    /** [count] numbered lines, so an assertion can name one of them exactly. */
    private fun output(count: Int): String = (0 until count).joinToString("\n") { "line $it" }

    private fun scene(text: String): Scene {
        val scene = Scene()
        scene.text = text
        rule.setContent { Panel(scene) }
        rule.waitForIdle()
        return scene
    }

    private fun panelList() = rule.onNode(hasScrollAction())

    /**
     * One long drag, downwards: the reader pulling history back into view.
     *
     * In steps rather than one jump, because a single `moveBy` of the whole distance
     * is a fling, and a fling would arrive at the top for a reason other than the
     * drag having scrolled there.
     */
    private fun dragDown(totalDp: Float) {
        panelList().performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, totalDp / 20f), delayMillis = 40) }
            up()
        }
        rule.waitForIdle()
    }

    /**
     * A long output opens at its newest line.
     *
     * This is two claims at once, which is why it is the first test. A `LazyColumn`
     * composes only what is on screen, so a panel that were clipped rather than
     * scrolled — the reported defect — would compose the *first* lines and could not
     * be showing the last one at all. Seeing line 299 and not line 0 is the follow
     * having run and the viewport having moved, together.
     */
    @Test
    fun `a long output opens at its newest line`() {
        scene(output(300))
        rule.onNodeWithText("line 299").assertExists()
        rule.onAllNodesWithText("line 0").assertCountEquals(0)
    }

    /** The whole output is reachable, not just the window it opens on. */
    @Test
    fun `the output scrolls back to its first line`() {
        scene(output(300))
        rule.onAllNodesWithText("line 0").assertCountEquals(0)

        dragDown(totalDp = 6000f)

        rule.onNodeWithText("line 0").assertExists()
    }

    /**
     * A drag that starts in the text's left gutter still scrolls the panel.
     *
     * This is the defect as it was found by reading the old code rather than by
     * running it: the 30dp text gutter was `padding` on the *list*, which puts it
     * outside the scrollable node. A drag starting there reached the panel and the
     * sheet behind it but never the list, so nothing moved — on a left-aligned block
     * of mono text, which is most of the panel, and exactly where a thumb lands.
     *
     * Four dp in from the left edge is inside that gutter and must still scroll.
     */
    @Test
    fun `a drag in the text gutter scrolls the panel`() {
        scene(output(300))

        panelList().performTouchInput {
            down(Offset(4f, center.y))
            repeat(20) { moveBy(Offset(0f, 300f), delayMillis = 40) }
            up()
        }
        rule.waitForIdle()

        rule.onNodeWithText("line 0").assertExists()
    }

    /**
     * A deliberate drag releases the follow, so output arriving afterwards does not
     * pull the reader back down — the failure mode that makes a live view worse than
     * no live view.
     *
     * The discriminator is that the reader is left at line 0 while 100 more lines
     * arrive. A panel still following would have jumped to line 399 and line 0 would
     * not be composed any more.
     */
    @Test
    fun `a drag releases the follow, so later output does not pull the reader back`() {
        val scene = scene(output(300))
        dragDown(totalDp = 6000f)
        rule.onNodeWithText("line 0").assertExists()

        scene.text = output(400)
        rule.waitForIdle()

        rule.onNodeWithText("line 0").assertExists()
        rule.onAllNodesWithText("line 399").assertCountEquals(0)
    }

    /**
     * And returning to the bottom hands the follow back, which is the other half of
     * the same rule: a reader who scrolls back down is asking to watch again.
     */
    @Test
    fun `returning to the tail resumes the follow`() {
        val scene = scene(output(300))
        dragDown(totalDp = 6000f)
        rule.onNodeWithText("line 0").assertExists()

        // Back down to the newest line, then more output arrives.
        panelList().performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(0f, -500f), delayMillis = 40) }
            up()
        }
        rule.waitForIdle()
        rule.onNodeWithText("line 299").assertExists()

        scene.text = output(400)
        rule.waitForIdle()

        rule.onNodeWithText("line 399").assertExists()
    }

    /**
     * The reader's position survives a recomposition that is not about the output —
     * a status change, a roster frame, the copy button resetting.
     *
     * A `LazyListState` that were recreated per composition would send the reader back
     * to wherever the follow last put them on every unrelated frame, which during a
     * turn is every frame.
     */
    @Test
    fun `the scroll position survives a recomposition`() {
        val scene = scene(output(300))
        dragDown(totalDp = 6000f)
        rule.onNodeWithText("line 0").assertExists()

        scene.running = false
        rule.waitForIdle()

        rule.onNodeWithText("line 0").assertExists()
    }

    /**
     * Long lines wrap; nothing pans sideways.
     *
     * The web wraps — `--dsl-terminal-line-whitespace: pre-wrap` on the jobs panel,
     * and its README says the panel "wraps commands and output lines in full" — and
     * this app reached the same conclusion for the transcript's shell block
     * (`14732a4`): a horizontal scroller nested in a vertically scrolling surface
     * "costs a gesture that has to be told apart from the list's".
     *
     * Height is the discriminator and width is not: a `Text` that did not wrap would
     * still be *clamped* to the panel's width, so its right edge would look correct
     * while the tail of every line was cut off. A wrapped 400-character line is many
     * rows tall; a clipped one is exactly one.
     */
    @Test
    fun `a long line wraps instead of panning sideways`() {
        val long = "x".repeat(400)
        scene(long)

        val bounds = rule.onNodeWithText(long).getUnclippedBoundsInRoot()
        assertTrue(
            "a 400-character line should wrap to many rows, not stand as one ${bounds.height}",
            bounds.height.value > 30f,
        )
        assertTrue(
            "the line must stay inside the panel, right=${bounds.right}",
            bounds.right.value <= panelWidth + 1f,
        )
    }

    /**
     * An output with nothing in it still says so, and offers no scroller to drag —
     * the panel's other state, kept here because the scrollable branch replaced the
     * early return the empty case used to take.
     */
    @Test
    fun `an empty output offers no scroll surface`() {
        scene("")
        rule.onNodeWithText("Waiting for output…").assertExists()
        rule.onAllNodes(hasScrollAction()).assertCountEquals(0)
    }
}
