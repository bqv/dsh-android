package uk.xa0.dsh.scroll

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What happens to a reader when a page of *older* rows arrives above them.
 *
 * The transcript loads history backwards: scrolling to the top asks the host for the
 * page before it, and those rows are prepended. The reader is looking at rows that
 * did not change, so they should not move — and Compose is documented to keep the
 * scroll position across a prepend when the items have stable keys.
 *
 * The recorder says otherwise on the device: five shifts in one session with every
 * visible row replaced (`kept: 0`) and the item count changing by tens, none of them
 * driven by a gesture. This is that question asked deterministically.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class PrependAnchorTest {

    @get:Rule
    val rule = createComposeRule()

    private val rowDp = 48

    @Test
    fun `a page prepended above the reader leaves them on the row they were reading`() {
        // Keys are absolute — a row keeps its key when older ones arrive, exactly as
        // `DisplayRow.key` is derived from the entry's seq.
        var oldest by mutableStateOf(1000)
        lateinit var state: LazyListState

        rule.setContent {
            state = rememberLazyListState()
            val keys = remember(oldest) { (oldest until 1020).toList() }
            LazyColumn(state = state, modifier = Modifier.fillMaxWidth().height(300.dp).testTag("list")) {
                items(keys, key = { it }) { key ->
                    Box(Modifier.fillMaxWidth().height(rowDp.dp).testTag("row-$key"))
                }
            }
        }
        rule.waitForIdle()

        val before = rule.onNodeWithTag("row-1000").getUnclippedBoundsInRoot().top.value
        assertEquals("the reader starts on row 1000 at the top", 0f, before, 1f)

        // The page before it lands.
        oldest = 990
        rule.waitForIdle()

        assertEquals(
            "row 1000 should not have moved when older rows arrived above it",
            before,
            rule.onNodeWithTag("row-1000").getUnclippedBoundsInRoot().top.value,
            1f,
        )
    }
}
