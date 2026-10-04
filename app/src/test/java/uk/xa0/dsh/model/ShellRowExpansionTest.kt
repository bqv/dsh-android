package uk.xa0.dsh.model

import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import uk.xa0.dsh.scroll.HarnessApplication
import uk.xa0.dsh.ui.components.BashCallRow
import uk.xa0.dsh.ui.components.ToolCallRow
import uk.xa0.dsh.ui.components.bashTerminalModel
import uk.xa0.dsh.ui.theme.DshTheme

/**
 * Tapping the header of either kind of shell row opens it.
 *
 * Two components draw a shell call: [BashCallRow] for the terminal card, and
 * [ToolCallRow] for everything the card declines — which includes a
 * `run_in_background` call, since it has no exit status for the card to draw. They were
 * written separately and share nothing but the shape of their header, so a report that
 * *neither* opens is a report about that shape.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = HarnessApplication::class)
class ShellRowExpansionTest {

    @get:Rule
    val rule = createComposeRule()

    private val command = "cd /home/user/var/work/dsh-android && ./build.sh :app:testDebugUnitTest"
    private val output = "BUILD SUCCESSFUL in 1m 20s"

    private fun call(callId: String, background: Boolean) = ChatEntry.ToolCall(
        seq = 1,
        callId = callId,
        name = "bash",
        arguments = """{"command":"$command","description":"run the tests"""" +
            (if (background) ""","run_in_background":true""" else "") + "}",
        result = output,
        isError = false,
        time = 1000L,
    )

    @Test
    fun `the terminal card opens on a tap`() {
        val entry = call("call_fg", background = false)
        val model = bashTerminalModel(entry.arguments, entry.result, entry.isError)
            ?: error("a foreground bash call should build the terminal card model")
        rule.setContent { DshTheme { BashCallRow(entry, model) } }
        rule.waitForIdle()

        // Closed: the card's own body is not there.
        rule.onAllNodesWithText(command, substring = true).assertCountEquals(0)

        rule.onNodeWithText("run the tests").performClick()
        rule.waitForIdle()

        rule.onNodeWithText(command, substring = true).assertExists()
    }

    @Test
    fun `the generic row opens on a tap`() {
        val entry = call("call_bg", background = true)
        // The card declines a background call, which is what puts it on the generic row.
        org.junit.Assert.assertNull(bashTerminalModel(entry.arguments, entry.result, entry.isError))
        rule.setContent { DshTheme { ToolCallRow(entry, loadImage = { null }) } }
        rule.waitForIdle()

        rule.onAllNodesWithText(command, substring = true).assertCountEquals(0)

        rule.onNodeWithText("run the tests").performClick()
        rule.waitForIdle()

        rule.onNodeWithText(command, substring = true).assertExists()
    }
}
