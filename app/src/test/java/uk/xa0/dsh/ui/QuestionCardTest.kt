package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import uk.xa0.dsh.PendingQuestion
import uk.xa0.dsh.PendingQuestionSet
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.QuestionCard
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * What a question card says when a skip did not go, and that it stays answerable.
 *
 * The report was "skip no longer sends": the skip went out in the approval vocabulary
 * (`{kind:"next"}`), the host read it as "ask another answerer", and the card cleared
 * over it while the agent stayed blocked. The card is the only place a reader could
 * learn that, so a refusal has to be visible on it and the card has to stay up with
 * both buttons live.
 *
 * What these can show: that a set carrying a notice draws it, that a set without one
 * draws nothing, that the line goes when the set is replaced, and that the Skip button
 * still reaches its callback — so a refusal can be retried. What they cannot show: the
 * copy the centre composes (the card renders whatever the set carries, and the wording
 * is `AttentionCenter`'s), that the host refuses or accepts anything, or anything about
 * size and wrapping — Robolectric has no text metrics, so a longer line measures the
 * same here as a short one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class QuestionCardTest {

    @get:Rule
    val rule = createComposeRule()

    private var skipped = 0

    private val refused = "Skip was not sent — it did not reach the host: no route"

    private fun setOf(notice: String?) = PendingQuestionSet(
        eventId = "event-1",
        sessionId = "session-1",
        questions = listOf(
            PendingQuestion(
                id = "q1",
                header = null,
                question = "Which database should I use?",
                detail = null,
                options = emptyList(),
                multiSelect = false,
                intent = null,
                approveLabel = null,
            ),
        ),
        notice = notice,
    )

    @Composable
    private fun Card(set: PendingQuestionSet) {
        DshTheme {
            Box(Modifier.size(width = 400.dp, height = 600.dp)) {
                QuestionCard(set = set, onSubmit = {}, onSkip = { skipped++ })
            }
        }
    }

    private fun show(set: PendingQuestionSet) {
        rule.setContent { Card(set) }
        rule.waitForIdle()
    }

    /** One composition whose set the test moves, so the notice can appear and go. */
    private fun showChanging(set: PendingQuestionSet): MutableState<PendingQuestionSet> {
        val held = mutableStateOf(set)
        rule.setContent { Card(held.value) }
        rule.waitForIdle()
        return held
    }

    @Test
    fun `a refused skip says so on the card and can be tried again`() {
        show(setOf(refused))
        rule.onNodeWithText(refused).assertExists()
        rule.onNodeWithText("Skip").performClick()
        rule.waitForIdle()
        assertEquals("a refused skip is still offerable", 1, skipped)
    }

    @Test
    fun `a card with nothing to report draws no notice`() {
        show(setOf(null))
        rule.onNodeWithText("Skip").assertExists()
        assertTrue(
            "no notice while there is nothing to report",
            rule.onAllNodesWithText(refused).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun `the notice goes with the set that carried it`() {
        val held = showChanging(setOf(refused))
        rule.onNodeWithText(refused).assertExists()

        held.value = setOf(null)
        rule.waitForIdle()

        assertTrue(
            "a fresh set must not inherit the last attempt's line",
            rule.onAllNodesWithText(refused).fetchSemanticsNodes().isEmpty(),
        )
    }
}
