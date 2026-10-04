package uk.xa0.dsh.scroll

import android.app.Application
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

/**
 * The two layouts, measured rather than argued about.
 *
 * The transcript renders with `reverseLayout = true` so that the newest row is
 * pinned to the bottom without the app having to scroll. That is a real benefit,
 * and it has a price that nobody wrote down: a reversed list anchors on the item
 * at the *bottom* of the viewport and lays the rest out upwards from it, so an
 * item's bottom edge is fixed by the rows below it. Grow a row and it grows
 * *upwards* — the header floats up by exactly the height it gained, and the body
 * lands where the header was. That is the whole of "sections open upwards".
 *
 * An ordinary list anchors on the top, so a growing row extends downwards and
 * everything above it stays put. These tests exist to establish that difference as
 * a measured fact before anything is rebuilt around it.
 *
 * Geometry is fixed rather than inherited: a 300dp viewport of 48dp rows, so
 * "which rows are visible" is arithmetic and not a property of the test device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ScrollBehaviourTest {

    @get:Rule
    val rule = createComposeRule()

    private val rowDp = 48
    private val grownDp = 240
    private val rows = 40

    /**
     * Row 2 is strictly inside a 300dp viewport whichever way the list is laid
     * out — from the top in an ordinary list, from the bottom in a reversed one —
     * so nothing here has to scroll first, and the geometry is the only variable.
     */
    private val target = 2

    @Composable
    private fun List(state: LazyListState, reverse: Boolean, grown: Boolean) {
        Box(Modifier.size(width = 300.dp, height = 300.dp)) {
            LazyColumn(
                state = state,
                reverseLayout = reverse,
                modifier = Modifier.fillMaxWidth().height(300.dp).testTag("list"),
            ) {
                items(rows, key = { it }) { index ->
                    val height = if (index == target && grown) grownDp.dp else rowDp.dp
                    Box(Modifier.fillMaxWidth().height(height).testTag("row-$index"))
                }
            }
        }
    }

    /** The row's top edge in the root, ignoring clipping — it may leave the viewport. */
    private fun bottomOf(index: Int) =
        rule.onNodeWithTag("row-$index").getUnclippedBoundsInRoot().bottom.value

    private fun topOf(index: Int) =
        rule.onNodeWithTag("row-$index").getUnclippedBoundsInRoot().top.value

    @Test
    fun `reversed, a row that grows moves its own top edge upwards`() {
        lateinit var state: LazyListState
        var grown by mutableStateOf(false)

        rule.setContent {
            state = rememberLazyListState()
            List(state, reverse = true, grown = grown)
        }
        val before = topOf(target)
        grown = true
        rule.waitForIdle()
        val after = topOf(target)

        // The row's bottom edge is fixed by the rows below it, so all of the new
        // height goes upwards: the header rises by the growth and the revealed body
        // takes the place it was in.
        assertEquals(
            "a reversed row should rise by exactly the height it gained",
            (grownDp - rowDp).toFloat(),
            before - after,
            2f,
        )
    }

    @Test
    fun `ordinary, a row that grows keeps its top edge`() {
        lateinit var state: LazyListState
        var grown by mutableStateOf(false)

        rule.setContent {
            state = rememberLazyListState()
            List(state, reverse = false, grown = grown)
        }
        val before = topOf(target)
        grown = true
        rule.waitForIdle()
        val after = topOf(target)

        // Top-anchored: the row extends downwards from where it was, which is what
        // opening a section is supposed to look like.
        assertEquals(
            "an ordinary row should not move its top edge at all",
            before,
            after,
            1f,
        )
    }

    @Test
    fun `a drag moves the list by the drag`() {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            List(state, reverse = false, grown = false)
        }
        val before = topOf(target)
        rule.onNodeWithTag("list").performTouchInput {
            down(center)
            moveBy(Offset(0f, -96f))
            up()
        }
        rule.waitForIdle()

        // Two 48dp rows' worth of drag, two rows' worth of movement. Anything else
        // is the app disagreeing with the finger.
        assertEquals(
            "a 96px drag should move the content 96px",
            96f,
            before - topOf(target),
            24f,
        )
    }

    @Test
    fun `a list can be told to open at its last row`() {
        // Opening a session must not show the oldest row first and then jump. The
        // index is not known when the state is created — it depends on how much
        // history the host sends — so the state is asked to start past the end and
        // left to clamp.
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState(initialFirstVisibleItemIndex = Int.MAX_VALUE)
            List(state, reverse = false, grown = false)
        }
        rule.waitForIdle()

        // The property is that the newest row is at the bottom of the viewport, not
        // that it is the first *visible* one — six others are on screen above it.
        assertEquals(
            "the last row should sit at the bottom of the viewport",
            300f,
            bottomOf(rows - 1),
            1f,
        )
        assertTrue(
            "and the list should be near its end, saw index ${state.firstVisibleItemIndex}",
            state.firstVisibleItemIndex > rows - 10,
        )
    }
}
