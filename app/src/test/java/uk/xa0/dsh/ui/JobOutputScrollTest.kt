package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
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
 * ## The trap these tests are written around
 *
 * **Robolectric stubs text metrics, in two ways that both make a plausible-looking
 * assertion vacuous.**
 *
 *  1. *Nothing wraps.* `TextOverflowProbeTest` measured it: 24,892 characters lay out
 *     as a single 260px line, about 0.6px per glyph, so there is no such thing as a
 *     second line here to count. `HANDOFF.md` draws the consequence — "a test whose
 *     subject is where text breaks cannot be written in this repository".
 *  2. *A line box's height is arbitrary.* A single line measures ~35dp whatever
 *     `lineHeight` says, so `height > 30dp` is satisfied by one row of text. The first
 *     version of the wrapping test here asserted exactly that, for a 400-character
 *     line, and would have passed against a `Text` that never wrapped — a green line
 *     that could not go red.
 *
 * The rule this file follows, and which is worth applying anywhere in this suite:
 * **assert a relationship between two measured things, or a count — never an absolute
 * height.** It appears here as the drag distances, which are derived from a measured
 * row rather than written down, so a row that measures differently on another harness
 * does not quietly stop the drag short of the end. Where even that cannot reach — the
 * wrapping claim itself — the test says so instead of pretending (see
 * `nothing in the panel pans sideways`).
 *
 * ## Would each test fail without its fix?
 *
 * Asked of every assertion here, because a green line that cannot go red is
 * documentation rather than a test:
 *
 *  * `a long output opens at its newest line` — without the tail follow the list
 *    starts at index 0, so the first line is composed and the last is not. Fails.
 *  * `the output scrolls back to its first line` — fails any version whose list a drag
 *    cannot move: a clipped rendering with no scroller, or one where the drag falls
 *    short. It is the headline requirement and it is not specific to the gutter.
 *  * `a drag in the text gutter scrolls the panel` — the specific one. It fails the
 *    construction this replaced, where the 30dp text inset was `padding` on the list
 *    and therefore outside the scrollable node: a drag there reached the panel and the
 *    sheet behind it but never the list, so nothing moved.
 *  * `a drag releases the follow…` — fails an implementation that follows but does
 *    not release. It would *pass* one with no follow at all, which is why the next
 *    test exists as well.
 *  * `returning to the tail resumes the follow` — fails an implementation with no
 *    follow at all, and one that never re-arms.
 *  * `the scroll position survives a recomposition` — parks at a known index by
 *    `performScrollToIndex` and asserts that same line afterwards, so a state
 *    recreated per composition (which would land back at index 0, or on the tail)
 *    fails by name.
 *  * `nothing in the panel pans sideways` — fails if a horizontal scroller is ever
 *    added. It does **not** test that lines wrap, and cannot: see the trap above. It
 *    is the half of that requirement the harness can still hold.
 *  * `an empty output offers no scroll surface` — fails a version that renders the
 *    list unconditionally.
 *
 * ## What none of these can show
 *
 * That a long line wraps rather than clipping — a text-break claim, and by the trap
 * above a device's to answer.
 *
 * The follow under a real finger with bytes actually streaming in: that a drag
 * arriving mid-stream releases it, and that the newest line stays pinned as it
 * arrives. A harness can change the text between idles, which is the same rule but
 * not the same timing. That, and which of the sheet's two nested lists takes a drag
 * on a device, are the device's to say — the latter through the latent diagnostics
 * wired for exactly that pair (`docs/SCROLL-DIAG.md`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class JobOutputScrollTest {

    @get:Rule
    val rule = createComposeRule()

    private val rowCount = 300

    /** Everything a test can change from outside the composition. */
    private class Scene {
        var text by mutableStateOf("")
        var running by mutableStateOf(true)
    }

    @Composable
    private fun Panel(scene: Scene) {
        DshTheme {
            Box(Modifier.size(width = 400.dp, height = 600.dp)) {
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
     * The measured height of one output row, taken while the tail line is on screen.
     *
     * Measured rather than assumed: the harness stubs text metrics, so a row is
     * whatever it is, and every distance below is derived from this number instead of
     * a constant that would silently stop meaning anything.
     */
    private fun rowHeight(): Float =
        rule.onNodeWithText("line ${rowCount - 1}").getUnclippedBoundsInRoot().height.value

    /**
     * One deliberate drag across the whole output, in steps.
     *
     * In steps rather than one `moveBy`, because a single jump of the whole distance
     * is a fling, and a fling arrives at the end for a reason other than the drag
     * having scrolled there. The distance is [rowHeight] times a row count past the
     * end, so it crosses the output whatever a row happens to measure.
     */
    private fun dragAcrossOutput(rowHeight: Float, down: Boolean) {
        val total = rowHeight * (rowCount + 30)
        val per = if (down) total / 30f else -total / 30f
        panelList().performTouchInput {
            down(center)
            repeat(30) { moveBy(Offset(0f, per), delayMillis = 30) }
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
        scene(output(rowCount))
        rule.onNodeWithText("line ${rowCount - 1}").assertExists()
        rule.onAllNodesWithText("line 0").assertCountEquals(0)
    }

    /** The whole output is reachable, not just the window it opens on. */
    @Test
    fun `the output scrolls back to its first line`() {
        scene(output(rowCount))
        rule.onAllNodesWithText("line 0").assertCountEquals(0)
        val row = rowHeight()

        dragAcrossOutput(row, down = true)

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
        scene(output(rowCount))
        val row = rowHeight()
        val total = row * (rowCount + 30)

        panelList().performTouchInput {
            down(Offset(4f, center.y))
            repeat(30) { moveBy(Offset(0f, total / 30f), delayMillis = 30) }
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
        val scene = scene(output(rowCount))
        val row = rowHeight()
        dragAcrossOutput(row, down = true)
        rule.onNodeWithText("line 0").assertExists()

        scene.text = output(rowCount + 100)
        rule.waitForIdle()

        rule.onNodeWithText("line 0").assertExists()
        rule.onAllNodesWithText("line ${rowCount + 99}").assertCountEquals(0)
    }

    /**
     * And returning to the bottom hands the follow back, which is the other half of
     * the same rule: a reader who scrolls back down is asking to watch again.
     *
     * Unlike the test above, this one also fails an implementation with no follow at
     * all — nothing else would bring the newest line on screen after the output grows.
     */
    @Test
    fun `returning to the tail resumes the follow`() {
        val scene = scene(output(rowCount))
        val row = rowHeight()
        dragAcrossOutput(row, down = true)
        rule.onNodeWithText("line 0").assertExists()

        dragAcrossOutput(row, down = false)
        rule.onNodeWithText("line ${rowCount - 1}").assertExists()

        scene.text = output(rowCount + 100)
        rule.waitForIdle()

        rule.onNodeWithText("line ${rowCount + 99}").assertExists()
    }

    /**
     * The reader's position survives a recomposition that is not about the output —
     * a status change, a roster frame, the copy button resetting.
     *
     * A `LazyListState` recreated per composition would send the reader back to
     * wherever a fresh state sits — index 0, or the tail if the follow re-armed — on
     * every unrelated frame, which during a turn is every frame.
     *
     * Parked by index rather than by dragging so the position is a name: line 150 is
     * asserted before and after, and neither end of the list is on screen at either
     * moment. A reset to either end fails by name.
     */
    @Test
    fun `the scroll position survives a recomposition`() {
        val scene = scene(output(rowCount))
        panelList().performScrollToIndex(150)
        rule.waitForIdle()

        rule.onNodeWithText("line 150").assertExists()
        rule.onAllNodesWithText("line 0").assertCountEquals(0)
        rule.onAllNodesWithText("line ${rowCount - 1}").assertCountEquals(0)

        scene.running = false
        rule.waitForIdle()

        rule.onNodeWithText("line 150").assertExists()
        rule.onAllNodesWithText("line 0").assertCountEquals(0)
        rule.onAllNodesWithText("line ${rowCount - 1}").assertCountEquals(0)
    }

    /**
     * Nothing in the panel pans sideways.
     *
     * Metric-free, and that is why it is the half of the wrapping requirement that
     * survives here: a horizontal scroller is the only thing that puts a
     * `HorizontalScrollAxisRange` into the semantics tree, so its absence is exactly
     * the claim, and adding one later fails this by name.
     *
     * **The other half — that a long line *wraps* rather than being clipped — cannot be
     * asserted in this repository at all, and is deliberately not attempted.**
     * `TextOverflowProbeTest` established that this harness lays text out with stub
     * metrics: 24,892 characters measure as a single 260px line and *nothing wraps*, so
     * a wrapped line and a clipped one are measurably identical. An earlier version of
     * this test asserted `height > 30dp` for a 400-character line and was therefore
     * vacuous — a single line box already measures ~35dp here, so it passed against the
     * very implementation it existed to catch.
     *
     * The rule that replaces it is recorded in `HANDOFF.md` under the harness's
     * text-metrics trap: **assert a relationship between two measured things, or a
     * count — never an absolute height.** Wrapping is a text-break claim, and that trap
     * is explicit that such a claim can only be seen on a device.
     *
     * What the production code does is wrap — `Text`'s default, matching the web's
     * `--dsl-terminal-line-whitespace: pre-wrap` and this app's own decision for the
     * transcript's shell block (`14732a4`, which rejected a horizontal scroller nested
     * inside a vertical one). That is a statement about the code, not a tested one.
     */
    @Test
    fun `nothing in the panel pans sideways`() {
        scene("x".repeat(4000))

        rule.onAllNodes(
            SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange),
        ).assertCountEquals(0)
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
