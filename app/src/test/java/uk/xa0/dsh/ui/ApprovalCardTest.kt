package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.ApprovalAnswer
import uk.xa0.dsh.model.ApprovalCopy
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.APPROVAL_STATUS_STRIP_TAG
import uk.xa0.dsh.ui.components.ApprovalCard
import uk.xa0.dsh.ui.theme.DshTheme
import uk.xa0.dsh.ui.theme.DshType

/**
 * The four things an approval card can say, which of them still take a tap, and the
 * card's box not moving when the state does.
 *
 * The first defect this is written around is a card that looked answered before
 * anything had been sent, so the properties here are about *telling the states
 * apart*: each one has to be legible on its own, and only the two that still have
 * something to send may answer. The second is the reported one: the state's sentence
 * ran to two lines, so the card grew when the host took the answer and shrank when
 * the card was cleared a second and a half later.
 *
 * ## What these can and cannot show
 *
 * They can show that the states read differently, that a tap in the two inert states
 * does not reach the callback — the click is injected at the button's own bounds, so
 * a version that left `enabled` true would fail the inert tests rather than pass
 * them — and that the *boxes* are the same size in every state. The strip and the
 * card are measured after layout, and box layout is real here: the strip carries an
 * exact `height`, so its measurement does not depend on the text inside it.
 *
 * They cannot show that the copy is *readable*, and in particular they cannot show
 * that a label fits on one line: Robolectric lays text out with stub metrics, so
 * every string is one line of the same height however long it is and nothing ever
 * wraps (`docs/HANDOFF.md`, `scroll/TextOverflowProbeTest`). A longer sentence would
 * therefore pass every measurement below — the card would still measure the same
 * here. What actually keeps the card still is the fixed-height strip in
 * `components/ChatRows.kt`, and the copy budget that stops the next editor
 * outgrowing it is `model/ApprovalCopyTest`.
 *
 * How long a `Sent` card is up is the gate's settle grace, which is a duration and
 * not something this harness can see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ApprovalCardTest {

    @get:Rule
    val rule = createComposeRule()

    private var allowed = 0
    private var rejected = 0

    private val tool = "shell"
    private val reason = "Runs a command on the host"

    private val states = listOf(
        ApprovalAnswer.Waiting,
        ApprovalAnswer.Answering(true),
        ApprovalAnswer.Sent(true),
        ApprovalAnswer.Unsent(true, "it did not reach the host — no route"),
    )

    @Composable
    private fun Card(answer: ApprovalAnswer) {
        DshTheme {
            Box(Modifier.size(width = 400.dp, height = 600.dp)) {
                ApprovalCard(
                    toolName = tool,
                    reason = reason,
                    answer = answer,
                    onAllow = { allowed++ },
                    onReject = { rejected++ },
                    modifier = Modifier.testTag(CARD_TAG),
                )
            }
        }
    }

    private fun show(answer: ApprovalAnswer) {
        rule.setContent { Card(answer) }
        rule.waitForIdle()
    }

    /** One composition whose state the test moves, so all four can be measured. */
    private fun showChanging(): MutableState<ApprovalAnswer> {
        val answer = mutableStateOf<ApprovalAnswer>(ApprovalAnswer.Waiting)
        rule.setContent { Card(answer.value) }
        rule.waitForIdle()
        return answer
    }

    // `getUnclippedBoundsInRoot()` returns a DpRect: corners, not a rect with a `height`.
    // The same mistake was made twice on the widget tests today.
    private fun heightOf(tag: String): Float =
        rule.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
            .let { (it.bottom - it.top).value }

    @Test
    fun `a waiting card offers both answers`() {
        show(ApprovalAnswer.Waiting)
        rule.onNodeWithText("Approval required").assertExists()
        rule.onNodeWithText("Allow once").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Reject").performClick()
        rule.waitForIdle()
        assertEquals("allow reached the callback", 1, allowed)
        assertEquals("reject reached the callback", 1, rejected)
    }

    @Test
    fun `an answering card says what it is doing and takes no tap`() {
        show(ApprovalAnswer.Answering(true))
        rule.onNodeWithText("Sending…").assertExists()
        rule.onNodeWithText("Allow once").performClick()
        rule.onNodeWithText("Reject").performClick()
        rule.waitForIdle()
        assertEquals("an answer in flight is not re-sent", 0, allowed)
        assertEquals(0, rejected)
    }

    @Test
    fun `a sent card says the host has it and takes no tap`() {
        show(ApprovalAnswer.Sent(true))
        rule.onNodeWithText("Approval sent").assertExists()
        rule.onNodeWithText("The host has it").assertExists()
        rule.onNodeWithText("Allow once").performClick()
        rule.waitForIdle()
        assertEquals("a taken answer is not sent twice", 0, allowed)
    }

    @Test
    fun `a not-sent card says so and answers again`() {
        show(ApprovalAnswer.Unsent(true, "it did not reach the host — no route"))
        rule.onNodeWithText("Not sent: it did not reach the host — no route").assertExists()
        rule.onNodeWithText("Allow once").performClick()
        rule.waitForIdle()
        assertEquals("a failed answer is retriable", 1, allowed)
    }

    /**
     * The strip is one meta line in every state, and the card is the same box in every
     * state.
     *
     * This is the part of "it does not reflow" that is *measured*: the strip is drawn
     * in `Waiting` too, with nothing in it, which is what stops the card growing on
     * the first tap, and its height is the declared one rather than whatever the text
     * would have wanted. It does not cover wrapping — see the class note.
     */
    @Test
    fun `the card and its status strip are one size in every state`() {
        val answer = showChanging()
        val line = with(rule.density) { DshType.meta.lineHeight.toPx().toDp() }.value
        val measured = states.map { state ->
            answer.value = state
            rule.waitForIdle()
            // Throws if the strip is not there at all, which is the point for Waiting.
            val strip = heightOf(APPROVAL_STATUS_STRIP_TAG)
            assertEquals("the strip is one meta line in $state", line, strip, 0.5f)
            state to heightOf(CARD_TAG)
        }
        val (from, height) = measured.first()
        measured.drop(1).forEach { (state, whole) ->
            assertEquals("the card must not move between $from and $state", height, whole, 0.5f)
        }
    }

    /**
     * A state cannot make two competing claims: the card carries the claim for the
     * state it is in, once, and none of the other three.
     */
    @Test
    fun `a state shows its own claim and no other state's`() {
        val answer = showChanging()
        val labels = states.map { ApprovalCopy.status(it) }
        states.forEach { state ->
            answer.value = state
            rule.waitForIdle()
            val mine = ApprovalCopy.status(state)
            labels.filterNotNull().forEach { label ->
                val drawn = rule.onAllNodesWithText(label).fetchSemanticsNodes().size
                if (label == mine) {
                    assertEquals("the claim for $state is drawn once", 1, drawn)
                } else {
                    assertEquals("$state must not also say \"$label\"", 0, drawn)
                }
            }
        }
    }

    private companion object {
        const val CARD_TAG = "approval-card"
    }
}
