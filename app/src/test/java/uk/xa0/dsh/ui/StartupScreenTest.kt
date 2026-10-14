package uk.xa0.dsh.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.model.StartupCopy
import uk.xa0.dsh.model.StartupStep
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * What the start-up screen draws, and the two promises it must not break.
 *
 * The first is that a reader is never shown the socket. The screen used to render
 * whatever the event multiplexer had last logged, so `item s2 baseline (#1)` was
 * on it; the assertions below name the traces the running multiplexer really
 * produces on the handset and require that none of them is on screen, for every
 * step.
 *
 * The second is that the throbber is *indeterminate*. None of these waits has a
 * denominator — a route probe, a sign-in probe and a roster read are each one
 * round trip of unknown length — so the screen may not fill a bar, and the test
 * counts the progress nodes rather than trusting that nobody adds one later.
 *
 * ## What this cannot show
 *
 * The harness cannot see the animation move: `OngoingChase` draws onto a `Canvas`,
 * and there is no frame at which this test could read its pixels, so "the throbber
 * throbs" is a device claim, not one of these. It also cannot show that the copy is
 * legible or that it fits on a line — Robolectric lays text out with stub metrics,
 * so every string here is one line of one height however long it is
 * (`docs/HANDOFF.md`) — and it cannot show how long any step is on screen, which is
 * a fact about the network rather than about this composable.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class StartupScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val steps = StartupStep.values().toList()

    /** Verbatim from the handset; see `model/StartupCopyTest` for the same list. */
    private val wireTracesOnScreen = listOf(
        "item s2 baseline (#1)",
        "item s3 ready (#1)",
        "item s1 rows (#1)",
        "open s2 -> session/follow (gen 1)",
        "Event socket connected (gen 1)",
    )

    /**
     * One composition whose step the test moves, so every step is seen without a
     * second `setContent` — which a compose rule allows only once per test.
     */
    private fun showEveryStep(): MutableState<StartupStep> {
        val step = mutableStateOf(StartupStep.FINDING_HOST)
        rule.setContent {
            DshTheme {
                Box(Modifier.size(width = 400.dp, height = 700.dp)) {
                    StartupScreen(step.value)
                }
            }
        }
        rule.waitForIdle()
        return step
    }

    private fun MutableState<StartupStep>.moveTo(step: StartupStep) {
        value = step
        rule.waitForIdle()
    }

    @Test
    fun `every step shows its own two lines`() {
        val step = showEveryStep()
        steps.forEach { subject ->
            step.moveTo(subject)
            rule.onNodeWithText(StartupCopy.title(subject)).assertIsDisplayed()
            rule.onNodeWithText(StartupCopy.detail(subject)).assertIsDisplayed()
        }
    }

    @Test
    fun `the throbber is there, and it is indeterminate`() {
        val step = showEveryStep()
        steps.forEach { subject ->
            step.moveTo(subject)
            // Exactly one progress node, and it is the indeterminate one: a
            // determinate bar here would be a fraction nobody measured.
            rule.onAllNodes(
                SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo),
            ).assertCountEquals(1)
            rule.onNodeWithTag(STARTUP_THROBBER_TAG).assertExists()
            rule.onNode(
                SemanticsMatcher.expectValue(
                    SemanticsProperties.ProgressBarRangeInfo,
                    ProgressBarRangeInfo.Indeterminate,
                ),
            ).assertExists()
        }
    }

    @Test
    fun `no step puts a line of the socket trace on screen`() {
        val step = showEveryStep()
        steps.forEach { subject ->
            step.moveTo(subject)
            wireTracesOnScreen.forEach { trace ->
                rule.onAllNodesWithText(trace).assertCountEquals(0)
            }
        }
    }
}
