package uk.xa0.dsh.scroll

import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.ChatEntry
import uk.xa0.dsh.model.DisplayRow

private val counts = HashMap<String, Int>()

@Composable
private fun Row(row: DisplayRow) {
    counts[row.key] = (counts[row.key] ?: 0) + 1
    val text = (row as? DisplayRow.Single)?.let { (it.entry as? ChatEntry.AssistantMessage)?.text }.orEmpty()
    Text(text, Modifier.height(48.dp))
}

/**
 * What one streaming token costs, in the real row types.
 *
 * The transcript rebuilds its row list whenever the live row's text changes, which
 * is every few milliseconds while an answer is being written. Building the list is
 * cheap. What is not cheap is `LazyColumn` recomposing rows it can no longer prove
 * unchanged — and it can prove nothing about a row whose type it cannot see into.
 *
 * The last row is the one that changes and it is off-screen: twenty 48dp rows in a
 * 300dp viewport show seven, so the honest answer for the other nineteen is
 * "nothing to do". Measured before `@Immutable` was put on the model, all seven
 * visible rows were recomposed for every token; this test is what keeps that from
 * coming back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class RecomposeCostTest {

    @get:Rule
    val rule = createComposeRule()

    private fun row(index: Int, text: String) = DisplayRow.Single(
        ChatEntry.AssistantMessage(
            seq = index,
            turn = 1,
            step = 1,
            reasoning = "",
            text = text,
            time = 0L,
        ),
    )

    @Test
    fun `changing a row on screen recomposes that row and no other`() {
        // The control for the test below: it proves the list really does update and
        // the counter really does count, so "nothing recomposed" means nothing
        // recomposed rather than nothing happened.
        var seed by mutableStateOf(0)
        rule.setContent {
            val rows = remember(seed) {
                (0 until 20).map { row(it, if (it == 0) "text $seed" else "row $it") }
            }
            LazyColumn(Modifier.height(300.dp)) {
                items(rows, key = { it.key }) { Row(it) }
            }
        }
        rule.waitForIdle()
        counts.clear()

        seed = 1
        rule.waitForIdle()

        assertEquals("only the changed row should recompose, saw $counts", 1, counts.size)
        assertEquals("and it should be the one that changed", 1, counts.values.first())
    }

    @Test
    fun `changing the live row recomposes nothing else on screen`() {
        var seed by mutableStateOf(0)
        rule.setContent {
            val rows = remember(seed) {
                (0 until 20).map { row(it, if (it == 19) "text $seed" else "row $it") }
            }
            LazyColumn(Modifier.height(300.dp)) {
                items(rows, key = { it.key }) { Row(it) }
            }
        }
        rule.waitForIdle()
        counts.clear()

        seed = 1
        rule.waitForIdle()

        assertEquals(
            "a token in an off-screen row must not recompose the rows on screen, saw $counts",
            0,
            counts.size,
        )
    }
}
