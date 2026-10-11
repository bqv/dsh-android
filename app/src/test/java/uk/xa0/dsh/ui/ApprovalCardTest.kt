package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
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
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.ApprovalCard
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * The four things an approval card can say, and which of them still take a tap.
 *
 * The defect this is written around is a card that looked answered before anything
 * had been sent, so the properties here are about *telling the states apart*: each
 * one has to be legible on its own, and only the two that still have something to
 * send may answer.
 *
 * ## What these can and cannot show
 *
 * They can show that the states read differently and that a tap in the two inert
 * states does not reach the callback — the click is injected at the button's own
 * bounds, so a version that left `enabled` true would fail the inert tests rather
 * than pass them.
 *
 * They cannot show that the copy is *readable*: Robolectric stubs text metrics, so no
 * claim about wrapping, truncation or how a long failure message lays out can be made
 * here (`docs/HANDOFF.md`). Nor can they show what a reader actually does — the state
 * machine behind these is `model/ApprovalGateTest`, and how long a `Sent` card is up
 * is the gate's settle grace, which is a duration and not something this harness can
 * see.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ApprovalCardTest {

    @get:Rule
    val rule = createComposeRule()

    private var allowed = 0
    private var rejected = 0

    private fun show(answer: ApprovalAnswer) {
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 400.dp, height = 600.dp)) {
                    ApprovalCard(
                        toolName = "shell",
                        reason = "Runs a command on the host",
                        answer = answer,
                        onAllow = { allowed++ },
                        onReject = { rejected++ },
                    )
                }
            }
        }
        rule.waitForIdle()
    }

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
        rule.onNodeWithText("Sending your answer…").assertExists()
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
        rule.onNodeWithText("Allowed once — the host has taken it.").assertExists()
        rule.onNodeWithText("Allow once").performClick()
        rule.waitForIdle()
        assertEquals("a taken answer is not sent twice", 0, allowed)
    }

    @Test
    fun `a not-sent card says so and answers again`() {
        show(ApprovalAnswer.Unsent(true, "it did not reach the host — no route"))
        rule.onNodeWithText("Not sent: it did not reach the host — no route.").assertExists()
        rule.onNodeWithText("Allow once").performClick()
        rule.waitForIdle()
        assertEquals("a failed answer is retriable", 1, allowed)
    }
}
