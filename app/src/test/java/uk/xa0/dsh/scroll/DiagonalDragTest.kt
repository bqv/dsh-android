package uk.xa0.dsh.scroll

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The drag that does nothing.
 *
 * Measured on the phone: of ninety substantial touch drags on the transcript, thirty
 * moved the list by less than a quarter of the finger's travel, and they all look the
 * same — a large vertical component (270-500px) with a small sideways drift (17-90px),
 * and a scroll delta of exactly zero. A thumb dragging up the screen is never straight.
 *
 * The cause is inside the rows: a horizontal scroller claims the gesture the moment
 * the sideways drift crosses touch slop, and then consumes the whole of it, so the
 * list behind never sees the vertical travel. The reader drags, nothing moves, they
 * drag again — which is what "it scrolls at 5fps" actually is.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class DiagonalDragTest {

    @get:Rule
    val rule = createComposeRule()

    private fun scene(wideRow: Boolean): LazyListState {
        lateinit var state: LazyListState
        rule.setContent {
            state = rememberLazyListState()
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(300.dp).testTag("list")) {
                items(40, key = { it }) { index ->
                    if (index == 0 && wideRow) {
                        // A row with a horizontally scrollable block in it, as a code
                        // block or a bash output is.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .horizontalScroll(rememberScrollState())
                                .testTag("wide"),
                        ) {
                            Text("x".repeat(2000))
                        }
                    } else {
                        Box(Modifier.fillMaxWidth().height(48.dp).testTag("row-$index"))
                    }
                }
            }
        }
        rule.waitForIdle()
        return state
    }

    /** A thumb: mostly up the screen, with a little sideways drift. */
    private fun thumbDrag(tag: String, start: Offset) {
        rule.onNodeWithTag(tag).performTouchInput {
            down(start)
            repeat(10) { step ->
                moveBy(Offset(6f, -40f), delayMillis = 120)
                if (step == 0) Unit
            }
            up()
        }
        rule.waitForIdle()
    }

    @Test
    fun `a mostly-vertical drag that starts on a horizontal scroller still scrolls the list`() {
        val state = scene(wideRow = true)
        thumbDrag("wide", Offset(100f, 24f))

        assertTrue(
            "the list should have scrolled, firstVisibleItemIndex=${state.firstVisibleItemIndex}",
            state.firstVisibleItemIndex > 2,
        )
    }

    @Test
    fun `the same drag on a plain row scrolls the list`() {
        // The control: the same input, on a row with nothing to steal it.
        val state = scene(wideRow = true)
        thumbDrag("row-3", Offset(100f, 24f))

        assertTrue(
            "the list should have scrolled, firstVisibleItemIndex=${state.firstVisibleItemIndex}",
            state.firstVisibleItemIndex > 2,
        )
    }
}
