package uk.xa0.dsh.scroll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.ui.components.ReasoningRow
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The expansion rule, with the row the reader actually taps.
 *
 * `ScrollBehaviourTest` establishes the layout property with plain boxes: a row that
 * grows keeps its top edge in a top-anchored list, and rose by exactly the height it
 * gained in the reversed one this replaces. What that cannot show is the app's own
 * disclosure rows behaving that way — a "Think" section is opened by a tap, its body
 * arrives, and the reader's eye is on the header that must not move.
 *
 * The device agrees but has only ever caught the live row growing, which is not the
 * case in question. This is the case in question.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class RealRowExpansionTest {

    @get:Rule
    val rule = createComposeRule()

    private val thinking = buildString {
        repeat(40) { appendLine("Reasoning line $it with enough words to wrap on a narrow screen.") }
    }

    private fun transcript(): LazyListState {
        lateinit var state: LazyListState
        rule.setContent {
            DshTheme {
                state = rememberLazyListState()
                LazyColumn(
                    state = state,
                    modifier = Modifier.fillMaxWidth().height(300.dp).testTag("list"),
                ) {
                    items(12, key = { it }) { index ->
                        // Row 2 is content-sized, so opening it can actually make it
                        // taller; the others are fixed stubs.
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .then(if (index == 2) Modifier else Modifier.height(24.dp))
                                .testTag("row-$index"),
                        ) {
                            if (index == 2) ReasoningRow(reasoning = thinking, running = false)
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
        return state
    }

    private fun topOf(tag: String) =
        rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().top.value

    private fun heightOf(tag: String) =
        (rule.onNodeWithTag(tag).getUnclippedBoundsInRoot().let { it.bottom - it.top }).value

    @Test
    fun `opening a thinking section grows it downward and leaves its top edge alone`() {
        transcript()

        val topBefore = topOf("row-2")
        val heightBefore = heightOf("row-2")

        rule.onNodeWithText("Think").performClick()
        rule.waitForIdle()

        assertTrue(
            "the row should have grown, height ${heightOf("row-2")} was $heightBefore",
            heightOf("row-2") > heightBefore + 10f,
        )
        assertEquals(
            "and its top edge must not have moved",
            topBefore,
            topOf("row-2"),
            1f,
        )
    }
}
