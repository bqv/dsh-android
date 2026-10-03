package uk.xa0.dsh.scroll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.ui.scroll.TailFollow

/**
 * The rules a transcript has to obey, as tests rather than as intentions.
 *
 * Every one of these is a complaint that was made about the app in words — "it
 * jumps", "it fights my finger", "it keeps autoscrolling when I have scrolled up",
 * "it closes the rows I'm reading" — turned into something that either holds or
 * does not, in a couple of seconds and without a device.
 *
 * Geometry is fixed: a 300px viewport of 48px rows, so "at the tail" is arithmetic
 * rather than a property of whatever the test happens to run on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class TailFollowTest {

    @get:Rule
    val rule = createComposeRule()

    private val rowDp = 48
    private val viewportDp = 300

    /** Everything a test can change from outside the composition. */
    private class Scene {
        var count by mutableStateOf(20)
        var tailDp by mutableStateOf(48)
        var holding by mutableStateOf(false)
        var state: LazyListState? = null
    }

    @Composable
    private fun Transcript(scene: Scene) {
        Box(Modifier.size(width = 300.dp, height = viewportDp.dp)) {
            val state = rememberLazyListState()
            scene.state = state

            TailFollow(state = state, itemCount = scene.count) { scene.holding }

            LazyColumn(
                state = state,
                modifier = Modifier.fillMaxWidth().height(viewportDp.dp).testTag("list"),
            ) {
                items(scene.count, key = { it }) { index ->
                    val height = if (index == scene.count - 1) scene.tailDp.dp else rowDp.dp
                    Box(Modifier.fillMaxWidth().height(height).testTag("row-$index"))
                }
            }
        }
    }

    /** A scene already settled at its newest row, which is how a session opens. */
    private fun atTail(body: Scene.() -> Unit = {}): Scene {
        val scene = Scene()
        rule.setContent { Transcript(scene) }
        rule.waitForIdle()
        scene.body()
        return scene
    }

    private fun topOf(index: Int) =
        rule.onNodeWithTag("row-$index").getUnclippedBoundsInRoot().top.value

    private fun bottomOf(index: Int) =
        rule.onNodeWithTag("row-$index").getUnclippedBoundsInRoot().bottom.value

    /** A deliberately slow drag: fast enough to be a drag, far too slow to fling. */
    private fun dragBy(dy: Float) {
        rule.onNodeWithTag("list").performTouchInput {
            down(center)
            repeat(6) { moveBy(Offset(0f, dy / 6f), delayMillis = 300) }
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun `a new row is followed when the reader is at the tail`() {
        val scene = atTail()
        scene.count = 21
        rule.waitForIdle()

        assertTrue(
            "the newest row should end up on screen, bottom=${bottomOf(20)}",
            bottomOf(20) <= viewportDp + 1f,
        )
    }

    @Test
    fun `a new row is not followed while a finger is on the list`() {
        val scene = atTail { holding = true }
        val before = topOf(19)
        scene.count = 21
        rule.waitForIdle()

        assertEquals("the list must not move under a finger", before, topOf(19), 1f)
    }

    @Test
    fun `a reader up in history is not dragged back by new rows`() {
        val scene = atTail()

        // Leave the tail the way a reader does — by dragging. That drag is the
        // signal that releases the follow.
        dragBy(240f)

        val beforeIndex = scene.state!!.firstVisibleItemIndex
        val beforeOffset = scene.state!!.firstVisibleItemScrollOffset
        assertTrue(
            "the drag should have taken the reader away from the tail, at $beforeIndex",
            beforeIndex < scene.count - 6,
        )

        scene.count = 21
        rule.waitForIdle()

        assertEquals(
            "a new row must not move a reader in history",
            beforeIndex,
            scene.state!!.firstVisibleItemIndex,
        )
        assertEquals(
            "…not even by a pixel",
            beforeOffset,
            scene.state!!.firstVisibleItemScrollOffset,
        )
    }

    @Test
    fun `a growing tail row stays on screen`() {
        val scene = atTail()
        scene.tailDp = 200
        rule.waitForIdle()

        assertTrue(
            "the tail should be followed as it grows, bottom=${bottomOf(19)}",
            bottomOf(19) <= viewportDp + 1f,
        )
    }

    @Test
    fun `a growing tail row is not followed while a finger is on the list`() {
        val scene = atTail { holding = true }
        val before = topOf(19)
        scene.tailDp = 200
        rule.waitForIdle()

        assertEquals("growth must not move the list under a finger", before, topOf(19), 1f)
    }

    @Test
    fun `the follow moves by exactly the overflow and no more`() {
        val scene = atTail()
        val before = topOf(16)
        scene.tailDp = 200
        rule.waitForIdle()

        // Keeping a growing tail visible necessarily carries the rows above it up —
        // that is what keeping it visible means. What must never happen is moving by
        // *more* than the growth: that is how a correction becomes the jump this
        // whole arrangement exists to remove.
        assertEquals(
            "the follow should move by exactly the height gained",
            before - (200 - rowDp),
            topOf(16),
            2f,
        )
    }

    /** Not a rule: the numbers the rules above are written against. */
    @Test
    fun `diagnostic - the geometry of the tail`() {
        val scene = atTail()

        fun dump(tag: String) {
            val info = scene.state!!.layoutInfo
            println(
                "[$tag] total=${info.totalItemsCount} viewportStart=${info.viewportStartOffset} " +
                    "viewportEnd=${info.viewportEndOffset}",
            )
            info.visibleItemsInfo.forEach {
                println("[$tag]   index=${it.index} offset=${it.offset} size=${it.size}")
            }
        }

        dump("before")
        scene.tailDp = 200
        rule.waitForIdle()
        dump("after")
    }
}
