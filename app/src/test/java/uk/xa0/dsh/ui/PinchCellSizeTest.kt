package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication

/**
 * The pinch, as a gesture rather than as arithmetic.
 *
 * Two fingers are the one thing here that a unit test of `PinchDetents` cannot
 * reach: whether the second finger is *seen*, whether the step it asks for arrives,
 * and — the part that matters most — whether a two-finger spread still lands on the
 * grid's tap target as a tap. That last one is a real defect if it gets it wrong: a
 * pinch that also pops the soft keyboard open is worse than no pinch, and no amount
 * of reading the source settles which handler won.
 *
 * The same question has a second half, and this test file is where it was found: a
 * *one-finger drag* on the grid was landing as a tap, because `clickable` cancels a
 * tap only on going out of bounds or on consumption and never on movement. The drag
 * and no-movement cases below bracket the slop rule that fixes it — they do not
 * assume the platform's slop value, which no test can read.
 *
 * The scene is shaped like the panel: a parent box that owns the gesture and a child
 * that owns the tap, which is the arrangement the pass order depends on. Positions
 * are raw pixels inside the node, so the spans are the arithmetic of
 * `PinchDetentsTest` and not of whatever density the harness happens to use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class PinchCellSizeTest {

    @get:Rule
    val rule = createComposeRule()

    /** What the gesture did, read from outside the composition. */
    private class Scene {
        var steps = 0
        var taps = 0
    }

    @Composable
    private fun Grid(scene: Scene) {
        Box(
            Modifier
                .size(300.dp)
                .testTag("grid")
                .pinchCellSize { scene.steps += it },
        ) {
            // The tap target, as a child: the panel's grid has exactly this shape —
            // an overlay that puts the keyboard back, under a surface that now also
            // reads two-finger gestures.
            Box(Modifier.fillMaxSize().clickableNoRipple { scene.taps++ })
        }
    }

    private fun scene(): Scene {
        val scene = Scene()
        rule.setContent { Grid(scene) }
        return scene
    }

    @Test
    fun `a spread of a quarter reports one step larger`() {
        val scene = scene()

        rule.onNodeWithTag("grid").performTouchInput {
            down(0, Offset(100f, 100f))
            down(1, Offset(200f, 100f))
            // 132 / 100: 1.24 detents at the 1.25 ratio, so one whole step.
            moveTo(1, Offset(232f, 100f))
            up(0)
            up(1)
        }

        rule.runOnIdle {
            assertEquals("a 1.24-detent spread is one step", 1, scene.steps)
            assertEquals("a pinch is not a tap", 0, scene.taps)
        }
    }

    @Test
    fun `a tightening pinch reports one step smaller`() {
        val scene = scene()

        rule.onNodeWithTag("grid").performTouchInput {
            down(0, Offset(200f, 100f))
            down(1, Offset(100f, 100f))
            // 76 / 100: −1.23 detents.
            moveTo(1, Offset(124f, 100f))
            up(0)
            up(1)
        }

        rule.runOnIdle {
            assertEquals(-1, scene.steps)
            assertEquals(0, scene.taps)
        }
    }

    @Test
    fun `a one-finger tap still reaches the grid's tap target`() {
        val scene = scene()

        rule.onNodeWithTag("grid").performTouchInput {
            down(0, center)
            up(0)
        }

        rule.runOnIdle {
            // The whole point of consuming nothing while the gesture might be a tap:
            // the tap that puts the soft keyboard back has to survive this.
            assertEquals(1, scene.taps)
            assertEquals(0, scene.steps)
        }
    }

    @Test
    fun `a move that never passes touch slop is still a tap`() {
        val scene = scene()

        rule.onNodeWithTag("grid").performTouchInput {
            down(0, Offset(100f, 100f))
            // A finger report with no movement in it. This is the case that says the
            // claim is driven by how far the finger went and not by the mere arrival
            // of a move event — and it does not depend on the platform's slop, which
            // is why the distance here is zero rather than a small guess.
            moveTo(0, Offset(100f, 100f))
            up(0)
        }

        rule.runOnIdle {
            assertEquals(1, scene.taps)
            assertEquals(0, scene.steps)
        }
    }

    @Test
    fun `a one-finger drag does not land as a tap`() {
        val scene = scene()

        rule.onNodeWithTag("grid").performTouchInput {
            down(0, Offset(100f, 100f))
            // 80px, far past any slop a phone or the harness reports.
            moveTo(0, Offset(180f, 100f))
            up(0)
        }

        rule.runOnIdle {
            // `clickable` has no movement test of its own — it cancels a tap only when
            // the pointer leaves the node or something consumes the change — so
            // without the slop rule in `pinchCellSize` this drag would have brought
            // the soft keyboard up.
            assertEquals("one finger does not size anything", 0, scene.steps)
            assertEquals("and a drag is not a tap", 0, scene.taps)
        }
    }

    @Test
    fun `a pinch does not wedge the gesture handler`() {
        val scene = scene()

        rule.onNodeWithTag("grid").performTouchInput {
            down(0, Offset(100f, 100f))
            down(1, Offset(200f, 100f))
            moveTo(1, Offset(232f, 100f))
            // One finger lifted first, which is how a pinch usually ends.
            up(0)
            up(1)
        }
        rule.onNodeWithTag("grid").performTouchInput {
            down(0, center)
            up(0)
        }

        rule.runOnIdle {
            assertEquals(1, scene.steps)
            assertEquals("the tap target works again after a pinch", 1, scene.taps)
        }
    }
}
