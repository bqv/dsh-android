package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.SessionStats
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.STATS_COUNTS_CHIP_TAG
import uk.xa0.dsh.ui.components.STATS_USAGE_CHIP_TAG
import uk.xa0.dsh.ui.components.SessionStatsPills
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * Each stats chip stays on its own end of the composer card.
 *
 * The reported screen held one pill: `16.3K tok · Cache 48%`, drawn at the composer
 * card's *left* edge — where the counts chip lives. The token strip had not moved to
 * the middle or stayed put; it had been placed where its absent neighbour belongs,
 * because the row's flexible slot was drawn only while both chips were present. The
 * counts chip's own gate is `stats.steps > 0`, and a projection block can carry the
 * `tokenUsage` fold without any `sessionStats` at all, so "only the token strip" is a
 * state the host really produces rather than a rendering failure.
 *
 * What is pinned here:
 *  * the token chip ends at the card's right edge when it is the only chip drawn;
 *  * it ends there just the same when the counts chip appears beside it — the value
 *    the reported symptom moved;
 *  * a lone counts chip keeps the card's left edge;
 *  * a row with no reading at all draws neither chip.
 *
 * **On the measurements.** The JVM harness cannot measure text — stub metrics leave a
 * label effectively zero-width — so nothing below asserts that a pill "looks right",
 * and nothing depends on a label's width. What is asserted is which node exists and
 * which edge of the row its placed node reaches. The ends are produced by the row's
 * own flexible slot, which is layout and not text: a node pinned to the right edge
 * reaches it whatever the text inside it measures, and a node with no slot in front of
 * it sits at the left edge however narrow it is. The values are also real: the
 * two-chip fixture is session `4034e345`'s live `session/list` row and the one-chip
 * fixture is the reading on the reported screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class, qualifiers = "w320dp-h640dp")
class StatsPillsAnchorTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        /** The row's own node, so a chip's edge is compared with the row's, not the screen's. */
        const val SLOT = "stats-row-slot"

        /** A pixel of slack for the layout's rounding, as the composer's bounds test uses. */
        const val TOLERANCE = 1f

        /**
         * The reported screen: 16.3K tokens billed, 48% of the prompt served from
         * cache, and no closed step yet — so the counts chip has no reading to draw.
         */
        val TOKEN_STRIP_ONLY = SessionStats(
            hasStats = false,
            turns = 0,
            steps = 0,
            hasUsage = true,
            uncachedInputTokens = 8_200,
            cacheReadTokens = 7_600,
            cacheWriteTokens = 0,
            outputTokens = 500,
        )

        /**
         * A session that has closed steps *and* been billed, with the counts the live
         * host reports for session `4034e345` — the shape that draws both chips.
         */
        val BOTH_READINGS = TOKEN_STRIP_ONLY.copy(
            hasStats = true,
            turns = 25,
            steps = 416,
            llmMs = 3_042_057,
            toolMs = 8_306_312,
            ttftMs = 1_133_161,
            ttftSteps = 416,
            decodeMs = 1_908_896,
            decodeTokens = 399_913,
            uncachedInputTokens = 361_258,
            cacheReadTokens = 81_880_960,
            outputTokens = 399_913,
        )

        /** Closed steps, and a billing projection that settled to nothing. */
        val COUNTS_ONLY = SessionStats(
            hasStats = true,
            turns = 1,
            steps = 13,
            llmMs = 48_512,
            toolMs = 235_889,
            ttftMs = 28_709,
            ttftSteps = 13,
            decodeMs = 19_803,
            decodeTokens = 4_168,
            hasUsage = true,
        )
    }

    private fun composeRow(stats: SessionStats?) {
        rule.setContent {
            DshTheme {
                Box(Modifier.fillMaxWidth().testTag(SLOT)) {
                    SessionStatsPills(stats = stats)
                }
            }
        }
        rule.waitForIdle()
    }

    private fun rowBounds() =
        rule.onNodeWithTag(SLOT, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun right(tag: String) =
        rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.right

    private fun assertFlushLeft(tag: String) {
        val row = rowBounds()
        val chip = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag should begin at the row's left edge: chip=$chip row=$row",
            chip.left >= row.left - TOLERANCE && chip.left <= row.left + TOLERANCE,
        )
    }

    private fun assertFlushRight(tag: String) {
        val row = rowBounds()
        val chip = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "$tag should end at the row's right edge: chip=$chip row=$row",
            chip.right >= row.right - TOLERANCE && chip.right <= row.right + TOLERANCE,
        )
    }

    private fun chip(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true)

    @Test
    fun `a token strip with no counts reading is drawn, and at the card's right edge`() {
        composeRow(TOKEN_STRIP_ONLY)

        // The counts chip has no reading to draw, so it is absent rather than empty.
        chip(STATS_COUNTS_CHIP_TAG).assertDoesNotExist()
        chip(STATS_USAGE_CHIP_TAG).assertExists()
        // The survivor is the token strip, not a counts chip drawing the wrong label.
        rule.onNodeWithText("16.3K tok", useUnmergedTree = true).assertExists()
        rule.onNodeWithText("Cache 48%", useUnmergedTree = true).assertExists()

        assertFlushRight(STATS_USAGE_CHIP_TAG)
    }

    @Test
    fun `both readings are drawn, each on its own end of the card`() {
        composeRow(BOTH_READINGS)

        chip(STATS_COUNTS_CHIP_TAG).assertExists()
        chip(STATS_USAGE_CHIP_TAG).assertExists()

        assertFlushLeft(STATS_COUNTS_CHIP_TAG)
        assertFlushRight(STATS_USAGE_CHIP_TAG)
    }

    @Test
    fun `a lone counts chip keeps the card's left edge`() {
        composeRow(COUNTS_ONLY)

        chip(STATS_COUNTS_CHIP_TAG).assertExists()
        chip(STATS_USAGE_CHIP_TAG).assertDoesNotExist()

        assertFlushLeft(STATS_COUNTS_CHIP_TAG)
    }

    /**
     * The reported symptom itself: the token chip's position is a fact about the token
     * chip, not about whether its neighbour happens to be drawn. Flip the counts
     * reading on under a live row and the token chip must not move; on the layout this
     * replaced it travelled from the card's right edge to its left.
     */
    @Test
    fun `the token chip does not move when the counts chip appears beside it`() {
        val stats = mutableStateOf(TOKEN_STRIP_ONLY)
        rule.setContent {
            DshTheme {
                Box(Modifier.fillMaxWidth().testTag(SLOT)) {
                    SessionStatsPills(stats = stats.value)
                }
            }
        }
        rule.waitForIdle()

        val alone = right(STATS_USAGE_CHIP_TAG)
        assertFlushRight(STATS_USAGE_CHIP_TAG)

        rule.runOnIdle { stats.value = BOTH_READINGS }
        rule.waitForIdle()

        chip(STATS_COUNTS_CHIP_TAG).assertExists()
        assertEquals(
            "the token chip moved when the counts chip appeared",
            alone.toDouble(),
            right(STATS_USAGE_CHIP_TAG).toDouble(),
            TOLERANCE.toDouble(),
        )
    }

    @Test
    fun `a row with no reading draws neither chip`() {
        composeRow(SessionStats())

        chip(STATS_COUNTS_CHIP_TAG).assertDoesNotExist()
        chip(STATS_USAGE_CHIP_TAG).assertDoesNotExist()
    }

    @Test
    fun `a row with no statistics at all draws nothing`() {
        composeRow(null)

        chip(STATS_COUNTS_CHIP_TAG).assertDoesNotExist()
        chip(STATS_USAGE_CHIP_TAG).assertDoesNotExist()
    }
}
